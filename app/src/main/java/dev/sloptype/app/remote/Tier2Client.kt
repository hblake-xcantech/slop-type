package dev.sloptype.app.remote

import android.content.Context
import android.util.Log
import dev.sloptype.app.SlopRuntime
import dev.sloptype.app.SlopSecrets
import dev.sloptype.engine.ClassifyRequest
import dev.sloptype.engine.ClassifyResult
import dev.sloptype.engine.EngineConfig
import dev.sloptype.engine.Tier2Classifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Pure (Android-free) request/response logic for the three tier-2 providers, kept separate so
 * it can be unit tested on the JVM. Nothing here does I/O.
 */
object Tier2Protocol {
    const val PROVIDER_JEV = "jev"
    const val PROVIDER_OPENAI_DECISIONS = "openai-decisions"
    const val PROVIDER_OPENAI_RESPONSES = "openai-responses"
    val PROVIDERS = setOf(PROVIDER_JEV, PROVIDER_OPENAI_DECISIONS, PROVIDER_OPENAI_RESPONSES)

    const val JEV_URL = "https://api.typesafe.ai/v1/systemone"
    const val JEV_DEFAULT_MODEL = "jev-1.13.0"
    const val DECISIONS_URL = "https://api.openai.com/v1/decisions"
    const val RESPONSES_URL = "https://api.openai.com/v1/responses"
    const val OPENAI_DEFAULT_MODEL = "gpt-6-luna"

    const val KEEP = "keep"
    const val NOVEL = "novel"
    const val QUESTION_NAME = "meant"
    const val MAX_CONTEXT_WORDS = 30

    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun defaultModel(provider: String): String = if (provider == PROVIDER_JEV) JEV_DEFAULT_MODEL else OPENAI_DEFAULT_MODEL

