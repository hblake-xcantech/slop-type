#!/usr/bin/env python3
"""Engine adapters for the eval harness.

An engine turns a log-schema JSONL trace into decision lines, exactly as the engine CLI
does (DESIGN.md, "Engine CLI"):

    {"i": <event index>, "typed": ..., "commit": ..., "kind": ..., "conf": ..., "alts": [...],
     "retro": [...], "ms": <decode ms>}
    ...
    {"summary": {...}}          # trailing line

Engine specs (the `--engine` flag everywhere in eval/):

* `mock`                 - trivial Python decoder (below). Needs no jar.
* `jar:<path>`           - `java -jar <path> decode --config <cfg> --trace <trace>`
* `cmd:<shell command>`  - any command; `--config <cfg> --trace <trace>` are appended.

The mock decoder: `literal` = key codes as resolved by the keyboard. If `literal` is in the
lexicon (main or `personalLexicon` from the config) -> KEEP. Otherwise candidates within one
edit (neighbour / far substitution, transposition, missing letter, extra letter) plus every
same-length lexicon word whose letters are all within the neighbour set of the tapped keys
(the "spatial" candidates, any number of near misses), scored by unigram log-frequency + a
per-tap spatial Gaussian + an edit penalty. If the taps are *precise* (mean residual offset from the resolved keys,
after removing the per-word mean offset, below `precise_threshold` key units) and the best
candidate only differs by edits the user could not have made by accident while precise
(neighbour substitution or an extra tapped letter) -> NOVEL (keep as typed). Otherwise
CORRECT to the best candidate, or NOVEL/KEEP when there is none.

An in-vocabulary literal is still corrected when a one-edit candidate beats it by more than
`keep_margin` (frequency + spatial fit), which is how the mock produces realistic wrong
autocorrects on rare words typed off-centre. A candidate that is a very common word
(`common_floor` count) wins even over precise taps (`tge` -> `the`).

Mock config keys (all optional, top-level JSON object): `personalLexicon` (list of words),
`mock.sigma_keys` (0.5), `mock.precise_threshold` (0.2), `mock.freq_weight` (0.6),
`mock.keep_margin` (1.5), `mock.common_floor` (1000), `mock.lexicon` (path; default
eval/data/lexicon.txt).

CLI:
    engines.py decode --engine mock --trace in.jsonl [--config cfg.json] > out.jsonl
    engines.py build-lexicon [--out eval/data/lexicon.txt]
"""
from __future__ import annotations

import argparse
import json
import math
import shlex
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import (DATA_DIR, LEXICON_PATH, SEPARATOR_CODES, eprint, iter_jsonl, load_lexicon,  # noqa: E402
                    strip_punct)
from geometry import Geometry, Key  # noqa: E402

ALPHABET = "abcdefghijklmnopqrstuvwxyz"


# ============================================================================ lexicon build
# ~400 very common English words, most frequent first; pseudo-counts are rank based.
CORE_WORDS = """the of and to in a is that for it as was with be by on not he i this are or his from at
which but have an had they you were their one all we can her has there been if more when will would who so
no she my than up what its about out them into only some could then other do time these two may first any
like new very now way our over just also people man him after another most year where much work still
through back how before being because each well such good make long those here get see day down own know
down take off come did little use many great little say under same last right think while should thing
even home too must give never go place old found want keep around small away find both thought why
again put went head high end always large name every need next few left life hand once part open let
night live mean told set call read eye seem look ask move feel try leave does water big really wait tell
sure run help start show hear play turn thank sorry love hope talk week month today tomorrow morning
yesterday tonight later soon early late check send sent got going gone made make meet meeting minute hour
house car phone email message text call number door kitchen room office school city street road park
friend family mother father sister brother son daughter baby kid kids dog cat food dinner lunch breakfast
coffee tea water bread rice soup cake pizza please thanks yes okay ok fine maybe probably definitely
actually basically honestly exactly obviously anyway something nothing everything anything someone
everyone anyone nobody everybody somebody better best worse worst bigger smaller faster slower happy sad
tired busy free ready done finished working running walking waiting coming leaving moving trying
looking thinking feeling hoping getting giving taking making seeing saying telling asking buying
black white red blue green yellow orange brown grey pink monday tuesday wednesday thursday friday
saturday sunday january february march april may june july august september october november december
money bank card pay paid price cost cheap expensive bill rent job boss team manager project plan report
deadline update meeting client customer company business market sale sales product service system
computer laptop screen keyboard mouse internet website online app apps software update version build
server database code bug fix test tests release deploy branch merge commit push pull request review
file files folder data backup password login account user users network wifi router cable battery
charger camera photo photos video music song movie film book books game games news weather rain sun
snow wind cold hot warm cool nice beautiful terrible awful amazing great wonderful lovely perfect
true false real same different easy hard simple difficult quick slow quiet loud dark light full empty
near far left right top bottom front side north south east west inside outside between above below
their there they're your you're its it's whose who's than then were where we're been being am
didn't don't doesn't isn't aren't wasn't weren't can't couldn't won't wouldn't shouldn't haven't
hasn't hadn't i'm i've i'll i'd you've you'll you'd he's she's he'd she'd we've we'll we'd they've
they'll they'd that's what's there's here's let's receive separate definitely believe achieve
necessary beginning government different business because through between people should would
could which about their there""".split()


