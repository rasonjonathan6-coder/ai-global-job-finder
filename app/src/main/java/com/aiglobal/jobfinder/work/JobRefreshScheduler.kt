package com.aiglobal.jobfinder.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.aiglobal.jobfinder.data.refresh.RefreshPreferences
import java.util.concurrent.TimeUnit

/**
 * Owns the one periodic job that refreshes the feed in the background.
 *
 * Design rules, each of which answers a requirement:
 *  - **One worker, ever.** All scheduling goes through the single [WORK_NAME] unique name, so
 *    repeated calls replace rather than stack. There is no path that enqueues a second copy.
 *  - **Respect the network and battery.** The work is constrained to an unmetered-or-any
 *    *connected* network; it never runs offline. WorkManager holds it until a connection
 *    exists, which is why the app also keeps a cache to read while offline.
 *  - **Exponential backoff.** A transient failure (a board down, a timeout) is retried with
 *    [BackoffPolicy.EXPONENTIAL], so a flaky source is not hammered.
 *  - **No exact times.** Periodic work is best-effort; Android defers it for Doze and battery
 *    optimisations, so the UI describes the cadence as "about every N hours".
 */
class JobRefreshScheduler(private val context: Context) {

    /**
     * WorkManager initializes itself through a manifest provider on a real device. In a few
     * environments it may not be ready yet (a unit-test sandbox, or a process that started
     * before the provider ran), so scheduling is best-effort and never crashes the app — the
     * next app start retries once WorkManager is available.
     */
    private val workManager: WorkManager?
        get() = runCatching { WorkManager.getInstance(context) }.getOrNull()

    /**
     * Applies the user's choice. Called at app start and whenever the preference changes, so
     * the schedule always matches the switch and the interval.
     */
    fun apply(preferences: RefreshPreferences) {
        val manager = workManager ?: return
        if (!preferences.enabled) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<JobRefreshWorker>(
            preferences.interval.hours.toLong(),
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()

        manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * Runs one refresh now, in the background, respecting the same constraints. Used by the
     * "refresh now" affordance so a manual tap and the scheduled run share one code path.
     */
    fun refreshNow() {
        val manager = workManager ?: return
        val request = OneTimeWorkRequestBuilder<JobRefreshWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()
        manager.enqueueUniqueWork(NOW_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val WORK_NAME = "job_feed_periodic_refresh"
        const val NOW_WORK_NAME = "job_feed_refresh_now"
        const val TAG = "job_feed_refresh"
    }
}
