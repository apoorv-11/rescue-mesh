package com.rescuemesh.net

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rescuemesh.RescueMeshApp
import java.util.concurrent.TimeUnit

/**
 * Periodic gateway sync (FIX5, requirements §"SAMAY-SAMAY PAR KHUD
 * JUDNE KI KOSHISH").
 *
 * Audit, 19 Nov 2026: this did NOT exist in FIX4. Sync ran on app
 * start and on a network callback — both only while the app process
 * was alive. Kill the app and queued records sat unsynced until the
 * user opened it again.
 *
 * Now WorkManager re-runs the same [GatewaySync.syncNow] every 15
 * minutes (WorkManager's minimum periodic interval) whenever ANY
 * network is available — Wi-Fi or mobile data, anywhere in the world.
 * The work is queue-safe by construction:
 * - GatewaySync collapses concurrent runs (its mutex) and only ever
 *   uploads records whose state / expiry select them; a run that
 *   finds nothing uploads nothing, and a failed run changes no
 *   record state, so a later run simply retries.
 * - The worker ALWAYS returns success: a failed sync is not a
 *   WorkManager failure to retry with backoff — the next periodic
 *   run is the retry. Battery: `requiresBatteryNotLow` is set, and
 *   no wake lock / foreground service is involved.
 * - Scheduling is KEEP: re-installs / restarts never stack duplicate
 *   workers. WorkManager itself re-schedules after reboot.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? RescueMeshApp
            ?: return Result.success()
        // GatewaySync is idempotent and collapse-safe; any exception is
        // swallowed here so a bad run never poisons the periodic chain.
        runCatching { app.gatewaySync.syncNow() }
        return Result.success()
    }
}

/** Scheduling constants + the pure due-check, kept separate so the policy is JVM-testable. */
object SyncWork {
    const val UNIQUE_WORK_NAME = "rescue-mesh-gateway-sync"

    /** WorkManager's minimum periodic interval — do not lower it. */
    const val REPEAT_INTERVAL_MINUTES = 15L

    /**
     * Pure due-check for the 15-minute cadence (used by tests; the
     * worker itself is driven by WorkManager's own scheduler).
     */
    fun isDue(lastRunMs: Long?, nowMs: Long): Boolean =
        lastRunMs == null || nowMs - lastRunMs >= REPEAT_INTERVAL_MINUTES * 60L * 1000L

    fun buildRequest() = PeriodicWorkRequestBuilder<SyncWorker>(
        REPEAT_INTERVAL_MINUTES, TimeUnit.MINUTES
    )
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
        )
        .build()

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            buildRequest()
        )
    }
}
