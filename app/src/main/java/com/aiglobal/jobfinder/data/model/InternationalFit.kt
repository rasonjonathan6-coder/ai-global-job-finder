package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable

/**
 * How well one posting fits this candidate **as an international applicant**, as a band rather
 * than only a number. It answers a question the three existing numbers cannot answer alone:
 * "is this role worth my time from Madagascar, and where exactly do I stand?".
 *
 * [INSUFFICIENT] is not a low score — it means the profile is too thin for the number to mean
 * anything, and it is set explicitly by the engine, never derived from a low score.
 */
enum class InternationalFitBand(val label: String, val shortLabel: String, val emoji: String) {
    INSUFFICIENT("Not enough profile to judge", "Insufficient", "❔"),
    WEAK("A stretch for you", "Stretch", "⚠️"),
    POSSIBLE("Worth a look", "Possible", "🔎"),
    STRONG("A strong international fit", "Strong", "✅"),
    EXCELLENT("One of your best fits", "Excellent", "🏆"),
    ;

    /** True for the bands the candidate should spend time on now. */
    val isActionable: Boolean get() = this == STRONG || this == EXCELLENT

    companion object {
        const val EXCELLENT_MIN = 80
        const val STRONG_MIN = 65
        const val POSSIBLE_MIN = 45

        /**
         * Maps a score to a band. A low score is [WEAK], never [INSUFFICIENT]: the latter is
         * reserved for "we cannot tell", which is a different statement from "this is a stretch".
         */
        fun fromScore(score: Int): InternationalFitBand = when {
            score >= EXCELLENT_MIN -> EXCELLENT
            score >= STRONG_MIN -> STRONG
            score >= POSSIBLE_MIN -> POSSIBLE
            else -> WEAK
        }
    }
}

/**
 * One ingredient of the International Fit Score. [points] is what the posting earned here and
 * [maxPoints] the most it could earn, so the total can be opened up and every point traced.
 * [strength] says whether the finding rests on the employer's own words, on an app inference,
 * or on silence.
 */
@Immutable
data class InternationalFitComponent(
    val label: String,
    val detail: String,
    val strength: EvidenceStrength,
    val points: Int,
    val maxPoints: Int,
) {
    /** Share of the available points earned here, used to rank strengths and weaknesses. */
    val earnedShare: Float get() = if (maxPoints <= 0) 0f else points.toFloat() / maxPoints
}

/**
 * The full International Fit result for one posting.
 *
 * It is deliberately a *separate* number from [Job.matchScore] (does the CV fit?), from
 * [Job.eligibility] (can the candidate apply?) and from [ExpatBenefitsAssessment] (does the
 * employer document the benefits the user wants?). Those three stay as they are; this one
 * folds them together with the destination, language and credential facts the candidate has to
 * clear to actually move, and it keeps the documented expat-benefit score visible beside it so
 * the two are never conflated.
 *
 * Two rules keep it honest, matching the rest of the pipeline:
 *  1. Silence is neutral, not negative. A posting that does not state a requirement neither
 *     earns nor loses the points for it, and the component is marked [EvidenceStrength.UNKNOWN].
 *  2. When the profile is too thin to judge, the band is [InternationalFitBand.INSUFFICIENT]
 *     and the explanation says so rather than showing a confident number built on nothing.
 */
@Immutable
data class InternationalFitAssessment(
    val score: Int,
    val band: InternationalFitBand,
    val components: List<InternationalFitComponent>,
    /** Facts from the posting that stand in the way, official ones first. */
    val blockers: List<String>,
    /** One honest sentence about the route in from Madagascar, or its absence. */
    val routeSummary: String,
    /** The documented Expat Benefits Score, shown beside this score, never blended into it. */
    val expatBenefitsScore: Int?,
    val expatEvidenceCoverage: Int,
    val expatLowEvidence: Boolean,
    val explanation: String,
) {
    val isInsufficient: Boolean get() = band == InternationalFitBand.INSUFFICIENT

    /** Components ordered strongest first, so the panel can lead with what carries the score. */
    val rankedComponents: List<InternationalFitComponent>
        get() = components.sortedByDescending { it.points }

    companion object {
        val EMPTY = InternationalFitAssessment(
            score = 0,
            band = InternationalFitBand.INSUFFICIENT,
            components = emptyList(),
            blockers = emptyList(),
            routeSummary = "Import and analyse your CV, then open a posting to see your international fit.",
            expatBenefitsScore = null,
            expatEvidenceCoverage = 0,
            expatLowEvidence = true,
            explanation = "There is not enough on your profile yet to judge this posting.",
        )
    }
}
