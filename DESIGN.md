# Slop Type — system design and module contracts

Slop Type is a HeliBoard fork (GPL-3.0) whose entire "intelligence" lives in the
standalone `engine/` module (pure Kotlin/JVM, no Android dependencies). The Android
app (`app/`) is a thin shell: it captures raw touches, hands them to the engine, and
applies the text edits the engine returns. Everything else (logging, remote tiers,
daemon, eval) is built around the engine's contracts below. Those contracts are the
things the nightly autopilot is allowed to depend on.

```
raw touches + key geometry + surrounding text + field metadata
        │
        ▼
   engine/  (spatial decoder ▸ n-gram LM beam ▸ policy ▸ router)  ──▶ text edits
        │                                              ▲
        ├── logs (JSONL, encrypted with age by the daemon before upload)
        └── configs (hot-reloaded JSON from slop-type-data)
```

## Repos

| repo | visibility | contents |
|---|---|---|
| `slop-type` | public | app, engine, llamabridge, agekt, eval harness, CI, AUTOPILOT.md, STATUS.md |
| `slop-type-data` | private | `logs/<device>/<yyyy-mm-dd>/<sid>.jsonl.age`, `configs/engine.json`, `configs/router.json`, `configs/prompts/*.txt`, `lexicon/personal.json`, `benchmarks/<date>.json`, `evals/<date>.json` |

## Gradle modules

- `app` — HeliBoard + Slop Type integration (Android).
- `engine` — Kotlin/JVM library. Also builds `engine-cli` fat jar (`./gradlew :engine:cliJar`) used by the eval harness.
- `agekt` — Kotlin/JVM implementation of the `age` file format (X25519 recipients only). Tested against the `age` CLI.
- `llamabridge` — Android library wrapping llama.cpp via JNI. Exposes `NeuralLm` (see engine interface) to the app.

## Engine API (package `dev.sloptype.engine`)

```kotlin
data class KeyGeom(val code: Int, val label: String, val x: Float, val y: Float, val w: Float, val h: Float)
data class KeyboardGeometry(val id: String, val width: Float, val height: Float, val keys: List<KeyGeom>)
data class Touch(val x: Float, val y: Float, val t: Long, val pressure: Float, val size: Float, val code: Int)
data class FieldInfo(val packageName: String, val inputType: Int, val imeOptions: Int, val sensitive: Boolean)

enum class DecisionKind { KEEP, CORRECT, NOVEL, PENDING }
data class RetroEdit(val wordsBack: Int, val oldText: String, val newText: String)
data class WordDecision(
    val typed: String, val commit: String, val kind: DecisionKind, val confidence: Float,
    val alternatives: List<String>, val retro: List<RetroEdit>, val tier: Int, val latencyMs: Long,
    val pendingId: Long?)
data class Ghost(val prefix: String, val completion: String)

interface SlopEngine {
    fun startField(field: FieldInfo, geometry: KeyboardGeometry, textBeforeCursor: String)
    fun endField(textBeforeCursor: String)
    fun onTouch(touch: Touch)                    // raw down/up for the key that produced a code
    fun onKey(code: Int, touch: Touch?)          // a letter/connector appended to the composing word
    fun onBackspace(composing: Boolean, deletedChar: Char?)
    fun onSeparator(separator: String, textBeforeCursor: String): WordDecision
    fun onCommitted(decision: WordDecision)      // the shell applied it
    fun onRevert(decision: WordDecision)         // user backspaced right after, original restored
    fun onCursorMoved(textBeforeCursor: String)
    fun onMistakeMarker()                        // MISTAKE key tapped: next word is a replacement
    fun resolveMistake(replacement: String, textBeforeCursor: String): MistakeResolution
    fun ghost(): Ghost?
    fun pendingResult(pendingId: Long): WordDecision?   // remote answer landed?
    fun onScreenText(words: Collection<String>)  // vocabulary bias from accessibility/root context
    fun reloadConfig(json: String)
    fun snapshotPersonalLexicon(): String        // JSON
}
```

### Decision rules (the policy)
1. Beam-decode the touch sequence against the lexicon trie: spatial likelihood (per-key 2D
   Gaussian under the current posture hypothesis) × edit operations (omission, insertion,
   transposition, double-tap) × n-gram LM prior (bigram with backoff, personal lexicon boost,
   screen-vocabulary boost).
2. `literal` = the key sequence as resolved by the keyboard. If `literal` is in the lexicon
   (main or personal) and is within `keepMargin` of the best candidate → KEEP.
3. If `literal` is OOV: compute `precision` = mean per-tap likelihood of the literal keys under
   the posture model. High precision + best candidate needs non-spatial edits → NOVEL (keep
   as typed, add to personal lexicon as tentative). Sloppy taps near a known word → CORRECT.
4. If the margin between KEEP/NOVEL and CORRECT is inside the uncertainty band → PENDING:
   a speculative tier-2 request is fired *while typing* (debounced); if it has landed by the
   time the separator arrives it decides; otherwise the word is committed as typed with a
   faint underline and resolved once, never swap-then-revert.
5. The band and margins are the output of an online logistic model updated from reverts,
   backspace-and-retype, and accepted corrections. There are no user-facing thresholds.
6. Guard rails that never change: `teh`→`the` must correct; a word the user retyped after a
   revert is locked for the session and learned; the corrected-to word is never penalised.

### Config (`configs/engine.json`, hot-reloaded)
See `engine/src/main/resources/engine-default.json`. Every numeric parameter the autopilot
may tune lives there. Unknown keys are ignored; missing keys fall back to defaults.

## Log schema (JSONL, one event per line)

