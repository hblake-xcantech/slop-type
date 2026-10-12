#!/usr/bin/env python3
"""Guard set: hand-written cases that must never regress (eval/data/guard.jsonl).

Each line:
    {"id": "typo-teh", "context": "I think", "typed": "teh", "touches": "precise|sloppy|null",
     "expect": "the", "personal": ["hblake"], "note": "..."}

* `typed`    - the keys the user aims for (the literal is whatever the noisy taps resolve to)
* `touches`  - how the taps are synthesised: precise (sigma 0.1 key), sloppy (sigma 0.35 key),
               null (no touch events at all: only `key` events)
* `expect`   - the required commit; compared with the replay rule (case-insensitive unless
               `expect` is capitalised)
* `personal` - words put in the engine's personal lexicon for the run, passed via the
               config key `personalLexicon` (unknown keys are ignored by the engine per
               DESIGN.md). Cases are grouped by their `personal` set and each group is one
               engine run, so cases without `personal` see a clean lexicon.
* `context`  - words typed precisely before the target (lets the engine use its LM).

One session per case is synthesised from the geometry with a fixed seed, each group is run
through the engine once, and the last decision of each session is compared with `expect`.
Exit code 1 on any failure.

    guard.py --engine mock|jar:<path> [--config engine.json] [--out guard.json] [--seed 7]
"""
from __future__ import annotations

import argparse
import json
import random
import shlex
import sys
import tempfile
from dataclasses import replace
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import DATA_DIR, eprint, iter_jsonl, read_jsonl, strip_punct, word_matches, write_jsonl  # noqa: E402
from engines import Decision, add_engine_args, run_engine  # noqa: E402
from geometry import load_or_default  # noqa: E402
from replay import decisions_by_sid  # noqa: E402
from synth import BASE_T, NoiseParams, Posture, Synth  # noqa: E402

GUARD_PATH = DATA_DIR / "guard.jsonl"
TOUCH_PARAMS = {
    "precise": NoiseParams(sigma_x=0.10, sigma_y=0.10),
    "sloppy": NoiseParams(sigma_x=0.35, sigma_y=0.35),
    "null": NoiseParams(sigma_x=0.10, sigma_y=0.10),
}
_STILL = dict(drift_step=0.0, drift_max=0.0, skew=0.0, rotation=0.0, p_neighbor=0.0, p_transpose=0.0,
              p_omit=0.0, p_double=0.0, p_insert=0.0, novel_rate=0.0)


def build_trace(cases: list[dict], geo, seed: int) -> list[dict]:
    events: list[dict] = []
    t = BASE_T
    for n, c in enumerate(cases):
        mode = c.get("touches", "precise")
        if mode not in TOUCH_PARAMS:
            raise ValueError(f"case {c.get('id')}: touches must be precise|sloppy|null")
        rng = random.Random(f"{seed}:{c['id']}")
        sid = str(c["id"])
        params = replace(TOUCH_PARAMS[mode], **_STILL)
        precise = replace(TOUCH_PARAMS["precise"], **_STILL)
        ev: list[dict] = [{"ev": "session_start", "t": t, "sid": sid, "pkg": "dev.sloptype.guard", "inputType": 1,
                           "imeOptions": 0, "geom": geo.to_dict(), "device": "synth", "app_version": "guard-0.1",
                           "engine_config_hash": ""}]
        post = Posture()
        syn = Synth(geo, precise, rng, set())
        for wi, w in enumerate(str(c.get("context", "")).split()):
            t += syn.dt()
            t, _ = syn.type_word(ev, sid, t, strip_punct(w), post, novel=False, sentence_start=(wi == 0))
            t += syn.dt()
            t = syn.type_separator(ev, sid, t, " ", post)
        syn.p = params
        t += syn.dt()
        start = len(ev)
        t, literal = syn.type_word(ev, sid, t, str(c["typed"]), post, novel=False, sentence_start=False)
        if mode == "null":
            ev = ev[:start] + [e for e in ev[start:] if e["ev"] != "touch"]
        c["literal"] = literal
        t += syn.dt()
        t = syn.type_separator(ev, sid, t, " ", post)
        t += 300
        ev.append({"ev": "session_end", "t": t, "sid": sid})
        events.extend(ev)
        t += 5_000
    return events


