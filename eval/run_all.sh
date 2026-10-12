#!/usr/bin/env bash
# Smoke test for the eval harness: synth -> replay -> guard -> promote (against itself).
# Usage: eval/run_all.sh [--engine mock|jar:<path>] [--config cfg.json] [--n <sentences>]
# Exit code is non-zero if any step fails (including a guard failure or a REJECT).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY="${PYTHON:-python3}"
ENGINE="mock"
CONFIG=""
N=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --engine) ENGINE="$2"; shift 2 ;;
    --config) CONFIG="$2"; shift 2 ;;
    --n) N="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
CFG_ARGS=()
if [[ -n "$CONFIG" ]]; then CFG_ARGS=(--config "$CONFIG"); fi

OUT="$HERE/out/run_all-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
echo "== out: $OUT"

echo "== 1. geometry"
"$PY" -I "$HERE/geometry.py" generate

echo "== 2. mock lexicon"
"$PY" -I "$HERE/engines.py" build-lexicon

echo "== 3. synth (sloppy, seed 1)"
"$PY" -I "$HERE/synth.py" --profile sloppy --seed 1 --n "$N" --out "$OUT/sloppy.jsonl" --stats "$OUT/sloppy.synth.json"

echo "== 4. replay ($ENGINE)"
"$PY" -I "$HERE/replay.py" --trace "$OUT/sloppy.jsonl" --engine "$ENGINE" ${CFG_ARGS[@]+"${CFG_ARGS[@]}"} \
  --out-csv "$OUT/words.csv" --out-json "$OUT/metrics_replay.json" --quiet

echo "== 5. guard ($ENGINE)"
"$PY" -I "$HERE/guard.py" --engine "$ENGINE" ${CFG_ARGS[@]+"${CFG_ARGS[@]}"} --out "$OUT/guard.json" --only-failed

echo "== 6. run_eval --synthetic (sloppy + novel-heavy)"
"$PY" -I "$HERE/run_eval.py" --synthetic --n "$N" --engine "$ENGINE" ${CFG_ARGS[@]+"${CFG_ARGS[@]}"} --out-dir "$OUT/eval"

echo "== 7. promote (candidate == baseline, must PROMOTE)"
"$PY" -I "$HERE/promote.py" --baseline "$OUT/eval/metrics.json" --candidate "$OUT/eval/metrics.json" \
  --guard "$OUT/guard.json" --max-p95-ms 120 --max-cost-per-1k 0.02 --out "$OUT/promote.json"

echo "== OK: $OUT"
