package com.aiglobal.jobfinder.data.jobs.provider

import androidx.compose.runtime.Immutable
import com.aiglobal.jobfinder.data.model.SalaryPeriod

/**
 * One posting exactly as a source reported it, before any matching or eligibility
 * work. Providers only translate a feed into this shape; they never score, guess or
 * enrich. Everything downstream is computed from these fields, so a field that the
 * source did not send stays null rather than being filled in.
 */
@Immutable
data class RawJob(
    val providerId: String,
    val providerName: String,
    val externalId: String,
    val title: String,
    val company: String,
    /** The location exactly as published, e.g. "Berlin", "France", "Worldwide". */
    val location: String,
    val descriptionHtml: String,
    val url: String,
    val tags: List<String> = emptyList(),
    val employmentType: String? = null,
    val postedAtEpochMillis: Long? = null,
    val isRemote: Boolean = false,
    val salaryMin: Int? = null,
    val salaryMax: Int? = null,
    val salaryCurrency: String? = null,
    val salaryPeriod: SalaryPeriod? = null,
)

/** Broad classification of a source, used for the "official source" trust signals. */
enum class ProviderKind { REMOTE_BOARD, GOVERNMENT, AGGREGATOR }

/**
 * A single job source. Implementations are stateless and network-bound; the
 * repository decides when to call them and how to combine the results.
 */
interface JobProvider {
    /** Stable id, used to key per-source sync status. */
    val id: String

    /** Display name shown on the job card and in source filters. */
    val name: String

    /** Public homepage, shown as the attribution link. */
    val homepage: String

    val kind: ProviderKind

    /**
     * Official API or developer-documentation page for this source. Shown in the
     * diagnostics screen so the user can read the source's own terms and limits.
     */
    val documentationUrl: String get() = homepage

    /** True when the source stays dormant until the user saves credentials. */
    val requiresCredentials: Boolean get() = false

    /**
     * True when the source supports server-side keyword search, so sending the
     * candidate's own title narrows the feed instead of being ignored.
     */
    val supportsServerSideSearch: Boolean get() = false

    /**
     * Fetches the current postings.
     *
     * @param keywords lower-cased search terms; a provider may ignore them when the
     * feed does not support server-side search, since the client filters anyway.
     * @param limit a soft cap on how many postings to request.
     */
    suspend fun fetch(keywords: List<String> = emptyList(), limit: Int = 60): List<RawJob>
}

/** Raised by a provider when the feed is unreachable or malformed. */
class JobProviderException(
    val providerId: String,
    message: String,
    cause: Throwable? = null,
    /**
     * Explicit reason, set when the provider itself can tell what went wrong (for
     * example "no key configured" or "HTTP 401"). When null, [ProviderErrorClassifier]
     * infers the category from [cause] and the message.
     */
    val category: ProviderErrorCategory? = null,
) : Exception(message, cause)

/**
 * Why a provider attempt did not return usable postings. Kept coarse and stable so the
 * diagnostics screen and the sync banner can describe the failure in plain words
 * instead of showing a stack trace.
 */
enum class ProviderErrorCategory(val label: String, val explanation: String) {
    NOT_CONFIGURED(
        "Not configured",
        "This source needs credentials before it can be searched. Add them in Profile → Job sources.",
    ),
    AUTHENTICATION(
        "Authentication failed",
        "The source rejected the saved credentials. Check that the key is correct and still active.",
    ),
    RATE_LIMITED(
        "Rate limited",
        "The source is temporarily refusing requests. Wait a little before trying again.",
    ),
    TIMEOUT(
        "Timed out",
        "The source did not answer in time. It may be slow or unreachable right now.",
    ),
    NETWORK(
        "Network error",
        "The source could not be reached. Check your internet connection and try again.",
    ),
    INVALID_RESPONSE(
        "Invalid response",
        "The source replied, but its response could not be understood. Nothing was changed.",
    ),
    HTTP_ERROR(
        "Request rejected",
        "The source answered with an error status instead of job data.",
    ),
    UNKNOWN(
        "Unknown failure",
        "The source failed for a reason the app could not classify.",
    ),
}
