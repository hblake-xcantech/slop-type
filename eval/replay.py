#!/usr/bin/env python3
"""Replay log-schema traces through an engine and score decisions against `truth`.

    replay.py --trace t.jsonl [--trace u.jsonl ...] --engine mock|jar:<path>|cmd:<cmd>
              [--config engine.json] --out-csv words.csv --out-json metrics.json

Alignment: per session, the engine's decisions (in order) are aligned to the truth words
(whitespace tokens of the `truth` events, de-overlapped since each event carries the last
<= 240 chars) with a Needleman-Wunsch alignment on typed-vs-truth edit distance, so a
split or merged word only costs that word. A word is correct if commit == truth ignoring
trailing punctuation, case-insensitively unless the truth word is capitalised.

Novel words: a truth word is novel if the `truth` event lists it in `novel` (synthetic
traces) or, with --novel-from-lexicon, if it is not in that lexicon (real logs).
"""
from __future__ import annotations

import argparse
import json
import shlex
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import (eprint, find_trace_files, iter_jsonl, load_lexicon, same_word, strip_punct, tokenize,  # noqa: E402
                    word_matches)
from engines import Decision, add_engine_args, run_engine  # noqa: E402
from metrics import compute_metrics, format_table, outcome, trace_counters, write_words_csv  # noqa: E402


# ------------------------------------------------------------------------------ truth words
def _overlap(prev: str, cur: str) -> int:
    """Largest k with prev[-k:] == cur[:k]."""
    for k in range(min(len(prev), len(cur)), 0, -1):
        if prev[-k:] == cur[:k]:
            return k
    return 0


def truth_words_per_session(events: list[dict]) -> dict[str, tuple[list[str], set[str]]]:
    """sid -> (truth words in order, novel word set (lowercase))."""
    out: dict[str, tuple[list[str], set[str]]] = {}
    last_text: dict[str, str] = {}
    for ev in events:
        if ev.get("ev") != "truth":
            continue
        sid = str(ev.get("sid", ""))
        text = str(ev.get("text", ""))
        words, novel = out.setdefault(sid, ([], set()))
        prev = last_text.get(sid, "")
        k = _overlap(prev, text) if prev else 0
        new = text[k:]
        if k and prev and not prev.endswith((" ", "\n")) and new and not new.startswith(" "):
            # the window boundary cut a word in half: the partial word was already counted
            new = new.split(" ", 1)[1] if " " in new else ""
        words.extend(tokenize(new))
        for w in ev.get("novel", []) or []:
            novel.add(strip_punct(str(w)).lower())
        last_text[sid] = text
    return out


