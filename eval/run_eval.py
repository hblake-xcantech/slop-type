#!/usr/bin/env python3
"""One-shot evaluation.

    run_eval.py --synthetic [--profiles sloppy,novel-heavy] [--n 0] [--seed 1] --engine mock
    run_eval.py --logs <dir of decrypted *.jsonl> --engine jar:engine/build/libs/engine-cli.jar --config cfg.json

--synthetic: generates (seeded) one trace per profile, replays each through the engine and
writes to eval/out/<timestamp>/ (override with --out-dir):
    traces/<profile>.jsonl   synthetic traces
    words_<profile>.csv      per-word records
    metrics.json             combined metrics (all profiles pooled) + "profiles": {...}
    table.txt                the printed table
--logs: replays every *.jsonl under the directory (real, decrypted logs) as one pool;
`--novel-from-lexicon` marks truth words outside the lexicon as novel.

metrics.json's top level is what promote.py consumes.
"""
from __future__ import annotations

import argparse
import json
import shlex
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import EVAL_DIR, eprint, find_trace_files, load_lexicon, write_jsonl  # noqa: E402
from engines import add_engine_args  # noqa: E402
from geometry import load_or_default  # noqa: E402
from metrics import compute_metrics, format_table, trace_counters, write_words_csv  # noqa: E402
from replay import replay  # noqa: E402
from synth import PROFILES, generate, load_sentences  # noqa: E402

DEFAULT_PROFILES = "sloppy,novel-heavy"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--synthetic", action="store_true")
    src.add_argument("--logs", default=None, help="directory of decrypted log JSONL files")
    ap.add_argument("--profiles", default=DEFAULT_PROFILES, help=f"comma list of {','.join(sorted(PROFILES))}")
    ap.add_argument("--n", type=int, default=0, help="sentences per profile (0 = all)")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--sentences", default=None)
    ap.add_argument("--geometry", default=None)
    ap.add_argument("--out-dir", default=None, help="default eval/out/<timestamp>")
    ap.add_argument("--novel-from-lexicon", default=None)
    ap.add_argument("--quiet", action="store_true")
    add_engine_args(ap)
    a = ap.parse_args(argv)

    out_dir = Path(a.out_dir) if a.out_dir else EVAL_DIR / "out" / time.strftime("%Y%m%d-%H%M%S")
    out_dir.mkdir(parents=True, exist_ok=True)
    engine_args = shlex.split(a.engine_args)
    novel_lex = load_lexicon(a.novel_from_lexicon) if a.novel_from_lexicon else None
    if a.engine.startswith("jar:") and not a.config:
        eprint("warning: jar engine without --config; the CLI contract expects one")

    per_profile: dict[str, dict] = {}
    all_records: list[dict] = []
    all_traces: list[Path] = []
    synth_stats: dict[str, dict] = {}
    if a.synthetic:
        sentences = load_sentences(a.sentences) if a.sentences else load_sentences()
        if a.n > 0:
            sentences = sentences[: a.n]
        geo = load_or_default(a.geometry)
        for pi, prof in enumerate([p.strip() for p in a.profiles.split(",") if p.strip()]):
            if prof not in PROFILES:
                eprint(f"unknown profile {prof!r}; choose from {sorted(PROFILES)}")
                return 2
            tr = out_dir / "traces" / f"{prof}.jsonl"
            events, st = generate(sentences, geo, PROFILES[prof], a.seed + pi, sid_prefix=prof)
            write_jsonl(tr, events)
            synth_stats[prof] = st
            try:
                records, m, _ = replay([tr], a.engine, a.config, engine_args, novel_lex)
            except (FileNotFoundError, RuntimeError, ValueError) as e:
                eprint(f"engine error: {e}")
                return 2
            per_profile[prof] = m
            write_words_csv(out_dir / f"words_{prof}.csv", records)
            all_records.extend(records)
            all_traces.append(tr)
    else:
        all_traces = find_trace_files(a.logs)
        if not all_traces:
            eprint(f"no *.jsonl under {a.logs}")
            return 2
        try:
            all_records, m, _ = replay(all_traces, a.engine, a.config, engine_args, novel_lex)
        except (FileNotFoundError, RuntimeError, ValueError) as e:
            eprint(f"engine error: {e}")
            return 2
        per_profile["logs"] = m
        write_words_csv(out_dir / "words_logs.csv", all_records)

    combined = compute_metrics(all_records, trace_counters(all_traces))
    combined["engine"] = a.engine
    combined["config"] = a.config
    combined["seed"] = a.seed
    combined["source"] = "synthetic" if a.synthetic else str(a.logs)
    combined["traces"] = [str(t) for t in all_traces]
    combined["profiles"] = per_profile
    if synth_stats:
        combined["synth"] = {k: {kk: vv for kk, vv in v.items() if kk != "params"} for k, v in synth_stats.items()}
    (out_dir / "metrics.json").write_text(json.dumps(combined, indent=2) + "\n", encoding="utf-8")
    table = format_table({**per_profile, "combined": combined} if len(per_profile) > 1 else per_profile)
    (out_dir / "table.txt").write_text(table + "\n", encoding="utf-8")
    if not a.quiet:
        print(f"engine={a.engine} config={a.config or '-'} out={out_dir}")
        print(table)
    return 0


if __name__ == "__main__":
    sys.exit(main())
