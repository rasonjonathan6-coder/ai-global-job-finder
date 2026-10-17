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
 */
object RefreshRetryPolicy {

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
}
