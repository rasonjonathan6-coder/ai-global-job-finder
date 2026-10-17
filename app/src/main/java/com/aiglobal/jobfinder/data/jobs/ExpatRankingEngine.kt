package com.aiglobal.jobfinder.data.jobs

import com.aiglobal.jobfinder.data.expat.ExpatOpportunityEngine
import com.aiglobal.jobfinder.data.model.ApplicationReadiness
import com.aiglobal.jobfinder.data.model.BenefitFinding
import com.aiglobal.jobfinder.data.model.EvidenceStrength
import com.aiglobal.jobfinder.data.model.ExpatOpportunity
import com.aiglobal.jobfinder.data.model.ExpatOpportunityBand
import com.aiglobal.jobfinder.data.model.ExpatPreferences
import com.aiglobal.jobfinder.data.model.ExpatRankedJob
import com.aiglobal.jobfinder.data.model.ExpatRankingState
import com.aiglobal.jobfinder.data.model.JobBenefitTypes
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.OpportunityDimension
import com.aiglobal.jobfinder.data.model.RankingLabel
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.ShortlistEntry
import com.aiglobal.jobfinder.data.model.ShortlistState
import com.aiglobal.jobfinder.data.model.VerificationState
import com.aiglobal.jobfinder.data.model.VisaSupport

/**
 * Ranks the shortlisted postings for an applicant in Madagascar and explains every place.
 *
 * The rank is a weighted blend of two numbers the app already derives and keeps separate:
 *
 *   readiness  0..60 — how ready the posting is to apply for ([ShortlistEntry.readinessScore]):
 *                      the route in, the CV match, the documented benefits and the profile.
 *   opportunity 0..40 — how good the documented expatriate package is ([ExpatOpportunity.score]):
 *                      the money, housing, flights and insurance the employer states.
 *
 * Both are read from the shortlist, so the ranking never re-derives eligibility or benefits
 * and can never disagree with the screens that show them. Three rules keep it honest:
 *
 *  1. **A disqualifier cannot be outvoted.** A posting that is blocked for the candidate, or
 *     whose package is disqualified (ineligible, scam-flagged, dead link), is placed below
 *     every posting that is not, regardless of score.
 *  2. **Labels need evidence.** A label such as "visa sponsorship" is only attached when the
 *     employer's stated sponsorship (or documented benefit) exists; silence produces the
 *     caution label "benefits not stated", never a positive one.
 *  3. **Ranking is deterministic.** Ties are broken by final score, then readiness, then the
 *     job id, so the same feed always yields the same order.
 */
