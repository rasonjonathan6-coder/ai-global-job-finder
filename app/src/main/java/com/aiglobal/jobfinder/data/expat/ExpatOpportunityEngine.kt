package com.aiglobal.jobfinder.data.expat

import com.aiglobal.jobfinder.data.geo.CurrencyConverter
import com.aiglobal.jobfinder.data.jobs.search.RoleFamily
import com.aiglobal.jobfinder.data.model.BenefitEvidence
import com.aiglobal.jobfinder.data.model.BenefitFinding
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.BenefitClaim
import com.aiglobal.jobfinder.data.model.EvidenceStrength
import com.aiglobal.jobfinder.data.model.ExpatOpportunity
import com.aiglobal.jobfinder.data.model.ExpatOpportunityBand
import com.aiglobal.jobfinder.data.model.ExpatPreferences
import com.aiglobal.jobfinder.data.model.InternationalFitAssessment
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobBenefitTypes
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.NormalisedSalary
import com.aiglobal.jobfinder.data.model.OpportunityConfidence
import com.aiglobal.jobfinder.data.model.OpportunityDimension
import com.aiglobal.jobfinder.data.model.OpportunityDimensionScore
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.VerificationState
import com.aiglobal.jobfinder.data.model.VisaSupport

/**
 * Builds the explainable **Expatriate Opportunity Score** for one posting.
 *
 * The score is a fixed, weighted sum of seven dimensions (see [OpportunityDimension]). Every
 * dimension reports the points it earned, why, and how strong the evidence was, so the total
 * can always be traced. Three honesty rules run through the whole engine, matching the rest
 * of the pipeline:
 *
 *  1. **Silence is not a claim.** A benefit the listing does not mention is [BenefitFinding.UNKNOWN]
 *     — it neither earns points nor is presented as a refusal. A vague phrase such as
 *     "competitive package" is never upgraded to [BenefitFinding.CONFIRMED].
 *  2. **A high total cannot hide a disqualifier.** An ineligible posting, a high scam risk, or
 *     a dead application link caps the score and sets [ExpatOpportunity.disqualified], so the
 *     number can never read as a good opportunity when the candidate cannot actually take it.
 *  3. **Missing data lowers confidence, not the verdict.** When too little of the posting is
 *     documented, the band is [ExpatOpportunityBand.INSUFFICIENT] and the explanation says the
 *     evidence is thin — which is a different statement from "this is a poor offer".
 *
 * The engine invents nothing: every point comes from the posting's own fields or the evidence
 * the rest of the app already extracted. It is a pure function of its inputs.
 */
