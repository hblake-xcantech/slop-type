package dev.sloptype.app.remote

import android.content.Context
import android.util.Log
import dev.sloptype.app.SlopPaths
import dev.sloptype.app.SlopRuntime
import dev.sloptype.engine.RewriteRequest
import dev.sloptype.engine.RewriteResult
import dev.sloptype.engine.Tier3Model
import dev.sloptype.engine.WordAlt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/** Android-free request/response logic for tier 3 (unit tested on the JVM). */
object Tier3Protocol {
    const val RESPONSES_URL = "https://api.openai.com/v1/responses"
    const val MODELS_URL = "https://api.openai.com/v1/models"
    const val MAX_CONTEXT_WORDS = 30
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** gpt-5.x / gpt-6.x small models: mini / luna / nano, in server order. */
    private val SMALL_MODEL_RE = Regex("^gpt-(5(\\.\\d+)?|6(\\.\\d+)?)-(mini|luna|nano)(-.*)?$")

    data class ModelInfo(val slug: String, val displayName: String)

    /**
     * Parse `GET /v1/models`. The SIWC shape is {models:[{slug, display_name, visibility}]} (keep
     * visibility == "list", preserve order); the classic shape {data:[{id}]} is accepted too.
     */
    fun parseModels(body: String): List<ModelInfo> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val out = ArrayList<ModelInfo>()
        (root["models"] as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val vis = (o["visibility"] as? JsonPrimitive)?.contentOrNull
            if (vis != null && vis != "list") return@forEach
            val slug = (o["slug"] as? JsonPrimitive)?.contentOrNull ?: (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            out.add(ModelInfo(slug, (o["display_name"] as? JsonPrimitive)?.contentOrNull ?: slug))
        }
        if (out.isEmpty()) (root["data"] as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            out.add(ModelInfo(id, id))
        }
        return out
    }

    fun pickDefaultModel(models: List<ModelInfo>): String? =
        models.firstOrNull { SMALL_MODEL_RE.matches(it.slug) }?.slug ?: models.firstOrNull()?.slug

    fun trimContext(context: String): String =
        context.split(Regex("\\s+")).filter { it.isNotEmpty() }.takeLast(MAX_CONTEXT_WORDS).joinToString(" ")

    private fun clean(s: String) = s.replace(Regex("[\\r\\n\\t]"), " ").trim()

    val DEFAULT_REWRITE_INSTRUCTIONS = """
        You fix typos in text typed on a phone keyboard. You are given the words of the most recent sentence fragment, in order, each with spell-check alternatives and a locked flag.
        Return ONLY a JSON array of strings with exactly the same number of elements, one corrected word per input word, in the same order.
        Rules: change a word ONLY when it is an obvious typo, and then replace it with one of its listed alternatives or a very close spelling (edit distance 1-2) of the same word.
        Never rephrase, reorder, merge, split, add or drop words. Never change punctuation attached to a word. Never change a locked word. Keep names, slang and abbreviations as typed. When unsure, keep the word.
    """.trimIndent()

    val DEFAULT_LOOKUP_INSTRUCTIONS = "You are a dictionary. Answer with a single word: yes or no."

    fun rewriteInput(req: RewriteRequest): String {
        val sb = StringBuilder()
        val before = clean(trimContext(req.contextBefore))
        if (before.isNotEmpty()) sb.append("Earlier text (do not change, for context only): \"").append(before).append("\"\n")
        sb.append("Words (").append(req.words.size).append("):\n")
        for ((i, w) in req.words.withIndex()) {
            sb.append(i + 1).append(". \"").append(clean(w.text)).append('"')
            if (w.locked) sb.append(" [locked]")
            if (w.alternatives.isNotEmpty()) sb.append(" alternatives: ").append(w.alternatives.joinToString(", ") { "\"${clean(it)}\"" })
            sb.append('\n')
        }
        sb.append("Return a JSON array of ").append(req.words.size).append(" strings.")
        return sb.toString()
    }

