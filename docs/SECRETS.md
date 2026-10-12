# Secrets: what exists, where it lives, who needs it

Nothing below is in either repo. Keep a copy of everything in 1Password.

## Already created (on this Mac, `~/sloptype/secrets/`)

| item | file | used by |
|---|---|---|
| APK signing keystore + passwords | `sloptype-release.jks`, `keystore.env` | local release builds; uploaded to `slop-type` Actions secrets `SLOPTYPE_KEYSTORE_BASE64`, `SLOPTYPE_KEYSTORE_PASSWORD`, `SLOPTYPE_KEY_ALIAS`, `SLOPTYPE_KEY_PASSWORD` (done) |
| age identity (log decryption key) | `age-identity.txt` | the nightly autopilot (`AGE_SECRET_KEY`). Its public key is in `slop-type-data/configs/recipients.txt` and baked into the app as the default recipient |

If the keystore is lost, the phone can no longer upgrade in place (signature mismatch);
you would have to uninstall and reinstall once.

## You need to provide

| secret | where it goes | how |
|---|---|---|
| GitHub fine-grained PAT for the phone: repo access `slop-type-data` (Contents: read/write) and `slop-type` (Contents: read, Metadata: read) | phone only | `scripts/provision_phone.sh --github-token '<pat>'` |
| GitHub PAT for the autopilot: `slop-type` (Contents, Pull requests, Actions, Workflows: read/write) + `slop-type-data` (Contents: read/write) | cloud session env `GITHUB_TOKEN` | configure in the scheduled session's secrets |
| age secret key | cloud session env `AGE_SECRET_KEY` | contents of `~/sloptype/secrets/age-identity.txt` (the `AGE-SECRET-KEY-1…` line) |
| OpenAI API key (tier 2, paid) | phone (`--openai-key`) and optionally the autopilot (`OPENAI_API_KEY`) for offline benchmarks | only after you pick a provider and set `tier2MonthlyCapUsd` in `slop-type-data/configs/router.json` |
| TypeSafe Jev API key (tier 2, paid) | phone (`--jev-key`) | same |
| Sign in with ChatGPT (tier 3) | phone only | Slop Type settings → "Sign in with ChatGPT" (OAuth; tokens stay on the phone) |
| Monthly tier-2 cap (USD) | `slop-type-data/configs/router.json` → `tier2MonthlyCapUsd` | tell me the number, or edit the file |

On the phone, secrets live at `/data/user_de/0/dev.sloptype/files/sloptype/secrets.json`
(root-only). `scripts/provision_phone.sh --status` shows the daemon state without printing secrets.