class ExpatOpportunityEngine(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun assess(
        job: Job,
        preferences: ExpatPreferences = ExpatPreferences.defaults(),
        internationalFit: InternationalFitAssessment? = null,
    ): ExpatOpportunity {
        val claims = claimsFor(job)
        val salary = salaryFor(job)
        val now = clock()

        val dimensions = buildList {
            add(eligibilityDimension(job))
            add(roleFitDimension(job, internationalFit))
            add(compensationDimension(job, salary))
            add(accommodationRelocationDimension(claims))
            add(travelRotationDimension(job, claims))
            add(insuranceOtherDimension(claims))
            add(reliabilityDimension(job))
        }

        val rawScore = dimensions.sumOf { it.points }.coerceIn(0, 100)

        val hardStops = hardStopsFor(job)
        val disqualified = hardStops.isNotEmpty()
        val score = if (disqualified) minOf(rawScore, DISQUALIFIED_CAP) else rawScore

        val confidence = confidenceFor(claims)
        val band = if (!confidence.isEnough) {
            ExpatOpportunityBand.INSUFFICIENT
        } else {
            ExpatOpportunityBand.fromScore(score)
        }

        return ExpatOpportunity(
            score = score,
            band = band,
            dimensions = dimensions,
            claims = claims,
            salary = salary,
            confidence = confidence,
            confidenceNote = confidenceNote(confidence, claims),
            hardStops = hardStops,
            disqualified = disqualified,
            labels = labelsFor(job, claims, salary, score),
            routeSummary = routeSummary(job),
            explanation = explanation(job, band, score, confidence, disqualified, hardStops),
        )
    }

    // ------------------------------------------------------------ dimensions

    private fun eligibilityDimension(job: Job): OpportunityDimensionScore {
        val max = OpportunityDimension.INTERNATIONAL_ELIGIBILITY.weightPercent
        val (points, summary, strength) = when (job.eligibility) {
            MadagascarEligibility.ELIGIBLE ->
                Triple(max, "The listing accepts international applicants.", EvidenceStrength.DOCUMENTED)
            MadagascarEligibility.VISA_SPONSORED -> when (job.visaSupport) {
                VisaSupport.FULL_SPONSORSHIP ->
                    Triple(max, "The listing states full visa sponsorship.", EvidenceStrength.DOCUMENTED)
                VisaSupport.RELOCATION_PACKAGE ->
                    Triple((max * 0.9).toInt(), "The listing states a relocation package with sponsorship.", EvidenceStrength.DOCUMENTED)
                VisaSupport.SPONSORSHIP_AVAILABLE ->
                    Triple((max * 0.75).toInt(), "Sponsorship is mentioned as available — confirm the terms.", EvidenceStrength.NEEDS_VERIFICATION)
                else ->
                    Triple((max * 0.7).toInt(), "The listing points to a sponsored route — confirm it in writing.", EvidenceStrength.NEEDS_VERIFICATION)
            }
            MadagascarEligibility.UNCLEAR ->
                Triple((max * 0.4).toInt(), "The listing does not say whether applicants from Madagascar are accepted.", EvidenceStrength.UNKNOWN)
            MadagascarEligibility.RESTRICTED ->
                Triple(0, "The listing appears restricted to local applicants.", EvidenceStrength.DOCUMENTED)
        }
        return OpportunityDimensionScore(
            dimension = OpportunityDimension.INTERNATIONAL_ELIGIBILITY,
            points = points,
            maxPoints = max,
            summary = summary,
            strength = strength,
        )
    }

    private fun roleFitDimension(job: Job, fit: InternationalFitAssessment?): OpportunityDimensionScore {
        val max = OpportunityDimension.ROLE_FIT.weightPercent
        // The CV match is the backbone; the role family nudges it so an unrelated title
        // cannot score as well as a target trade even at the same match percentage.
        val familyFactor = when (job.roleFamily) {
            RoleFamily.TARGET -> 1.0
            RoleFamily.MAINTENANCE_FAMILY -> 0.85
            RoleFamily.UNKNOWN -> 0.6
            RoleFamily.UNRELATED -> 0.25
        }
        val fitBonus = fit?.let { (it.score - 50).coerceIn(-10, 10) } ?: 0
        val points = ((job.matchScore * familyFactor).toInt() + fitBonus).coerceIn(0, 100)
        val scaled = (points * max / 100).coerceIn(0, max)
        val summary = when (job.roleFamily) {
            RoleFamily.TARGET -> "A target role for your trade (CV match ${job.matchScore}%)."
            RoleFamily.MAINTENANCE_FAMILY -> "In your wider maintenance family (CV match ${job.matchScore}%)."
            RoleFamily.UNRELATED -> "A different profession from your trade."
            RoleFamily.UNKNOWN -> "The role family could not be confirmed from the title."
        }
        return OpportunityDimensionScore(
            dimension = OpportunityDimension.ROLE_FIT,
            points = scaled,
            maxPoints = max,
            summary = summary,
            strength = if (job.roleFamily == RoleFamily.UNRELATED) EvidenceStrength.UNKNOWN else EvidenceStrength.INFERRED,
        )
    }

    private fun compensationDimension(job: Job, salary: NormalisedSalary?): OpportunityDimensionScore {
        val max = OpportunityDimension.COMPENSATION.weightPercent
        val disclosed = job.salaryMin > 0 && !job.salaryIsEstimated
        val conflicting = job.salaryMin > 0 && job.salaryMax > 0 && job.salaryMin > job.salaryMax
        val points: Int
        val summary: String
        val strength: EvidenceStrength
        when {
            conflicting -> {
                points = (max * 0.3).toInt()
                summary = "The advertised salary range is internally inconsistent — verify it with the employer."
                strength = EvidenceStrength.NEEDS_VERIFICATION
            }
            disclosed && salary?.converted == true -> {
                points = max
                summary = "Salary disclosed (${salary.originalCurrency} ${salary.originalAmount}/${salary.originalPeriod.name.lowercase()}); " +
                    "about €${salary.eurPerYear}/year (estimated)."
                strength = EvidenceStrength.DOCUMENTED
            }
            disclosed -> {
                points = (max * 0.8).toInt()
                summary = "Salary disclosed, but no exchange rate is available to normalise it."
                strength = EvidenceStrength.DOCUMENTED
            }
            else -> {
                points = 0
                summary = "The listing does not state a salary."
                strength = EvidenceStrength.UNKNOWN
            }
        }
        return OpportunityDimensionScore(
            dimension = OpportunityDimension.COMPENSATION,
            points = points,
            maxPoints = max,
            summary = summary,
            strength = strength,
        )
    }

    private fun accommodationRelocationDimension(claims: List<BenefitClaim>): OpportunityDimensionScore {
        val max = OpportunityDimension.ACCOMMODATION_RELOCATION.weightPercent
        val accommodation = claim(claims, JobBenefitTypes.ACCOMMODATION.key)
        val relocation = claim(claims, JobBenefitTypes.RELOCATION_SUPPORT.key)
        var points = 0
        val parts = mutableListOf<String>()
        when (accommodation?.finding) {
            BenefitFinding.CONFIRMED -> { points += (max * 0.6).toInt(); parts += "Accommodation confirmed" }
            BenefitFinding.CONFLICTING_EVIDENCE -> { points += (max * 0.2).toInt(); parts += "Accommodation evidence conflicts" }
            else -> {}
        }
        when (relocation?.finding) {
            BenefitFinding.CONFIRMED -> { points += (max * 0.4).toInt(); parts += "relocation support confirmed" }
            BenefitFinding.CONFLICTING_EVIDENCE -> { points += (max * 0.15).toInt(); parts += "relocation evidence conflicts" }
            else -> {}
        }
        val summary = if (parts.isEmpty()) "Neither accommodation nor relocation support is stated." else parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
        return OpportunityDimensionScore(
            dimension = OpportunityDimension.ACCOMMODATION_RELOCATION,
            points = points.coerceIn(0, max),
            maxPoints = max,
            summary = summary,
            strength = strengthOf(accommodation, relocation),
        )
    }

    private fun travelRotationDimension(job: Job, claims: List<BenefitClaim>): OpportunityDimensionScore {
        val max = OpportunityDimension.TRAVEL_ROTATION.weightPercent
        val flights = claim(claims, JobBenefitTypes.FLIGHTS_HOME.key)
        val transport = claim(claims, JobBenefitTypes.TRANSPORT.key)
        var points = 0
        val parts = mutableListOf<String>()
        when (flights?.finding) {
            BenefitFinding.CONFIRMED -> { points += (max * 0.7).toInt(); parts += "return flights stated" }
            BenefitFinding.CONFLICTING_EVIDENCE -> { points += (max * 0.2).toInt(); parts += "flight evidence conflicts" }
            else -> {}
        }
        when (transport?.finding) {
            BenefitFinding.CONFIRMED -> { points += (max * 0.3).toInt(); parts += "transport allowance stated" }
            else -> {}
        }
        val summary = if (parts.isEmpty()) "Return flights, rotation or transport are not stated." else parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
        return OpportunityDimensionScore(
            dimension = OpportunityDimension.TRAVEL_ROTATION,
            points = points.coerceIn(0, max),
            maxPoints = max,
            summary = summary,
            strength = strengthOf(flights, transport),
        )
    }

    private fun insuranceOtherDimension(claims: List<BenefitClaim>): OpportunityDimensionScore {
        val max = OpportunityDimension.INSURANCE_OTHER.weightPercent
        val insurance = claim(claims, JobBenefitTypes.HEALTH_INSURANCE.key)
        val meals = claim(claims, JobBenefitTypes.MEALS.key)
        val family = claim(claims, JobBenefitTypes.FAMILY_SUPPORT.key)
        var points = 0
        val parts = mutableListOf<String>()
        if (insurance?.finding == BenefitFinding.CONFIRMED) { points += (max * 0.6).toInt(); parts += "health insurance confirmed" }
        if (meals?.finding == BenefitFinding.CONFIRMED) { points += (max * 0.2).toInt(); parts += "meals stated" }
        if (family?.finding == BenefitFinding.CONFIRMED) { points += (max * 0.2).toInt(); parts += "family support stated" }
        val summary = if (parts.isEmpty()) "Insurance and other benefits are not stated." else parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
        return OpportunityDimensionScore(
            dimension = OpportunityDimension.INSURANCE_OTHER,
            points = points.coerceIn(0, max),
            maxPoints = max,
            summary = summary,
            strength = strengthOf(insurance, meals, family),
        )
    }

    private fun reliabilityDimension(job: Job): OpportunityDimensionScore {
        val max = OpportunityDimension.RELIABILITY.weightPercent
        val verification = job.verification
        var points = 0
        val notes = mutableListOf<String>()
        when (verification.activity) {
            VerificationState.CONFIRMED -> { points += (max * 0.35).toInt(); notes += "vacancy confirmed open" }
            VerificationState.POSSIBLE -> { points += (max * 0.2).toInt(); notes += "vacancy looks open" }
            else -> {}
        }
        when (verification.link) {
            VerificationState.CONFIRMED -> { points += (max * 0.25).toInt(); notes += "link works" }
            VerificationState.POSSIBLE -> { points += (max * 0.12).toInt(); notes += "link reachable" }
            else -> {}
        }
        if (job.scamRisk == ScamRisk.LOW) { points += (max * 0.2).toInt(); notes += "no scam signals" }
        if (job.openToApplicants == true) { points += (max * 0.2).toInt(); notes += "source says still open" }
        val summary = if (notes.isEmpty()) "The posting's reliability could not be confirmed yet." else notes.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
        return OpportunityDimensionScore(
            dimension = OpportunityDimension.RELIABILITY,
            points = points.coerceIn(0, max),
            maxPoints = max,
            summary = summary,
            strength = if (notes.isEmpty()) EvidenceStrength.UNKNOWN else EvidenceStrength.INFERRED,
        )
    }

    // ------------------------------------------------------------ claims

    /**
     * Turns the posting's extracted benefit evidence into [BenefitClaim]s, one per benefit
     * type, merging repeated findings. When the same benefit is both stated and refused —
     * or stated twice with different outcomes — the finding is [BenefitFinding.CONFLICTING_EVIDENCE]
     * rather than a guess.
     */
    private fun claimsFor(job: Job): List<BenefitClaim> {
        val now = clock()
        val byType = job.benefitEvidence.groupBy { it.type.key }
        return JobBenefitTypes.all.map { type ->
            val findings = byType[type.key].orEmpty()
            claimFrom(type.key, type.label, findings, job, now)
        }
    }

    private fun claimFrom(
        key: String,
        label: String,
        findings: List<BenefitEvidence>,
        job: Job,
        now: Long,
    ): BenefitClaim {
        if (findings.isEmpty()) {
            return BenefitClaim(
                label = label,
                finding = BenefitFinding.UNKNOWN,
                evidence = "",
                sourceUrl = job.sourceUrl,
                checkedAtEpochMillis = now,
                explicit = false,
                limitations = "The listing does not mention this.",
            )
        }
        val statuses = findings.map { it.status }.toSet()
        val evidence = findings.firstOrNull { it.evidence.isNotBlank() }?.evidence.orEmpty()
        val finding = when {
            statuses.contains(BenefitStatus.CONFIRMED) && statuses.contains(BenefitStatus.NOT_OFFERED) ->
                BenefitFinding.CONFLICTING_EVIDENCE
            statuses == setOf(BenefitStatus.CONFIRMED) -> BenefitFinding.CONFIRMED
            statuses == setOf(BenefitStatus.NOT_OFFERED) -> BenefitFinding.NOT_OFFERED
            statuses.contains(BenefitStatus.NEEDS_VERIFICATION) ->
                // A mention that needs checking is never presented as confirmed.
                BenefitFinding.UNKNOWN
            else -> BenefitFinding.UNKNOWN
        }
        val explicit = finding == BenefitFinding.CONFIRMED && statuses == setOf(BenefitStatus.CONFIRMED)
        val limitations = when (finding) {
            BenefitFinding.CONFLICTING_EVIDENCE -> "The listing states this more than once, with conflicting wording."
            BenefitFinding.UNKNOWN -> "Mentioned in the listing, but not stated clearly enough to confirm."
            BenefitFinding.NOT_OFFERED -> "The listing states this is not provided."
            BenefitFinding.CONFIRMED -> ""
        }
        return BenefitClaim(
            label = label,
            finding = finding,
            evidence = evidence,
            sourceUrl = job.sourceUrl,
            checkedAtEpochMillis = now,
            explicit = explicit,
            limitations = limitations,
        )
    }

    private fun salaryFor(job: Job): NormalisedSalary? {
        if (job.salaryMin <= 0) return null
        val eur = CurrencyConverter.toEurPerYear(job.salaryMin, job.currencyCode, job.salaryPeriod)
        val converted = eur != null
        return NormalisedSalary(
            originalAmount = job.salaryMin,
            originalCurrency = job.currencyCode.ifBlank { "—" },
            originalPeriod = job.salaryPeriod,
            eurPerYear = eur,
            rateSource = if (converted) CurrencyConverter.rateSource else null,
            rateAsOfEpochMillis = if (converted) CurrencyConverter.rateAsOfEpochMillis else null,
            converted = converted,
            note = if (converted) {
                "Converted to EUR/year using a coarse on-device rate table; treat it as an estimate, not a quote."
            } else {
                "No exchange rate is available on device, so only the original amount is shown."
            },
        )
    }

    // ------------------------------------------------------------ hard stops, confidence, labels

    private fun hardStopsFor(job: Job): List<String> = buildList {
        if (job.eligibility == MadagascarEligibility.RESTRICTED) {
            add("The listing appears restricted to local applicants.")
        }
        if (job.scamRisk == ScamRisk.HIGH) {
            add("The scam check flagged this posting.")
        }
        if (job.verification.link.isNegative) {
            add("The application link looks broken or inaccessible.")
        }
    }

    private fun confidenceFor(claims: List<BenefitClaim>): OpportunityConfidence {
        val known = claims.count { it.finding.isKnown }
        val total = claims.size.coerceAtLeast(1)
        val coverage = known * 100 / total
        return when {
            known == 0 -> OpportunityConfidence.INSUFFICIENT
            coverage >= 60 -> OpportunityConfidence.HIGH
            coverage >= 30 -> OpportunityConfidence.MEDIUM
            else -> OpportunityConfidence.LOW
        }
    }

    private fun confidenceNote(confidence: OpportunityConfidence, claims: List<BenefitClaim>): String {
        val known = claims.count { it.finding.isKnown }
        return when (confidence) {
            OpportunityConfidence.INSUFFICIENT ->
                "None of the expatriate benefits are stated in the listing, so the score cannot be trusted."
            OpportunityConfidence.LOW ->
                "Only $known of ${claims.size} benefits are documented — read this as a rough signal."
            OpportunityConfidence.MEDIUM ->
                "$known of ${claims.size} benefits are documented — a partial but useful picture."
            OpportunityConfidence.HIGH ->
                "$known of ${claims.size} benefits are documented — a well-evidenced picture."
        }
    }

    /**
     * Labels are only ever emitted when the supporting evidence exists. A label that would
     * imply a guarantee (sponsorship, a confirmed offer) is never produced from silence.
     */
    private fun labelsFor(
        job: Job,
        claims: List<BenefitClaim>,
        salary: NormalisedSalary?,
        score: Int,
    ): List<String> = buildList {
        if (job.matchScore >= 75 && job.roleFamily != RoleFamily.UNRELATED) add("Strong technical match")
        if (job.visaSupport == VisaSupport.FULL_SPONSORSHIP) add("Visa sponsorship explicitly offered")
        if (claim(claims, JobBenefitTypes.ACCOMMODATION.key)?.finding == BenefitFinding.CONFIRMED) add("Accommodation confirmed")
        if (claim(claims, JobBenefitTypes.FLIGHTS_HOME.key)?.finding == BenefitFinding.CONFIRMED) add("Return flights explicitly included")
        if (salary != null && !job.salaryIsEstimated) add("Salary disclosed")
        if (job.eligibility == MadagascarEligibility.UNCLEAR) add("International eligibility needs verification")
        if (claims.none { it.finding == BenefitFinding.CONFIRMED }) add("Expatriate benefits not disclosed")
        if (job.verification.link != VerificationState.CONFIRMED &&
            job.verification.link != VerificationState.POSSIBLE
        ) {
            add("Application link needs checking")
        }
        if (job.roleFamily == RoleFamily.UNRELATED) add("Outside your trade")
        if (score >= ExpatOpportunityBand.STRONG_MIN) add("Expatriate package worth pursuing")
    }

    private fun routeSummary(job: Job): String = when (job.eligibility) {
        MadagascarEligibility.ELIGIBLE -> "The listing accepts international applicants; no sponsorship is mentioned."
        MadagascarEligibility.VISA_SPONSORED ->
            if (job.visaSupport == VisaSupport.FULL_SPONSORSHIP) {
                "The listing states visa sponsorship for international hires."
            } else {
                "The listing points to a sponsored route — confirm the terms with the employer."
            }
        MadagascarEligibility.UNCLEAR -> "The listing does not say whether applicants from Madagascar are accepted."
        MadagascarEligibility.RESTRICTED -> "The listing appears restricted to local applicants."
    }

    private fun explanation(
        job: Job,
        band: ExpatOpportunityBand,
        score: Int,
        confidence: OpportunityConfidence,
        disqualified: Boolean,
        hardStops: List<String>,
    ): String = when {
        disqualified ->
            "Capped at $score% — ${hardStops.first().lowercase()} A strong package cannot outweigh this."
        !confidence.isEnough ->
            "The listing states too little about its expatriate package to judge. That is a gap in the listing, not a verdict."
        band == ExpatOpportunityBand.EXCELLENT ->
            "An outstanding expatriate package ($score%), with most benefits documented."
        band == ExpatOpportunityBand.STRONG ->
            "A strong expatriate package ($score%). Check the unstated items before applying."
        band == ExpatOpportunityBand.POSSIBLE ->
            "A reasonable package ($score%), but several benefits are not stated."
        else ->
            "A limited package ($score%) — few expatriate benefits are documented."
    }

    private fun claim(claims: List<BenefitClaim>, key: String): BenefitClaim? {
        val label = JobBenefitTypes.byKey(key)?.label ?: return null
        return claims.firstOrNull { it.label == label }
    }

    private fun strengthOf(vararg claims: BenefitClaim?): EvidenceStrength {
        val present = claims.filterNotNull()
        return when {
            present.any { it.finding == BenefitFinding.CONFLICTING_EVIDENCE } -> EvidenceStrength.NEEDS_VERIFICATION
            present.any { it.finding == BenefitFinding.CONFIRMED && it.explicit } -> EvidenceStrength.DOCUMENTED
            present.any { it.finding == BenefitFinding.CONFIRMED } -> EvidenceStrength.INFERRED
            present.any { it.finding == BenefitFinding.NOT_OFFERED } -> EvidenceStrength.REFUSED
            else -> EvidenceStrength.UNKNOWN
        }
    }

    private companion object {
        /** A disqualifying posting is never scored as a good opportunity. */
        const val DISQUALIFIED_CAP = 35
    }
}
