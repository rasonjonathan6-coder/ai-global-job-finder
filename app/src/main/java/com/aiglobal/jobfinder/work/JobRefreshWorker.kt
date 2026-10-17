package com.aiglobal.jobfinder.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aiglobal.jobfinder.AiJobFinderApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Background refresh entry point. WorkManager constructs this with the application context;
 * it pulls the real repository and notification coordinator from [AiJobFinderApp.container]
 * so a scheduled run uses exactly the same feed, cache and scoring as the foreground app.
 *
 * Retry policy: delegated to [RefreshRetryPolicy], which retries only when a later attempt
 * could plausibly help — offline, or every source failed transiently. A run that produced
 * data, even partially, succeeds, because the repository already kept the last good cache
 * and deduplicated what arrived; a permanent failure (a missing key, rejected credentials)
 * is not retried, so the app does not spend battery repeating something that cannot change.
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
            alertMinMatchScore = container.preferencesRepository.refreshPreferences
                .map { it.alertMinMatchScore },
            demoMode = container.demoMode,
            pendingDrafts = container.applicationDraftCenter.awaitingApproval,
        )

        runCatching { runner.run() }.getOrElse { error ->
            return@withContext if (error is java.io.IOException || error is java.util.concurrent.TimeoutException) {
                Result.retry()
            } else {
                Result.failure()
            }
        }

        val diagnostics = container.jobRepository.diagnostics.first()
        if (RefreshRetryPolicy.shouldRetry(diagnostics)) Result.retry() else Result.success()
    }
}
