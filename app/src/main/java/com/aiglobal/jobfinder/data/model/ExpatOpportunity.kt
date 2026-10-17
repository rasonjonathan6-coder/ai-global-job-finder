package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable

/**
 * One dimension of the Expatriate Opportunity Score. The weights are fixed and documented
 * here so the total can always be opened up and every point traced back to a dimension the
 * user can name. They sum to 100.
 */
enum class OpportunityDimension(val label: String, val weightPercent: Int) {
    INTERNATIONAL_ELIGIBILITY("International eligibility & work permit", 25),
    ROLE_FIT("Role and skills alignment", 20),
    COMPENSATION("Salary and compensation transparency", 15),
    ACCOMMODATION_RELOCATION("Accommodation and relocation", 15),
    TRAVEL_ROTATION("Travel, rotation and return flights", 10),
    INSURANCE_OTHER("Insurance and other benefits", 5),
    RELIABILITY("Offer reliability and application availability", 10),
}

/**
 * The honest finding for one benefit. Distinct from [BenefitStatus] because this is the
 * vocabulary the expatriate assessment speaks: it adds [CONFLICTING_EVIDENCE], which the
 * app must never paper over, and it keeps [UNKNOWN] ("the listing did not say") strictly
 * separate from [NOT_OFFERED] ("the employer stated it is not provided").
 */
enum class BenefitFinding(val label: String, val emoji: String) {
    CONFIRMED("Confirmed", "✅"),
    NOT_OFFERED("Stated as not offered", "🚫"),
    CONFLICTING_EVIDENCE("Conflicting evidence", "⚠️"),
    UNKNOWN("Not stated", "❔");

    /** True when the listing actually told us something, one way or the other. */
    val isKnown: Boolean get() = this != UNKNOWN
}

/**
 * One benefit finding with the evidence behind it. [evidence] is the employer's own words
 * (or a faithful short excerpt); [explicit] distinguishes "the listing states this" from
 * "the app worked it out". [limitations] records what the finding does *not* prove.
 */
@Immutable
data class BenefitClaim(
    val label: String,
    val finding: BenefitFinding,
    val evidence: String,
    val sourceUrl: String = "",
    val sourceType: String = "Posting text",
    val checkedAtEpochMillis: Long? = null,
    /** True when the statement is explicit in the listing rather than inferred. */
    val explicit: Boolean = true,
    val limitations: String = "",
) {
    /** True when the claim rests on the employer's own text. */
    val isDocumented: Boolean get() = finding == BenefitFinding.CONFIRMED && explicit
}

/**
 * An advertised salary, normalised without ever discarding the original. The converted
 * figure is an *estimate* and is labelled as such; the original amount, currency and period
 * are always kept so nothing is lost in translation, and the coarse on-device rate table is
 * named so the user knows where the number came from.
 */
@Immutable
data class NormalisedSalary(
    val originalAmount: Int,
    val originalCurrency: String,
    val originalPeriod: SalaryPeriod,
    /** The estimate in EUR/year, or null when no rate exists for the currency. */
    val eurPerYear: Int?,
    val rateSource: String?,
    val rateAsOfEpochMillis: Long?,
    /** True when a normalised figure is shown; false when only the original is available. */
    val converted: Boolean,
    val note: String,
)

/**
 * How much of the posting actually told us something. A high score built on one known
 * dimension is worth less than a middling score built on six, and the UI must be able to
 * say so. [INSUFFICIENT] means "we cannot judge", which is not the same as "this is poor".
 */
enum class OpportunityConfidence(val label: String) {
    INSUFFICIENT("Not enough evidence to judge"),
    LOW("Low — most benefits not stated"),
    MEDIUM("Medium — some benefits documented"),
    HIGH("High — most benefits documented");

    val isEnough: Boolean get() = this != INSUFFICIENT
}

/** The band a score falls into. Mirrors [InternationalFitBand] so the two read alike. */
enum class ExpatOpportunityBand(val label: String, val shortLabel: String, val emoji: String) {
    INSUFFICIENT("Not enough evidence to judge", "Insufficient", "❔"),
    WEAK("Weak expatriate offer", "Weak", "⚠️"),
    POSSIBLE("Worth a look", "Possible", "🔎"),
    STRONG("A strong expatriate offer", "Strong", "✅"),
    EXCELLENT("An outstanding expatriate offer", "Excellent", "🏆");

    companion object {
        const val EXCELLENT_MIN = 80
        const val STRONG_MIN = 65
        const val POSSIBLE_MIN = 45

        fun fromScore(score: Int): ExpatOpportunityBand = when {
            score >= EXCELLENT_MIN -> EXCELLENT
            score >= STRONG_MIN -> STRONG
            score >= POSSIBLE_MIN -> POSSIBLE
            else -> WEAK
        }
    }
}

/** What one dimension earned, and on what basis. */
@Immutable
data class OpportunityDimensionScore(
    val dimension: OpportunityDimension,
    val points: Int,
    val maxPoints: Int,
    val summary: String,
    val strength: EvidenceStrength,
) {
    val earnedShare: Float get() = if (maxPoints <= 0) 0f else points.toFloat() / maxPoints
}

/**
 * The complete, explainable expatriate assessment for one posting.
 *
 * It is deliberately *separate* from [Job.matchScore] (does the CV fit?), from the
 * eligibility verdict (can the candidate apply at all?), from the scam read, and from
 * [InternationalFitAssessment]. This one weighs the documented expatriate *package* — the
 * money, the housing, the flights, the insurance — and it never lets a high total hide a
 * disqualifying eligibility condition or a high scam risk: such a posting is capped and
 * marked [disqualified].
 */
@Immutable
data class ExpatOpportunity(
    val score: Int,
    val band: ExpatOpportunityBand,
    val dimensions: List<OpportunityDimensionScore>,
    val claims: List<BenefitClaim>,
    val salary: NormalisedSalary?,
    val confidence: OpportunityConfidence,
    val confidenceNote: String,
    /** Hard stops that cap the score: ineligibility, high scam risk, a dead link. */
    val hardStops: List<String>,
    val disqualified: Boolean,
    /** Explainable labels; each is only emitted when its supporting evidence exists. */
    val labels: List<String>,
    val routeSummary: String,
    val explanation: String,
) {
    val isInsufficient: Boolean get() = band == ExpatOpportunityBand.INSUFFICIENT

    /** Dimensions strongest first, so a panel can lead with what carries the score. */
    val rankedDimensions: List<OpportunityDimensionScore>
        get() = dimensions.sortedByDescending { it.points }

    companion object {
        /** The honest empty read, used before a posting has been assessed. */
        val EMPTY = ExpatOpportunity(
            score = 0,
            band = ExpatOpportunityBand.INSUFFICIENT,
            dimensions = emptyList(),
            claims = emptyList(),
            salary = null,
            confidence = OpportunityConfidence.INSUFFICIENT,
            confidenceNote = "Import and analyse your CV, then open a posting to assess its expatriate package.",
            hardStops = emptyList(),
            disqualified = false,
            labels = emptyList(),
            routeSummary = "Open a posting to see the route in from Madagascar.",
            explanation = "There is not enough on the posting yet to judge its expatriate offer.",
        )
    }
}
