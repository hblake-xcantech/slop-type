package dev.sloptype.app.daemon

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.util.Log
import dev.sloptype.app.Secrets
import dev.sloptype.app.SlopPaths
import dev.sloptype.app.SlopRuntime
import dev.sloptype.app.SlopSecrets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Daemon step 3: self-update from the latest GitHub release of `slop-type`.
 *
 * The APK is downloaded, its signer certificates are compared with the running app's, the current
 * APK is preserved as `last-good.apk`, `install.json` is written (for [RollbackWatchdog]) and only
 * then `pm install -r` is launched as root, detached, because the install kills this process.
 */
object ApkUpdater {
    private const val TAG = "SlopDaemon"
    /** pm needs a world-readable path outside our sandbox. */
    const val TMP_APK = "/data/local/tmp/sloptype-update.apk"
    const val TMP_LOG = "/data/local/tmp/sloptype-install.log"

    enum class Status {
        UP_TO_DATE, PENDING_WATCHDOG, NO_RELEASE, NO_ASSET, NO_ROOT, SKIPPED_TAG,
        SIGNATURE_MISMATCH, DOWNLOAD_FAILED, INSTALL_FAILED, INSTALL_STARTED, ERROR,
    }

    data class UpdateResult(val status: Status, val tag: String? = null, val message: String = "") {
        override fun toString() = "$status" + (tag?.let { " $it" } ?: "") + (if (message.isNotEmpty()) ": $message" else "")
    }

