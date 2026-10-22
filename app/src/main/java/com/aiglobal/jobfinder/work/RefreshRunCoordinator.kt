package com.aiglobal.jobfinder.work

import com.aiglobal.jobfinder.data.discovery.DiscoveryDelta
import com.aiglobal.jobfinder.data.discovery.RefreshHistoryStore
import com.aiglobal.jobfinder.data.discovery.RefreshOutcomeClassifier
import com.aiglobal.jobfinder.data.discovery.RefreshProviderResult
import com.aiglobal.jobfinder.data.discovery.RefreshRunRecord
import com.aiglobal.jobfinder.data.discovery.RefreshTrigger
import com.aiglobal.jobfinder.data.jobs.provider.runCatchingCancellable
import com.aiglobal.jobfinder.data.model.ProviderDiagnostics
import com.aiglobal.jobfinder.data.repository.JobRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** The result of asking the coordinator to refresh, for a caller that wants to know. */
sealed interface RefreshRequestResult {
    /** The refresh ran (possibly with bounded retries) and was recorded. */
    data class Ran(val record: RefreshRunRecord) : RefreshRequestResult

    /** Another refresh was already in flight, so this request was not started. */
    data object AlreadyRunning : RefreshRequestResult
}

/**
 * The single entry point for a refresh, whether a person tapped "Refresh now" or the periodic
 * worker woke up. Everything that decides what a run *is* lives here, so the manual and
 * scheduled paths cannot drift:
 *
 *  - **One at a time.** A [Mutex.tryLock] means a second request while one is running is
 *    refused rather than queued, so two callers can never duplicate the same network work.
 *  - **Honest about whether it ran.** The repository refuses a re-entrant read silently, so
 *    the coordinator checks the repository's attempt timestamp before and after. If it did not
 *    advance, the run never happened and is recorded as such — never inferred from a request.
 *  - **Bounded.** A manual run retries under [RefreshRetryPolicy] exactly like a scheduled one.
 *  - **Recorded.** Every finished run is written to [RefreshHistoryStore], with the counts only
 *    when the read actually measured them.
 */
class RefreshRunCoordinator(
    private val repository: JobRepository,
    private val historyStore: RefreshHistoryStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Delay before a retry, kept injectable so tests do not actually wait. */
    private val retryDelayMillis: (attempt: Int) -> Long = { attempt -> attempt * 2_000L },
) {

    private val lock = Mutex()

    private val _running = MutableStateFlow(false)
    /** True while a refresh is in flight, so a button can show progress and refuse a second tap. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * Runs one refresh under [trigger], single-flight, and records the outcome.
     *
     * @param retries whether a transient failure may be retried (manual and scheduled both do)
     */
    suspend fun refresh(
        trigger: RefreshTrigger,
        retries: Boolean = true,
    ): RefreshRequestResult {
        if (!lock.tryLock()) return RefreshRequestResult.AlreadyRunning
        _running.value = true
        val startedAt = clock()
        try {
            return withContext(Dispatchers.IO) {
                val before = attemptStamp()
                val work: suspend () -> Unit = {
                    runCatchingCancellable { repository.runLiveValidation() }
                }
                if (retries) {
                    RefreshRetryPolicy.runWithBoundedRetry(
                        delayMillis = retryDelayMillis,
                        shouldRetry = {
                            // Read fresh each time: a later attempt may have cleared the failure.
                            runCatchingCancellable { RefreshRetryPolicy.shouldRetry(repository.diagnostics.first()) }
                                .getOrDefault(false)
                        },
                        attempt = work,
                    )
                } else {
                    work()
                }

                val after = attemptStamp()
                val attempted = after != null && after != before
                val diagnostics = runCatchingCancellable { repository.diagnostics.first() }
                    .getOrDefault(ProviderDiagnostics())
                val delta = runCatchingCancellable { repository.discovery.first() }.getOrDefault(DiscoveryDelta())

                val record = refreshRunRecord(
                    trigger = trigger,
                    startedAtEpochMillis = startedAt,
                    endedAtEpochMillis = clock(),
                    attempted = attempted,
                    diagnostics = diagnostics,
                    delta = delta,
                )
                historyStore.record(record)
                RefreshRequestResult.Ran(record)
            }
        } finally {
            _running.value = false
            lock.unlock()
        }
    }

    /** The repository's last-attempt timestamp, or null when it has none yet. */
    private suspend fun attemptStamp(): Long? =
        runCatchingCancellable { repository.diagnostics.first().lastAttemptAtEpochMillis }.getOrNull()
}

/**
 * Builds the honest record for a finished run from what the repository actually recorded.
 * Shared by the coordinator (manual and scheduled-from-app runs) and the worker (scheduled
 * runs), so both describe a run the same way and neither can drift into optimism.
 */
internal fun refreshRunRecord(
    trigger: RefreshTrigger,
    startedAtEpochMillis: Long,
    endedAtEpochMillis: Long,
    attempted: Boolean,
    diagnostics: ProviderDiagnostics,
    delta: DiscoveryDelta,
): RefreshRunRecord {
    val classification = RefreshOutcomeClassifier.classify(attempted, diagnostics, delta)
    val providers = diagnostics.providers
        .filter { !it.status.isDormant || it.enabled }
        .map { RefreshProviderResult(it.providerId, it.name, it.status) }
    return RefreshRunRecord(
        id = startedAtEpochMillis,
        trigger = trigger,
        startedAtEpochMillis = startedAtEpochMillis,
        endedAtEpochMillis = endedAtEpochMillis,
        outcome = classification.outcome,
        reason = classification.reason,
        providers = providers,
        newCount = classification.newCount,
        updatedCount = classification.updatedCount,
        duplicateCount = classification.duplicateCount,
    )
}