    fun lookupInput(word: String, context: String): String {
        val ctx = clean(trimContext(context))
        return (if (ctx.isNotEmpty()) "Context: \"$ctx\"\n" else "") +
            "Is '${clean(word)}' a real English word, name, product, or technical term? Answer yes or no."
    }

    /** Fill `{word}` / `{context}` placeholders when a custom prompt uses them. */
    fun applyTemplate(template: String, word: String, context: String): String =
        template.replace("{word}", clean(word)).replace("{context}", clean(trimContext(context)))

    /** Responses request on the plan-usage path: store:false and stream:true are required; no sampling params. */
    fun responsesBody(model: String, instructions: String, input: String, reasoningEffort: String?): String = buildJsonObject {
        put("model", model)
        put("instructions", instructions)
        put("input", input)
        put("store", false)
        put("stream", true)
        if (!reasoningEffort.isNullOrBlank()) putJsonObject("reasoning") { put("effort", reasoningEffort) }
    }.toString()

    data class Streamed(val text: String, val completed: Boolean, val errorCode: String?, val inputTokens: Long?, val outputTokens: Long?, val model: String?)

    /**
     * Fold an SSE body: `response.output_text.delta` carries text, `response.completed` is the
     * only success signal, `response.failed` carries `error.code` (e.g.
     * subscription_sharing_usage_limit_exceeded). A stream without `response.completed` is a failure.
     */
    fun foldStream(sse: String): Streamed {
        val text = StringBuilder()
        var completed = false
        var error: String? = null
        var inTok: Long? = null; var outTok: Long? = null; var model: String? = null
        for (ev in Http.parseSse(sse)) {
            if (ev.data.isBlank() || ev.data == "[DONE]") continue
            val o = runCatching { json.parseToJsonElement(ev.data).jsonObject }.getOrNull() ?: continue
            val type = ev.event ?: (o["type"] as? JsonPrimitive)?.contentOrNull ?: continue
            when (type) {
                "response.output_text.delta" -> (o["delta"] as? JsonPrimitive)?.contentOrNull?.let { text.append(it) }
                "response.completed" -> {
                    completed = true
                    val resp = o["response"] as? JsonObject
                    val usage = resp?.get("usage") as? JsonObject
                    inTok = (usage?.get("input_tokens") as? JsonPrimitive)?.longOrNull
                    outTok = (usage?.get("output_tokens") as? JsonPrimitive)?.longOrNull
                    model = (resp?.get("model") as? JsonPrimitive)?.contentOrNull
                    if (text.isEmpty() && resp != null) text.append(Tier2Protocol.responsesOutputText(resp))
                }
                "response.failed" -> {
                    val resp = o["response"] as? JsonObject
                    val err = (resp?.get("error") ?: o["error"]) as? JsonObject
                    error = (err?.get("code") as? JsonPrimitive)?.contentOrNull ?: (err?.get("message") as? JsonPrimitive)?.contentOrNull ?: "response.failed"
                }
                "response.incomplete" -> error = error ?: "response.incomplete"
                "error" -> error = (o["code"] as? JsonPrimitive)?.contentOrNull ?: (o["message"] as? JsonPrimitive)?.contentOrNull ?: "error"
            }
        }
        return Streamed(text.toString(), completed && error == null, error, inTok, outTok, model)
    }

    private fun stripFences(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            val end = t.lastIndexOf("```")
            if (end >= 0) t = t.substring(0, end)
        }
        return t.trim()
    }

    /**
     * Strict parse of the rewrite answer: a JSON array of [words].size single-token strings.
     * Locked words are forced back to their input text. Any other mismatch → null.
     */
    fun parseRewrite(output: String, words: List<WordAlt>): List<String>? {
        val t = stripFences(output)
        val start = t.indexOf('['); val end = t.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val arr = runCatching { json.parseToJsonElement(t.substring(start, end + 1)) as? JsonArray }.getOrNull() ?: return null
        if (arr.size != words.size) return null
        val out = ArrayList<String>(words.size)
        for ((i, el) in arr.withIndex()) {
            val s = (el as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
            if (s.isEmpty() || s.any { it.isWhitespace() }) return null
            out.add(if (words[i].locked) words[i].text else s)
        }
        return out
    }

    fun parseYesNo(output: String): Boolean? {
        val t = stripFences(output).lowercase().trimStart('"', '\'', ' ', '*', '-', '.', ':')
        return when {
            t.startsWith("yes") -> true
            t.startsWith("no") -> false
            else -> null
        }
    }
}

