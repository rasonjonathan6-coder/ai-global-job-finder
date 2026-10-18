package com.aiglobal.jobfinder.data.discovery

import androidx.compose.runtime.Immutable

/**
 * The honest, observable outcome of a background refresh.
 *
 * [com.aiglobal.jobfinder.work.RefreshRetryPolicy] answers "retry or not?" for one run; this
 * answers the question the user actually cares about — "did the automatic search work, and if
 * it gave up, why?" A scheduled run can end in a way the feed's own diagnostics cannot express:
 * it can exhaust its retries on an unreachable host, or fail before it ever read a source.
 * Recording that separately is what lets the discovery card say so instead of staying silent.
 */
@Immutable
data class RefreshRunState(
    /** When a background run last finished, or null when none has. */
    val lastRunAtEpochMillis: Long? = null,
    /** When a background run last succeeded. */
    val lastSuccessAtEpochMillis: Long? = null,
    /**
     * When a background run last gave up after exhausting its retries, or null. Cleared by
     * the next success, so it reflects the most recent outcome rather than an old failure.
     */
    val lastGiveUpAtEpochMillis: Long? = null,
    /**
     * True when the most recent run gave up after its retries were exhausted. The next run
     * clears it, so it never lingers as a stale warning.
     */
    val retryExhausted: Boolean = false,
    /** A short, plain-language reason for the last outcome, or null when it succeeded. */
    val reason: String? = null,
) {
    companion object {
        /**
         * Folds one finished run into the running state. A success clears the give-up flag;
         * a give-up sets it and records the reason, keeping the previous success timestamp.
         */
        fun after(
            previous: RefreshRunState,
            outcome: RefreshRunOutcome,
            atEpochMillis: Long,
        ): RefreshRunState = when (outcome) {
            is RefreshRunOutcome.Succeeded -> previous.copy(
                lastRunAtEpochMillis = atEpochMillis,
                lastSuccessAtEpochMillis = atEpochMillis,
                retryExhausted = false,
                reason = null,
            )
            is RefreshRunOutcome.GaveUp -> previous.copy(
                lastRunAtEpochMillis = atEpochMillis,
                lastGiveUpAtEpochMillis = atEpochMillis,
                retryExhausted = true,
                reason = outcome.reason,
            )
        }
    }
}

/** The outcome of one finished background run, before it is folded into [RefreshRunState]. */
sealed interface RefreshRunOutcome {
    /** The run read the feeds; the schedule continues. */
    object Succeeded : RefreshRunOutcome

    /**
     * The run could not produce a usable read and its retries are exhausted. The schedule
     * continues on its next period; this only records that *this* attempt gave up.
     */
    data class GaveUp(val reason: String) : RefreshRunOutcome
}
