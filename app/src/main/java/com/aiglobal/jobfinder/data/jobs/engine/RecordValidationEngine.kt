package com.aiglobal.jobfinder.data.jobs.engine

import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import androidx.compose.runtime.Immutable

/**
 * Why a retrieved record was not allowed into the feed. Each value is a decision the app
 * can defend: the record was missing something the UI or an engine needs, or it was a
 * repeat of a posting already kept. "The data was thin" is never a reason on its own —
 * only a field the app genuinely cannot render or score counts.
 */
enum class RecordRejectionReason(val label: String, val explanation: String) {
    MISSING_TITLE(
        "No job title",
        "The record had no title, so it could not be shown or matched.",
    ),
    MISSING_EMPLOYER(
        "No employer name",
        "The record had neither a company nor a usable title, so it could not be presented.",
    ),
    MISSING_LOCATION(
        "No location",
        "The record gave no location, so Madagascar eligibility could not be judged.",
    ),
    MISSING_DESCRIPTION(
        "No description",
        "The record had no description text, so match and benefit signals could not be read.",
    ),
    MISSING_URL(
        "No application link",
        "The record had no posting URL, so there would be nothing to apply through.",
    ),
    INVALID_URL(
        "Unusable application link",
        "The posting link was not a valid http(s) address, so it would lead nowhere.",
    ),
    STALE_POSTING(
        "Too old",
        "The posting is older than the search window, so it is most likely filled.",
    ),
    IMPLAUSIBLE_SALARY(
        "Implausible salary",
        "The stated salary was outside any believable range, so it was not shown.",
    ),
    INCOMPLETE_SALARY(
        "Incomplete salary",
        "Only one end of the salary range was published; the pair was dropped to avoid guessing.",
    ),
    DUPLICATE(
        "Duplicate posting",
        "The same role from the same employer was already kept from another source.",
    ),
    CLOSED_TO_APPLICANTS(
        "No longer accepting applicants",
        "The source itself reports the posting is closed to new applicants, so applying would lead nowhere.",
    ),
    EXPIRED(
        "Application deadline passed",
        "The posting stated an application deadline that has already passed, so applying would be too late.",
    ),
}

/** The outcome of validating one record. */
@Immutable
data class RecordValidation(
    val accepted: Boolean,
    val reasons: List<RecordRejectionReason>,
    /** Stable id, identical to the id the UI uses, so counters and cards agree. */
    val recordId: String,
    /** Identity used for deduplication: provider-agnostic, so cross-source repeats collapse. */
    val dedupKey: String,
)

/** Per-provider outcome of a validation + dedup pass. */
@Immutable
data class ValidationSummary(
    val providerId: String,
    val receivedCount: Int = 0,
    val acceptedCount: Int = 0,
    val duplicateCount: Int = 0,
    val rejectedByReason: Map<RecordRejectionReason, Int> = emptyMap(),
) {
    val rejectedCount: Int get() = rejectedByReason.values.sum()
    val allRejected: Boolean get() = receivedCount > 0 && acceptedCount == 0
    val hasRejections: Boolean get() = rejectedCount > 0 || duplicateCount > 0
}

/** The whole pass: what survived, what was dropped, and why, grouped by source. */
@Immutable
data class DeduplicationResult(
    val kept: List<RawJob>,
    val summaries: List<ValidationSummary>,
    val totalReceived: Int,
    val totalKept: Int,
) {
    val totalDuplicates: Int get() = summaries.sumOf { it.duplicateCount }
    val totalRejected: Int get() = summaries.sumOf { it.rejectedCount }
    fun summaryFor(providerId: String): ValidationSummary? = summaries.firstOrNull { it.providerId == providerId }
}

/**
 * Decides which retrieved records are real enough to show and collapses repeats.
 *
 * Two rules keep this honest:
 *  - A record is rejected only for a field the app cannot work without. Anything merely
 *    absent is left absent; it is never replaced with an invented value.
 *  - Two postings are duplicates only when the employer and the title agree after
 *    normalisation. Salary, location and source are deliberately excluded, because the
 *    same role legitimately appears at different salaries and in different markets, and
 *    treating those as duplicates would silently drop real openings.
 *
 * Ordering is stable: input order is preserved for the records that are kept, so the
 * feed does not reshuffle between refreshes.
 */
