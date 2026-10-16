package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.model.ProviderStatus
import com.aiglobal.jobfinder.data.model.SourceState
import com.aiglobal.jobfinder.data.repository.LiveJobRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Waits until the repository's startup refresh has actually finished, so a caller that then
 * reads [LiveJobRepository.diagnostics] and [LiveJobRepository.syncState] is reading the
 * outcome of that attempt rather than the blank state the object was constructed with.
 *
 * The completion signal is "an attempt has been recorded AND both flows agree it is idle".
 * The recorded-attempt check ([lastAttemptAtEpochMillis] is non-null) is the part that makes
 * this deterministic: the repository starts a refresh from an init coroutine, and the state
 * flows are seeded as "not refreshing" with no source in progress, so a predicate that only
 * looks for idleness also matches the seed *before* the init coroutine has run. Sampling then
 * returns the seed — the Stage 22 flake — instead of waiting. Requiring a recorded attempt
 * excludes the seed, which no attempt has stamped.
 */
internal suspend fun LiveJobRepository.awaitSettled() {
    withTimeout(5_000) {
        diagnostics.first { state ->
            state.lastAttemptAtEpochMillis != null &&
                !state.isRefreshing &&
                state.providers.none { it.status == ProviderStatus.IN_PROGRESS }
        }
        syncState.first { state ->
            !state.isRefreshing && state.sources.none { it.state == SourceState.LOADING }
        }
    }
}

/**
 * True once the diagnostics describe a finished attempt rather than the untouched seed.
 * Exposed so the Stage 22 regression test can pin the exact interleaving that caused the
 * flake without racing the repository's init coroutine.
 */
internal fun isSettledDiagnostics(
    lastAttemptAtEpochMillis: Long?,
    isRefreshing: Boolean,
    anyProviderInProgress: Boolean,
): Boolean = lastAttemptAtEpochMillis != null && !isRefreshing && !anyProviderInProgress
