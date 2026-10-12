package dev.sloptype.app.remote

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal blocking HTTP helper on top of HttpURLConnection (no OkHttp). Every call is
 * synchronous; callers run it on their own pool. Timeouts are applied to both connect and read.
 */
object Http {
    const val USER_AGENT = "sloptype/0.1 (Android)"

    data class Response(val status: Int, val body: String, val latencyMs: Long, val headers: Map<String, String> = emptyMap()) {
        val ok: Boolean get() = status in 200..299
    }

    /** One server-sent event: the `event:` field (may be null) and the joined `data:` lines. */
    data class SseEvent(val event: String?, val data: String)

    fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 10_000): Response =
        request("POST", url, body.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8", headers, timeoutMs)

    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 10_000): Response =
        request("POST", url, encodeForm(form).toByteArray(Charsets.UTF_8), "application/x-www-form-urlencoded", headers, timeoutMs)

    fun getJson(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 10_000): Response =
        request("GET", url, null, null, headers, timeoutMs)

    /**
     * POST a JSON body and read the whole response. If the server streams `text/event-stream`
     * the body returned is the raw SSE text; use [parseSse] on it. On non-2xx the error body is
     * returned as-is (JSON error object).
     */
    fun postSse(url: String, body: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 10_000): Response =
        request("POST", url, body.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8", headers + ("Accept" to "text/event-stream"), timeoutMs)

    fun encodeForm(form: Map<String, String>): String =
        form.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }

    /**
     * Parse a complete SSE document into events. Multi-line `data:` fields are joined with "\n";
     * comment lines (`:`) and unknown fields are ignored; an empty line terminates an event.
     */
    fun parseSse(text: String): List<SseEvent> {
        val out = ArrayList<SseEvent>()
        var event: String? = null
        val data = StringBuilder()
        var hasData = false
        fun flush() {
            if (hasData || event != null) out.add(SseEvent(event, data.toString()))
            event = null; data.setLength(0); hasData = false
        }
        for (rawLine in text.split("\n")) {
            val line = rawLine.removeSuffix("\r")
            when {
                line.isEmpty() -> flush()
                line.startsWith(":") -> {}
                else -> {
                    val colon = line.indexOf(':')
                    val field = if (colon < 0) line else line.substring(0, colon)
                    var value = if (colon < 0) "" else line.substring(colon + 1)
                    if (value.startsWith(" ")) value = value.substring(1)
                    when (field) {
                        "event" -> event = value
                        "data" -> { if (hasData) data.append('\n'); data.append(value); hasData = true }
                    }
                }
            }
        }
        flush()
        return out
    }

    private fun request(method: String, url: String, body: ByteArray?, contentType: String?, headers: Map<String, String>, timeoutMs: Long): Response {
        val t0 = System.nanoTime()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs.coerceIn(500, Int.MAX_VALUE.toLong()).toInt()
            conn.readTimeout = timeoutMs.coerceIn(500, Int.MAX_VALUE.toLong()).toInt()
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (!headers.containsKey("Accept")) conn.setRequestProperty("Accept", "application/json")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val stream: InputStream? = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { readAll(it) } ?: ""
            val hdrs = HashMap<String, String>()
            for ((k, v) in conn.headerFields) if (k != null && v != null && v.isNotEmpty()) hdrs[k.lowercase()] = v.last()
            return Response(status, text, (System.nanoTime() - t0) / 1_000_000, hdrs)
        } finally {
            conn.disconnect()
        }
    }

    private fun readAll(s: InputStream): String {
        val sb = StringBuilder()
        BufferedReader(s.reader(Charsets.UTF_8)).use { r ->
            val buf = CharArray(8192)
            while (true) {
                val n = r.read(buf)
                if (n < 0) break
                sb.append(buf, 0, n)
            }
        }
        return sb.toString()
    }

    /** Short, loggable description of a failed response (never the body of a success). */
    fun describeFailure(r: Response?, e: Throwable? = null): String = when {
        e != null -> (e::class.java.simpleName + (e.message?.let { ": " + it.take(120) } ?: ""))
        r == null -> "no response"
        else -> "HTTP ${r.status} " + r.body.replace('\n', ' ').take(200)
    }

    class HttpException(val status: Int, message: String) : IOException(message)
}
