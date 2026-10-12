package dev.sloptype.engine.cli

import dev.sloptype.engine.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.system.exitProcess

/**
 * engine-cli: replays a log-schema JSONL trace through the engine and prints one JSON line per
 * decision, then a summary line. Used by eval/replay.py and eval/guard.py.
 *
 *   decode --trace in.jsonl [--config engine.json] [--tier2 none|mock|file:answers.jsonl] [--state state.json] [--lexicon personal.json]
 *   bench  --trace in.jsonl   (decode timing only)
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) { usage(); exitProcess(2) }
    val cmd = args[0]
    val opts = HashMap<String, String>()
    var i = 1
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) { opts[a.removePrefix("--")] = args.getOrNull(i + 1) ?: ""; i += 2 } else i++
    }
    when (cmd) {
        "decode", "bench" -> decode(opts, cmd == "bench")
        "version" -> println("engine-cli 1")
        else -> { usage(); exitProcess(2) }
    }
}

private fun usage() {
    System.err.println("usage: engine-cli decode --trace in.jsonl [--config engine.json] [--tier2 none|mock|file:answers.jsonl] [--state state.json] [--lexicon personal.json]")
}

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

private class MockTier2(private val answers: Map<String, String>?) : Tier2Classifier {
    override val available = true
    override fun classify(req: ClassifyRequest, callback: (ClassifyResult?) -> Unit) {
        val choice = answers?.get(req.typed) ?: "keep"
        callback(ClassifyResult(req.id, choice, 0.9f, 1, 0.0000063, "mock"))
    }
}

private fun decode(opts: Map<String, String>, benchOnly: Boolean) {
    val tracePath = opts["trace"] ?: run { usage(); exitProcess(2) }
    val cfg = opts["config"]?.takeIf { it.isNotEmpty() }?.let { EngineConfig.fromJson(File(it).readText()) } ?: EngineConfig.loadDefaultResource()
    val out = System.out.bufferedWriter()
    val events = ArrayList<Map<String, Any?>>()
    val sink = LogSink { name, fields -> if (name == "decision") events.add(fields) }
    val tLoad = System.currentTimeMillis()
    val engine = SlopEngine.createDefault(sink, cfg)
    val loadMs = System.currentTimeMillis() - tLoad
    opts["state"]?.takeIf { it.isNotEmpty() && File(it).exists() }?.let { engine.importState(File(it).readText()) }
    opts["lexicon"]?.takeIf { it.isNotEmpty() && File(it).exists() }?.let { engine.loadPersonalLexicon(File(it).readText()) }
    // eval/guard.py passes `"personalLexicon": ["word", ...]` inside the config JSON
    opts["config"]?.takeIf { it.isNotEmpty() }?.let { path ->
        runCatching {
            val o = json.parseToJsonElement(File(path).readText()).jsonObject
            o["personalLexicon"]?.jsonArray?.forEach { w -> engine.personal.learn(w.jsonPrimitive.content, 1.0f, "config"); engine.personal.lock(w.jsonPrimitive.content) }
        }
    }
    when (val t2 = opts["tier2"] ?: "none") {
        "none" -> {}
        "mock" -> engine.tier2 = MockTier2(null)
        else -> if (t2.startsWith("file:")) {
            val m = HashMap<String, String>()
            File(t2.removePrefix("file:")).forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val o = json.parseToJsonElement(line).jsonObject
                m[o["typed"]!!.jsonPrimitive.content] = o["choice"]!!.jsonPrimitive.content
            }
            engine.tier2 = MockTier2(m)
        }
    }
    if (engine.tier2 != null) engine.reloadRouter("""{"tier2Provider":"mock","tier2MonthlyCapUsd":1.0}""")

    var text = StringBuilder()          // simulated field text before cursor
    var geometry: KeyboardGeometry? = null
    var lineNo = 0
    var decisions = 0
    var totalDecodeMs = 0L
    var maxDecodeMs = 0L
    val decodeTimes = ArrayList<Long>()
    val pendingTouch = arrayOfNulls<Touch>(1)
    val truths = ArrayList<String>()
    var inField = false
    val retroApplied = ArrayList<Map<String, Any?>>()

    fun emitDecision(idx: Int, d: WordDecision, ms: Long) {
        if (benchOnly) return
        val o = buildJsonObject {
            put("i", idx); put("typed", d.typed); put("commit", d.commit); put("kind", d.kind.name)
            put("conf", d.confidence); put("tier", d.tier); put("ms", ms); put("reason", d.reason)
            put("alts", JsonArray(d.alternatives.map { JsonPrimitive(it) }))
            put("retro", JsonArray(d.retro.map { buildJsonObject { put("wordsBack", it.wordsBack); put("old", it.oldText); put("new", it.newText) } }))
            put("pendingId", d.pendingId?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        out.write(o.toString()); out.newLine()
    }

    fun applyRetro(d: WordDecision) {
        // simulate the shell applying retro edits to the field text: words before the just-committed word
        for (r in d.retro) {
            val words = text.toString().split(" ").toMutableList()
            // text ends with "<commit><sep>" possibly; find the word r.wordsBack back among non-empty tokens
            val idxs = words.indices.filter { words[it].isNotEmpty() }
            if (idxs.size < r.wordsBack) continue
            val k = idxs[idxs.size - r.wordsBack]
            val tok = words[k]
            val core = tok.trim { !it.isLetterOrDigit() && it != '\'' }
            if (!core.equals(r.oldText, true)) continue
            words[k] = tok.replace(core, r.newText)
            text = StringBuilder(words.joinToString(" "))
            retroApplied.add(mapOf("old" to r.oldText, "new" to r.newText))
        }
    }

    File(tracePath).bufferedReader().useLines { lines ->
        for (line in lines) {
            lineNo++
            if (line.isBlank()) continue
            val ev = try { json.parseToJsonElement(line).jsonObject } catch (e: Exception) { continue }
            when (ev["ev"]?.jsonPrimitive?.content) {
                "session_start" -> {
                    geometry = ev["geom"]?.let { json.decodeFromJsonElement(KeyboardGeometry.serializer(), it) } ?: geometry
                    val g = geometry ?: continue
                    val pkg = ev["pkg"]?.jsonPrimitive?.content ?: "eval"
                    engine.startField(FieldInfo(pkg), g, "")
                    text = StringBuilder(); inField = true
                }
                "touch" -> {
                    if (ev["a"]?.jsonPrimitive?.content == "d") {
                        pendingTouch[0] = Touch(
                            ev["x"]!!.jsonPrimitive.float, ev["y"]!!.jsonPrimitive.float, ev["t"]?.jsonPrimitive?.long ?: 0L,
                            ev["p"]?.jsonPrimitive?.floatOrNull ?: 0f, ev["s"]?.jsonPrimitive?.floatOrNull ?: 0f, ev["code"]!!.jsonPrimitive.int,
                        )
                    }
                }
                "key" -> {
                    if (!inField) continue
                    val code = ev["code"]!!.jsonPrimitive.int
                    val touch = pendingTouch[0]?.takeIf { it.code == code }
                    pendingTouch[0] = null
                    if (KeyboardGeometry.isWordChar(code)) {
                        engine.onKey(code, touch)
                        text.append(code.toChar())
                    } else {
                        val sep = code.toChar().toString()
                        val t0 = System.nanoTime()
                        val d = engine.onSeparator(sep, text.toString())
                        val ms = (System.nanoTime() - t0) / 1_000_000
                        totalDecodeMs += ms; if (ms > maxDecodeMs) maxDecodeMs = ms; decodeTimes.add(ms)
                        // apply to simulated text
                        if (d.typed.isNotEmpty() && text.endsWith(d.typed)) text.setLength(text.length - d.typed.length)
                        text.append(d.commit).append(sep)
                        if (d.retro.isNotEmpty()) applyRetro(d)
                        if (d.typed.isNotEmpty()) { decisions++; emitDecision(lineNo, d, ms) }
                    }
                }
                "backspace" -> {
                    val composing = engine.isComposing()
                    engine.onBackspace(composing, null)
                    if (text.isNotEmpty()) text.setLength(text.length - 1)
                }
                "truth" -> truths.add(ev["text"]?.jsonPrimitive?.content ?: "")
                "session_end" -> { engine.endField(text.toString()); inField = false }
            }
        }
    }
    val sorted = decodeTimes.sorted()
    fun pct(p: Double) = if (sorted.isEmpty()) 0L else sorted[((sorted.size - 1) * p).toInt()]
    val summary = buildJsonObject {
        put("summary", buildJsonObject {
            put("decisions", decisions); put("loadMs", loadMs); put("decodeMsTotal", totalDecodeMs)
            put("decodeMsP50", pct(0.5)); put("decodeMsP95", pct(0.95)); put("decodeMsMax", maxDecodeMs)
            put("retroApplied", retroApplied.size)
            put("finalText", text.toString())
            put("personalLexiconSize", engine.personal.size())
        })
    }
    out.write(summary.toString()); out.newLine()
    out.flush()
    engine.shutdown()
}
