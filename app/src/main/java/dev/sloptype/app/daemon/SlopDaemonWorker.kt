package dev.sloptype.app.daemon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.sloptype.app.SlopRuntime
import dev.sloptype.app.SlopSecrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One daemon run: rollback watchdog -> log upload -> config pull -> apk update -> status.
 * Every step is isolated; a failure is recorded in `lastError` and the next step still runs.
 */
class SlopDaemonWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runDaemon(applicationContext)
        Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val ctx = applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Slop Type sync", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(ctx, CHANNEL)
            .setContentTitle("Slop Type")
            .setContentText("Syncing logs and configs")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(NOTIFICATION_ID, n)
    }

    companion object {
        private const val TAG = "SlopDaemon"
        private const val CHANNEL = "slop_daemon"
        private const val NOTIFICATION_ID = 0x51a9
        private val running = AtomicBoolean(false)

        /** Runs all steps synchronously on the calling thread. Safe to call from tests / the receiver. */
        fun runDaemon(ctx: Context) {
            if (!running.compareAndSet(false, true)) { Log.i(TAG, "daemon already running; skipping"); return }
            val startedAt = System.currentTimeMillis()
            val steps = LinkedHashMap<String, String>()
            val errors = ArrayList<String>()
            try {
                SlopRuntime.init(ctx)
                val secrets = SlopSecrets.load(ctx)
                if (!secrets.hasGithub) {
                    Log.w(TAG, "no github token; daemon idle (run scripts/provision_phone.sh)")
                    DaemonStatusStore.write(ctx, DaemonStatusStore.build(ctx, secrets, startedAt, null, mapOf("daemon" to "idle"), listOf("no github token")))
                    return
                }
                val gh = GitHubClient(secrets.githubToken)

                fun step(name: String, block: () -> String) {
                    try {
                        val r = block()
                        steps[name] = r
                        Log.i(TAG, "$name: $r")
                    } catch (e: Throwable) {
                        val msg = "$name: ${e.javaClass.simpleName}: ${e.message}"
                        steps[name] = "error: ${e.message}"
                        errors += msg
                        Log.w(TAG, msg, e)
                    }
                }

                step("rollback") { RollbackWatchdog.check(ctx, gh, secrets).toString() }
                var upload: LogUploader.UploadStats? = null
                step("upload") {
                    val s = LogUploader.run(ctx, gh, secrets)
                    upload = s
                    errors += s.errors.map { "upload: $it" }
                    "encrypted=${s.encrypted} uploaded=${s.files} bytes=${s.bytes} skipped=${s.skipped} errors=${s.errors.size}"
                }
                step("config") { if (ConfigPuller.run(ctx, gh, secrets)) "changed (reloaded)" else "unchanged" }
                // Snapshot the status locally before the apk step: a successful install kills this process.
                DaemonStatusStore.write(ctx, DaemonStatusStore.build(ctx, secrets, startedAt, upload, steps + ("apk" to "running"), errors))
                step("apk") { ApkUpdater.run(ctx, gh, secrets).toString() }
                step("status") {
                    val status = DaemonStatusStore.build(ctx, secrets, startedAt, upload, steps, errors)
                    DaemonStatusStore.write(ctx, status)
                    DaemonStatusStore.upload(ctx, gh, secrets, status)
                    "uploaded"
                }
                if (steps["status"]?.startsWith("error") == true) {
                    // The upload failed; still keep the local status current with the error recorded.
                    DaemonStatusStore.write(ctx, DaemonStatusStore.build(ctx, SlopSecrets.load(ctx), startedAt, upload, steps, errors))
                }
            } catch (e: Throwable) {
                Log.e(TAG, "daemon run failed", e)
                runCatching { DaemonStatusStore.write(ctx, DaemonStatusStore.build(ctx, SlopSecrets.load(ctx), startedAt, null, steps, errors + "fatal: ${e.message}")) }
            } finally {
                running.set(false)
                Log.i(TAG, "daemon run finished in ${System.currentTimeMillis() - startedAt} ms; errors=${errors.size}")
            }
        }
    }
}

/** WorkManager scheduling for [SlopDaemonWorker]. */
object Daemon {
    private const val TAG = "SlopDaemon"
    const val PERIODIC_WORK = "slop-daemon"
    const val ONE_SHOT_WORK = "slop-daemon-now"
    const val PERIOD_HOURS = 6L

    /** Enqueues the 6-hourly run (unmetered network + charging). Existing schedule is kept. */
    fun schedule(ctx: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresCharging(true)
                .build()
            val req = PeriodicWorkRequestBuilder<SlopDaemonWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .addTag(PERIODIC_WORK)
                .build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, req)
            Log.i(TAG, "periodic daemon scheduled (every $PERIOD_HOURS h, unmetered + charging)")
        } catch (e: Throwable) {
            Log.e(TAG, "schedule failed", e)
        }
    }

    /** Runs the daemon as soon as any network is available (expedited when the OS allows). */
    fun runNow(ctx: Context) {
        try {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val req = OneTimeWorkRequestBuilder<SlopDaemonWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(ONE_SHOT_WORK)
                .build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniqueWork(ONE_SHOT_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
            Log.i(TAG, "one-shot daemon run enqueued")
        } catch (e: Throwable) {
            Log.e(TAG, "runNow failed", e)
        }
    }
}
