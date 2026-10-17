package com.aiglobal.jobfinder.data.notifications

import androidx.compose.runtime.Immutable
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.VerificationState
import com.aiglobal.jobfinder.data.model.VisaSupport

/**
 * What a notification about one posting would say, decided before anything is posted.
 *
 * Kept as a pure value so the wording rules — never claim sponsorship or international
 * eligibility the listing does not support — are unit-testable without Android.
 */
@Immutable
data class JobNotification(
    val jobId: String,
    val title: String,
    val company: String,
    val body: String,
    val matchScore: Int,
    /** The source that supplied the posting, so attribution travels with the alert. */
    val sourceName: String = "",
)

/**
 * Decides which freshly-discovered postings deserve a notification.
 *
 * Three rules, in order:
 *  1. Only postings that score at or above [minMatchScore] are considered — a notification
 *     is a claim the app is confident enough to interrupt the user for. The threshold is the
 *     user's to set (see [com.aiglobal.jobfinder.data.refresh.AlertThreshold]); [DEFAULT_MIN_MATCH]
 *     is the conservative default.
 *  2. A posting the user has already been told about is never repeated, even across
 *     restarts, because the set of notified ids is persisted.
 *  3. The body states only facts from the listing. Sponsorship and "open to international
 *     applicants" are mentioned *only* when the eligibility engine already found official
 *     evidence for them; otherwise the wording stays neutral. The source is always named,
 *     and the verification read is quoted only when it has something to say.
 *
 * The result is also capped at [maxPerRun] so a big feed cannot fire a dozen notifications
 * at once; the strongest matches win.
 */
class JobNotificationPolicy(
    private val minMatchScore: Int = DEFAULT_MIN_MATCH,
    private val maxPerRun: Int = 3,
) {

    /**
     * @param jobs postings from the latest refresh
     * @param alreadyNotified job ids the user has already been notified about
     * @return the notifications to post now, strongest first, and never repeating an id
     */
    fun select(
        jobs: List<Job>,
        alreadyNotified: Set<String>,
    ): List<JobNotification> = jobs
        .asSequence()
        .filter { it.id !in alreadyNotified }
        .filter { it.matchScore >= minMatchScore }
        .sortedByDescending { it.matchScore }
        .take(maxPerRun)
        .map(::toNotification)
        .toList()

    private fun toNotification(job: Job): JobNotification {
        val where = if (job.country.isBlank()) "" else " · ${job.countryFlag} ${job.country}".trimEnd()
        val eligibility = when (job.eligibility) {
            MadagascarEligibility.VISA_SPONSORED ->
                if (job.visaSupport == VisaSupport.FULL_SPONSORSHIP) {
                    "The listing states visa sponsorship."
                } else {
                    "The listing mentions visa support — confirm the terms."
                }
            MadagascarEligibility.ELIGIBLE -> "The listing accepts international applicants."
            MadagascarEligibility.UNCLEAR -> "Check whether they accept applicants from Madagascar."
            MadagascarEligibility.RESTRICTED -> "This one looks local-only — probably not a fit."
        }
        val lines = buildList {
            add("${job.company}$where")
            add(eligibility)
            job.matchedTargetRole?.let { add("Matches your target role: $it.") }
            verificationNote(job)?.let(::add)
            if (job.source.isNotBlank()) add("Source: ${job.source}.")
        }
        return JobNotification(
            jobId = job.id,
            title = "${job.matchScore}% match: ${job.title}",
            company = job.company,
            body = lines.joinToString("\n"),
            matchScore = job.matchScore,
            sourceName = job.source,
        )
    }

    /**
     * A short verification line, or null when the app has nothing to say. A broken link is
     * the one finding worth interrupting for, because it changes whether applying is worth it.
     */
    private fun verificationNote(job: Job): String? = when {
        job.verification.link.isNegative -> "The application link looks broken — check the posting first."
        job.verification.activity == VerificationState.CONFIRMED -> "The source confirms it is still open."
        else -> null
    }

    companion object {
        /** The conservative default: only strong matches interrupt the user until they change it. */
        const val DEFAULT_MIN_MATCH = 75
    }
}
