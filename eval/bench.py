#!/usr/bin/env python3
"""Trigger the in-app inference-tier benchmark over adb and collect its result.

    bench.py --out eval/out/bench.json [--serial <adb serial>] [--timeout 600] [--dry-run]

Steps:
  1. `adb shell am broadcast -a dev.sloptype.BENCH_RUN`   (the app runs every tier/model
     on a fixed word set over the current network and writes latest.json when done)
  2. poll `adb shell su -c 'cat /data/data/dev.sloptype/files/bench/latest.json'` until a
     result with a `started` timestamp newer than the trigger appears (or --timeout)
  3. write it to --out

Expected result JSON written by the app (`bench/latest.json`):

    {
      "started": 1760000000000, "finished": 1760000042000,      // ms since epoch
      "device": "Pixel 10 Pro", "app_version": "0.3.1", "engine_config_hash": "…",
      "network": "wifi" | "cell",
      "results": [
        {"tier": 1, "model": "spatial+ngram",      "n": 500, "p50": 3.1,  "p95": 9.8,   "errors": 0, "costUsd": 0.0},
        {"tier": 1, "model": "llama-q4-small",     "n": 200, "p50": 41.0, "p95": 88.0,  "errors": 0, "costUsd": 0.0},
        {"tier": 2, "model": "<router.json tier2>", "n": 100, "p50": 310.0, "p95": 720.0, "errors": 2, "costUsd": 0.0113},
        {"tier": 3, "model": "<chatgpt model>",     "n": 20,  "p50": 1900.0, "p95": 4100.0, "errors": 0, "costUsd": 0.0}
      ]
    }

p50/p95 are end-to-end latencies in ms per request, `n` the number of requests, `errors`
failed requests (timeouts, HTTP errors), `costUsd` the summed cost reported by the
provider for the run. The autopilot stores the file as `benchmarks/<date>.json` in
slop-type-data and feeds p95/cost into router.json.
"""
from __future__ import annotations

import argparse
import json
import shlex
import subprocess
import sys
import time
from pathlib import Path

BROADCAST = "dev.sloptype.BENCH_RUN"
RESULT_PATH = "/data/data/dev.sloptype/files/bench/latest.json"


def adb(serial: str | None, *args: str) -> list[str]:
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    return cmd + list(args)


def run(cmd: list[str], dry_run: bool, timeout: float = 60.0) -> str:
    print("$", " ".join(shlex.quote(c) for c in cmd), file=sys.stderr)
    if dry_run:
        return ""
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if p.returncode != 0:
        raise RuntimeError(f"command failed ({p.returncode}): {p.stderr.strip()[-500:]}")
    return p.stdout


def read_result(serial: str | None, dry_run: bool) -> dict | None:
    out = run(adb(serial, "shell", "su", "-c", f"cat {RESULT_PATH}"), dry_run, timeout=30)
    out = out.strip()
    if not out or not out.startswith("{"):
        return None
    try:
        return json.loads(out)
    except json.JSONDecodeError:
        return None


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True, help="where to write the benchmark JSON")
    ap.add_argument("--serial", default=None, help="adb device serial (default: the only connected device)")
    ap.add_argument("--timeout", type=float, default=600.0, help="seconds to wait for the result")
    ap.add_argument("--poll", type=float, default=5.0, help="seconds between polls")
    ap.add_argument("--dry-run", action="store_true", help="print the adb commands without running them")
    a = ap.parse_args(argv)

    trigger_ms = int(time.time() * 1000)
    run(adb(a.serial, "shell", "am", "broadcast", "-a", BROADCAST), a.dry_run)
    if a.dry_run:
        run(adb(a.serial, "shell", "su", "-c", f"cat {RESULT_PATH}"), True)
        print(f"(dry run) would poll every {a.poll}s for up to {a.timeout}s and write {a.out}")
        return 0
    deadline = time.time() + a.timeout
    result: dict | None = None
    while time.time() < deadline:
        time.sleep(a.poll)
        r = read_result(a.serial, False)
        if r and int(r.get("started", 0)) >= trigger_ms - 60_000 and r.get("finished"):
            result = r
            break
    if result is None:
        print(f"bench: no result within {a.timeout}s", file=sys.stderr)
        return 1
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    for r in result.get("results", []):
        print(f"tier {r.get('tier')} {r.get('model', ''):<28} n={r.get('n')} p50={r.get('p50')} p95={r.get('p95')} "
              f"errors={r.get('errors')} cost={r.get('costUsd')} network={result.get('network')}")
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