    /** `apk/install.json`, written before `pm install` so the watchdog can evaluate the update. */
    @Serializable
    data class InstallRecord(
        val fromVersion: String,
        val toVersion: String,
        val tag: String,
        val installedAt: Long,
        val baselineCpw: Double? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun installFile(ctx: Context): File = File(SlopPaths.apk(ctx), "install.json")
    fun lastGoodFile(ctx: Context): File = File(SlopPaths.apk(ctx), "last-good.apk")
    fun pendingFile(ctx: Context): File = File(SlopPaths.apk(ctx), "pending.apk")
    private fun skipFile(ctx: Context): File = File(SlopPaths.apk(ctx), "skip-tags.txt")
    private fun tagFile(ctx: Context): File = File(SlopPaths.apk(ctx), "tag.txt")

    fun run(
        ctx: Context,
        gh: GitHubClient = GitHubClient(SlopSecrets.load(ctx).githubToken),
        secrets: Secrets = SlopSecrets.load(ctx),
    ): UpdateResult {
        if (!secrets.hasGithub) return UpdateResult(Status.ERROR, message = "no github token")
        if (installFile(ctx).exists()) return UpdateResult(Status.PENDING_WATCHDOG, message = "previous install still under the rollback watchdog")

        val rel = gh.latestRelease(secrets.githubOwner, secrets.appRepo) ?: return UpdateResult(Status.NO_RELEASE)
        val tag = rel.tagName
        val installed = installedVersionName(ctx)
        if (Semver.parse(tag) == null) return UpdateResult(Status.ERROR, tag, "release tag is not vX.Y.Z")
        if (!Semver.isNewer(tag, installed)) return UpdateResult(Status.UP_TO_DATE, tag, "installed $installed")
        if (isSkipped(ctx, tag)) return UpdateResult(Status.SKIPPED_TAG, tag, "tag failed verification or was rolled back earlier")
        val asset = rel.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: return UpdateResult(Status.NO_ASSET, tag, "release has no .apk asset")
        if (!Root.available()) return UpdateResult(Status.NO_ROOT, tag, "su not available")

        val pending = pendingFile(ctx)
        if (!(pending.isFile && asset.size > 0 && pending.length() == asset.size)) {
            try {
                Log.i(TAG, "downloading ${asset.name} ($tag, ${asset.size} bytes)")
                gh.download(asset.url, pending, asset.size.takeIf { it > 0 })
            } catch (e: GitHubException) {
                return UpdateResult(Status.DOWNLOAD_FAILED, tag, e.message ?: "download failed")
            }
        }

        @Suppress("DEPRECATION")
        val archive = ctx.packageManager.getPackageArchiveInfo(pending.absolutePath, 0)
        if (archive == null || archive.packageName != ctx.packageName) {
            pending.delete(); markSkipped(ctx, tag)
            return UpdateResult(Status.SIGNATURE_MISMATCH, tag, "asset package is '${archive?.packageName}', expected '${ctx.packageName}'")
        }
        val ours = installedSigners(ctx)
        val theirs = archiveSigners(ctx, pending)
        if (theirs.isEmpty() || ours.isEmpty() || theirs != ours) {
            pending.delete(); markSkipped(ctx, tag)
            Log.w(TAG, "signature mismatch for $tag: installed=$ours asset=$theirs")
            return UpdateResult(Status.SIGNATURE_MISMATCH, tag, "signer certificates differ from the installed app")
        }

        try {
            File(ctx.applicationInfo.sourceDir).copyTo(lastGoodFile(ctx), overwrite = true)
        } catch (e: Exception) {
            return UpdateResult(Status.ERROR, tag, "could not preserve current apk: ${e.message}")
        }
        val toVersion = archive.versionName ?: tag.removePrefix("v")
        val record = InstallRecord(
            fromVersion = installed,
            toVersion = toVersion,
            tag = tag,
            installedAt = System.currentTimeMillis(),
            baselineCpw = runCatching { SlopRuntime.correctionsPerWord(ctx, 7) }.getOrNull(),
        )
        writeInstallRecord(ctx, record)          // BEFORE pm: the install kills this process
        runCatching { SlopRuntime.saveState(ctx) }
        DaemonStatusStore.note(ctx, "apk: installing $tag ($installed -> $toVersion)")

        val r = launchInstall(pending, downgrade = false)
        if (!r.ok) {
            installFile(ctx).delete()
            return UpdateResult(Status.INSTALL_FAILED, tag, r.brief())
        }
        return UpdateResult(Status.INSTALL_STARTED, tag, "$installed -> $toVersion")
    }

    // ------------------------------------------------------------------ helpers shared with the watchdog

    @Suppress("DEPRECATION")
    fun installedVersionName(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    /** Tag of the last accepted self-update (apk/tag.txt), or null if never updated by the daemon. */
    fun installedTag(ctx: Context): String? = tagFile(ctx).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    fun markInstalledTag(ctx: Context, tag: String) { runCatching { DaemonUtil.writeAtomic(tagFile(ctx), tag) } }

    fun readInstallRecord(ctx: Context): InstallRecord? {
        val f = installFile(ctx)
        if (!f.isFile) return null
        return runCatching { json.decodeFromString(InstallRecord.serializer(), f.readText()) }.getOrNull()
    }

    fun writeInstallRecord(ctx: Context, rec: InstallRecord) =
        DaemonUtil.writeAtomic(installFile(ctx), json.encodeToString(InstallRecord.serializer(), rec))

    fun isSkipped(ctx: Context, tag: String): Boolean =
        skipFile(ctx).takeIf { it.isFile }?.readLines()?.any { it.trim() == tag } ?: false

    fun markSkipped(ctx: Context, tag: String) {
        if (isSkipped(ctx, tag)) return
        runCatching { skipFile(ctx).appendText("$tag\n") }
    }

    /** SHA-256 hex of each signer certificate of the installed app. */
    @Suppress("DEPRECATION")
    fun installedSigners(ctx: Context): Set<String> =
        signersOf(runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull())

    /** SHA-256 hex of each signer certificate of an APK file. */
    @Suppress("DEPRECATION")
    fun archiveSigners(ctx: Context, apk: File): Set<String> {
        val pm = ctx.packageManager
        var pi = runCatching { pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull()
        if (pi?.signingInfo == null) {
            // API 28 does not populate signingInfo for archives; fall back to the legacy flag.
            pi = runCatching { pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES) }.getOrNull()
        }
        return signersOf(pi)
    }

    @Suppress("DEPRECATION")
    private fun signersOf(pi: PackageInfo?): Set<String> {
        if (pi == null) return emptySet()
        val sigs: Array<Signature>? = pi.signingInfo?.apkContentsSigners ?: pi.signatures
        return sigs?.map { DaemonUtil.sha256Hex(it.toByteArray()) }?.toSet() ?: emptySet()
    }

    /**
     * Copies [apk] to [TMP_APK] (chmod 644) and launches `pm install` detached with nohup so it
     * survives this process being killed by the install. Output goes to [TMP_LOG] (+ `exit=N`).
     */
    fun launchInstall(apk: File, downgrade: Boolean): Root.Result {
        val flags = if (downgrade) "-r -d --user 0" else "-r --user 0"
        val src = Root.quote(apk.absolutePath)
        val cmd = "rm -f $TMP_LOG; cp $src $TMP_APK && chmod 644 $TMP_APK && " +
            "(nohup sh -c 'pm install $flags $TMP_APK > $TMP_LOG 2>&1; echo \"exit=\$?\" >> $TMP_LOG' >/dev/null 2>&1 &)"
        Log.i(TAG, "pm install $flags (${apk.name}, ${apk.length()} bytes)")
        return Root.run(cmd, 120_000)
    }

    /** The last `pm install` output (log-safe; pm prints no secrets). */
    fun readInstallLog(): String = Root.run("cat $TMP_LOG", 15_000).let { if (it.ok) it.stdout.trim().take(1000) else "(no install log: ${it.brief()})" }
}
