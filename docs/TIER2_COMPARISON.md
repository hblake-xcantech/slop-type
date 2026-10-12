# Tier-2 fast classifier: provider comparison (researched 2026-10-12)

Tier 2 is the paid, millisecond-class remote decision used only for ambiguous per-word
calls: given context + typed token + ≤5 candidates, answer `keep` / `correct-to-N` / `novel`.
Each call is ~150 input tokens + ≤3 output tokens. Expected volume: 500–3,000 calls/day.
Nothing is enabled or paid for until you provide a key; the router treats tier 2 as absent
until then.

## What "JEV" is

**Jev** is TypeSafe AI's "System One" decision model (`jev-1.13.0`; aliases `jev-latest`,
`jev-preview`). It is classification-only: `POST https://api.typesafe.ai/v1/systemone` with a
`state` and typed `questions` (`choice` / `score` / `noul`) returns probabilities + confidence.
No text generation, no decoding loop, which is why it is fast. Priced at $0.042 per 1M input
tokens, output free. Sources: https://docs.typesafe.ai/models.md, https://docs.typesafe.ai/api.md,
https://typesafe.ai/blog/introducing-system-one-models-and-jev.

## OpenAI's equivalent

On 2026-10-06 OpenAI launched the **Decisions API** (`POST /v1/decisions`, public beta,
`gpt-6-luna` only): `questions[]` of type `predicate` / `choice` / `score`, returns per-option
probabilities + confidence. $0.10 per 1M input tokens, no output charge, no caching.
Officially "about 10× faster than the Responses API"; no millisecond figure is published.
Source: https://developers.openai.com/api/docs/guides/decisions.

OpenAI's small generative models are now the **Luna** tier (`gpt-6-luna` $0.10/$0.50 per 1M
in/out; `gpt-5.6-luna` $0.20/$1.20). `service_tier: "fast"` (formerly "priority") costs 2×
and promises "up to 2.5× faster" but no TTFT. Reasoning defaults to `medium`, so any
Responses call for this job must set `reasoning.effort: "none"`.
Sources: https://developers.openai.com/api/docs/pricing, https://developers.openai.com/api/docs/guides/fast-mode.

## Comparison

Cost per 1k calls = 0.15 × ($/1M in) + 0.003 × ($/1M out). Month = 45,000 calls (1,500/day).
"AA" = Artificial Analysis third-party TTFT measured with 10k-token inputs (our prompts are
~150 tokens, so real numbers will be lower, but AA is the only cross-provider source).

| Provider / model | $/1M in | $/1M out | $/1k calls | $/month | Published latency |
|---|---|---|---|---|---|
| **TypeSafe Jev** `jev-1.13.0` | 0.042 | 0 | 0.0063 | 0.28 | vendor sample 114 ms; launch post 70–500 ms; LiteLLM median 127 ms; no p95 published |
| **OpenAI Decisions API** `gpt-6-luna` | 0.10 | 0 | 0.0150 | 0.67 | "10× faster than Responses" (Responses TTFT ≈ 0.9 s on AA) |
| OpenAI `gpt-6-luna` Responses, standard | 0.10 | 0.50 | 0.0165 | 0.74 | AA TTFT 0.88 s |
| OpenAI `gpt-6-luna` Responses, fast tier | 0.20 | 1.00 | 0.0330 | 1.49 | "up to 2.5× faster", no figure |
| OpenAI `gpt-5-nano` (deprecated path) | 0.05 | 0.40 | 0.0087 | 0.39 | AA TTFT 0.93 s |
| Google Gemini 2.5 Flash-Lite (thinking off) | 0.10 | 0.40 | 0.0162 | 0.73 | AA TTFT 0.32 s |
| Google Gemini 3.5 Flash-Lite (thinking cannot be fully off) | 0.30 | 2.50 | 0.0525 | 2.36 | AA first chunk 6.95 s |
| Anthropic Claude Haiku 5.5 | 0.10 | 0.50 | 0.0165 | 0.74 | none published; thinking on by default |
| Cerebras gpt-oss-120b | 0.35 | 0.75 | 0.0548 | 2.46 | AA first chunk 0.46 s |
| Groq gpt-oss-20b | 0.075 | 0.30 | 0.0121 | 0.55 | AA first chunk 0.77 s; dev tier 1k req/day |
| SambaNova gpt-oss-120b | 0.22 | 0.59 | 0.0348 | 1.56 | AA first chunk 1.16 s |
| Mistral Ministral 3 3B | 0.10 | 0.10 | 0.0153 | 0.69 | AA TTFT 0.74 s (8B); EU-hosted by default |
| Cloudflare Workers AI llama-3.1-8b-fp8-fast | 0.045 | 0.384 | 0.0079 | 0.36 | none; JSON schema best-effort only |

