package dev.sloptype.app.daemon

import android.content.Context
import android.util.Log
import dev.sloptype.agekt.Age
import dev.sloptype.app.Secrets
import dev.sloptype.app.SlopPaths
import dev.sloptype.app.SlopRuntime
import dev.sloptype.app.SlopSecrets
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Daemon step 1: encrypt closed session logs with age into the outbox, then upload the outbox,
 * counters and benchmark results to `slop-type-data` via the Contents API.
 */
object LogUploader {
    private const val TAG = "SlopDaemon"
    /** The Contents API rejects files above 100 MB; keep a margin. Larger files are kept locally. */
    const val MAX_UPLOAD_BYTES: Long = 90L * 1024 * 1024
    private const val MARKS_FILE = ".upload-marks.json"

    data class UploadStats(
        var encrypted: Int = 0,
        var files: Int = 0,
        var bytes: Long = 0,
        var skipped: Int = 0,
        val errors: MutableList<String> = mutableListOf(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val marksSerializer = MapSerializer(String.serializer(), String.serializer())

    fun run(
        ctx: Context,
        gh: GitHubClient = GitHubClient(SlopSecrets.load(ctx).githubToken),
        secrets: Secrets = SlopSecrets.load(ctx),
    ): UploadStats {
        val stats = UploadStats()
        runCatching { encryptClosedLogs(ctx, secrets.ageRecipients, stats) }
            .onFailure { stats.errors += "encrypt: ${it.message}"; Log.w(TAG, "encrypt step failed", it) }
        if (!secrets.hasGithub) { stats.errors += "upload: no github token"; return stats }
        runCatching { uploadOutbox(ctx, gh, secrets, stats) }
            .onFailure { stats.errors += "upload: ${it.message}"; Log.w(TAG, "outbox upload failed", it) }
        runCatching { uploadCounters(ctx, gh, secrets) }
            .onFailure { stats.errors += "counters: ${it.message}"; Log.w(TAG, "counters upload failed", it) }
        runCatching { uploadBench(ctx, gh, secrets) }
            .onFailure { stats.errors += "bench: ${it.message}"; Log.w(TAG, "bench upload failed", it) }
        return stats
    }

    /** Encrypts every closed log into outbox/<name>.age; plaintext is deleted only after the .age file is complete. */
    fun encryptClosedLogs(ctx: Context, recipients: List<String>, stats: UploadStats = UploadStats()): Int {
        val logs = try { SlopRuntime.logger.closedLogs() } catch (e: Exception) {
            // logger needs SlopRuntime.init; fall back to the directory listing (current.jsonl is never present by that name)
            SlopPaths.logs(ctx).listFiles { f -> f.name.endsWith(".jsonl") }?.toList() ?: emptyList()
        }
        if (logs.isEmpty()) return 0
        val recips = recipients.filter { it.isNotBlank() }
        if (recips.isEmpty()) { stats.errors += "encrypt: no age recipients configured"; return 0 }
        val outbox = SlopPaths.outbox(ctx)
        var n = 0
        for (plain in logs) {
            if (!plain.isFile) continue
            val enc = File(outbox, plain.name + ".age")
            val tmp = File(outbox, plain.name + ".age.tmp")
            try {
                if (!enc.exists() || enc.length() == 0L) {
                    plain.inputStream().buffered().use { input ->
                        tmp.outputStream().buffered().use { out -> Age.encrypt(input, out, recips) }
                    }
                    if (!tmp.renameTo(enc)) throw java.io.IOException("rename failed for ${enc.name}")
                }
                // The .age file is complete on disk now; the plaintext can go.
                if (!plain.delete()) Log.w(TAG, "could not delete plaintext ${plain.name}")
                n++
            } catch (e: Exception) {
                tmp.delete()
                stats.errors += "encrypt ${plain.name}: ${e.message}"
                Log.w(TAG, "encrypt ${plain.name} failed", e)
            }
        }
        stats.encrypted += n
        return n
    }

    /** Uploads every outbox file to logs/<device>/<date>/<name>; deletes each after a 200/201. */
    fun uploadOutbox(ctx: Context, gh: GitHubClient, secrets: Secrets, stats: UploadStats) {
        val files = SlopPaths.outbox(ctx).listFiles { f -> f.isFile && f.name.endsWith(".age") }?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) return
        val device = secrets.deviceName
        val message = "logs: $device ${files.size} files"
        for (f in files) {
            if (f.length() > MAX_UPLOAD_BYTES) {
                stats.skipped++
                Log.w(TAG, "skipping ${f.name}: ${f.length()} bytes exceeds the upload limit")
                continue
            }
            val date = DaemonUtil.dateForLogName(f.name, f.lastModified())
            val remote = "logs/$device/$date/${f.name}"
            try {
                val bytes = f.readBytes()
                val r = gh.upsertContent(secrets.githubOwner, secrets.dataRepo, remote, bytes, message)
                if (r.status == 200 || r.status == 201) {
                    stats.files++; stats.bytes += bytes.size
                    if (!f.delete()) Log.w(TAG, "could not delete uploaded ${f.name}")
                } else {
                    stats.errors += "upload ${f.name}: HTTP ${r.status}"
                }
            } catch (e: GitHubException) {
                stats.errors += "upload ${f.name}: ${e.message}"
                Log.w(TAG, "upload ${f.name} failed: ${e.message}")
                if (e.status == 401 || e.status == 403) break  // no point retrying the rest with a bad token
            }
        }
    }

    /** metrics/<device>/counters.json (only when the content changed since the last upload). */
    fun uploadCounters(ctx: Context, gh: GitHubClient, secrets: Secrets) {
        runCatching { SlopRuntime.logger.counters().save() }
        val f = File(SlopPaths.state(ctx), "counters.json")
        if (!f.isFile) return
        uploadIfChanged(ctx, gh, secrets, f, "metrics/${secrets.deviceName}/counters.json", "metrics: ${secrets.deviceName} counters")
    }

    /** benchmarks/<device>/<yyyy-mm-dd>.json from bench/latest.json (only when changed). */
    fun uploadBench(ctx: Context, gh: GitHubClient, secrets: Secrets) {
        val f = File(SlopPaths.bench(ctx), "latest.json")
        if (!f.isFile) return
        val date = DaemonUtil.utcDate(f.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis())
        uploadIfChanged(ctx, gh, secrets, f, "benchmarks/${secrets.deviceName}/$date.json", "bench: ${secrets.deviceName} $date", markKey = "bench/latest.json")
    }

    private fun uploadIfChanged(ctx: Context, gh: GitHubClient, secrets: Secrets, f: File, remote: String, message: String, markKey: String = remote) {
        val bytes = f.readBytes()
        val digest = DaemonUtil.sha256Hex(bytes)
        val marks = loadMarks(ctx)
        if (marks[markKey] == digest) return
        val r = gh.upsertContent(secrets.githubOwner, secrets.dataRepo, remote, bytes, message)
        if (r.status == 200 || r.status == 201) {
            marks[markKey] = digest
            saveMarks(ctx, marks)
        }
    }

    private fun loadMarks(ctx: Context): MutableMap<String, String> {
        val f = File(SlopPaths.state(ctx), MARKS_FILE)
        if (!f.isFile) return mutableMapOf()
        return runCatching { json.decodeFromString(marksSerializer, f.readText()).toMutableMap() }.getOrDefault(mutableMapOf())
    }

    private fun saveMarks(ctx: Context, marks: Map<String, String>) {
        runCatching { DaemonUtil.writeAtomic(File(SlopPaths.state(ctx), MARKS_FILE), json.encodeToString(marksSerializer, marks)) }
    }
}