/**
 * Tier 3: ChatGPT subscription models through the plan-usage bearer token. Only
 * `POST /v1/responses` with `store:false, stream:true` is allowed on that path
 * (https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference).
 * Both calls run on a 2-thread pool with an 8 s timeout. costUsd is always 0 (subscription).
 */
class Tier3Client(ctx: Context, private val modelOverride: String = "", private val reasoningEffort: String? = "low") : Tier3Model {
    private val app = ctx.applicationContext

    override val available: Boolean get() = ChatGptAuth.isSignedIn(app)

    // ------------------------------------------------------------------ models

    /** Models the signed-in account may use; cached for a day in state/chatgpt-models.json. */
    fun listModels(force: Boolean = false): List<Tier3Protocol.ModelInfo> {
        val f = modelCacheFile(app)
        if (!force && f.isFile && System.currentTimeMillis() - f.lastModified() < MODEL_CACHE_MS) {
            runCatching { Tier3Protocol.parseModels(f.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val token = ChatGptAuth.accessToken(app) ?: return emptyList()
        val r = runCatching { Http.getJson(Tier3Protocol.MODELS_URL, mapOf("Authorization" to "Bearer $token"), TIMEOUT_MS) }.getOrNull() ?: return emptyList()
        if (!r.ok) { Log.w(TAG, "models: HTTP ${r.status}"); return emptyList() }
        val models = Tier3Protocol.parseModels(r.body)
        if (models.isNotEmpty()) runCatching { f.writeText(r.body) }
        return models
    }

    /** router.json `tier3Model` if set, else the first small gpt-5.x/6 model the account lists. */
    fun model(): String? {
        if (modelOverride.isNotBlank()) return modelOverride
        return Tier3Protocol.pickDefaultModel(listModels())
    }

    // ------------------------------------------------------------------ Tier3Model

    override fun rewrite(req: RewriteRequest, callback: (RewriteResult?) -> Unit) {
        if (!available) { callback(null); return }
        submit({ rewriteBlocking(req) }, callback)
    }

    override fun lookupWord(word: String, context: String, callback: (Boolean?) -> Unit) {
        if (!available) { callback(null); return }
        submit({ lookupBlocking(word, context) }, callback)
    }

    private fun <T> submit(work: () -> T?, callback: (T?) -> Unit) {
        try {
            pool.execute {
                val r = try { work() } catch (t: Throwable) { fail("exception", null, t, "call"); null }
                try { callback(r) } catch (t: Throwable) { Log.w(TAG, "tier3 callback threw", t) }
            }
        } catch (_: Throwable) { callback(null) }
    }

    fun rewriteBlocking(req: RewriteRequest): RewriteResult? {
        if (req.words.isEmpty()) return null
        val instructions = promptFile("rewrite.txt") ?: Tier3Protocol.DEFAULT_REWRITE_INSTRUCTIONS
        val r = complete(instructions, Tier3Protocol.rewriteInput(req), "rewrite") ?: return null
        val words = Tier3Protocol.parseRewrite(r.text, req.words)
        if (words == null) { fail("unparsable rewrite", null, null, "rewrite", r.latencyMs); return null }
        return RewriteResult(req.id, words, r.latencyMs, 0.0, r.model)
    }

    fun lookupBlocking(word: String, context: String): Boolean? {
        val custom = promptFile("lookup.txt")
        val instructions: String
        val input: String
        if (custom != null && (custom.contains("{word}"))) {
            instructions = Tier3Protocol.DEFAULT_LOOKUP_INSTRUCTIONS
            input = Tier3Protocol.applyTemplate(custom, word, context)
        } else {
            instructions = custom ?: Tier3Protocol.DEFAULT_LOOKUP_INSTRUCTIONS
            input = Tier3Protocol.lookupInput(word, context)
        }
        val r = complete(instructions, input, "lookup") ?: return null
        val b = Tier3Protocol.parseYesNo(r.text)
        if (b == null) fail("unparsable yes/no", null, null, "lookup", r.latencyMs)
        return b
    }

    data class Completion(val text: String, val latencyMs: Long, val model: String)

    /** One streamed Responses call; null on any failure (logged). */
    fun complete(instructions: String, input: String, kind: String): Completion? {
        val token = ChatGptAuth.accessToken(app) ?: run { fail("no access token", null, null, kind); return null }
        val model = model() ?: run { fail("no model available", null, null, kind); return null }
        val headers = mapOf("Authorization" to "Bearer $token")
        val t0 = System.nanoTime()
        var resp = try {
            Http.postSse(Tier3Protocol.RESPONSES_URL, Tier3Protocol.responsesBody(model, instructions, input, reasoningEffort), headers, TIMEOUT_MS)
        } catch (e: Exception) { fail("exception", null, e, kind, (System.nanoTime() - t0) / 1_000_000, model); return null }
        if (resp.status == 400 && reasoningEffort != null && resp.body.contains("reasoning")) {
            // model does not accept the reasoning parameter on this path: retry without it
            resp = try {
                Http.postSse(Tier3Protocol.RESPONSES_URL, Tier3Protocol.responsesBody(model, instructions, input, null), headers, TIMEOUT_MS)
            } catch (e: Exception) { fail("exception", null, e, kind, (System.nanoTime() - t0) / 1_000_000, model); return null }
        }
        val latency = (System.nanoTime() - t0) / 1_000_000
        if (!resp.ok) {
            if (resp.status == 401) runCatching { ChatGptAuth.refresh(app) }
            fail("http", resp, null, kind, latency, model); return null
        }
        val s = Tier3Protocol.foldStream(resp.body)
        if (!s.completed) { fail(s.errorCode ?: "stream ended without response.completed", null, null, kind, latency, model); return null }
        if (s.text.isBlank()) { fail("empty output", null, null, kind, latency, model); return null }
        return Completion(s.text, latency, s.model ?: model)
    }

    private fun promptFile(name: String): String? {
        val f = File(File(SlopPaths.configs(app), "prompts"), name)
        return if (f.isFile) runCatching { f.readText().trim().takeIf { it.isNotEmpty() } }.getOrNull() else null
    }

    private fun fail(what: String, resp: Http.Response?, e: Throwable?, kind: String, latency: Long = resp?.latencyMs ?: 0, model: String = modelOverride) {
        val err = if (resp != null || e != null) Http.describeFailure(resp, e) else what
        Log.w(TAG, "tier3 $kind failed: $err")
        runCatching {
            // The engine logs its own `remote` event per tier-3 call; this adds model/error detail.
            SlopRuntime.logger.event(Tier2Client.FAILURE_EVENT, mapOf(
                "tier" to 3, "provider" to "chatgpt", "model" to model, "kind" to kind, "ok" to false, "latencyMs" to latency,
                "status" to (resp?.status ?: 0), "error" to err, "inTokens" to 0, "outTokens" to 0, "costUsd" to 0.0,
            ))
        }
    }

    companion object {
        private const val TAG = "Tier3Client"
        const val TIMEOUT_MS = 8_000L
        private const val MODEL_CACHE_MS = 24 * 60 * 60 * 1000L
        private val pool = Executors.newFixedThreadPool(2, ThreadFactory { r -> Thread(r, "slop-tier3").apply { isDaemon = true } })

        fun modelCacheFile(ctx: Context): File = File(SlopPaths.state(ctx), "chatgpt-models.json")
        fun invalidateModelCache(ctx: Context) { runCatching { modelCacheFile(ctx).delete() } }
    }
}
