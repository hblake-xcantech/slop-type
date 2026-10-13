package dev.sloptype.app.daemon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.sloptype.app.SlopPaths
import dev.sloptype.app.SlopRuntime
import dev.sloptype.app.SlopSecrets
import java.io.File

/**
 * Control surface for adb / the provisioning script (declared in the manifest, not exported;
 * trigger as root: `su -c "am broadcast -n dev.sloptype/dev.sloptype.app.daemon.DaemonReceiver -a <action>"`).
 */
class DaemonReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SlopDaemon"
        const val ACTION_RUN_NOW = "dev.sloptype.DAEMON_RUN_NOW"
        const val ACTION_BENCH_RUN = "dev.sloptype.BENCH_RUN"
        const val ACTION_EXPORT_GEOMETRY = "dev.sloptype.EXPORT_GEOMETRY"
        const val ACTION_RELOAD_CONFIG = "dev.sloptype.RELOAD_CONFIG"
        const val ACTION_PROVISION = "dev.sloptype.PROVISION"

        const val EXTRA_GITHUB_TOKEN = "github_token"
        const val EXTRA_OPENAI_KEY = "openai_api_key"
        const val EXTRA_JEV_KEY = "jev_api_key"
        const val EXTRA_AGE_RECIPIENT = "age_recipient"
        const val EXTRA_DEVICE_NAME = "device_name"

        const val GEOMETRY_FILE = "geometry.json"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val action = intent.action ?: return
        Log.i(TAG, "receiver: $action")
        try {
            when (action) {
                ACTION_RUN_NOW -> { SlopRuntime.init(ctx); Daemon.schedule(ctx); Daemon.runNow(ctx) }
                ACTION_RELOAD_CONFIG -> { SlopRuntime.init(ctx); SlopRuntime.reloadConfigs(ctx) }
                ACTION_EXPORT_GEOMETRY -> exportGeometry(ctx)
                ACTION_PROVISION -> provision(ctx, intent)
                ACTION_BENCH_RUN -> benchRun(ctx)
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                    SlopRuntime.init(ctx)
                    Daemon.schedule(ctx)
                    if (action == Intent.ACTION_MY_PACKAGE_REPLACED) Log.i(TAG, "package replaced; now ${ApkUpdater.installedVersionName(ctx)}")
                }
                else -> Log.w(TAG, "unknown action $action")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "receiver $action failed", e)
        }
    }

    private fun exportGeometry(ctx: Context) {
        val out = File(SlopPaths.bench(ctx), GEOMETRY_FILE)
        val geom = SlopRuntime.geometryJson()
        if (geom == null) {
            Log.w(TAG, "no keyboard geometry yet (show the keyboard once in this process first); not writing ${out.path}")
            return
        }
        DaemonUtil.writeAtomic(out, geom)
        Log.i(TAG, "geometry written to ${out.path} (${geom.length} chars)")
    }

    private fun provision(ctx: Context, intent: Intent) {
        fun extra(key: String): String? = intent.getStringExtra(key)?.trim()?.takeIf { it.isNotEmpty() }
        val token = extra(EXTRA_GITHUB_TOKEN)
        val openai = extra(EXTRA_OPENAI_KEY)
        val jev = extra(EXTRA_JEV_KEY)
        val recipient = extra(EXTRA_AGE_RECIPIENT)
        val device = extra(EXTRA_DEVICE_NAME)
        val recipients = recipient?.split(',', ' ', '\n')?.map { it.trim() }?.filter { it.startsWith("age1") }?.distinct()
        if (recipient != null && recipients.isNullOrEmpty()) Log.w(TAG, "age_recipient ignored: not an age1... key")
        SlopSecrets.update(ctx) { s ->
            s.copy(
                githubToken = token ?: s.githubToken,
                openaiApiKey = openai ?: s.openaiApiKey,
                jevApiKey = jev ?: s.jevApiKey,
                ageRecipients = if (!recipients.isNullOrEmpty()) recipients else s.ageRecipients,
                deviceName = device ?: s.deviceName,
            )
        }
        val set = listOfNotNull(
            token?.let { "github_token" }, openai?.let { "openai_api_key" }, jev?.let { "jev_api_key" },
            recipients?.takeIf { it.isNotEmpty() }?.let { "age_recipient" }, device?.let { "device_name=$it" },
        )
        Log.i(TAG, "provisioned: ${set.joinToString(", ").ifEmpty { "(nothing)" }}")
        SlopRuntime.init(ctx)
        runCatching { SlopRuntime.reloadConfigs(ctx) }  // re-attaches tier 2/3 clients with the new keys
        Daemon.schedule(ctx)
        Daemon.runNow(ctx)
    }

    /**
     * `dev.sloptype.app.remote.Benchmark.runAsync(ctx)` is written by another module author; it is
     * resolved reflectively so this package compiles even if that class is absent.
     */
    private fun benchRun(ctx: Context) {
        try { dev.sloptype.app.remote.Benchmark.runAsync(ctx) } catch (e: Throwable) { Log.w(TAG, "BENCH_RUN failed: $e") }
    }
}
