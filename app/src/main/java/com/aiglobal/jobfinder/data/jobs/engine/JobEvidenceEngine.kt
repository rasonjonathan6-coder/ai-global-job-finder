package com.aiglobal.jobfinder.data.jobs.engine

import com.aiglobal.jobfinder.data.model.BenefitEvidence
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.EligibilityReason
import com.aiglobal.jobfinder.data.model.Evidence
import com.aiglobal.jobfinder.data.model.EvidenceKind
import com.aiglobal.jobfinder.data.model.EvidenceStrength
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.QualificationGap
import com.aiglobal.jobfinder.data.model.VisaSupport

/**
 * Builds the typed, source-linked evidence list shown on a posting.
 *
 * The rules it enforces:
 *  - A claim read from the employer's words is [EvidenceStrength.DOCUMENTED] and carries
 *    the quote. A heuristic the engines worked out is [EvidenceStrength.INFERRED]. A fact
 *    the posting does not state is [EvidenceStrength.UNKNOWN] — never quietly omitted and
 *    never presented as a positive.
 *  - Every documented item carries the posting URL, so the user can verify it.
 */
class JobEvidenceEngine {

    fun build(
        eligibility: MadagascarEligibility,
        visaSupport: VisaSupport,
        eligibilityReasons: List<EligibilityReason>,
        benefits: List<BenefitEvidence>,
        gaps: List<QualificationGap>,
        sourceUrl: String,
    ): List<Evidence> = buildList {
        addAll(eligibilityEvidence(eligibility, visaSupport, eligibilityReasons, sourceUrl))
        addAll(benefitEvidence(benefits, sourceUrl))
        addAll(requirementEvidence(gaps, sourceUrl))
    }

    private fun eligibilityEvidence(
        eligibility: MadagascarEligibility,
        visaSupport: VisaSupport,
        reasons: List<EligibilityReason>,
        sourceUrl: String,
    ): List<Evidence> {
        val out = mutableListOf<Evidence>()
        val official = reasons.firstOrNull { it.official }

        out += when (eligibility) {
            MadagascarEligibility.VISA_SPONSORED -> Evidence(
                kind = EvidenceKind.VISA,
                strength = EvidenceStrength.DOCUMENTED,
                label = "Visa sponsorship",
                detail = official?.detail?.takeIf { it.isNotBlank() }
                    ?: "The posting states the employer sponsors a work visa.",
                sourceUrl = sourceUrl,
            )
            MadagascarEligibility.ELIGIBLE -> Evidence(
                kind = EvidenceKind.ELIGIBILITY,
                strength = EvidenceStrength.DOCUMENTED,
                label = "Open to international applicants",
                detail = official?.detail?.takeIf { it.isNotBlank() }
                    ?: "The posting does not restrict applicants by country.",
                sourceUrl = sourceUrl,
            )
            MadagascarEligibility.RESTRICTED -> Evidence(
                kind = EvidenceKind.ELIGIBILITY,
                strength = EvidenceStrength.DOCUMENTED,
                label = "Local applicants only",
                detail = official?.detail?.takeIf { it.isNotBlank() }
                    ?: "The posting restricts applications to local candidates.",
                sourceUrl = sourceUrl,
            )
            MadagascarEligibility.UNCLEAR -> Evidence(
                kind = EvidenceKind.ELIGIBILITY,
                strength = EvidenceStrength.UNKNOWN,
                label = "Eligibility not stated",
                detail = "The posting does not say whether it accepts applicants from Madagascar. Verify before applying.",
                sourceUrl = sourceUrl,
            )
        }

        // A relocation hint the eligibility engine inferred but the employer never stated as
        // sponsorship. It is labelled INFERRED so it is never read as a documented offer.
        val relocationHint = reasons.firstOrNull { !it.official && it.label.contains("reloc", ignoreCase = true) }
        if (relocationHint != null && visaSupport != VisaSupport.FULL_SPONSORSHIP) {
            out += Evidence(
                kind = EvidenceKind.RELOCATION,
                strength = EvidenceStrength.INFERRED,
                label = "Relocation hinted",
                detail = relocationHint.detail.ifBlank {
                    "The posting mentions relocation without stating sponsorship. Confirm with the employer."
                },
                sourceUrl = sourceUrl,
            )
        }
        return out
    }

    private fun benefitEvidence(benefits: List<BenefitEvidence>, sourceUrl: String): List<Evidence> =
        benefits.filter { it.status != BenefitStatus.UNKNOWN }.map { finding ->
            Evidence(
                kind = EvidenceKind.BENEFIT,
                strength = when (finding.status) {
                    BenefitStatus.CONFIRMED -> EvidenceStrength.DOCUMENTED
                    BenefitStatus.NOT_OFFERED -> EvidenceStrength.REFUSED
                    BenefitStatus.NEEDS_VERIFICATION -> EvidenceStrength.NEEDS_VERIFICATION
                    BenefitStatus.UNKNOWN -> EvidenceStrength.UNKNOWN
                },
                label = finding.type.label,
                detail = finding.evidence.ifBlank { finding.type.label },
                sourceUrl = sourceUrl,
            )
        }

    private fun requirementEvidence(gaps: List<QualificationGap>, sourceUrl: String): List<Evidence> =
        gaps.map { gap ->
            Evidence(
                kind = EvidenceKind.REQUIREMENT,
                strength = EvidenceStrength.DOCUMENTED,
                label = gap.label,
                detail = gap.evidence,
                sourceUrl = sourceUrl,
            )
        }
}
