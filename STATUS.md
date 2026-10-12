# STATUS

_Last updated: 2026-10-12 (initial build, written by the bootstrap session)_

## What changed
- Forked HeliBoard v4.2 → Slop Type 0.1.0. Engine module (spatial noisy-channel beam decoder,
  bigram LM, posture/EM adaptation, self-calibrating policy, personal lexicon, MISTAKE
  alignment, retro revision, tier router), llama.cpp bridge, age encryption, root daemon,
  remote tiers (Jev / OpenAI Decisions / Responses, Sign in with ChatGPT), eval harness, CI.

## Metrics
| set | primary ↓ | wrong-autocorrect | missed-correction | novel preservation | p95 decode |
|---|---|---|---|---|---|
| guard (65 cases) | 62/65 pass | — | — | — | — |
| synthetic sloppy / novel-heavy | see `eval/baseline/metrics.json` once CI publishes it | | | | ~2 ms |
| real logs | none yet | | | | |

On-device corrections/word: no data yet.

## Spend vs cap
Tier 2 provider: none (no key provisioned). Cap: $0 (unset). Spend: $0.

## Needs Blake
- Fine-grained GitHub PAT for the phone (see `docs/SECRETS.md`), then `scripts/provision_phone.sh`.
- Tier-2 decision: provider (Jev recommended) + monthly cap in USD.
- Schedule the nightly session with `AUTOPILOT.md` and the three env secrets.
