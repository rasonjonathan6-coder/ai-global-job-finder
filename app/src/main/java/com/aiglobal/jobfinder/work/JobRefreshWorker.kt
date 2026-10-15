package com.aiglobal.jobfinder.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aiglobal.jobfinder.AiJobFinderApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Background refresh entry point. WorkManager constructs this with the application context;
 * it pulls the real repository and notification coordinator from [AiJobFinderApp.container]
 * so a scheduled run uses exactly the same feed, cache and scoring as the foreground app.
 *
 * Retry policy: a run that produced data — even partially — succeeds, because the repository
 * already kept the last good cache and deduplicated what arrived. A run that came back with
 * nothing usable (offline at the moment the work ran, or every source failed) returns
 * [Result.retry], which is what lets WorkManager's exponential backoff do its job.
 */
class JobRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val container = (applicationContext as? AiJobFinderApp)?.container
            ?: return@withContext Result.success()

        val runner = JobRefreshRunner(
            repository = container.jobRepository,
            notifications = container.jobNotificationsCoordinator,
            notificationsEnabled = container.preferencesRepository.notificationsEnabled,
            demoMode = container.demoMode,
        )

        runCatching { runner.run() }.getOrElse { error ->
            return@withContext if (error is java.io.IOException || error is java.util.concurrent.TimeoutException) {
                Result.retry()
            } else {
                Result.failure()
            }
        }

        val diagnostics = container.jobRepository.diagnostics.first()
        when {
            !diagnostics.networkAvailable -> Result.retry()
            diagnostics.showingCachedOnly -> Result.retry()
            else -> Result.success()
        }
    }
}