def build_lexicon(sentences_path: Path, out: Path, exclude: set[str]) -> dict[str, int]:
    """Lexicon = words of the sentence corpus (count x 20) + CORE_WORDS (rank-based pseudo
    counts), minus `exclude` (the synthetic novel-word seeds, so the mock has an English-only
    vocabulary)."""
    counts: dict[str, int] = {}
    with open(sentences_path, encoding="utf-8") as f:
        for line in f:
            for tok in line.split():
                w = strip_punct(tok).lower()
                if w and all(c.isalpha() or c == "'" for c in w):
                    counts[w] = counts.get(w, 0) + 20
    n = len(CORE_WORDS)
    for rank, w in enumerate(CORE_WORDS):
        counts[w] = counts.get(w, 0) + int(60_000 / (rank + 10)) + 100
    for w in exclude:
        counts.pop(w.lower(), None)
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        f.write("# word<TAB>count  (generated by engines.py build-lexicon; the mock engine's vocabulary)\n")
        for w, c in sorted(counts.items(), key=lambda t: (-t[1], t[0])):
            f.write(f"{w}\t{c}\n")
    return counts


# =============================================================================== decisions
@dataclass
class Decision:
    i: int
    typed: str
    commit: str
    kind: str
    conf: float
    alts: list[str]
    retro: list[dict]
    ms: float
    tier: int = 1
    sid: str = ""

    def to_json(self) -> dict:
        return {"i": self.i, "typed": self.typed, "commit": self.commit, "kind": self.kind,
                "conf": round(self.conf, 3), "alts": self.alts, "retro": self.retro, "ms": round(self.ms, 3),
                "tier": self.tier, "sid": self.sid}


# ============================================================================= mock engine
EDIT_PENALTY = {"keep": 0.0, "sub_near": -1.0, "sub_far": -3.0, "transpose": -2.0, "missing": -2.5,
                "extra": -2.5, "spatial": -1.0}  # spatial: per substituted letter
# Edits that a *precise* typist would not make by accident -> the OOV literal is deliberate.
DELIBERATE_EDITS = {"sub_near", "extra", "spatial"}


