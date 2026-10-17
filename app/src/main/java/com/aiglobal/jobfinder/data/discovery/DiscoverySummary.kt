package com.aiglobal.jobfinder.data.discovery

import androidx.compose.runtime.Immutable
import com.aiglobal.jobfinder.data.jobs.provider.ProviderErrorCategory
import com.aiglobal.jobfinder.data.model.ProviderDiagnostic
import com.aiglobal.jobfinder.data.model.ProviderDiagnostics

/** One source that failed on the last read, kept for the compact status line. */
@Immutable
data class ProviderFailure(
    val providerId: String,
    val name: String,
    val category: ProviderErrorCategory,
    val message: String? = null,
)

/**
 * The plain-language state of automatic discovery, assembled from the diagnostics the
 * repository already records plus the last incremental delta. This is what the settings
 * section shows, so the user can see that a search happened, when it last succeeded, how
 * much is new, and which sources are failing — without opening the full diagnostics table.
 */
@Immutable
data class DiscoverySummary(
    /** When the last search attempt started, or null when none has run. */
    val lastSearchAtEpochMillis: Long? = null,
    /** When a search last returned a usable response, or null when none has yet. */
    val lastSuccessAtEpochMillis: Long? = null,
    /** Genuinely new postings found by the most recent read. */
    val newCount: Int = 0,
    /** Already-known postings whose content changed in the most recent read. */
    val updatedCount: Int = 0,
    /** How many postings the seen ledger holds in total. */
    val knownCount: Int = 0,
    /** Sources that failed on the last read, so a partial failure is visible, not hidden. */
    val failures: List<ProviderFailure> = emptyList(),
    /** True while a read is in flight. */
    val isRefreshing: Boolean = false,
    /** True when every source failed, so the feed on screen is cached data only. */
    val showingCachedOnly: Boolean = false,
) {
    val discoveredCount: Int get() = newCount + updatedCount
    val hasFailures: Boolean get() = failures.isNotEmpty()
    val rateLimitedCount: Int get() = failures.count { it.category == ProviderErrorCategory.RATE_LIMITED }
    val authFailureCount: Int get() = failures.count { it.category == ProviderErrorCategory.AUTHENTICATION }

    companion object {
        /** Assembles the summary from the repository's diagnostics and the last delta. */
        fun from(diagnostics: ProviderDiagnostics, delta: DiscoveryDelta): DiscoverySummary =
            DiscoverySummary(
                lastSearchAtEpochMillis = diagnostics.lastAttemptAtEpochMillis,
                lastSuccessAtEpochMillis = diagnostics.lastSuccessAtEpochMillis,
                newCount = delta.newJobIds.size,
                updatedCount = delta.updatedJobIds.size,
                knownCount = delta.knownCount,
                failures = diagnostics.providers
                    .filter { it.status.isFailure }
                    .map { it.toFailure() },
                isRefreshing = diagnostics.isRefreshing,
                showingCachedOnly = diagnostics.showingCachedOnly,
            )

        private fun ProviderDiagnostic.toFailure(): ProviderFailure = ProviderFailure(
            providerId = providerId,
            name = name,
            category = errorCategory ?: ProviderErrorCategory.UNKNOWN,
            message = message,
        )
    }
}
