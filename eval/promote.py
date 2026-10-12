#!/usr/bin/env python3
"""Promotion rule (DESIGN.md, "Eval harness"): decide whether a candidate engine/config
may replace the baseline.

    promote.py --baseline base_metrics.json --candidate cand_metrics.json --guard guard.json
               [--max-p95-ms 120] [--max-cost-per-1k 0.02] [--min-improvement 0.0]
               [--min-words 500] [--baseline-guard base_guard.json] [--out decision.json]

PROMOTE iff all of:
  1. candidate.primary <= baseline.primary - min_improvement   (primary: lower is better;
     primary = wrong_autocorrect_rate*2 + missed_correction_rate + (1 - novel_word_preservation))
  2. zero guard regressions: guard.failed == 0, or with --baseline-guard, no case that
     passed in the baseline fails in the candidate (the --allow-known-failures mode)
  3. candidate.latency_p95_ms <= max_p95_ms
  4. candidate.cost_per_1k_words <= max_cost_per_1k
  5. candidate.words >= min_words (the eval actually ran on enough data)

Prints PROMOTE or REJECT with reasons; exit 0 on PROMOTE, 1 on REJECT, 2 on bad input.
The metrics files are what replay.py / run_eval.py write (a top-level `primary` etc.).
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def load_json(path: str) -> dict:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def decide(baseline: dict, candidate: dict, guard: dict, max_p95_ms: float, max_cost_per_1k: float,
           min_improvement: float = 0.0, min_words: int = 500, baseline_guard: dict | None = None,
           allow_known_failures: bool = False) -> dict:
    reasons: list[str] = []
    ok = True

    def num(d: dict, k: str, default: float = float("nan")) -> float:
        v = d.get(k, default)
        try:
            return float(v)
        except (TypeError, ValueError):
            return default

    bp, cp = num(baseline, "primary"), num(candidate, "primary")
    if cp != cp or bp != bp:
        ok = False
        reasons.append("primary metric missing in baseline or candidate")
    elif cp <= bp - min_improvement:
        reasons.append(f"primary {cp:.4f} <= baseline {bp:.4f} - {min_improvement:.4f}  [ok]")
    else:
        ok = False
        reasons.append(f"primary {cp:.4f} > baseline {bp:.4f} - {min_improvement:.4f}  [FAIL]")

    failed_ids = [c["id"] for c in guard.get("cases", []) if not c.get("pass")]
    if baseline_guard is not None and allow_known_failures:
        base_pass = {c["id"] for c in baseline_guard.get("cases", []) if c.get("pass")}
        regressions = [i for i in failed_ids if i in base_pass]
        if regressions:
            ok = False
            reasons.append(f"guard regressions vs baseline: {', '.join(regressions)}  [FAIL]")
        else:
            reasons.append(f"guard: no regressions ({len(failed_ids)} known failures)  [ok]")
    else:
        if guard.get("failed", len(failed_ids)):
            ok = False
            reasons.append(f"guard failures: {', '.join(failed_ids) or guard.get('failed')}  [FAIL]")
        else:
            reasons.append(f"guard: {guard.get('passed', '?')}/{guard.get('total', '?')} passed  [ok]")

    p95 = num(candidate, "latency_p95_ms", 0.0)
    if p95 <= max_p95_ms:
        reasons.append(f"p95 latency {p95:.1f} ms <= {max_p95_ms:.1f}  [ok]")
    else:
        ok = False
        reasons.append(f"p95 latency {p95:.1f} ms > {max_p95_ms:.1f}  [FAIL]")

    cost = num(candidate, "cost_per_1k_words", 0.0)
    if cost <= max_cost_per_1k:
        reasons.append(f"cost {cost:.5f} USD/1k words <= {max_cost_per_1k}  [ok]")
    else:
        ok = False
        reasons.append(f"cost {cost:.5f} USD/1k words > {max_cost_per_1k}  [FAIL]")

    words = int(num(candidate, "words", 0))
    if words >= min_words:
        reasons.append(f"words {words} >= {min_words}  [ok]")
    else:
        ok = False
        reasons.append(f"words {words} < {min_words}  [FAIL]")

    deltas = {k: round(num(candidate, k, 0.0) - num(baseline, k, 0.0), 4) for k in
              ("primary", "wrong_autocorrect_rate", "missed_correction_rate", "novel_word_preservation",
               "word_error_rate_after", "latency_p95_ms", "cost_per_1k_words")}
    return {"decision": "PROMOTE" if ok else "REJECT", "reasons": reasons, "deltas": deltas,
            "budgets": {"max_p95_ms": max_p95_ms, "max_cost_per_1k": max_cost_per_1k,
                        "min_improvement": min_improvement, "min_words": min_words}}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--baseline", required=True, help="baseline metrics JSON")
    ap.add_argument("--candidate", required=True, help="candidate metrics JSON")
    ap.add_argument("--guard", required=True, help="candidate guard result JSON (guard.py --out)")
    ap.add_argument("--baseline-guard", default=None, help="baseline guard result JSON (for --allow-known-failures)")
    ap.add_argument("--allow-known-failures", action="store_true",
                    help="only cases that passed in --baseline-guard count as regressions")
    ap.add_argument("--max-p95-ms", type=float, default=120.0)
    ap.add_argument("--max-cost-per-1k", type=float, default=0.02)
    ap.add_argument("--min-improvement", type=float, default=0.0,
                    help="required drop in primary (0 = must not be worse; the autopilot should use e.g. 0.002)")
    ap.add_argument("--min-words", type=int, default=500)
    ap.add_argument("--out", default=None, help="write the decision JSON here")
    a = ap.parse_args(argv)
    try:
        baseline, candidate, guard = load_json(a.baseline), load_json(a.candidate), load_json(a.guard)
        bg = load_json(a.baseline_guard) if a.baseline_guard else None
    except (OSError, json.JSONDecodeError) as e:
        print(f"REJECT: cannot read inputs: {e}")
        return 2
    res = decide(baseline, candidate, guard, a.max_p95_ms, a.max_cost_per_1k, a.min_improvement, a.min_words,
                 bg, a.allow_known_failures)
    print(res["decision"])
    for r in res["reasons"]:
        print("  -", r)
    print("  deltas:", json.dumps(res["deltas"]))
    if a.out:
        Path(a.out).parent.mkdir(parents=True, exist_ok=True)
        Path(a.out).write_text(json.dumps(res, indent=2) + "\n", encoding="utf-8")
    return 0 if res["decision"] == "PROMOTE" else 1


if __name__ == "__main__":
    sys.exit(main())