class MockEngine:
    def __init__(self, config: dict | None = None, lexicon: dict[str, int] | None = None):
        cfg = config or {}
        m = cfg.get("mock", {}) if isinstance(cfg.get("mock"), dict) else {}
        self.sigma_keys = float(m.get("sigma_keys", 0.5))
        self.precise_threshold = float(m.get("precise_threshold", 0.2))
        self.freq_weight = float(m.get("freq_weight", 0.6))
        self.keep_margin = float(m.get("keep_margin", 1.5))
        self.common_floor = int(m.get("common_floor", 1000))
        lex_path = m.get("lexicon", str(LEXICON_PATH))
        self.lexicon = lexicon if lexicon is not None else load_lexicon(lex_path)
        if not self.lexicon:
            raise FileNotFoundError(f"mock lexicon missing/empty: {lex_path} (run engines.py build-lexicon)")
        self.personal = {str(w).lower() for w in cfg.get("personalLexicon", [])}
        self.by_len: dict[int, list[str]] = {}
        for w in list(self.lexicon) + sorted(self.personal):
            self.by_len.setdefault(len(w), []).append(w)
        self.log_total = math.log(sum(self.lexicon.values()) + 1)
        self._lf: dict[str, float] = {}
        # per-session state
        self.geo: Geometry | None = None
        self.keys_by_char: dict[str, Key] = {}
        self.neigh: dict[str, set[str]] = {}
        self.reset_word()

    # ---- state ----------------------------------------------------------------------
    def reset_word(self) -> None:
        self.composing: list[str] = []
        self.taps: list[tuple[float, float] | None] = []
        self.last_down: tuple[int, float, float] | None = None

    def start_session(self, geom: dict | None) -> None:
        self.reset_word()
        self.geo = Geometry.from_dict(geom) if geom else None
        self.keys_by_char = {}
        self.neigh = {}
        if self.geo:
            for k in self.geo.letter_keys():
                self.keys_by_char[k.label.lower()] = k
            for k in self.geo.letter_keys():
                self.neigh[k.label.lower()] = {n.label.lower() for n in self.geo.neighbours(k)}

    def logfreq(self, w: str) -> float:
        v = self._lf.get(w)
        if v is None:
            c = self.lexicon.get(w, 0)
            v = (math.log(c + 1) - self.log_total) if c else -self.log_total - 2.0
            self._lf[w] = v
        return v

    def in_vocab(self, w: str) -> bool:
        return w in self.lexicon or w in self.personal

    # ---- spatial model ----------------------------------------------------------------
    def tap_loglik(self, tap: tuple[float, float] | None, ch: str) -> float:
        if tap is None:
            return 0.0
        k = self.keys_by_char.get(ch)
        if k is None:
            return -1.0
        dx = (tap[0] - k.cx) / (self.sigma_keys * k.w)
        dy = (tap[1] - k.cy) / (self.sigma_keys * k.h)
        return -0.5 * (dx * dx + dy * dy)

    def precision(self, literal: str, taps: list[tuple[float, float] | None]) -> float | None:
        """Mean residual offset (key units) of the taps from the resolved keys' centres after
        removing the per-word mean offset (a crude posture estimate). None if taps missing."""
        if len(taps) != len(literal) or len(taps) < 2 or any(t is None for t in taps):
            return None
        offs: list[tuple[float, float]] = []
        for t, ch in zip(taps, literal):
            k = self.keys_by_char.get(ch)
            if k is None:
                continue
            offs.append(((t[0] - k.cx) / k.w, (t[1] - k.cy) / k.h))  # type: ignore[index]
        if len(offs) < 2:
            return None
        mx = sum(o[0] for o in offs) / len(offs)
        my = sum(o[1] for o in offs) / len(offs)
        if len(offs) < 3:
            mx = my = 0.0
        return sum(math.hypot(o[0] - mx, o[1] - my) for o in offs) / len(offs)

    # ---- candidates -------------------------------------------------------------------
    def score(self, word: str, taps: list[tuple[float, float] | None], edit: str, mult: int = 1) -> float:
        sp = sum(self.tap_loglik(t, c) for t, c in zip(taps, word))
        return self.freq_weight * self.logfreq(word) + sp + EDIT_PENALTY[edit] * mult

    def candidates(self, lit: str, taps: list[tuple[float, float] | None]) -> dict[str, tuple[float, str]]:
        """{word: (score, edit)} for lexicon words within one edit of `lit`, plus same-length
        words reachable by neighbour substitutions only (spatial candidates)."""
        L = len(lit)
        if len(taps) != L:
            taps = [None] * L
        out: dict[str, tuple[float, str]] = {}

        def consider(w: str, tp: list[tuple[float, float] | None], edit: str, mult: int = 1) -> None:
            if not self.in_vocab(w):
                return
            s = self.score(w, tp, edit, mult)
            if w not in out or s > out[w][0]:
                out[w] = (s, edit)

        for i in range(L):
            ci = lit[i]
            if ci.isalpha():
                nb = self.neigh.get(ci, set())
                for c in ALPHABET:
                    if c != ci:
                        consider(lit[:i] + c + lit[i + 1:], taps, "sub_near" if c in nb else "sub_far")
            if L > 1:
                consider(lit[:i] + lit[i + 1:], taps[:i] + taps[i + 1:], "extra")
            if i + 1 < L and lit[i] != lit[i + 1]:
                consider(lit[:i] + lit[i + 1] + lit[i] + lit[i + 2:], taps[:i] + [taps[i + 1], taps[i]] + taps[i + 2:], "transpose")
        for i in range(L + 1):
            for c in ALPHABET:
                consider(lit[:i] + c + lit[i:], taps[:i] + [None] + taps[i:], "missing")
        for w in self.by_len.get(L, ()):
            if w == lit:
                continue
            nsub = 0
            for a, b in zip(lit, w):
                if a != b:
                    if b in self.neigh.get(a, ()):
                        nsub += 1
                    else:
                        nsub = -1
                        break
            if nsub > 1:
                consider(w, taps, "spatial", nsub)
        return out

    # ---- decision ---------------------------------------------------------------------
    @staticmethod
    def recase(src: str, word: str) -> str:
        if len(src) > 1 and src.isupper():
            return word.upper()
        if src[:1].isupper():
            return word[:1].upper() + word[1:]
        return word

    def decide(self, i: int, sid: str) -> Decision | None:
        typed = "".join(self.composing)
        taps = list(self.taps)
        self.reset_word()
        lit = typed.lower()
        if not strip_punct(lit):
            return None
        t0 = time.perf_counter()
        kind, commit, conf, alts = "KEEP", typed, 0.9, []
        cands = self.candidates(lit, taps)
        prec = self.precision(lit, taps)
        ranked = sorted(cands.items(), key=lambda kv: -kv[1][0])
        alts = [self.recase(typed, w) for w, _ in ranked[:3]]
        if self.in_vocab(lit):
            keep_score = self.score(lit, taps, "keep")
            if ranked and ranked[0][1][0] > keep_score + self.keep_margin and lit not in self.personal:
                best, (bs, _edit) = ranked[0]
                kind, commit, conf = "CORRECT", self.recase(typed, best), 0.5 + min(0.45, (bs - keep_score - self.keep_margin) / 10.0)
            else:
                conf = 0.9 if not ranked else 0.6 + 0.3 * min(1.0, max(0.0, (keep_score + self.keep_margin - ranked[0][1][0]) / 5.0))
        elif ranked:
            best, (bs, edit) = ranked[0]
            second = ranked[1][1][0] if len(ranked) > 1 else bs - 5.0
            margin = min(1.0, max(0.0, (bs - second) / 5.0))
            precise = prec is not None and prec <= self.precise_threshold
            very_common = self.lexicon.get(best, 0) >= self.common_floor
            if precise and edit in DELIBERATE_EDITS and not very_common:
                kind, conf = "NOVEL", 0.6 + 0.3 * (1 - prec / self.precise_threshold)
            else:
                kind, commit, conf = "CORRECT", self.recase(typed, best), 0.5 + 0.45 * margin
        else:
            kind = "NOVEL" if (prec is not None and prec <= self.precise_threshold) else "KEEP"
            conf = 0.5
        ms = (time.perf_counter() - t0) * 1000.0
        return Decision(i, typed, commit, kind, conf, alts, [], ms, 1, sid)

    # ---- trace processing -------------------------------------------------------------
    def run(self, events: list[dict] | "Iterable[dict]") -> tuple[list[Decision], dict]:
        decisions: list[Decision] = []
        n_sessions = 0
        sid = ""
        for i, ev in enumerate(events):
            kind = ev.get("ev")
            if kind == "session_start":
                n_sessions += 1
                sid = str(ev.get("sid", ""))
                self.start_session(ev.get("geom"))
            elif kind == "touch":
                if ev.get("a") == "d":
                    self.last_down = (int(ev.get("code", 0)), float(ev.get("x", 0)), float(ev.get("y", 0)))
            elif kind == "key":
                code = int(ev.get("code", 0))
                if code in SEPARATOR_CODES or code < 0:
                    d = self.decide(i, sid)
                    if d:
                        decisions.append(d)
                    continue
                try:
                    ch = chr(code)
                except (ValueError, OverflowError):
                    continue
                if not (ch.isalpha() or ch == "'"):
                    continue
                tap = None
                ld = self.last_down
                if ld is not None and (ld[0] == code or (ld[0] < 0x110000 and chr(ld[0]).lower() == ch.lower())):
                    tap = (ld[1], ld[2])
                elif "x" in ev and "y" in ev and ld is not None:
                    tap = (float(ev["x"]), float(ev["y"]))
                self.last_down = None
                self.composing.append(ch)
                self.taps.append(tap)
            elif kind == "backspace":
                n = int(ev.get("count", 1))
                for _ in range(n):
                    if self.composing:
                        self.composing.pop()
                        self.taps.pop()
            elif kind == "session_end":
                d = self.decide(i, sid)
                if d:
                    decisions.append(d)
        summary = {"engine": "mock", "sessions": n_sessions, "decisions": len(decisions),
                   "kinds": _count(d.kind for d in decisions)}
        return decisions, summary