    /** Candidates that may be offered as options (never equal to the reserved labels or the typed token). */
    fun options(req: ClassifyRequest): List<String> {
        val cands = req.candidates.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(KEEP, true) && !it.equals(NOVEL, true) && !it.equals(req.typed, true) }
            .distinct().take(6).toList()
        return listOf(KEEP) + cands + listOf(NOVEL)
    }

    /** Last [MAX_CONTEXT_WORDS] words of the context, whitespace/newlines collapsed. */
    fun trimContext(context: String): String =
        context.split(Regex("\\s+")).filter { it.isNotEmpty() }.takeLast(MAX_CONTEXT_WORDS).joinToString(" ")

    private fun clean(s: String) = s.replace(Regex("[\\r\\n\\t\"]"), " ").trim()

    /** The shared state/input text: short, one line, no quotes from user text. */
    fun stateText(req: ClassifyRequest): String {
        val ctx = clean(trimContext(req.context))
        val typed = clean(req.typed)
        val cands = options(req).drop(1).dropLast(1)
        val sb = StringBuilder()
        sb.append("A phone keyboard user is typing English. ")
        if (ctx.isNotEmpty()) sb.append("Text so far: \"").append(ctx).append("\". ") else sb.append("Start of a message. ")
        sb.append("They then typed the word \"").append(typed).append("\". ")
        if (cands.isNotEmpty()) sb.append("Spell-check suggestions: ").append(cands.joinToString(", ") { "\"$it\"" }).append(". ")
        sb.append("Which word did they mean?")
        return sb.toString()
    }

    fun instructions(req: ClassifyRequest): String =
        "Decide what the user meant by the typed word \"${clean(req.typed)}\". " +
            "Choose \"$KEEP\" if it is already the correct, correctly spelled word; choose a suggestion only if the typed word is an obvious typo of it; " +
            "choose \"$NOVEL\" if it is a name, slang, abbreviation, or a real word not in the suggestions that should be kept exactly as typed."

    fun describeOption(req: ClassifyRequest, opt: String): String = when (opt) {
        KEEP -> "\"${clean(req.typed)}\" is correct as typed (a real, correctly spelled word)"
        NOVEL -> "\"${clean(req.typed)}\" is a name, slang, abbreviation or new word; keep it exactly as typed"
        else -> "the user meant \"$opt\" and mistyped it"
    }

    // ---------------------------------------------------------------- request bodies

    /** docs.typesafe.ai/api.md: {state, model, questions: {name: {type: "choice", instructions, criteria: {option: description}}}} */
    fun jevBody(req: ClassifyRequest, model: String): String {
        val opts = options(req)
        val o = buildJsonObject {
            put("state", stateText(req))
            put("model", model)
            putJsonObject("questions") {
                putJsonObject(QUESTION_NAME) {
                    put("type", "choice")
                    put("instructions", instructions(req))
                    putJsonObject("criteria") { for (opt in opts) put(opt, describeOption(req, opt)) }
                }
            }
        }
        return o.toString()
    }

    /** developers.openai.com/api/docs/guides/decisions: {model, input, questions: [{type: "choice", name, instructions, choices: [{value, description}]}]} */
    fun decisionsBody(req: ClassifyRequest, model: String): String {
        val opts = options(req)
        val o = buildJsonObject {
            put("model", model)
            put("input", stateText(req))
            put("questions", buildJsonArray {
                add(buildJsonObject {
                    put("type", "choice")
                    put("name", QUESTION_NAME)
                    put("instructions", instructions(req))
                    put("choices", buildJsonArray {
                        for (opt in opts) add(buildJsonObject { put("value", opt); put("description", describeOption(req, opt)) })
                    })
                })
            })
        }
        return o.toString()
    }

    /** Responses API with a strict json_schema enum output, reasoning off, nothing stored. */
    fun responsesBody(req: ClassifyRequest, model: String, serviceTier: String?): String {
        val opts = options(req)
        val o = buildJsonObject {
            put("model", model)
            put("instructions", instructions(req) + " Reply with JSON only.")
            put("input", stateText(req))
            putJsonObject("reasoning") { put("effort", "none") }
            putJsonObject("text") {
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("name", "decision")
                    put("strict", true)
                    putJsonObject("schema") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("choice") {
                                put("type", "string")
                                put("enum", JsonArray(opts.map { JsonPrimitive(it) }))
                            }
                        }
                        put("required", buildJsonArray { add(JsonPrimitive("choice")) })
                        put("additionalProperties", false)
                    }
                }
            }
            put("max_output_tokens", 16)
            put("store", false)
            if (!serviceTier.isNullOrBlank()) put("service_tier", serviceTier)
        }
        return o.toString()
    }

    // ---------------------------------------------------------------- responses

    data class Parsed(val choice: String, val confidence: Float, val inputTokens: Long?, val outputTokens: Long?, val model: String?)

    /** Map a raw provider choice to "keep" / "novel" / the exact candidate string, or null. */
    fun normaliseChoice(raw: String?, req: ClassifyRequest): String? {
        val c = raw?.trim()?.trim('"') ?: return null
        if (c.equals(KEEP, true)) return KEEP
        if (c.equals(NOVEL, true)) return NOVEL
        if (c.equals(req.typed, true)) return KEEP
        return req.candidates.firstOrNull { it.equals(c, true) }
    }

    private fun JsonElement?.obj(): JsonObject? = (this as? JsonObject)
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
    private fun JsonElement?.dbl(): Double? = (this as? JsonPrimitive)?.doubleOrNull

    /** {model, answers: {name: {type: "choice", choice, probabilities, confidence}}, usage: {input_tokens, output_tokens}} */
    fun parseJev(body: String): Parsed? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val answer = root["answers"].obj()?.get(QUESTION_NAME).obj() ?: root["answers"].obj()?.values?.firstOrNull().obj() ?: return null
        val choice = answer["choice"].str() ?: return null
        val conf = answer["confidence"].dbl() ?: answer["probabilities"].obj()?.get(choice).dbl() ?: 0.0
        val usage = root["usage"].obj()
        return Parsed(choice, conf.toFloat(), usage?.get("input_tokens").long(), usage?.get("output_tokens").long(), root["model"].str())
    }

    /** {answers: [{type: "choice", name, choice, probabilities: [{value, probability}], confidence}], usage?} */
    fun parseDecisions(body: String): Parsed? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val answers = root["answers"] as? JsonArray ?: return null
        val answer = answers.map { it.jsonObject }.firstOrNull { it["name"].str() == QUESTION_NAME } ?: answers.firstOrNull()?.jsonObject ?: return null
        if (answer["type"].str() == "refusal") return null
        val choice = answer["choice"].str() ?: return null
        val conf = answer["confidence"].dbl()
            ?: (answer["probabilities"] as? JsonArray)?.map { it.jsonObject }?.firstOrNull { it["value"].str() == choice }?.get("probability").dbl()
            ?: 0.0
        val usage = root["usage"].obj()
        return Parsed(choice, conf.toFloat(), usage?.get("input_tokens").long(), usage?.get("output_tokens").long(), root["model"].str())
    }

    /** Concatenated text of every output_text part of a Responses API response object. */
    fun responsesOutputText(root: JsonObject): String {
        root["output_text"].str()?.let { return it }
        val sb = StringBuilder()
        val out = root["output"] as? JsonArray ?: return ""
        for (item in out) {
            val o = item as? JsonObject ?: continue
            if (o["type"].str() != "message") continue
            val content = o["content"] as? JsonArray ?: continue
            for (part in content) {
                val p = part as? JsonObject ?: continue
                if (p["type"].str() == "output_text") sb.append(p["text"].str() ?: "")
            }
        }
        return sb.toString()
    }

    /** Non-streaming Responses object → the json_schema `choice` field. */
    fun parseResponses(body: String): Parsed? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (root["error"] != null && root["error"] !is JsonNull) return null
        val text = responsesOutputText(root).trim()
        if (text.isEmpty()) return null
        val choice = runCatching { json.parseToJsonElement(text).jsonObject["choice"].str() }.getOrNull()
            ?: text.trim('"', ' ', '\n')
        val usage = root["usage"].obj()
        return Parsed(choice, 0.75f, usage?.get("input_tokens").long(), usage?.get("output_tokens").long(), root["model"].str())
    }
}

