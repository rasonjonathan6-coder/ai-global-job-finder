package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable
import com.aiglobal.jobfinder.data.jobs.provider.ProviderErrorCategory

/**
 * What the most recent attempt to read one source actually did.
 *
 * This is deliberately finer-grained than [SourceState]: "0 jobs" and "source down" and
 * "you have not entered your key yet" are three different situations and the user needs
 * to be able to tell them apart. Every value here is recorded from a real attempt — a
 * provider that has never been tried reports [IDLE], never a success.
 */
enum class ProviderStatus(val label: String) {
    /** Never attempted in this session, or no attempt recorded yet. */
    IDLE("Not tried yet"),
    /** The source needs credentials and none are saved. Not a failure. */
    NOT_CONFIGURED("Not configured"),
    /** The user has switched the source off. */
    DISABLED("Disabled"),
    /** No usable network, so the request was not sent. */
    WAITING_FOR_NETWORK("Waiting for network"),
    /** A request is in flight right now. */
    IN_PROGRESS("Checking…"),
    /** Succeeded and returned at least one valid posting. */
    SUCCESS_WITH_RESULTS("Success"),
    /** Succeeded and the source genuinely has nothing for this search. */
    SUCCESS_EMPTY("No matching jobs"),
    /** Succeeded with results, but some records were rejected as malformed. */
    PARTIAL_SUCCESS("Partial success"),
    RATE_LIMITED("Rate limited"),
    AUTHENTICATION_FAILED("Authentication failed"),
    NETWORK_ERROR("Network error"),
    TIMEOUT("Timed out"),
    INVALID_RESPONSE("Invalid response"),
    HTTP_ERROR("Request rejected"),
    UNKNOWN_FAILURE("Unknown failure");

    /** True when the attempt produced a usable response, with or without results. */
    val isSuccess: Boolean
        get() = this == SUCCESS_WITH_RESULTS || this == SUCCESS_EMPTY || this == PARTIAL_SUCCESS

    /** True when the attempt failed outright. */
    val isFailure: Boolean
        get() = this == RATE_LIMITED || this == AUTHENTICATION_FAILED || this == NETWORK_ERROR ||
            this == TIMEOUT || this == INVALID_RESPONSE || this == HTTP_ERROR ||
            this == UNKNOWN_FAILURE

    /** True when no request was made, so a red "failed" state would be misleading. */
    val isDormant: Boolean
        get() = this == IDLE || this == NOT_CONFIGURED || this == DISABLED || this == WAITING_FOR_NETWORK

    companion object {
        fun forErrorCategory(category: ProviderErrorCategory): ProviderStatus = when (category) {
            ProviderErrorCategory.NOT_CONFIGURED -> NOT_CONFIGURED
            ProviderErrorCategory.AUTHENTICATION -> AUTHENTICATION_FAILED
            ProviderErrorCategory.RATE_LIMITED -> RATE_LIMITED
            ProviderErrorCategory.TIMEOUT -> TIMEOUT
            ProviderErrorCategory.NETWORK -> NETWORK_ERROR
            ProviderErrorCategory.INVALID_RESPONSE -> INVALID_RESPONSE
            ProviderErrorCategory.HTTP_ERROR -> HTTP_ERROR
            ProviderErrorCategory.UNKNOWN -> UNKNOWN_FAILURE
        }
    }
}

/**
 * One provider's recorded diagnostic. Counts and timestamps are only ever set from a real
 * attempt; a field the app did not observe stays null or zero.
 */
@Immutable
data class ProviderDiagnostic(
    val providerId: String,
    val name: String,
    val homepage: String,
    val documentationUrl: String,
    val status: ProviderStatus = ProviderStatus.IDLE,
    /** Whether the user has this source switched on. */
    val enabled: Boolean = true,
    /**
     * True when this source's API narrows the feed by keyword. Recorded per attempt so the
     * coverage report can tell a targeted search apart from a broad sample.
     */
    val serverSideSearch: Boolean = false,
    /** True when the source needs credentials at all. */
    val requiresCredentials: Boolean = false,
    /**
     * Whether this source exposes an application route the app itself can drive. False
     * everywhere today, so the diagnostics screen can say so per source rather than leave
     * the user to assume the app can submit.
     */
    val realSubmissionSupported: Boolean = false,
    /** Whether usable credentials are saved. Never the credential value itself. */
    val credentialsConfigured: Boolean = true,
    /** Valid postings this attempt returned. */
    val validJobCount: Int = 0,
    /** Records the validation layer rejected as malformed on the latest attempt. */
    val malformedRejectedCount: Int = 0,
    /** Records dropped as duplicates of another source's posting on the latest attempt. */
    val duplicateRemovedCount: Int = 0,
    /** Wall-clock time of the last attempt, or null when never attempted. */
    val lastAttemptAtEpochMillis: Long? = null,
    /** Wall-clock time of the last attempt that returned a usable response. */
    val lastSuccessAtEpochMillis: Long? = null,
    /** Valid postings returned by the last successful attempt. */
    val lastSuccessJobCount: Int = 0,
    /** How long the last attempt took, in milliseconds. */
    val lastDurationMillis: Long? = null,
    /** Classified reason when the attempt failed. */
    val errorCategory: ProviderErrorCategory? = null,
    /** Human-readable explanation. Never contains a credential. */
    val message: String? = null,
) {
    /** True when credentials are missing, so the source is dormant rather than broken. */
    val needsSetup: Boolean get() = requiresCredentials && !credentialsConfigured
}

/**
 * The user's source configuration, as the repository sees it. Kept separate from the
 * credentials themselves so nothing sensitive is ever held in the feed layer.
 */
@Immutable
data class ProviderSettings(
    /** Sources the user has switched off. */
    val disabledIds: Set<String> = emptySet(),
    /** Sources whose credentials are saved. Key-free sources never need an entry here. */
    val configuredCredentialIds: Set<String> = emptySet(),
)

/**
 * The whole feed's diagnostics: every provider's state plus the aggregate timing. This is
 * what the Profile → Job sources diagnostics section and the live-validation screen read.
 */
@Immutable
data class ProviderDiagnostics(
    val providers: List<ProviderDiagnostic> = emptyList(),
    val isRefreshing: Boolean = false,
    /** Whether the device reported a usable network at the start of the last attempt. */
    val networkAvailable: Boolean = true,
    /** Time of the most recent refresh attempt across all providers. */
    val lastAttemptAtEpochMillis: Long? = null,
    /** Time of the most recent attempt that returned anything usable. */
    val lastSuccessAtEpochMillis: Long? = null,
    /** True when every source failed, so the feed on screen is cached data only. */
    val showingCachedOnly: Boolean = false,
) {
    val successCount: Int get() = providers.count { it.status.isSuccess }
    val failureCount: Int get() = providers.count { it.status.isFailure }
    val configuredCount: Int get() = providers.count { !it.needsSetup && it.enabled }
    val totalValidJobs: Int get() = providers.sumOf { it.validJobCount }
    val totalRejected: Int get() = providers.sumOf { it.malformedRejectedCount }
    val totalDuplicatesRemoved: Int get() = providers.sumOf { it.duplicateRemovedCount }

    /** True when at least one source produced a usable response. */
    val hasAnySuccess: Boolean get() = providers.any { it.status.isSuccess }
}