def _count(it) -> dict[str, int]:
    out: dict[str, int] = {}
    for x in it:
        out[x] = out.get(x, 0) + 1
    return out


# ============================================================================ jar / cmd
def run_external(cmd: list[str], config: str | None, trace: str | Path, extra_args: list[str] | None = None,
                 timeout: float = 1800.0) -> tuple[list[Decision], dict]:
    """Run an external engine CLI and parse its stdout into decisions + summary."""
    argv = list(cmd)
    if config:
        argv += ["--config", str(config)]
    argv += ["--trace", str(trace)]
    argv += extra_args or []
    proc = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
    if proc.returncode != 0:
        raise RuntimeError(f"engine failed ({proc.returncode}): {' '.join(shlex.quote(a) for a in argv)}\n{proc.stderr[-4000:]}")
    decisions: list[Decision] = []
    summary: dict = {}
    for ln, line in enumerate(proc.stdout.splitlines(), 1):
        line = line.strip()
        if not line.startswith("{"):
            if line:
                eprint(f"engine stdout:{ln}: ignoring non-JSON line: {line[:120]}")
            continue
        obj = json.loads(line)
        if "summary" in obj:
            summary = obj["summary"] if isinstance(obj["summary"], dict) else {"summary": obj["summary"]}
            continue
        decisions.append(Decision(int(obj.get("i", -1)), str(obj.get("typed", "")), str(obj.get("commit", "")),
                                  str(obj.get("kind", "KEEP")), float(obj.get("conf", 0.0)),
                                  list(obj.get("alts", [])), list(obj.get("retro", [])), float(obj.get("ms", 0.0)),
                                  int(obj.get("tier", 1)), str(obj.get("sid", ""))))
    if proc.stderr.strip():
        eprint(proc.stderr.strip()[-2000:])
    return decisions, summary


