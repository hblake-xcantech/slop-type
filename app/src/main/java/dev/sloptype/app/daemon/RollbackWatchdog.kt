package dev.sloptype.app.daemon

import android.content.Context
import android.util.Log
import dev.sloptype.app.Secrets
import dev.sloptype.app.SlopPaths
import dev.sloptype.app.SlopRuntime
import dev.sloptype.app.SlopSecrets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Daemon step 0: for 6 h after a self-update, compare today's corrections-per-word against the
 * pre-install 7-day baseline recorded in `install.json`; if it is > 1.8x (with >= 200 words),
 * reinstall `last-good.apk` with `pm install -r -d` and record the rollback in `slop-type-data`.
 */
object RollbackWatchdog {
    private const val TAG = "SlopDaemon"
    const val WINDOW_MS = 6L * 60 * 60 * 1000
    /** Time allowed for `pm install` to complete before a still-old versionName counts as a failed install. */
    const val INSTALL_GRACE_MS = 10L * 60 * 1000
    const val THRESHOLD = 1.8
    const val MIN_WORDS = 200L

    enum class Outcome { NONE, PENDING, ACCEPTED, INSTALL_FAILED, ROLLED_BACK, ROLLBACK_FAILED }
    data class Result(val outcome: Outcome, val message: String = "") {
        override fun toString() = if (message.isEmpty()) "$outcome" else "$outcome: $message"
    }

    @Serializable
    data class RollbackRecord(
        val reason: String,
        val baselineCpw: Double?,
        val current: Double?,
        val tag: String,
        val fromVersion: String,
        val toVersion: String,
        val device: String,
        val at: Long,
        val atIso: String,
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private fun pendingDir(ctx: Context) = File(SlopPaths.apk(ctx), "rollbacks").apply { mkdirs() }

    fun check(
        ctx: Context,
        gh: GitHubClient? = SlopSecrets.load(ctx).takeIf { it.hasGithub }?.let { GitHubClient(it.githubToken) },
        secrets: Secrets = SlopSecrets.load(ctx),
    ): Result {
        if (gh != null) runCatching { uploadPendingRecords(ctx, gh, secrets) }.onFailure { Log.w(TAG, "rollback record upload failed: ${it.message}") }

        val f = ApkUpdater.installFile(ctx)
        if (!f.exists()) return Result(Outcome.NONE)
        val rec = ApkUpdater.readInstallRecord(ctx) ?: run { f.delete(); return Result(Outcome.NONE, "corrupt install.json removed") }
        val now = System.currentTimeMillis()
        val age = now - rec.installedAt

        val version = ApkUpdater.installedVersionName(ctx)
        if (Semver.compare(version, rec.toVersion) != 0) {
            if (age < INSTALL_GRACE_MS) return Result(Outcome.PENDING, "install of ${rec.tag} in progress")
            val log = ApkUpdater.readInstallLog()
            f.delete()
            Log.w(TAG, "install of ${rec.tag} did not take (still $version): $log")
            return Result(Outcome.INSTALL_FAILED, "still on $version after ${age / 60_000} min: $log")
        }
        if (age > WINDOW_MS) {
            f.delete()
            ApkUpdater.markInstalledTag(ctx, rec.tag)
            Log.i(TAG, "update ${rec.tag} accepted after ${age / 3_600_000} h")
            return Result(Outcome.ACCEPTED, rec.tag)
        }

        val baseline = rec.baselineCpw
        val current = runCatching { SlopRuntime.logger.counters().correctionsPerWord(1, MIN_WORDS) }.getOrNull()
        if (baseline == null || current == null) {
            return Result(Outcome.PENDING, "${rec.tag}: baseline=$baseline current=$current (${(WINDOW_MS - age) / 60_000} min left)")
        }
        if (current <= THRESHOLD * baseline) {
            return Result(Outcome.PENDING, "${rec.tag}: cpw %.3f vs baseline %.3f ok".format(current, baseline))
        }

        // ---- roll back
        val reason = "corrections/word %.3f > %.1f x baseline %.3f within %d min of installing %s".format(current, THRESHOLD, baseline, age / 60_000, rec.tag)
        Log.w(TAG, "ROLLBACK: $reason")
        val lastGood = ApkUpdater.lastGoodFile(ctx)
        if (!lastGood.isFile) { f.delete(); return Result(Outcome.ROLLBACK_FAILED, "no last-good.apk ($reason)") }
        if (!Root.available()) return Result(Outcome.ROLLBACK_FAILED, "su not available ($reason)")

        val record = RollbackRecord(reason, baseline, current, rec.tag, rec.fromVersion, rec.toVersion, secrets.deviceName, now, DaemonUtil.isoUtc(now))
        val text = json.encodeToString(RollbackRecord.serializer(), record)
        runCatching { DaemonUtil.writeAtomic(File(SlopPaths.apk(ctx), "rollback.json"), text) }
        // Queue the record and try to upload it now: the reinstall below kills this process.
        val queued = File(pendingDir(ctx), "$now.json")
        runCatching { DaemonUtil.writeAtomic(queued, text) }
        var note = ""
        if (gh != null && secrets.hasGithub) {
            try { uploadRecord(gh, secrets, queued); queued.delete() } catch (e: Exception) { note = "; record queued (upload failed: ${e.message})" }
        } else note = "; record queued (no github token)"

        f.delete()
        ApkUpdater.markSkipped(ctx, rec.tag)
        ApkUpdater.markInstalledTag(ctx, "v${rec.fromVersion}")
        DaemonStatusStore.note(ctx, "rollback: $reason")
        val r = ApkUpdater.launchInstall(lastGood, downgrade = true)
        if (!r.ok) return Result(Outcome.ROLLBACK_FAILED, "${r.brief()} ($reason)$note")
        return Result(Outcome.ROLLED_BACK, "$reason$note")
    }

    private fun uploadRecord(gh: GitHubClient, secrets: Secrets, file: File) {
        val remote = "rollbacks/${secrets.deviceName}/${file.name}"
        gh.upsertContent(secrets.githubOwner, secrets.dataRepo, remote, file.readBytes(), "rollback: ${secrets.deviceName} ${file.nameWithoutExtension}")
    }

    /** Uploads rollback records whose upload failed at rollback time. */
    private fun uploadPendingRecords(ctx: Context, gh: GitHubClient, secrets: Secrets) {
        val dir = File(SlopPaths.apk(ctx), "rollbacks")
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { f ->
            uploadRecord(gh, secrets, f)
            f.delete()
        }
    }
}