Cost is a non-issue everywhere: under $7/month at the top of the volume range. Latency is
the only real axis, and only Jev and the Decisions API are designed for sub-200 ms.

## Recommendation

1. **Jev** as the primary tier-2 provider. It is the only option with an independent
   sub-200 ms measurement, its API is exactly our question shape, and it is the cheapest.
   Risks: small vendor, rate limits "adjust dynamically", no SLA, reportedly US-West only.
2. **OpenAI Decisions API** as the second provider (fallback or A/B). Same shape, OpenAI
   infrastructure, beta today, GA "in the coming weeks".
3. **OpenAI `gpt-6-luna` via Responses** with `reasoning.effort: "none"`, a strict
   `json_schema` enum output and `max_output_tokens: 5` as the conservative generic path.
   Will likely miss p50 < 150 ms.

The app's router supports all three (`router.json` → `tier2.provider`: `jev` |
`openai-decisions` | `openai-responses`). The in-app benchmark measures p50/p95 from the
phone on Wi-Fi and cellular for whichever providers have keys, and the nightly autopilot
picks the winner from the data. Nothing is called until a key is provisioned and a monthly
cap is set.

## Tier 3: Sign in with ChatGPT (status 2026-10-12)

There is now an official program with two parts (https://developers.openai.com/siwc):
- *Identity sign-in* (OIDC) is a limited trial for selected commercial partners.
- *ChatGPT plan usage* ("token sharing") is "available to all open-source partners and
  selected private clients" and launched for Plus/Pro users on 2026-09-29. Users set a
  per-app weekly cap under ChatGPT Settings → Usage.

The open-source/local-tool flow is documented at
https://developers.openai.com/siwc/token-sharing-open-source: dynamic client registration
(`client_id=dynamic_agent_client` + `agent_name_hint`, persist `ext_agent_host_id`), PKCE,
no client secret, grant `chatgpt.tokens.use.direct` for resource `https://api.openai.com/v1`.
Inference is only `POST https://api.openai.com/v1/responses` with `store:false, stream:true`;
the models available are whatever `GET /v1/models` returns for the signed-in account.
`max_output_tokens`, `temperature`, `top_logprobs` and `top_p` are unsupported on that path.

Reusing the Codex CLI's OAuth client from another app is **not** sanctioned by any official
document, and community reports say those tokens are now rejected at api.openai.com.
Slop Type therefore implements the documented open-source flow only (it is GPL-3.0 and runs
on your own device, which is the scope OpenAI's cookbook describes). If OpenAI tightens that
scope, tier 3 degrades to "absent" and tiers 1/2 carry on.

Sources for this section: https://developers.openai.com/siwc/quickstart,
https://developers.openai.com/siwc/token-sharing-open-source,
https://developers.openai.com/siwc/models-and-inference,
https://developers.openai.com/siwc/preview-limitations,
https://developers.openai.com/cookbook/articles/sign-in-with-chatgpt,
https://help.openai.com/en/articles/20001542-using-your-chatgpt-plan-in-other-apps-and-sites.