def run_engine(engine: str, config: str | None, trace: str | Path, extra_args: list[str] | None = None,
               _mock_cache: dict = {}) -> tuple[list[Decision], dict]:
    """Dispatch on the engine spec (`mock`, `jar:<path>`, `cmd:<command>`)."""
    if engine == "mock":
        cfg = {}
        if config:
            with open(config, encoding="utf-8") as f:
                cfg = json.load(f)
        lex = _mock_cache.get("lexicon")
        if lex is None:
            lex = load_lexicon((cfg.get("mock") or {}).get("lexicon", LEXICON_PATH) if isinstance(cfg.get("mock"), dict) else LEXICON_PATH)
            _mock_cache["lexicon"] = lex
        eng = MockEngine(cfg, lex)
        return eng.run(list(iter_jsonl(trace)))
    if engine.startswith("jar:"):
        jar = engine[4:]
        if not Path(jar).exists():
            raise FileNotFoundError(f"engine jar not found: {jar}")
        return run_external(["java", "-jar", jar, "decode"], config, trace, extra_args)
    if engine.startswith("cmd:"):
        return run_external(shlex.split(engine[4:]), config, trace, extra_args)
    raise ValueError(f"unknown engine spec {engine!r} (use mock | jar:<path> | cmd:<command>)")


def add_engine_args(ap: argparse.ArgumentParser) -> None:
    ap.add_argument("--engine", default="mock", help="mock | jar:<engine-cli.jar> | cmd:<command>")
    ap.add_argument("--config", default=None, help="engine config JSON (engine.json); optional for mock")
    ap.add_argument("--engine-args", default="", help="extra args appended to an external engine command, e.g. '--tier2 none'")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    d = sub.add_parser("decode", help="decode a trace, print decision lines (same format as the engine CLI)")
    d.add_argument("--trace", required=True)
    add_engine_args(d)
    b = sub.add_parser("build-lexicon", help="build the mock engine lexicon from the sentence corpus")
    b.add_argument("--sentences", default=str(DATA_DIR / "sentences.txt"))
    b.add_argument("--out", default=str(LEXICON_PATH))
    a = ap.parse_args(argv)
    if a.cmd == "build-lexicon":
        from synth import NOVEL_SEEDS
        lex = build_lexicon(Path(a.sentences), Path(a.out), set(NOVEL_SEEDS) | {"asdf"})
        eprint(f"wrote {a.out}: {len(lex)} words")
        return 0
    decisions, summary = run_engine(a.engine, a.config, a.trace, shlex.split(a.engine_args))
    for dec in decisions:
        print(json.dumps(dec.to_json(), ensure_ascii=False))
    print(json.dumps({"summary": summary}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
