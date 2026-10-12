package dev.sloptype.app.daemon

import android.content.Context
import android.util.Log
import dev.sloptype.app.Secrets
import dev.sloptype.app.SlopPaths
import dev.sloptype.app.SlopRuntime
import dev.sloptype.app.SlopSecrets
import dev.sloptype.engine.Spend
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** `state/daemon-status.json`, also uploaded to `status/<device>/daemon-status.json` after each run. */
@Serializable
data class DaemonStatus(
    val lastRun: Long = 0,
    val lastRunIso: String = "",
    val lastDurationMs: Long = 0,
    val lastUploadCount: Int = 0,
    val lastUploadBytes: Long = 0,
    /** blob sha of configs/engine.json as last pulled */
    val configSha: String? = null,
    val apkTag: String? = null,
    val installedVersion: String = "",
    val spend: Spend? = null,
    val lastError: String? = null,
    val rootAvailable: Boolean = false,
    val device: String = "",
    /** per-step one-line outcomes of the last run */
    val steps: Map<String, String> = emptyMap(),
    /** free-form notes written by steps that may kill the process (apk install, rollback) */
    val notes: List<String> = emptyList(),
)

object DaemonStatusStore {
    private const val TAG = "SlopDaemon"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun file(ctx: Context): File = File(SlopPaths.state(ctx), "daemon-status.json")

    fun load(ctx: Context): DaemonStatus? {
        val f = file(ctx)
        if (!f.isFile) return null
        return runCatching { json.decodeFromString(DaemonStatus.serializer(), f.readText()) }.getOrNull()
    }

    fun write(ctx: Context, status: DaemonStatus) {
        runCatching { DaemonUtil.writeAtomic(file(ctx), json.encodeToString(DaemonStatus.serializer(), status)) }
            .onFailure { Log.w(TAG, "write daemon-status.json failed", it) }
    }

    /** Appends a note to the stored status immediately (used right before actions that kill the process). */
    fun note(ctx: Context, text: String) {
        val cur = load(ctx) ?: DaemonStatus()
        val stamped = "${DaemonUtil.isoUtc(System.currentTimeMillis())} $text"
        write(ctx, cur.copy(notes = (cur.notes + stamped).takeLast(20)))
    }

    /** Builds the status for a finished run; [steps] / [errors] come from the worker. */
    fun build(
        ctx: Context,
        secrets: Secrets,
        startedAt: Long,
        upload: LogUploader.UploadStats?,
        steps: Map<String, String>,
        errors: List<String>,
    ): DaemonStatus {
        val now = System.currentTimeMillis()
        val prev = load(ctx)
        return DaemonStatus(
            lastRun = now,
            lastRunIso = DaemonUtil.isoUtc(now),
            lastDurationMs = now - startedAt,
            lastUploadCount = upload?.files ?: 0,
            lastUploadBytes = upload?.bytes ?: 0,
            configSha = ConfigPuller.localShas(ctx)["configs/engine.json"],
            apkTag = ApkUpdater.installedTag(ctx) ?: ApkUpdater.readInstallRecord(ctx)?.tag,
            installedVersion = ApkUpdater.installedVersionName(ctx),
            spend = runCatching { SlopRuntime.spendSummary() }.getOrNull(),
            lastError = errors.takeIf { it.isNotEmpty() }?.joinToString(" | ")?.take(2000),
            rootAvailable = Root.available(),
            device = secrets.deviceName,
            steps = steps,
            notes = prev?.notes ?: emptyList(),
        )
    }

    fun upload(ctx: Context, gh: GitHubClient, secrets: Secrets = SlopSecrets.load(ctx), status: DaemonStatus? = load(ctx)) {
        val s = status ?: return
        val bytes = json.encodeToString(DaemonStatus.serializer(), s).toByteArray(Charsets.UTF_8)
        gh.upsertContent(secrets.githubOwner, secrets.dataRepo, "status/${secrets.deviceName}/daemon-status.json", bytes, "status: ${secrets.deviceName} ${s.lastRunIso}")
    }
}
