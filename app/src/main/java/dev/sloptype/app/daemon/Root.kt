package dev.sloptype.app.daemon

import java.util.concurrent.TimeUnit

/**
 * Runs shell commands as root through Magisk (`su -c <cmd>`).
 *
 * Callers must never log [Result.stdout] of a command that could print secrets; this object
 * itself logs nothing.
 */
object Root {
    data class Result(val exit: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exit == 0
        /** A short, log-safe description (exit code + first stderr line only). */
        fun brief(): String = "exit=$exit" + (stderr.lineSequence().firstOrNull { it.isNotBlank() }?.let { " stderr=${it.take(200)}" } ?: "")
    }

    const val EXIT_SPAWN_FAILED = -1
    const val EXIT_TIMEOUT = -2

    @Volatile private var cachedAvailable: Boolean? = null

    fun run(cmd: String, timeoutMs: Long = 60_000): Result {
        val process = try {
            ProcessBuilder("su", "-c", cmd).start()
        } catch (e: Exception) {
            return Result(EXIT_SPAWN_FAILED, "", "su: ${e.message}")
        }
        val out = StringBuilder()
        val err = StringBuilder()
        val tOut = Thread({ drain(process.inputStream, out) }, "su-stdout").apply { isDaemon = true; start() }
        val tErr = Thread({ drain(process.errorStream, err) }, "su-stderr").apply { isDaemon = true; start() }
        runCatching { process.outputStream.close() }
        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            return Result(EXIT_TIMEOUT, out.toString(), "interrupted")
        }
        if (!finished) {
            process.destroyForcibly()
            joinQuietly(tOut); joinQuietly(tErr)
            return Result(EXIT_TIMEOUT, out.toString(), "timeout after $timeoutMs ms")
        }
        joinQuietly(tOut); joinQuietly(tErr)
        return Result(process.exitValue(), out.toString(), err.toString())
    }

    /** True if `su` works and gives uid 0. Cached for the life of the process (see [invalidate]). */
    fun available(): Boolean {
        cachedAvailable?.let { return it }
        synchronized(this) {
            cachedAvailable?.let { return it }
            val r = run("id", 15_000)
            val ok = r.exit == 0 && r.stdout.contains("uid=0")
            cachedAvailable = ok
            return ok
        }
    }

    fun invalidate() { cachedAvailable = null }

    /** Quotes [s] for POSIX sh (single quotes). */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun drain(input: java.io.InputStream, sb: StringBuilder) {
        try {
            input.bufferedReader().use { r ->
                val buf = CharArray(4096)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                }
            }
        } catch (_: Exception) {}
    }

    private fun joinQuietly(t: Thread) { try { t.join(5_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() } }
}
