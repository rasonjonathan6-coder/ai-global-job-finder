package com.aiglobal.jobfinder.work

import com.aiglobal.jobfinder.data.jobs.provider.ProviderErrorCategory
import com.aiglobal.jobfinder.data.model.ProviderDiagnostics

/**
 * Decides whether a finished background refresh should ask WorkManager to retry.
 *
 * The rule is deliberately conservative, because a retry re-queries every source:
 *  - A run that reached the network and got anything usable succeeds, even partially —
 *    the cache already holds the last good feed, so there is nothing to gain by retrying.
 *  - A run with no connection retries, so WorkManager's network constraint can hold it
 *    until a connection exists.
 *  - A run where every source failed retries *only* when the failures are transient
 *    (network, timeout, a rejected request). A missing key, rejected credentials or an
 *    unparseable body will not fix themselves, so retrying those just burns battery.
 *  - A rate-limited source is never retried: hammering it is exactly what the source is
 *    asking the app not to do.
 *  - Retrying stops after [MAX_ATTEMPTS] runs. WorkManager reschedules a retry *without*
 *    resetting its attempt counter, so an unbounded retry loop would keep re-querying every
 *    source forever while a host stayed unreachable. The cap is what turns that into a
 *    finite, honest attempt: the next scheduled period (which *does* reset the counter) is
 *    the natural place to try again.
 */
object RefreshRetryPolicy {

    /**
     * The most times one scheduled run may execute (the first try plus its retries). A
     * periodic run that reaches this count gives up and lets the next period try afresh.
     */
    const val MAX_ATTEMPTS = 3

    /** Failures that a later attempt could plausibly clear on their own. */
    private val TRANSIENT = setOf(
        ProviderErrorCategory.NETWORK,
        ProviderErrorCategory.TIMEOUT,
        ProviderErrorCategory.HTTP_ERROR,
        ProviderErrorCategory.UNKNOWN,
    )

    fun isTransient(category: ProviderErrorCategory): Boolean = category in TRANSIENT

    fun shouldRetry(diagnostics: ProviderDiagnostics): Boolean {
        // No connection: not a failure, just "later". WorkManager will wait for the network.
        if (!diagnostics.networkAvailable) return true
        // Anything usable means the run did its job; a partial feed is still a feed.
        if (diagnostics.hasAnySuccess) return false

        val failures = diagnostics.providers.filter { it.status.isFailure }
        if (failures.isEmpty()) return false

        val categories = failures.mapNotNull { it.errorCategory }
        // Never aggravate a source that is already refusing requests.
        if (categories.any { it == ProviderErrorCategory.RATE_LIMITED }) return false
        // Retry only when at least one failure is the kind a later attempt could clear.
        return categories.any(::isTransient)
    }

    /**
     * Runs [attempt] under the same finite rule a scheduled run follows, for paths that are
     * not themselves driven by WorkManager — a manual refresh, where no external scheduler is
     * counting attempts. It stops at [maxAttempts] or as soon as [shouldRetry] says a further
     * try would not help, and returns how many attempts were made.
     */
    suspend fun runWithBoundedRetry(
        maxAttempts: Int = MAX_ATTEMPTS,
        delayMillis: (attempt: Int) -> Long = { 0L },
        shouldRetry: suspend () -> Boolean,
        attempt: suspend () -> Unit,
    ): Int {
        var made = 0
        while (made < maxAttempts) {
            attempt()
            made++
            if (made >= maxAttempts || !shouldRetry()) break
            val wait = delayMillis(made)
            if (wait > 0) kotlinx.coroutines.delay(wait)
        }
        return made
    }
}
