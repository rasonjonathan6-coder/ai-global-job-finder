package com.aiglobal.jobfinder.data.autoapply

import com.aiglobal.jobfinder.data.model.AutoApplyItem
import com.aiglobal.jobfinder.data.model.AutoApplyStatus
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.ScamRisk

/**
 * Decides which postings the safe auto-apply flow should prepare, and why.
 *
 * The engine refuses to prepare anything it cannot defend: a posting below the user's
 * match threshold, one that is not open to a Madagascar-based applicant, or one the scam
 * detector flagged. Refusals are surfaced as [AutoApplyStatus.BLOCKED] with the reason,
 * never silently dropped.
 *
 * It prepares drafts only. Submission is always a separate, explicit user action.
 */
class AutoApplyEngine(
    private val threshold: Int = DEFAULT_THRESHOLD,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** One decision about one posting. */
    data class Decision(val status: AutoApplyStatus, val reason: String, val eligible: Boolean)

    fun decide(job: Job): Decision = when {
        job.scamRisk == ScamRisk.HIGH ->
            Decision(AutoApplyStatus.BLOCKED, "The scam check flagged this posting. Review it by hand before doing anything.", false)

        !job.applyableFromMadagascar ->
            Decision(AutoApplyStatus.BLOCKED, "This posting is not open to applicants from Madagascar.", false)

        job.matchScore < threshold ->
            Decision(AutoApplyStatus.SKIPPED, "Your match is ${job.matchScore}%, below your $threshold% threshold.", false)

        job.sourceUrl.isBlank() ->
            Decision(AutoApplyStatus.BLOCKED, "There is no application link, so nothing can be prepared.", false)

        else ->
            Decision(AutoApplyStatus.DRAFT_READY, "Match ${job.matchScore}% and open to Madagascar applicants. Draft ready for your approval.", true)
    }

    /**
     * Builds the queue for a feed, best match first. Jobs already tracked keep their
     * existing entry so approving one is never undone by a refresh.
     */
    fun buildQueue(
        jobs: List<Job>,
        existing: List<AutoApplyItem>,
        limit: Int = DEFAULT_LIMIT,
    ): List<AutoApplyItem> {
        val existingByJob = existing.associateBy { it.jobId }
        return jobs
            .sortedByDescending { it.matchScore }
            .mapNotNull { job ->
                val decision = decide(job)
                if (decision.status == AutoApplyStatus.SKIPPED) return@mapNotNull null
                val previous = existingByJob[job.id]
                val status = when {
                    previous == null -> decision.status
                    previous.status == AutoApplyStatus.APPROVED -> AutoApplyStatus.APPROVED
                    previous.status == AutoApplyStatus.SKIPPED -> AutoApplyStatus.SKIPPED
                    else -> decision.status
                }
                AutoApplyItem(
                    jobId = job.id,
                    jobTitle = job.title,
                    company = job.company,
                    country = job.country,
                    countryFlag = job.countryFlag,
                    matchScore = job.matchScore,
                    sourceUrl = job.sourceUrl,
                    status = status,
                    coverLetterId = previous?.coverLetterId,
                    reason = if (status == AutoApplyStatus.APPROVED) "You approved this draft. Send it from the posting." else decision.reason,
                    preparedAtEpochMillis = previous?.preparedAtEpochMillis ?: clock(),
                )
            }
            .take(limit)
    }

    companion object {
        const val DEFAULT_THRESHOLD = 70
        const val DEFAULT_LIMIT = 12
    }
}
