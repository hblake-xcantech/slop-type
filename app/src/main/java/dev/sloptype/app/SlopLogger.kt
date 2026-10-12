package dev.sloptype.app

import android.content.Context
import dev.sloptype.engine.LogSink
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileWriter
import java.util.concurrent.Executors

/**
 * JSONL event log (DESIGN.md "Log schema"). One file per keyboard session (`<sid>.jsonl`), closed
 * on session end. Sensitive sessions are never opened. Also keeps `state/counters.json`
 * (per-day words / corrections / reverts / backspaces) used by the rollback watchdog and STATUS.
 */
class SlopLogger(private val ctx: Context) : LogSink {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "slop-log").apply { isDaemon = true } }
    @Volatile private var writer: FileWriter? = null
    @Volatile private var current: File? = null
    @Volatile var sid: String = ""
        private set
    @Volatile var enabled = true
    private val json = Json { encodeDefaults = false }
    private val counters = Counters(SlopPaths.state(ctx))

    fun startSession(fields: Map<String, Any?>) {
        if (!enabled) return
        exec.execute {
            closeInternal()
            sid = "%d-%04x".format(System.currentTimeMillis(), (Math.random() * 65535).toInt())
            val f = File(SlopPaths.logs(ctx), "$sid.jsonl")
            current = f
            writer = FileWriter(f, true)
            writeInternal("session_start", fields)
        }
    }

    fun endSession() {
        exec.execute {
            if (writer != null) writeInternal("session_end", emptyMap())
            closeInternal()
        }
    }

    override fun event(name: String, fields: Map<String, Any?>) {
        if (!enabled) return
        when (name) {
            "decision" -> counters.bump("words", 1).also { if (fields["kind"] == "CORRECT") counters.bump("corrections", 1) }
            "revert" -> counters.bump("reverts", 1)
            "backspace" -> counters.bump("backspaces", (fields["count"] as? Int) ?: 1)
        }
        exec.execute { if (writer != null) writeInternal(name, fields) }
    }

    private fun writeInternal(name: String, fields: Map<String, Any?>) {
        val w = writer ?: return
        try {
            val obj = buildJsonObject {
                put("ev", name)
                put("sid", sid)
                if (!fields.containsKey("t")) put("t", System.currentTimeMillis())
                for ((k, v) in fields) put(k, toJson(v))
            }
            w.write(obj.toString()); w.write("\n")
        } catch (_: Exception) {}
    }

    private fun closeInternal() {
        try { writer?.flush(); writer?.close() } catch (_: Exception) {}
        writer = null
        current?.let { if (it.length() < 200) it.delete() }  // empty sessions are noise
        current = null
    }

    /** Closed session files ready for encryption + upload (never the open one). */
    fun closedLogs(): List<File> {
        val cur = current
        return SlopPaths.logs(ctx).listFiles { f -> f.name.endsWith(".jsonl") && f != cur }?.toList() ?: emptyList()
    }

    fun flush() { exec.execute { try { writer?.flush() } catch (_: Exception) {} } }

    fun counters(): Counters = counters

    private fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Enum<*> -> JsonPrimitive(v.name)
        is Map<*, *> -> buildJsonObject { for ((k, x) in v) put(k.toString(), toJson(x)) }
        is Iterable<*> -> JsonArray(v.map { toJson(it) })
        is FloatArray -> JsonArray(v.map { JsonPrimitive(it) })
        else -> JsonPrimitive(v.toString())
    }

    /** Per-day counters persisted as JSON; cheap enough to rewrite on every bump batch. */
    class Counters(private val dir: File) {
        private val file = File(dir, "counters.json")
        private val data = HashMap<String, HashMap<String, Long>>()
        private var dirty = false
        private var lastSave = 0L
        init { load() }
        @Synchronized private fun load() {
            if (!file.exists()) return
            runCatching {
                val o = Json.parseToJsonElement(file.readText()).jsonObject
                for ((day, m) in o) data[day] = HashMap(m.jsonObject.mapValues { it.value.jsonPrimitive.long })
            }
        }
        private fun today(): String {
            val c = java.util.Calendar.getInstance()
            return "%04d-%02d-%02d".format(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
        }
        @Synchronized fun bump(key: String, n: Int) {
            val d = data.getOrPut(today()) { HashMap() }
            d[key] = (d[key] ?: 0L) + n
            dirty = true
            val now = System.currentTimeMillis()
            if (now - lastSave > 15_000) save()
        }
        @Synchronized fun save() {
            if (!dirty) return
            runCatching {
                val o = buildJsonObject { for ((day, m) in data.toSortedMap()) put(day, buildJsonObject { for ((k, v) in m) put(k, v) }) }
                file.writeText(o.toString())
            }
            dirty = false; lastSave = System.currentTimeMillis()
        }
        /** corrections per word over the last [days] days (today included); null if too few words */
        @Synchronized fun correctionsPerWord(days: Int, minWords: Long = 200): Double? {
            val keys = data.keys.sorted().takeLast(days)
            var w = 0L; var c = 0L
            for (k in keys) { w += data[k]?.get("words") ?: 0; c += data[k]?.get("corrections") ?: 0 }
            return if (w >= minWords) c.toDouble() / w else null
        }
        @Synchronized fun snapshot(): Map<String, Map<String, Long>> = data.mapValues { it.value.toMap() }
    }
}
