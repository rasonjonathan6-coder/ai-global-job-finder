package com.aiglobal.jobfinder.data.notifications

import com.aiglobal.jobfinder.data.jobs.search.RoleFamily
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.ScamRisk

/**
 * The quality gate a posting must clear before it is worth interrupting the candidate for.
 *
 * Stage 20 answers "is this a strong match?" with a single score bar. That is not enough for
 * a *high-quality international* alert: a listing can score well and still be a bad
 * interruption — an obviously unrelated role, a scam, a posting that rules Madagascar
 * applicants out, or one whose application link is dead. This gate is the extra filter, kept
 * as its own pure value so each rule can be tested in isolation and every exclusion can be
 * explained to the user.
 *
 * It is deliberately *additive*: the score bar still applies (the coordinator keeps it), and
 * everything here only ever removes a posting from the alert set. Nothing is added.
 */
class SmartAlertPolicy(
    /**
     * When true, only postings the incremental ledger reports as genuinely new are eligible.
     * The background runner sets this, because a scheduled refresh re-reads the whole feed and
     * must not re-announce an old posting that simply still sits above the bar. A manual
     * "notify me about what I see" pass leaves it false and keeps the Stage 20 behaviour.
     */
    private val requireGenuinelyNew: Boolean = false,
) {

    /**
     * @param jobs the scored feed from the latest read
     * @param newJobIds the ids the ledger reports as first seen since the last review; only
     *   consulted when [requireGenuinelyNew] is set
     * @return the subset of [jobs] that clears every quality rule, order preserved
     */
    fun filter(jobs: List<Job>, newJobIds: Set<String> = emptySet()): List<Job> = jobs.filter { job ->
        qualifies(job, newJobIds)
    }

    /** True when [job] is a high-quality, actionable, international opportunity. */
    fun qualifies(job: Job, newJobIds: Set<String> = emptySet()): Boolean = reasonsAgainst(job, newJobIds).isEmpty()

    /**
     * Every rule the posting fails, in plain language, or an empty list when it passes. Exposed
     * so a test (and a future UI) can name the exact reason a match stayed silent instead of
     * guessing why nothing was announced.
     */
    fun reasonsAgainst(job: Job, newJobIds: Set<String> = emptySet()): List<String> = buildList {
        if (requireGenuinelyNew && job.id !in newJobIds) {
            add("Not a newly discovered posting")
        }
        if (job.scamRisk == ScamRisk.HIGH) {
            add("Flagged as a likely scam")
        }
        if (job.eligibility == MadagascarEligibility.RESTRICTED) {
            add("Restricted to local applicants")
        }
        if (job.roleFamily == RoleFamily.UNRELATED) {
            add("A different profession from your trade")
        }
        if (job.verification.link.isNegative) {
            add("The application link looks broken")
        }
        if (!job.verification.isActionable) {
            add("The posting cannot be acted on right now")
        }
    }

    companion object {
        /** A gate that also requires the posting to be genuinely new — used by the worker. */
        val NEW_ONLY: SmartAlertPolicy = SmartAlertPolicy(requireGenuinelyNew = true)

        /** A gate that judges quality alone, for callers that already narrowed to new ids. */
        val QUALITY_ONLY: SmartAlertPolicy = SmartAlertPolicy(requireGenuinelyNew = false)
    }
}