All events carry `t` (ms since epoch) and `sid` (session id). Never written for sensitive
fields (password input types, `IME_FLAG_NO_PERSONALIZED_LEARNING`) or excluded packages.

| ev | fields |
|---|---|
| `session_start` | `pkg`, `inputType`, `imeOptions`, `geom` (KeyboardGeometry), `device`, `app_version`, `engine_config_hash` |
| `touch` | `x`, `y`, `p` (pressure), `s` (size), `a` (`d`/`u`), `code` |
| `key` | `code`, `x`, `y`, `repeat` |
| `backspace` | `composing`, `count` |
| `decision` | `typed`, `commit`, `kind`, `conf`, `tier`, `latencyMs`, `alts`, `retro`, `pendingId?` |
| `pending_resolved` | `pendingId`, `kind`, `commit`, `latencyMs`, `tier` |
| `revert` | `from`, `to` |
| `retype` | `word`, `previouslyCorrectedTo` |
| `mistake` | `replacement`, `chosenSpan`, `candidates`, `shifted` |
| `ghost_accept` | `prefix`, `completion` |
| `truth` | `text` (last ≤240 chars before cursor; at sentence boundaries and on field exit) |
| `session_end` | — |
| `remote` | `tier`, `model`, `ok`, `latencyMs`, `inTokens`, `outTokens`, `costUsd` |

## Engine CLI (used by `eval/`)

```
java -jar engine/build/libs/engine-cli.jar decode --config engine.json --trace in.jsonl > out.jsonl
```
Input: a log-schema JSONL stream (synthetic or real). Output: one line per `decision` event:
`{"i": <event index>, "typed": ..., "commit": ..., "kind": ..., "conf": ..., "alts": [...], "retro": [...], "ms": <decode ms>}`
plus, for traces containing `truth` events, a trailing `{"summary": {...}}` line. Remote tiers
are stubbed in the CLI (`--tier2 none|mock|file:answers.jsonl`).

## Keyboard geometry used for synthetic data

`eval/data/geometry/pixel10pro_qwerty.json` is exported by the app (`adb shell am broadcast
-a dev.sloptype.EXPORT_GEOMETRY`). Until the app has run, `eval/synth.py` falls back to a
generated QWERTY grid matching the Pixel 10 Pro portrait keyboard (1080 px wide, 4 rows).

## Daemon (inside the app, root via `su`)

Periodic job (WorkManager, constraints: unmetered + charging; also triggerable by
`adb shell am broadcast -a dev.sloptype.DAEMON_RUN_NOW`):
1. Rotate the current log, encrypt each closed log with `agekt` to the recipient in
   `configs/recipients.txt`, upload via GitHub Contents API to `slop-type-data/logs/...`,
   delete local plaintext after upload is confirmed.
2. Pull `configs/*` from `slop-type-data`; if the sha changed, write to the app's files dir
   and call `engine.reloadConfig()` (hot reload, no reinstall).
3. Check `GET /repos/<owner>/slop-type/releases/latest`; if the tag is newer than the
   installed `versionName`, download the APK asset, verify its signature matches ours,
   keep the current APK as `last-good.apk`, `su -c pm install -r`.
4. Rollback watchdog: for 6 h after an install, compare corrections-per-word (engine-side
   counter) against the pre-install 7-day baseline; if > 1.8× with ≥ 200 words,
   `su -c pm install -r -d last-good.apk` and write `rollback.json` to the data repo.
5. Writes `daemon-status.json` (last sync, bytes uploaded, config sha, apk tag, spend).

Secrets live in `/data/data/dev.sloptype/files/secrets.json` (root-only, written by
`scripts/provision_phone.sh`) and never in a repo: `github_token` (fine-grained PAT:
`slop-type-data` contents read/write, `slop-type` read), `openai_api_key` (tier 2),
`chatgpt_oauth` (tier 3, obtained in-app via Sign in with ChatGPT).

## Inference tiers and router

| tier | where | used for | cost |
|---|---|---|---|
| 1 | on-device: spatial decoder + n-gram + llama.cpp (`llamabridge`) | every word; ghost completion; retro revision | 0 |
| 2 | fast remote classifier (OpenAI-compatible, provider from `router.json`) | ambiguous per-word decisions (keep / correct-to-X / novel) | paid, monthly cap |
| 3 | ChatGPT subscription models (OAuth) | sentence rewrites at pauses/punctuation; novel-word lookups; nightly work | included |

`router.json` holds per-situation tier preferences learned from eval data
(`accuracy × latency × cost`), the monthly tier-2 cap in USD, and the current month's spend.
When the cap is hit, tier 2 is disabled until the 1st; tiers 1/3 continue.

## Eval harness (`eval/`, Python 3.11+)

- `synth.py` — typo simulator: text → touch traces (Gaussian key noise, thumb-skew drift,
  fat-finger neighbours, transpositions, omissions, doubles, novel words).
- `replay.py` — log JSONL → engine CLI → compare with `truth`.
- `metrics.py` — corrections/word, backspaces/word, wrong-autocorrect rate, novel-word
  preservation, latency p50/p95, cost/1k words.
- `guard.py` — guard set (`eval/data/guard.jsonl`) that must never regress.
- `promote.py` — promotion rule: primary metric (wrong-autocorrect rate + missed-correction
  rate, lower is better) improves, zero guard regressions, p95 latency and cost within budget.
- `bench.py` — triggers the in-app tier benchmark over adb, collects results.

## CI

`.github/workflows/ci.yml`: on PR and push to main — build `engine` + unit tests, build
`engine-cli`, run `eval/run_eval.py --synthetic`, build the signed release APK. On push to
main: create a GitHub Release `v<versionName>` with the APK attached. The phone's daemon
installs it.
