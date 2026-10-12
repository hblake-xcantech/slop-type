package dev.sloptype.llamabridge

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Where the on-device GGUF lives and how it gets there. */
object ModelStore {
    private const val TAG = "ModelStore"
    private const val MAX_REDIRECTS = 5

    /** `filesDir/models/lm.gguf` */
    fun modelFile(context: Context): File = File(File(context.filesDir, "models"), "lm.gguf")

    /** True when [modelFile] exists and (if a checksum was recorded) was verified after download. */
    fun isDownloaded(context: Context, sha256: String? = DefaultModel.SHA256): Boolean {
        val f = modelFile(context)
        if (!f.isFile || f.length() == 0L) return false
        if (sha256 == null) return true
        return readSidecar(f).equals(sha256, ignoreCase = true)
    }

    /**
     * Ensure [modelFile] is present, downloading from [url] if needed. Blocking; call off the main
     * thread. Downloads go to `lm.gguf.part` (resumed with a Range request if interrupted), are
     * hashed, then atomically renamed into place. A verified hash is remembered in `lm.gguf.sha256`
     * so later calls return immediately.
     *
     * @param sha256 expected hex digest, or null to skip verification.
     * @param onProgress called with (bytesSoFar, totalBytes or -1 if unknown).
     * @throws IOException on network failure or checksum mismatch.
     */
    @Throws(IOException::class)
    fun ensureDownloaded(
        context: Context,
        url: String = DefaultModel.URL,
        sha256: String? = DefaultModel.SHA256,
        onProgress: (bytesSoFar: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File {
        val target = modelFile(context)
        val expected = sha256?.lowercase()
        if (target.isFile && target.length() > 0) {
            if (expected == null) return target
            if (readSidecar(target) == expected) return target
            val actual = sha256Of(target, onProgress)
            if (actual == expected) {
                writeSidecar(target, actual)
                return target
            }
            Log.w(TAG, "existing model has wrong hash ($actual), re-downloading")
            target.delete()
            sidecar(target).delete()
        }

        target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("cannot create ${it.path}") }
        val part = File(target.path + ".part")
        download(url, part, onProgress)

        if (expected != null) {
            val actual = sha256Of(part) { _, _ -> }
            if (actual != expected) {
                part.delete()
                throw IOException("sha256 mismatch for $url: expected $expected, got $actual")
            }
        }
        if (!part.renameTo(target)) {
            // Different filesystem or stale target: fall back to copy + delete.
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        if (expected != null) writeSidecar(target, expected)
        return target
    }

    /** Delete the model and its sidecar. */
    fun delete(context: Context) {
        val f = modelFile(context)
        f.delete()
        sidecar(f).delete()
        File(f.path + ".part").delete()
    }

    private fun sidecar(f: File) = File(f.path + ".sha256")
    private fun readSidecar(f: File): String? = runCatching { sidecar(f).readText().trim().lowercase() }.getOrNull()
    private fun writeSidecar(f: File, hex: String) = runCatching { sidecar(f).writeText(hex) }

    @Throws(IOException::class)
    private fun download(url: String, part: File, onProgress: (Long, Long) -> Unit) {
        var already = if (part.isFile) part.length() else 0L
        var attempt = 0
        while (true) {
            attempt++
            val conn = open(url, already)
            try {
                val code = conn.responseCode
                val append: Boolean
                when (code) {
                    HttpURLConnection.HTTP_PARTIAL -> append = true
                    HttpURLConnection.HTTP_OK -> { append = false; already = 0L }
                    416 -> { // our .part is larger than the file; start over
                        part.delete(); already = 0L
                        if (attempt < 2) continue else throw IOException("HTTP 416 from $url")
                    }
                    else -> throw IOException("HTTP $code from $url")
                }
                val remaining = conn.contentLengthLong
                val total = if (remaining >= 0) remaining + already else -1L
                val buf = ByteArray(256 * 1024)
                var done = already
                conn.inputStream.use { input ->
                    FileOutputStream(part, append).use { out ->
                        onProgress(done, total)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                        out.fd.sync()
                    }
                }
                if (total >= 0 && done != total) throw IOException("short download: $done of $total bytes")
                return
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Opens [url], following up to [MAX_REDIRECTS] redirects (including cross-host, as HF uses). */
    @Throws(IOException::class)
    private fun open(url: String, rangeStart: Long): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "sloptype-llamabridge/1.0")
                setRequestProperty("Accept-Encoding", "identity")
                if (rangeStart > 0) setRequestProperty("Range", "bytes=$rangeStart-")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw IOException("redirect without Location from $current")
                conn.disconnect()
                current = URL(URL(current), loc).toString()
                return@repeat
            }
            return conn
        }
        throw IOException("too many redirects for $url")
    }

    private fun sha256Of(f: File, onProgress: (Long, Long) -> Unit): String {
        val md = MessageDigest.getInstance("SHA-256")
        val total = f.length()
        var done = 0L
        RandomAccessFile(f, "r").use { raf ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = raf.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                done += n
                onProgress(done, total)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
