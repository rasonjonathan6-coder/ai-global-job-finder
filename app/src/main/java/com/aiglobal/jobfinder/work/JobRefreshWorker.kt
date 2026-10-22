package com.aiglobal.jobfinder.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aiglobal.jobfinder.AiJobFinderApp
import com.aiglobal.jobfinder.AppContainer
import com.aiglobal.jobfinder.data.jobs.provider.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Background refresh entry point. WorkManager constructs this with the application context;
 * it pulls the real repository and notification coordinator from [AiJobFinderApp.container]
 * so a scheduled run uses exactly the same feed, cache and scoring as the foreground app.
 *
 * Retry policy: delegated to [RefreshRetryPolicy] and [RefreshRunClassifier], which retry only
 * when a later attempt could plausibly help — offline, or every source failed transiently —
 * and only up to [RefreshRetryPolicy.MAX_ATTEMPTS] times. A run that produced data, even
 * partially, succeeds, because the repository already kept the last good cache and
 * deduplicated what arrived; a permanent failure (a missing key, rejected credentials) is not
 * retried. When a run does exhaust its retries, the outcome is written to
 * [RefreshRunStateStore] so the discovery card can say so honestly rather than staying silent.
 */
class JobRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val container = (applicationContext as? AiJobFinderApp)?.container
            ?: return@withContext Result.success()
        val store = container.refreshRunStateStore
        val startedAt = System.currentTimeMillis()
        val before = attemptStamp(container)

        val runner = JobRefreshRunner(
            repository = container.jobRepository,
            notifications = container.jobNotificationsCoordinator,
            notificationsEnabled = container.preferencesRepository.notificationsEnabled,
            alertMinMatchScore = container.preferencesRepository.refreshPreferences
                .map { it.alertMinMatchScore },
            demoMode = container.demoMode,
            pendingDrafts = container.applicationDraftCenter.awaitingApproval,
        )

        runCatchingCancellable { runner.run() }.getOrElse { error ->
            val transient = error is java.io.IOException || error is java.util.concurrent.TimeoutException
            return@withContext finish(
                store = store,
                verdict = RefreshRunClassifier.forThrownError(runAttemptCount, transient),
                now = System.currentTimeMillis(),
            )
        }

        val now = System.currentTimeMillis()
        // The repository silently refuses a re-entrant read, so a run that overlapped a manual
        // refresh did not actually happen. The attempt timestamp is the only honest witness.
        val after = attemptStamp(container)
        recordHistory(
            container = container,
            startedAt = startedAt,
            now = now,
            attempted = after != null && after != before,
        )
        val diagnostics = container.jobRepository.diagnostics.first()
        finish(
            store = store,
            verdict = RefreshRunClassifier.classify(diagnostics, runAttemptCount),
            now = now,
        )
    }

    /** The repository's last-attempt timestamp, or null when it has none yet. */
    private suspend fun attemptStamp(container: AppContainer): Long? =
        runCatchingCancellable { container.jobRepository.diagnostics.first().lastAttemptAtEpochMillis }.getOrNull()

    /**
     * Records one finished scheduled run in the shared history, so the manual and automatic
     * paths produce the same kind of entry. Called only after the runner returned, so a thrown
     * error — which leaves nothing honest to record — never reaches here.
     */
    private suspend fun recordHistory(
        container: AppContainer,
        startedAt: Long,
        now: Long,
        attempted: Boolean,
    ) {
        val diagnostics = runCatchingCancellable { container.jobRepository.diagnostics.first() }.getOrNull()
            ?: return
        val delta = runCatchingCancellable { container.jobRepository.discovery.first() }
            .getOrDefault(com.aiglobal.jobfinder.data.discovery.DiscoveryDelta())
        runCatchingCancellable {
            container.refreshHistoryStore.record(
                refreshRunRecord(
                    trigger = com.aiglobal.jobfinder.data.discovery.RefreshTrigger.SCHEDULED,
                    startedAtEpochMillis = startedAt,
                    endedAtEpochMillis = now,
                    attempted = attempted,
                    diagnostics = diagnostics,
                    delta = delta,
                ),
            )
        }
    }

    /** Records the outcome (when there is one) and maps the decision to a WorkManager result. */
    private suspend fun finish(
        store: RefreshRunStateStore,
        verdict: RefreshVerdict,
        now: Long,
    ): Result {
        verdict.outcome?.let { store.record(it, now) }
        return when (verdict.decision) {
            RefreshDecision.RETRY -> Result.retry()
            RefreshDecision.SUCCEED -> Result.success()
            // GIVE_UP: stop retrying this attempt. For periodic work WorkManager re-enqueues the
            // next period with a fresh counter, so the search is not abandoned — just not
            // hammered. Result.failure() is the only result that ends the retry loop.
            RefreshDecision.GIVE_UP -> Result.failure()
        }
    }
}
