package dev.sloptype.app.daemon

import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Pure helpers shared by the daemon steps (no Android dependencies so they are unit-testable). */
internal object DaemonUtil {
    /** `yyyy-MM-dd` in UTC for a ms-since-epoch timestamp. */
    fun utcDate(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    /** ISO-8601 UTC timestamp. */
    fun isoUtc(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    /**
     * Date folder for a log file. Log names are `<sid>.jsonl[.age]` where the sid prefix (before
     * the first `-`) is the session start in ms since epoch. Falls back to [fallbackMs].
     */
    fun dateForLogName(name: String, fallbackMs: Long): String {
        val prefix = name.substringBefore('-').substringBefore('.')
        val ms = prefix.toLongOrNull()?.takeIf { it in 946_684_800_000L..4_102_444_800_000L } ?: fallbackMs
        return utcDate(ms)
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Writes [bytes] to [dest] via a temp file in the same directory + rename. */
    fun writeAtomic(dest: File, bytes: ByteArray) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.outputStream().use { it.write(bytes); it.flush(); it.fd.sync() }
        if (!tmp.renameTo(dest)) {
            dest.delete()
            if (!tmp.renameTo(dest)) throw java.io.IOException("rename ${tmp.name} -> ${dest.name} failed")
        }
    }

    fun writeAtomic(dest: File, text: String) = writeAtomic(dest, text.toByteArray(Charsets.UTF_8))

    /** Recipients file: one `age1...` per line; `#` comments and blank lines ignored. */
    fun parseRecipients(text: String): List<String> =
        text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.startsWith("age1") }.distinct().toList()
}

/** Semantic-version helpers for release tags (`v1.2.3`) and `versionName`s (`1.2.3`, `1.2.3-debug`). */
internal object Semver {
    /** Numeric components of [s], or null if it does not start with `[v]N[.N...]`. */
    fun parse(s: String): List<Int>? {
        var core = s.trim().removePrefix("v").removePrefix("V")
        core = core.substringBefore('-').substringBefore('+').substringBefore(' ')
        if (core.isEmpty()) return null
        val parts = core.split('.')
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return nums
    }

    /** Negative if a < b, zero if equal, positive if a > b. Unparseable versions compare as 0.0.0. */
    fun compare(a: String, b: String): Int {
        val pa = parse(a) ?: emptyList()
        val pb = parse(b) ?: emptyList()
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    fun isNewer(candidate: String, installed: String): Boolean = parse(candidate) != null && compare(candidate, installed) > 0
}
