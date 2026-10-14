package com.aiglobal.jobfinder.data.expat

import com.aiglobal.jobfinder.data.model.BenefitEvidence
import com.aiglobal.jobfinder.data.model.BenefitPriority
import com.aiglobal.jobfinder.data.model.BenefitScoreComponent
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.BenefitType
import com.aiglobal.jobfinder.data.model.ExpatBenefitsAssessment
import com.aiglobal.jobfinder.data.model.ExpatPreferences
import com.aiglobal.jobfinder.data.model.JobBenefitTypes
import kotlin.math.roundToInt

/**
 * Scores how well a posting's *documented* expat benefits match the user's priorities.
 *
 * This is intentionally a different number from the CV match score and the eligibility
 * verdict, and it is never allowed to imply either of them. Two rules keep it honest:
 *
 *  1. Only documented benefits earn points. A benefit the listing never mentions scores
 *     nothing, but it is reported as "not stated" rather than "refused", and the separate
 *     [ExpatBenefitsAssessment.evidenceCoverage] number says how much of the listing could
 *     actually be read.
 *  2. A low score is not a statement about the employer. When coverage is thin the score
 *     is flagged as unreliable and the explanation says the listing simply lacks detail.
 */
class ExpatBenefitsEngine {

    /**
     * @param benefits the extracted findings for the posting.
     * @param preferences the user's priorities and gates.
     * @param salaryEurPerYear the advertised salary normalised to EUR/year, or null when
     *   the source published none (never invented).
     */
    fun assess(
        benefits: List<BenefitEvidence>,
        preferences: ExpatPreferences,
        salaryEurPerYear: Int?,
    ): ExpatBenefitsAssessment {
        val byType = benefits.associateBy { it.type.key }
        val components = mutableListOf<BenefitScoreComponent>()

        for (type in JobBenefitTypes.extractable) {
            val priority = preferences.priorityOf(type)
            val evidence = byType[type.key] ?: BenefitEvidence(type, BenefitStatus.UNKNOWN, "")
            components += component(type, priority, evidence.status, evidence.evidence)
        }

        // Minimum salary is a preference, not a phrase in the listing, so it is scored
        // separately: it only counts once the user has actually set a target.
        val salaryType = JobBenefitTypes.MINIMUM_SALARY
        val salaryPriority = preferences.priorityOf(salaryType)
        val minimum = preferences.minimumSalaryEur
        if (minimum != null && salaryPriority.weight > 0) {
            val status = when {
                salaryEurPerYear == null -> BenefitStatus.UNKNOWN
                salaryEurPerYear >= minimum -> BenefitStatus.CONFIRMED
                else -> BenefitStatus.NOT_OFFERED
            }
            val evidence = when {
                salaryEurPerYear == null -> "The source did not publish a salary."
                salaryEurPerYear >= minimum -> "Advertised pay is at or above your €$minimum minimum."
                else -> "Advertised pay is below your €$minimum minimum."
            }
            components += component(salaryType, salaryPriority, status, evidence)
        }

        val weighted = components.filter { it.priority.weight > 0 }
        val totalPotential = weighted.sumOf { it.potentialPoints }
        val earned = weighted.sumOf { it.earnedPoints }
        val score = if (totalPotential == 0) 0 else (100f * earned / totalPotential).roundToInt()

        val documented = weighted.count { it.status != BenefitStatus.UNKNOWN }
        val coverage = if (weighted.isEmpty()) 0 else (100f * documented / weighted.size).roundToInt()
        val lowEvidence = weighted.isNotEmpty() && coverage < LOW_EVIDENCE_THRESHOLD

        val unmetEssentials = weighted
            .filter { it.priority == BenefitPriority.ESSENTIAL && it.status != BenefitStatus.CONFIRMED }
            .map { it.type }

        return ExpatBenefitsAssessment(
            score = score,
            evidenceCoverage = coverage,
            components = components,
            explanation = explain(score, coverage, lowEvidence, weighted, unmetEssentials),
            unmetEssentials = unmetEssentials,
            lowEvidence = lowEvidence,
        )
    }

    private fun component(
        type: BenefitType,
        priority: BenefitPriority,
        status: BenefitStatus,
        evidence: String,
    ): BenefitScoreComponent {
        val credit = when (status) {
            BenefitStatus.CONFIRMED -> 1f
            BenefitStatus.NEEDS_VERIFICATION -> 0.5f
            BenefitStatus.NOT_OFFERED -> 0f
            BenefitStatus.UNKNOWN -> 0f
        }
        return BenefitScoreComponent(
            type = type,
            priority = priority,
            status = status,
            evidence = evidence,
            credit = credit,
            potentialPoints = priority.weight,
            earnedPoints = (priority.weight * credit).roundToInt(),
        )
    }

    private fun explain(
        score: Int,
        coverage: Int,
        lowEvidence: Boolean,
        weighted: List<BenefitScoreComponent>,
        unmetEssentials: List<BenefitType>,
    ): String {
        if (weighted.isEmpty()) {
            return "You have not set any benefit priorities yet. Choose the benefits that matter to you " +
                "to score postings on them."
        }
        val documented = weighted.count { it.status != BenefitStatus.UNKNOWN }
        val base = "$score% of the benefits you marked as important are documented in this posting " +
            "($documented of ${weighted.size} benefits addressed)."

        if (lowEvidence) {
            return "$base Coverage is only $coverage%, so this score mainly reflects missing information — " +
                "not a shortcoming of the employer. Confirm the package with the employer before deciding."
        }

        val refused = unmetEssentials.filter { type ->
            weighted.any { it.type.key == type.key && it.status == BenefitStatus.NOT_OFFERED }
        }
        val unstated = unmetEssentials.filter { type ->
            weighted.any { it.type.key == type.key && it.status == BenefitStatus.UNKNOWN }
        }
        return buildString {
            append(base)
            if (refused.isNotEmpty()) {
                append(" The listing states it does not offer ${refused.joinToString(", ") { it.shortLabel.lowercase() }}.")
            }
            if (unstated.isNotEmpty()) {
                append(" It does not say whether it offers ${unstated.joinToString(", ") { it.shortLabel.lowercase() }}.")
            }
            if (refused.isEmpty() && unstated.isEmpty()) {
                append(" Every benefit you marked as essential is documented.")
            }
        }
    }

    private companion object {
        /** Below this share of documented benefits the score is reported as unreliable. */
        const val LOW_EVIDENCE_THRESHOLD = 40
    }
}
