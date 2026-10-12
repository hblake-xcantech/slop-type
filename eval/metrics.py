#!/usr/bin/env python3
"""Metrics over aligned per-word records (decision + truth) and trace-level events.

A per-word record (produced by replay.py) has:
    sid, i, truth, typed, commit, kind, conf, tier, ms, novel, typed_correct, commit_correct

Definitions (all rates are per truth word unless stated; see README.md):

    words                    number of truth words
    corrections_per_word     decisions whose commit != typed / words
    typed_error_rate         words whose literal (typed) != truth / words   (input difficulty)
    wrong_autocorrect_rate   typed was correct and the engine changed it / words
    missed_correction_rate   typed was wrong and the engine kept it / words
    bad_fix_rate             typed was wrong, engine changed it, still wrong / words
    correct_fix_rate         typed wrong and commit correct / typed wrong   (recall of fixes)
    novel_word_preservation  novel truth words whose commit is correct / novel words (1.0 if none)
    word_error_rate_after    words whose final commit is wrong (+ unaligned words) / words
    backspaces_per_word      sum of `backspace.count` events / words        (real logs)
    latency_p50_ms/p95_ms    percentiles of `ms` over decisions
    cost_per_1k_words        sum of `remote.costUsd` / words x 1000
    tier_mix / kind_mix      fraction of decisions per tier / per DecisionKind

    primary = wrong_autocorrect_rate * 2 + missed_correction_rate + (1 - novel_word_preservation)

`primary` is the promotion metric (lower is better). Wrong autocorrects weigh double because
they destroy correct input and cost the user a revert; a missed correction only costs what a
plain keyboard would; losing novel words is the failure mode autocorrect is hated for.

CLI:
    metrics.py --words words.csv [--trace t.jsonl ...] [--out metrics.json]
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path
from typing import Iterable

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import iter_jsonl, percentile  # noqa: E402

WORD_FIELDS = ["sid", "i", "truth", "typed", "commit", "kind", "conf", "tier", "ms", "novel",
               "typed_correct", "commit_correct", "outcome"]


def _b(v) -> bool:
    if isinstance(v, bool):
        return v
    return str(v).strip().lower() in ("1", "true", "yes", "y")


def outcome(r: dict) -> str:
    """Classify one record (used for the CSV and for the per-outcome counts)."""
    truth, typed, commit = r.get("truth", ""), r.get("typed", ""), r.get("commit", "")
    if not truth:
        return "extra_decision"
    if not typed and not commit:
        return "unaligned"
    changed = commit != typed
    tc, cc, novel = _b(r.get("typed_correct")), _b(r.get("commit_correct")), _b(r.get("novel"))
    if novel:
        return "novel_kept" if cc else "novel_lost"
    if tc and changed:
        return "wrong_autocorrect"
    if tc:
        return "keep_ok"
    if not changed:
        return "keep_missed"
    return "fix_ok" if cc else "fix_bad"


def trace_counters(traces: Iterable[str | Path]) -> dict:
    """Backspace, remote cost and remote latency counters from raw trace events."""
    bs = 0
    cost = 0.0
    remote_calls = 0
    remote_fail = 0
    for p in traces:
        for ev in iter_jsonl(p):
            k = ev.get("ev")
            if k == "backspace":
                bs += int(ev.get("count", 1))
            elif k == "remote":
                remote_calls += 1
                cost += float(ev.get("costUsd", 0.0) or 0.0)
                if not ev.get("ok", True):
                    remote_fail += 1
    return {"backspaces": bs, "remote_cost_usd": cost, "remote_calls": remote_calls, "remote_failed": remote_fail}


def compute_metrics(records: list[dict], counters: dict | None = None) -> dict:
    counters = counters or {}
    words = sum(1 for r in records if r.get("truth"))
    decisions = [r for r in records if r.get("typed") or r.get("commit")]
    outs: dict[str, int] = {}
    for r in records:
        o = outcome(r)
        outs[o] = outs.get(o, 0) + 1
    n = max(words, 1)
    typed_wrong = outs.get("keep_missed", 0) + outs.get("fix_ok", 0) + outs.get("fix_bad", 0)
    novel_n = outs.get("novel_kept", 0) + outs.get("novel_lost", 0)
    changed = sum(1 for r in decisions if r.get("commit") != r.get("typed"))
    lat = [float(r.get("ms") or 0.0) for r in decisions]
    tiers: dict[str, int] = {}
    kinds: dict[str, int] = {}
    for r in decisions:
        t = str(r.get("tier", 1) or 1)
        tiers[t] = tiers.get(t, 0) + 1
        k = str(r.get("kind", "") or "")
        kinds[k] = kinds.get(k, 0) + 1
    nd = max(len(decisions), 1)
    wrong_ac = outs.get("wrong_autocorrect", 0) / n
    missed = outs.get("keep_missed", 0) / n
    novel_pres = (outs.get("novel_kept", 0) / novel_n) if novel_n else 1.0
    wer_after = (outs.get("keep_missed", 0) + outs.get("fix_bad", 0) + outs.get("wrong_autocorrect", 0)
                 + outs.get("novel_lost", 0) + outs.get("unaligned", 0)) / n
    m = {
        "words": words,
        "decisions": len(decisions),
        "novel_words": novel_n,
        "corrections_per_word": round(changed / n, 4),
        "typed_error_rate": round((typed_wrong + outs.get("novel_lost", 0) * 0) / n, 4),
        "wrong_autocorrect_rate": round(wrong_ac, 4),
        "missed_correction_rate": round(missed, 4),
        "bad_fix_rate": round(outs.get("fix_bad", 0) / n, 4),
        "correct_fix_rate": round(outs.get("fix_ok", 0) / typed_wrong, 4) if typed_wrong else 1.0,
        "novel_word_preservation": round(novel_pres, 4),
        "word_error_rate_after": round(wer_after, 4),
        "backspaces_per_word": round(counters.get("backspaces", 0) / n, 4),
        "latency_p50_ms": round(percentile(lat, 50), 3),
        "latency_p95_ms": round(percentile(lat, 95), 3),
        "cost_per_1k_words": round(counters.get("remote_cost_usd", 0.0) / n * 1000.0, 5),
        "remote_calls": counters.get("remote_calls", 0),
        "tier_mix": {k: round(v / nd, 4) for k, v in sorted(tiers.items())},
        "kind_mix": {k: round(v / nd, 4) for k, v in sorted(kinds.items())},
        "outcomes": dict(sorted(outs.items())),
    }
    m["primary"] = round(wrong_ac * 2 + missed + (1 - novel_pres), 4)
    return m


def read_words_csv(path: str | Path) -> list[dict]:
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def write_words_csv(path: str | Path, records: list[dict]) -> None:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=WORD_FIELDS, extrasaction="ignore")
        w.writeheader()
        for r in records:
            row = dict(r)
            row.setdefault("outcome", outcome(r))
            w.writerow(row)


def format_table(rows: dict[str, dict], keys: list[str] | None = None) -> str:
    """Compact text table: one column per metrics dict in `rows` (name -> metrics)."""
    keys = keys or ["words", "typed_error_rate", "corrections_per_word", "wrong_autocorrect_rate",
                    "missed_correction_rate", "bad_fix_rate", "correct_fix_rate", "novel_words",
                    "novel_word_preservation", "word_error_rate_after", "backspaces_per_word",
                    "latency_p50_ms", "latency_p95_ms", "cost_per_1k_words", "primary"]
    names = list(rows)
    w0 = max(len(k) for k in keys) + 1
    wc = max(12, max(len(n) for n in names) + 2)
    out = [" " * w0 + "".join(n.rjust(wc) for n in names)]
    for k in keys:
        line = k.ljust(w0)
        for n in names:
            v = rows[n].get(k, "")
            line += (f"{v:.4f}" if isinstance(v, float) else str(v)).rjust(wc)
        out.append(line)
    return "\n".join(out)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--words", required=True, help="per-word CSV from replay.py")
    ap.add_argument("--trace", action="append", default=[], help="trace(s) for backspace/remote counters")
    ap.add_argument("--out", default=None, help="write metrics JSON here (default: stdout)")
    a = ap.parse_args(argv)
    m = compute_metrics(read_words_csv(a.words), trace_counters(a.trace))
    js = json.dumps(m, indent=2)
    if a.out:
        Path(a.out).parent.mkdir(parents=True, exist_ok=True)
        Path(a.out).write_text(js + "\n", encoding="utf-8")
        print(format_table({"metrics": m}))
    else:
        print(js)
    return 0


if __name__ == "__main__":
    sys.exit(main())
