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
) : Exception(message, cause)