class RecordValidationEngine(
    /** Postings older than this are treated as filled. Null disables the age check. */
    private val maxPostedAgeDays: Int? = 120,
    /** Below this a stated annual salary is treated as a typo rather than a real figure. */
    private val minPlausibleAnnual: Int = 1_000,
    /** Above this a stated annual salary is treated as a typo rather than a real figure. */
    private val maxPlausibleAnnual: Int = 2_000_000,
) {

    fun validate(raw: RawJob, now: Long): RecordValidation {
        val reasons = mutableListOf<RecordRejectionReason>()

        val title = raw.title.trim()
        val company = raw.company.trim()

        if (title.isEmpty()) reasons += RecordRejectionReason.MISSING_TITLE
        // A record needs some employer identity. A title is the minimum the card can
        // carry; a blank company with a title is kept and labelled "Unknown employer".
        if (company.isEmpty() && title.isEmpty()) reasons += RecordRejectionReason.MISSING_EMPLOYER
        if (raw.location.trim().isEmpty()) reasons += RecordRejectionReason.MISSING_LOCATION
        if (raw.descriptionHtml.trim().isEmpty()) reasons += RecordRejectionReason.MISSING_DESCRIPTION

        val url = raw.url.trim()
        if (url.isEmpty()) {
            reasons += RecordRejectionReason.MISSING_URL
        } else if (!isHttpUrl(url)) {
            reasons += RecordRejectionReason.INVALID_URL
        }

        reasons += salaryIssues(raw)
        reasons += ageIssues(raw, now)

        // A deadline the source stated that has already passed is positive evidence the
        // posting is closed, so it is dropped with its own reason. A posting with no stated
        // deadline is never rejected here: "unknown" is not "expired".
        val expires = raw.expiresAtEpochMillis
        if (expires != null && expires <= now) reasons += RecordRejectionReason.EXPIRED

        // A posting the source itself marks as closed is not shown as if it were open. This
        // is a stated fact, not an age guess, so it is surfaced with its own reason.
        if (raw.closedToNewApplicants) reasons += RecordRejectionReason.CLOSED_TO_APPLICANTS

        return RecordValidation(
            accepted = reasons.isEmpty(),
            reasons = reasons,
            recordId = recordId(raw),
            dedupKey = dedupKey(raw),
        )
    }

    /** Validates every record, then collapses cross-source duplicates, counting each reason. */
    fun deduplicate(records: List<RawJob>, now: Long): DeduplicationResult {
        val kept = mutableListOf<RawJob>()
        val seen = mutableSetOf<String>()
        val received = mutableMapOf<String, Int>()
        val accepted = mutableMapOf<String, Int>()
        val duplicates = mutableMapOf<String, Int>()
        val reasons = mutableMapOf<String, MutableMap<RecordRejectionReason, Int>>()

        for (raw in records) {
            received[raw.providerId] = (received[raw.providerId] ?: 0) + 1
            val validation = validate(raw, now)
            if (!validation.accepted) {
                val bucket = reasons.getOrPut(raw.providerId) { mutableMapOf() }
                for (reason in validation.reasons) {
                    bucket[reason] = (bucket[reason] ?: 0) + 1
                }
                continue
            }
            if (!seen.add(validation.dedupKey)) {
                duplicates[raw.providerId] = (duplicates[raw.providerId] ?: 0) + 1
                val bucket = reasons.getOrPut(raw.providerId) { mutableMapOf() }
                bucket[RecordRejectionReason.DUPLICATE] = (bucket[RecordRejectionReason.DUPLICATE] ?: 0) + 1
                continue
            }
            accepted[raw.providerId] = (accepted[raw.providerId] ?: 0) + 1
            kept += raw
        }

        val providerIds = (received.keys + reasons.keys).toSortedSet()
        val summaries = providerIds.map { id ->
            ValidationSummary(
                providerId = id,
                receivedCount = received[id] ?: 0,
                acceptedCount = accepted[id] ?: 0,
                duplicateCount = duplicates[id] ?: 0,
                rejectedByReason = reasons[id]?.toMap() ?: emptyMap(),
            )
        }

        return DeduplicationResult(
            kept = kept,
            summaries = summaries,
            totalReceived = records.size,
            totalKept = kept.size,
        )
    }

    private fun salaryIssues(raw: RawJob): List<RecordRejectionReason> {
        val min = raw.salaryMin
        val max = raw.salaryMax
        val issues = mutableListOf<RecordRejectionReason>()

        if (min != null && max != null && min > max) issues += RecordRejectionReason.IMPLAUSIBLE_SALARY
        val low = min ?: max
        val high = max ?: min
        if (low != null && low < 0) issues += RecordRejectionReason.IMPLAUSIBLE_SALARY

        // Only annual figures have a stable enough meaning to sanity-check.
        if (raw.salaryPeriod == SalaryPeriod.YEAR || raw.salaryPeriod == null) {
            if (high != null && high > maxPlausibleAnnual) issues += RecordRejectionReason.IMPLAUSIBLE_SALARY
            if (low != null && low in 1 until minPlausibleAnnual) issues += RecordRejectionReason.IMPLAUSIBLE_SALARY
        }
        return issues.distinct()
    }

    private fun ageIssues(raw: RawJob, now: Long): List<RecordRejectionReason> {
        val posted = raw.postedAtEpochMillis ?: return emptyList()
        val limit = maxPostedAgeDays ?: return emptyList()
        // A future date is a source clock problem, not a reason to hide the posting.
        if (posted > now) return emptyList()
        val ageDays = (now - posted) / DAY_MS
        return if (ageDays > limit) listOf(RecordRejectionReason.STALE_POSTING) else emptyList()
    }

    companion object {
        private const val DAY_MS = 86_400_000L

        /** The id the UI keys a record by. Kept here so validation and rendering cannot drift. */
        fun recordId(raw: RawJob): String {
            val external = raw.externalId.trim().ifBlank { raw.url.trim().ifBlank { raw.title.trim() } }
            return "${raw.providerId}:$external"
        }

        /**
         * Provider-agnostic identity for deduplication. Employer and title are normalised
         * so "Northline Industrial" and "northline industrial " collapse, while genuinely
         * different roles at the same employer stay separate.
         */
        fun dedupKey(raw: RawJob): String {
            val company = normalise(raw.company.ifBlank { raw.title })
            val title = normalise(raw.title)
            return "$company|$title"
        }

        private fun normalise(value: String): String =
            value.lowercase()
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()

        private fun isHttpUrl(url: String): Boolean {
            val lower = url.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
            val host = lower.substringAfter("://").substringBefore('/').substringBefore('?')
            return host.isNotBlank() && host.contains('.')
        }
    }
}