/**
 * Tier 2 over HTTP. The provider is chosen by `router.json` (`tier2Provider`): `jev`,
 * `openai-decisions` or `openai-responses`. [classify] never throws and never blocks the caller:
 * the request runs on a 2-thread pool and the callback fires on that pool with a [ClassifyResult]
 * or null (timeout, HTTP error, unparsable answer, missing key).
 */
class Tier2Client(
    ctx: Context,
    private val config: EngineConfig,
    val provider: String,
    modelOverride: String = "",
    private val serviceTier: String? = null,
    /** endpoint override, e.g. Jev through Vercel AI Gateway: https://ai-gateway.vercel.sh/typesafe/v1/systemone */
    private val baseUrlOverride: String? = null,
) : Tier2Classifier {
    private val app = ctx.applicationContext
    val model: String = modelOverride.ifBlank { Tier2Protocol.defaultModel(provider) }

    private fun apiKey(): String {
        val s = SlopSecrets.load(app)
        return when (provider) {
            Tier2Protocol.PROVIDER_JEV -> s.jevApiKey
            Tier2Protocol.PROVIDER_OPENAI_DECISIONS, Tier2Protocol.PROVIDER_OPENAI_RESPONSES -> s.openaiApiKey
            else -> ""
        }.trim()
    }

    override val available: Boolean
        get() = provider in Tier2Protocol.PROVIDERS && apiKey().isNotEmpty()

    override fun classify(req: ClassifyRequest, callback: (ClassifyResult?) -> Unit) {
        if (!available) { callback(null); return }
        try {
            pool.execute {
                val r = try { classifyBlocking(req) } catch (t: Throwable) { fail(req, null, t); null }
                try { callback(r) } catch (t: Throwable) { Log.w(TAG, "tier2 callback threw", t) }
            }
        } catch (t: Throwable) {
            callback(null)
        }
    }

    /** Synchronous variant used by the benchmark. */
    fun classifyBlocking(req: ClassifyRequest): ClassifyResult? {
        val key = apiKey()
        if (key.isEmpty()) return null
        val headers = mapOf("Authorization" to "Bearer $key")
        val timeout = config.tier2TimeoutMs.coerceAtLeast(200)
        val t0 = System.nanoTime()
        val resp: Http.Response
        val parsed: Tier2Protocol.Parsed?
        val body: String
        when (provider) {
            Tier2Protocol.PROVIDER_JEV -> {
                body = Tier2Protocol.jevBody(req, model)
                resp = Http.postJson(baseUrlOverride?.takeIf { it.isNotBlank() } ?: Tier2Protocol.JEV_URL, body, headers, timeout)
                parsed = if (resp.ok) Tier2Protocol.parseJev(resp.body) else null
            }
            Tier2Protocol.PROVIDER_OPENAI_DECISIONS -> {
                body = Tier2Protocol.decisionsBody(req, model)
                resp = Http.postJson(Tier2Protocol.DECISIONS_URL, body, headers, timeout)
                parsed = if (resp.ok) Tier2Protocol.parseDecisions(resp.body) else null
            }
            Tier2Protocol.PROVIDER_OPENAI_RESPONSES -> {
                body = Tier2Protocol.responsesBody(req, model, serviceTier)
                resp = Http.postJson(Tier2Protocol.RESPONSES_URL, body, headers, timeout)
                parsed = if (resp.ok) Tier2Protocol.parseResponses(resp.body) else null
            }
            else -> return null
        }
        val latency = (System.nanoTime() - t0) / 1_000_000
        if (parsed == null) { fail(req, resp, null, latency); return null }
        val choice = Tier2Protocol.normaliseChoice(parsed.choice, req)
        if (choice == null) { fail(req, resp, IllegalStateException("unexpected choice '${parsed.choice}'"), latency); return null }
        val inTok = parsed.inputTokens ?: Pricing.estimateTokens(body)
        val outTok = parsed.outputTokens ?: 0L
        val cost = Pricing.cost(model, inTok, outTok, serviceTier)
        return ClassifyResult(req.id, choice, parsed.confidence.coerceIn(0f, 1f), latency, cost, model)
    }

    private fun fail(req: ClassifyRequest, resp: Http.Response?, e: Throwable?, latency: Long = resp?.latencyMs ?: 0) {
        val err = Http.describeFailure(resp, e)
        Log.w(TAG, "tier2 $provider/$model failed: $err")
        runCatching {
            // The engine logs its own `remote` event for tier 2 (success and bare failure); this one
            // adds provider/model/error detail and the estimated cost of the wasted call.
            SlopRuntime.logger.event(FAILURE_EVENT, mapOf(
                "tier" to 2, "provider" to provider, "model" to model, "ok" to false, "latencyMs" to latency,
                "status" to (resp?.status ?: 0), "error" to err, "inTokens" to 0, "outTokens" to 0, "costUsd" to 0.0,
            ))
        }
    }

    companion object {
        private const val TAG = "Tier2Client"
        /** Event name for client-side failure detail. */
        const val FAILURE_EVENT = "remote"
        private val pool = Executors.newFixedThreadPool(2, ThreadFactory { r -> Thread(r, "slop-tier2").apply { isDaemon = true } })
    }
}