# ---------------------------------------------------------------------------------- align
def _lev(a: str, b: str) -> int:
    if a == b:
        return 0
    if not a or not b:
        return len(a) + len(b)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def align(decisions: list[Decision], truth: list[str], gap: float = 0.75) -> list[tuple[Decision | None, str | None]]:
    """Needleman-Wunsch between decisions and truth words; match cost = normalised edit
    distance of the better of typed/commit vs truth (0 for a correct word)."""
    n, m = len(decisions), len(truth)
    if n == m and all(same_word(d.typed, t) or word_matches(d.commit, t) for d, t in zip(decisions, truth)):
        return list(zip(decisions, truth))

    def cost(d: Decision, t: str) -> float:
        tl = strip_punct(t).lower()
        if same_word(d.typed, t) or word_matches(d.commit, t):
            return 0.0
        a, b = strip_punct(d.typed).lower(), strip_punct(d.commit).lower()
        best = min(_lev(a, tl), _lev(b, tl))
        return best / max(1, len(tl), len(a))

    D = [[0.0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        D[i][0] = i * gap
    for j in range(1, m + 1):
        D[0][j] = j * gap
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            D[i][j] = min(D[i - 1][j - 1] + cost(decisions[i - 1], truth[j - 1]), D[i - 1][j] + gap, D[i][j - 1] + gap)
    out: list[tuple[Decision | None, str | None]] = []
    i, j = n, m
    while i > 0 or j > 0:
        if i > 0 and j > 0 and abs(D[i][j] - (D[i - 1][j - 1] + cost(decisions[i - 1], truth[j - 1]))) < 1e-9:
            out.append((decisions[i - 1], truth[j - 1]))
            i, j = i - 1, j - 1
        elif i > 0 and abs(D[i][j] - (D[i - 1][j] + gap)) < 1e-9:
            out.append((decisions[i - 1], None))
            i -= 1
        else:
            out.append((None, truth[j - 1]))
            j -= 1
    out.reverse()
    return out


# -------------------------------------------------------------------------------- records
def decisions_by_sid(events: list[dict], decisions: list[Decision]) -> dict[str, list[Decision]]:
    """Group decisions by session id (from the decision itself, else from the event index)."""
    sid_at: list[str] = []
    cur = ""
    for ev in events:
        if ev.get("ev") == "session_start":
            cur = str(ev.get("sid", ""))
        sid_at.append(cur)
    by_sid: dict[str, list[Decision]] = {}
    for d in decisions:
        sid = d.sid or (sid_at[d.i] if 0 <= d.i < len(sid_at) else "")
        by_sid.setdefault(sid, []).append(d)
    return by_sid


def score_trace(trace: Path, decisions: list[Decision], lexicon: dict[str, int] | None) -> list[dict]:
    events = list(iter_jsonl(trace))
    truth = truth_words_per_session(events)
    by_sid = decisions_by_sid(events, decisions)
    records: list[dict] = []
    for sid in sorted(set(by_sid) | set(truth)):
        words, novel = truth.get(sid, ([], set()))
        for d, t in align(by_sid.get(sid, []), words):
            tw = strip_punct(t).lower() if t else ""
            is_novel = bool(t) and (tw in novel or (lexicon is not None and tw.isalpha() and tw not in lexicon))
            r = {"sid": sid, "i": d.i if d else "", "truth": t or "", "typed": d.typed if d else "",
                 "commit": d.commit if d else "", "kind": d.kind if d else "", "conf": d.conf if d else "",
                 "tier": d.tier if d else "", "ms": d.ms if d else "", "novel": is_novel,
                 "typed_correct": bool(d and t and same_word(d.typed, t)),
                 "commit_correct": bool(d and t and word_matches(d.commit, t))}
            r["outcome"] = outcome(r)
            records.append(r)
    return records


def replay(traces: list[Path], engine: str, config: str | None, engine_args: list[str] | None = None,
           novel_lexicon: dict[str, int] | None = None) -> tuple[list[dict], dict, list[dict]]:
    """Returns (records, metrics, engine summaries)."""
    records: list[dict] = []
    summaries: list[dict] = []
    for tr in traces:
        decisions, summary = run_engine(engine, config, tr, engine_args)
        summaries.append({"trace": str(tr), **summary})
        records.extend(score_trace(tr, decisions, novel_lexicon))
    metrics = compute_metrics(records, trace_counters(traces))
    return records, metrics, summaries


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--trace", action="append", required=True, help="trace file or directory (repeatable)")
    add_engine_args(ap)
    ap.add_argument("--out-csv", default=None, help="per-word CSV")
    ap.add_argument("--out-json", default=None, help="metrics JSON")
    ap.add_argument("--novel-from-lexicon", default=None, help="treat truth words not in this lexicon as novel (real logs)")
    ap.add_argument("--quiet", action="store_true")
    a = ap.parse_args(argv)
    traces: list[Path] = []
    for t in a.trace:
        traces += find_trace_files(t)
    lex = load_lexicon(a.novel_from_lexicon) if a.novel_from_lexicon else None
    try:
        records, metrics, summaries = replay(traces, a.engine, a.config, shlex.split(a.engine_args), lex)
    except (FileNotFoundError, RuntimeError, ValueError) as e:
        eprint(f"engine error: {e}")
        return 2
    metrics["engine"] = a.engine
    metrics["traces"] = [str(t) for t in traces]
    if a.out_csv:
        write_words_csv(a.out_csv, records)
    if a.out_json:
        Path(a.out_json).parent.mkdir(parents=True, exist_ok=True)
        Path(a.out_json).write_text(json.dumps(metrics, indent=2) + "\n", encoding="utf-8")
    if not a.quiet:
        print(format_table({"replay": metrics}))
        for s in summaries:
            eprint("engine summary:", json.dumps(s))
    return 0


if __name__ == "__main__":
    sys.exit(main())