def run_guard(cases: list[dict], engine: str, config: str | None, seed: int, engine_args: list[str] | None,
              geometry: str | None = None, keep_trace: str | None = None) -> dict:
    geo = load_or_default(geometry)
    base_cfg: dict = {}
    if config:
        with open(config, encoding="utf-8") as f:
            base_cfg = json.load(f)
    groups: dict[tuple[str, ...], list[dict]] = {}
    for c in cases:
        groups.setdefault(tuple(sorted(str(w) for w in (c.get("personal") or []))), []).append(c)
    results_by_id: dict[str, dict] = {}
    summaries: list[dict] = []
    with tempfile.TemporaryDirectory(prefix="guard-") as td:
        for gi, (personal, group) in enumerate(groups.items()):
            events = build_trace(group, geo, seed)
            trace = Path(td) / f"guard_trace_{gi}.jsonl"
            write_jsonl(trace, events)
            if keep_trace:
                kp = Path(keep_trace)
                write_jsonl(kp if gi == 0 else kp.with_name(f"{kp.stem}_{gi}{kp.suffix}"), events)
            cfg = dict(base_cfg)
            cfg["personalLexicon"] = sorted(set(cfg.get("personalLexicon", [])) | set(personal))
            cfg_path = Path(td) / f"guard_config_{gi}.json"
            cfg_path.write_text(json.dumps(cfg, indent=1), encoding="utf-8")
            decisions, summary = run_engine(engine, str(cfg_path), trace, engine_args)
            summaries.append({"personal": list(personal), **summary})
            by_sid = decisions_by_sid(events, decisions)
            for c in group:
                ds = by_sid.get(str(c["id"]), [])
                d: Decision | None = ds[-1] if ds else None
                got = d.commit if d else ""
                ok = d is not None and word_matches(got, str(c["expect"]))
                results_by_id[str(c["id"])] = {
                    "id": c["id"], "touches": c.get("touches", "precise"), "typed": c["typed"],
                    "literal": c.get("literal", ""), "expect": c["expect"], "got": got,
                    "kind": d.kind if d else "", "conf": d.conf if d else None, "pass": ok,
                    "personal": list(personal), "note": c.get("note", "")}
    results = [results_by_id[str(c["id"])] for c in cases]
    n_pass = sum(1 for r in results if r["pass"])
    return {"engine": engine, "seed": seed, "total": len(results), "passed": n_pass,
            "failed": len(results) - n_pass, "engine_runs": summaries, "cases": results}


def format_results(res: dict, only_failed: bool = False) -> str:
    lines = []
    for r in res["cases"]:
        if only_failed and r["pass"]:
            continue
        mark = "PASS" if r["pass"] else "FAIL"
        lit = f" literal={r['literal']}" if r["literal"] and r["literal"] != r["typed"] else ""
        lines.append(f"{mark} {r['id']:<26} [{r['touches']:<7}] typed={r['typed']}{lit} expect={r['expect']} "
                     f"got={r['got']} ({r['kind']})")
    lines.append(f"guard: {res['passed']}/{res['total']} passed, {res['failed']} failed  engine={res['engine']}")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cases", default=str(GUARD_PATH))
    ap.add_argument("--geometry", default=None)
    ap.add_argument("--seed", type=int, default=7, help="fixed seed for the synthesised touches")
    ap.add_argument("--out", default=None, help="write the result JSON here")
    ap.add_argument("--keep-trace", default=None, help="also write the synthesised guard trace here")
    ap.add_argument("--only-failed", action="store_true", help="print only failing cases")
    add_engine_args(ap)
    a = ap.parse_args(argv)
    cases = read_jsonl(a.cases)
    try:
        res = run_guard(cases, a.engine, a.config, a.seed, shlex.split(a.engine_args), a.geometry, a.keep_trace)
    except (FileNotFoundError, RuntimeError, ValueError) as e:
        eprint(f"engine error: {e}")
        return 2
    print(format_results(res, a.only_failed))
    if a.out:
        Path(a.out).parent.mkdir(parents=True, exist_ok=True)
        Path(a.out).write_text(json.dumps(res, indent=2) + "\n", encoding="utf-8")
    return 0 if res["failed"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
