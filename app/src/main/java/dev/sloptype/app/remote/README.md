# `dev.sloptype.app.remote` — inference tiers 2 and 3, on-device neural LM, benchmark

Wired by `RemoteWiring.install(ctx)` (call from `App.onCreate` before `SlopRuntime.init`).
Nothing here is called until a key is provisioned (tier 2) or the user signs in (tier 3).

| file | what |
|---|---|
| `Http.kt` | `HttpURLConnection` helper: `postJson`, `postForm`, `getJson`, `postSse` (+ `parseSse`) |
| `Pricing.kt` | per-1M prices from `docs/TIER2_COMPARISON.md`, `LAST_UPDATED` date |
| `Tier2Client.kt` | `Tier2Protocol` (pure request/response logic) + `Tier2Client : Tier2Classifier` |
| `ChatGptAuth.kt` | Sign in with ChatGPT (plan usage) OAuth: PKCE, callback validation, token exchange, refresh, ID-token verification, storage |
| `ChatGptSignInActivity.kt` | loopback listener on 127.0.0.1 + Chrome Custom Tab; `ChatGptSignInActivity.start(ctx)` / `start(ctx, signOut = true)` |
| `Tier3Client.kt` | `Tier3Protocol` (pure) + `Tier3Client : Tier3Model` over `POST /v1/responses` (stream, store:false) |
| `NeuralLmBridge.kt` | `NeuralLmBridge : NeuralLm` over `LlamaLm`; `NeuralLoader.loadIfPresent` / `ensureModelAsync` |
| `Benchmark.kt` | `Benchmark.run(ctx)` / `runAsync` → `bench/latest.json`, `bench/history.jsonl` |
| `Wiring.kt` | `RemoteWiring.install`, `router.json` extras |

## router.json keys read here

Beyond the engine's `RouterConfig` (`tier2Provider`, `tier2Model`, ...), this package also reads
`tier2ServiceTier` (OpenAI `service_tier`, e.g. `fast`), `tier3Model` (slug from `GET /v1/models`;
default = first `gpt-5.x/6.x-{mini,luna,nano}` the account lists) and `tier3ReasoningEffort`
(default `low`; `default` = don't send). The nested form `{"tier2": {"provider": ...}}` is accepted too.

## Tier 2 request shapes (verified 2026-10-12)

- **jev** — `POST https://api.typesafe.ai/v1/systemone` (https://docs.typesafe.ai/api.md):
  `{state, model, questions: {meant: {type: "choice", instructions, criteria: {option: description}}}}` →
  `answers.meant.{choice, probabilities, confidence}`, `usage.input_tokens`.
- **openai-decisions** — `POST https://api.openai.com/v1/decisions`
  (https://developers.openai.com/api/docs/guides/decisions): `{model, input, questions: [{type: "choice",
  name, instructions, choices: [{value, description}]}]}` → `answers[0].{choice, probabilities[], confidence}`.
  The guide does not document a `usage` object; cost is estimated from `chars/4` when absent.
- **openai-responses** — `POST https://api.openai.com/v1/responses`
  (https://developers.openai.com/api/reference/resources/responses/methods/create): `reasoning.effort: "none"`,
  strict `text.format` json_schema enum, `max_output_tokens: 16`, `store: false`, optional `service_tier`.

Options are always `["keep"] + candidates + ["novel"]`; the state text carries at most 30 context
words with newlines stripped.

## Sign in with ChatGPT — what is implemented and from where

| item | value | source |
|---|---|---|
| authorization endpoint | `https://auth.openai.com/api/accounts/authorize` | sign-in guide; `/.well-known/openid-configuration` |
| token endpoint | `https://auth.openai.com/api/accounts/oauth/token` | same |
| revocation endpoint | `https://auth.openai.com/api/accounts/oauth/revoke` | discovery document |
| JWKS | `https://auth.openai.com/.well-known/jwks.json` (RS256 keys) | discovery document |
| `client_id` | `dynamic_agent_client` on first sign-in, then the issued `oaiapp_…` id | sign-in guide |
| `agent_name_hint` | `Slop Type` (only with the dynamic client id) | sign-in guide |
| `ext_agent_host_id` | `urn:uuid:<v4>` generated once and persisted | token-sharing overview |
| `redirect_uri` | `http://127.0.0.1:1455/auth/callback` for initial registration; any port after (never `localhost`) | sign-in guide |
| `scope` | `openid profile email offline_access resource.invoke chatgpt.tokens.use.direct` | sign-in guide, devkit `oauth.ts` |
| `resource` | `https://api.openai.com/v1` | sign-in guide |
| PKCE | `code_challenge_method=S256`, base64url SHA-256 without padding | sign-in guide |
| `state`, `nonce` | fresh random per attempt; validated on callback / in the ID token | sign-in guide |
| reauth hints | `id_token_hint` (last ID token), `login_hint` (validated email) when an issued client id exists | sign-in guide |
| callback | `code`, `state`, `client_id` (new registration), `scope`; `error=access_denied` aborts | sign-in guide |
| token exchange | form `grant_type=authorization_code, client_id, code, code_verifier, redirect_uri, resource` | sign-in guide, devkit |
| refresh | form `grant_type=refresh_token, client_id, refresh_token, resource` (no `scope`); refresh token rotates | profiles-and-sessions guide |
| revoke | form `token, token_type_hint=refresh_token, client_id` | profiles-and-sessions guide, devkit |
| ID token checks | RS256 signature vs JWKS, `iss`, `aud == issued client_id`, `exp`, `nonce`; `sub` = account identity | sign-in guide |
| plan permission | granted scopes must include `chatgpt.tokens.use.direct` before any inference | sign-in guide |
| inference | `POST /v1/responses` with `store:false, stream:true`; `response.completed` is the only success signal; `response.failed` → `error.code` (`subscription_sharing_usage_limit_exceeded`, `subscription_sharing_usage_unavailable`) | models-and-inference guide |
| models | `GET /v1/models` → `models[]{slug, display_name, visibility}` keep `visibility == "list"`, server order | models-and-inference guide |

Stored in `SlopSecrets.chatgptOauth`: `{ext_agent_host_id, client_id, access_token, refresh_token,
expires_at (ms), id_token, account_id (= sub), email, scope}`. Sign-out revokes the refresh token and
clears tokens but keeps the host id and issued client id (the registration persists server-side).

### Assumptions / not verifiable without a live account

- The docs mandate port **1455** for the *initial* dynamic registration. If the port is busy the
  activity falls back to an ephemeral port; OpenAI may reject that for a first registration.
- Access tokens are used as-is in `Authorization: Bearer`; no `ChatGPT-Account-ID` header is sent
  (none of the open-source docs mention one).
- The `reasoning.effort` parameter is sent as `low` by default on the plan-usage path; the docs
  list only `max_output_tokens`, `temperature`, `top_p`, `top_logprobs` as unsupported. On a 400
  that mentions `reasoning` the request is retried without it.
- `GET /v1/models` on the plan-usage path returns `{models: [...]}` per the docs; the classic
  `{data: [{id}]}` shape is accepted as a fallback.
- The Decisions API usage object is undocumented; cost falls back to a chars/4 estimate.

## Logging

The engine logs a `remote` event for every tier-2 and tier-3 call (success and failure). Clients
here log an additional `remote` event **only on failure** with `provider`, `model`, `status`,
`error` (see `Tier2Client.FAILURE_EVENT`). Counting `ok:false` events therefore double counts
failures; count on `provider` presence (client) or absence (engine) to disambiguate.
