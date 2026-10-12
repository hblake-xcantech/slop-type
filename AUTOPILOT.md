# AUTOPILOT.md — nightly instructions for the cloud Claude session

You are the autonomous maintainer of Slop Type. You run once a night. Nobody approves your
work; the gates are CI, the eval harness and the guard set. You may change code, configs,
router weights and prompts. You may not change the guard set to make it pass, lower budgets,
add user-facing thresholds, or touch anything in `docs/SECRETS.md`'s "never in a repo" list.

Repos: `hblake-xcantech/slop-type` (public, code) and `hblake-xcantech/slop-type-data`
(private, data + configs). Read `DESIGN.md` once; it is the contract.

## Secrets you are given (as environment variables)
- `GITHUB_TOKEN` — push to both repos, open/merge PRs in `slop-type`.
- `AGE_SECRET_KEY` — decrypts `logs/**/*.age` in `slop-type-data`.
- `OPENAI_API_KEY` / `JEV_API_KEY` — only for offline evals of tier 2 (never commit).

## Nightly procedure

1. **Sync.** Clone both repos (`git clone --recurse-submodules` for slop-type). Install JDK 17,
   Android command-line tools only if you will build the APK (CI does that; you usually don't).
   `./gradlew :engine:test :engine:cliJar` must pass before anything else.

2. **Decrypt new logs.** For every `logs/<device>/<date>/*.age` not yet listed in
   `evals/processed.txt`: `age -d -i <(echo "$AGE_SECRET_KEY") file.age > work/logs/<name>.jsonl`.
   Never commit decrypted logs. Append the names to `evals/processed.txt` at the end.

3. **Replay and measure.**
   - `python3 -I eval/run_eval.py --logs work/logs --engine jar:engine/build/libs/engine-cli.jar --config configs/engine.json --out work/real`
   - `python3 -I eval/run_eval.py --synthetic --engine jar:... --config configs/engine.json --out work/synth`
   - `python3 -I eval/guard.py --engine jar:... --config configs/engine.json --out work/guard.json`
   Real-log metrics are the primary signal; synthetic is the regression floor. Also read
   `metrics/<device>/counters.json` (on-device corrections, reverts, backspaces per day) and
   `benchmarks/<device>/*.json` (tier latency/accuracy/cost from the phone).

4. **Tune configs** (`configs/engine.json`, `configs/router.json`, `configs/prompts/*`):
   - Propose at most a handful of parameter changes per night (spatial sigmas, edit penalties,
     policy weights, LM weight, ghost/retro margins, tier bands). Evaluate each candidate
     with `eval/replay.py` on real logs + synthetic + guard.
   - Promotion rule (`eval/promote.py`): primary metric improves on real logs by ≥ 0.002
     (or on synthetic if there are < 500 real words), zero guard failures, p95 decode ≤ 120 ms,
     cost/1k words within the cap. If it promotes, commit the new config to `slop-type-data`
     with a message that states the metric before/after. If not, leave the config alone.
   - Router: from the benchmarks, pick for each situation the tier with the best
     `accuracy × (1 / latency) × (1 / cost)` ordering that stays under `tier2MaxP95Ms`.
     Write `preference` in `router.json`. Never raise `tier2MonthlyCapUsd`.
   - Personal lexicon: merge words learned on the phone (from `retype`/`revert` events and
     `NOVEL` decisions that were never reverted) into `lexicon/personal.json`; drop words that
     the user later corrected away. Never add words from `engine/src/main/resources/never_learn.txt`.

5. **Code improvements.** If the logs show a systematic failure (a class of typo the decoder
   cannot produce, a flicker, a crash in `logcat`-style error events, a slow decode), fix it
   in a branch, add a unit test or a guard case that captures it, open a PR with the eval
   numbers in the description, and merge it yourself when CI (engine tests + guard +
   synthetic eval + release build) is green. Bump the version with `scripts/bump_version.sh patch`
   in the same PR so a release is published; the phone installs it automatically and rolls
   back on regression. One code PR per night at most.

6. **Spend.** Sum `remote.costUsd` from the night's logs and the `spend` in
   `status/<device>/daemon-status.json`. If projected monthly spend exceeds 80% of
   `tier2MonthlyCapUsd`, lower the tier-2 preference for the least valuable situation.

7. **Write `STATUS.md`** in `slop-type` (commit to main directly): what changed tonight,
   the metric trend (last 7 nights: primary, wrong-autocorrect rate, novel preservation,
   corrections/word on device), spend vs cap, benchmark summary per tier, and anything stuck
   (missing secrets, failing CI, no new logs). Keep it under 60 lines.

8. **Housekeeping.** Commit `evals/<date>.json` (metrics only, no text) to `slop-type-data`.
   Delete `work/`.

## Hard rules
- Guard set must stay 65/65 (or grow). `teh`→`the` and precise novel words are sacred.
- Never log, print or commit decrypted log text, tokens or keys.
- Never disable logging, encryption, the sensitive-field filter or the excluded-app list.
- If anything needs a human (expired token, cap decision, a change you are unsure about),
  write it under "Needs Blake" in STATUS.md and stop that thread; do everything else.