class ExpatRankingEngine(
    private val opportunityEngine: ExpatOpportunityEngine = ExpatOpportunityEngine(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun rank(
        shortlist: ShortlistState,
        preferences: ExpatPreferences = ExpatPreferences.defaults(),
        limit: Int = DEFAULT_LIMIT,
    ): ExpatRankingState {
        if (shortlist.entries.isEmpty()) {
            return ExpatRankingState(
                isLoading = shortlist.isLoading,
                ranked = emptyList(),
                totalJobs = shortlist.totalJobs,
                generatedAtEpochMillis = clock(),
            )
        }

        val ranked = shortlist.entries
            .map { entry -> scored(entry, preferences) }
            .sortedWith(
                compareByDescending<ExpatRankedJob> { !isDisqualified(it) }
                    .thenByDescending { it.finalScore }
                    .thenByDescending { it.readinessScore }
                    .thenBy { it.job.id },
            )
            .take(limit)
            .mapIndexed { index, item -> item.copy(rank = index + 1) }

        return ExpatRankingState(
            isLoading = false,
            ranked = ranked,
            totalJobs = shortlist.totalJobs,
            generatedAtEpochMillis = clock(),
        )
    }

    private fun scored(entry: ShortlistEntry, preferences: ExpatPreferences): ExpatRankedJob {
        val opportunity = opportunityEngine.assess(entry.job, preferences)
        val raw = (entry.readinessScore * READINESS_WEIGHT / 100) +
            (opportunity.score * OPPORTUNITY_WEIGHT / 100)
        val disqualified = entry.readiness == ApplicationReadiness.BLOCKED || opportunity.disqualified
        val finalScore = if (disqualified) minOf(raw, DISQUALIFIED_CAP) else raw.coerceIn(0, 100)

        return ExpatRankedJob(
            job = entry.job,
            rank = 0,
            finalScore = finalScore,
            readinessScore = entry.readinessScore,
            readiness = entry.readiness,
            opportunity = opportunity,
            labels = labelsFor(entry, opportunity, disqualified),
            reasons = reasonsFor(entry, opportunity),
        )
    }

    private fun isDisqualified(item: ExpatRankedJob): Boolean =
        item.opportunity.disqualified || item.readiness == ApplicationReadiness.BLOCKED

    private fun labelsFor(
        entry: ShortlistEntry,
        opportunity: ExpatOpportunity,
        disqualified: Boolean,
    ): List<RankingLabel> = buildList {
        val job = entry.job
        if (disqualified) {
            add(RankingLabel.NOT_OPEN_TO_YOU)
        } else {
            when (entry.readiness) {
                ApplicationReadiness.PRIORITY -> add(RankingLabel.TOP_PRIORITY)
                ApplicationReadiness.READY -> add(RankingLabel.READY_TO_APPLY)
                else -> {}
            }
            if (job.visaSupport == VisaSupport.FULL_SPONSORSHIP) add(RankingLabel.VISA_SPONSORSHIP)
            if (opportunity.hasConfirmedBenefit(JobBenefitTypes.RELOCATION_SUPPORT.key)) {
                add(RankingLabel.RELOCATION_CONFIRMED)
            }
            if (opportunity.hasConfirmedBenefit(JobBenefitTypes.ACCOMMODATION.key)) {
                add(RankingLabel.ACCOMMODATION_CONFIRMED)
            }
            if (opportunity.hasConfirmedBenefit(JobBenefitTypes.FLIGHTS_HOME.key)) {
                add(RankingLabel.FLIGHTS_CONFIRMED)
            }
            if (opportunity.salary != null && !job.salaryIsEstimated) add(RankingLabel.SALARY_DISCLOSED)
            if (opportunity.band.isStrongOrBetter) add(RankingLabel.STRONG_PACKAGE)
        }
        // Cautions are always surfaced, so a strong score cannot hide an unresolved risk.
        if (!disqualified && job.eligibility == MadagascarEligibility.UNCLEAR) {
            add(RankingLabel.VERIFY_ELIGIBILITY)
        }
        if (opportunity.claims.none { it.finding == BenefitFinding.CONFIRMED }) {
            add(RankingLabel.BENEFITS_NOT_DISCLOSED)
        }
        if (job.verification.link != VerificationState.CONFIRMED &&
            job.verification.link != VerificationState.POSSIBLE
        ) {
            add(RankingLabel.LINK_TO_CHECK)
        }
        if (job.scamRisk == ScamRisk.MEDIUM) add(RankingLabel.SCAM_CAUTION)
    }

    private fun reasonsFor(entry: ShortlistEntry, opportunity: ExpatOpportunity): List<String> = buildList {
        // The strongest documented benefit, quoted from the employer's own words.
        opportunity.claims
            .firstOrNull { it.finding == BenefitFinding.CONFIRMED && it.evidence.isNotBlank() }
            ?.let { add("${it.label}: “${it.evidence.trim().take(120)}”") }
        // The single biggest thing holding the posting back, if anything is.
        entry.orderedBlockers.firstOrNull()?.let { add("${it.label} — ${it.detail}") }
        // The reliability read, stated plainly rather than as a number.
        val reliabilityUnknown = opportunity.dimensions.any {
            it.dimension == OpportunityDimension.RELIABILITY && it.strength == EvidenceStrength.UNKNOWN
        }
        if (reliabilityUnknown) add("The posting's reliability could not be confirmed yet.")
        // Confidence in the expatriate package, so a thin listing is not read as a verdict.
        if (!opportunity.confidence.isEnough) add(opportunity.confidenceNote)
    }

    private companion object {
        const val READINESS_WEIGHT = 60
        const val OPPORTUNITY_WEIGHT = 40
        const val DISQUALIFIED_CAP = 35
        const val DEFAULT_LIMIT = 60
    }
}

/** True when the posting documents this benefit, keyed by the stable benefit key. */
private fun ExpatOpportunity.hasConfirmedBenefit(key: String): Boolean {
    val label = JobBenefitTypes.byKey(key)?.label ?: return false
    return claims.any { it.finding == BenefitFinding.CONFIRMED && it.label == label }
}

private val ExpatOpportunityBand.isStrongOrBetter: Boolean
    get() = this == ExpatOpportunityBand.STRONG || this == ExpatOpportunityBand.EXCELLENT
