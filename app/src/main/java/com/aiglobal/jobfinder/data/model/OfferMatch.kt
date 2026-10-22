package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable
import com.aiglobal.jobfinder.data.jobs.freshness.Freshness
import com.aiglobal.jobfinder.data.jobs.freshness.FreshnessWindow

/**
 * The band a compatibility score falls into.
 *
 * The bands describe *compatibility with the CV*, which is a different question from
 * [ApplicationReadiness] (can I actually apply?), from [ExpatOpportunityBand] (how good is
 * the package?) and from [MatchTier] (the coarse CV-match tier). They are kept apart so a
 * strong compatibility number can never be read as a promise of eligibility.
 */
enum class OfferMatchBand(val label: String, val shortLabel: String, val emoji: String) {
    EXCELLENT("Excellent fit for your CV", "Excellent", "🏆"),
    STRONG("Strong fit for your CV", "Strong", "✅"),
    GOOD("A good fit", "Good", "👍"),
    FAIR("A possible fit", "Possible", "🔎"),
    LOW("A weak fit", "Weak", "•");

    companion object {
        const val EXCELLENT_MIN = 85
        const val STRONG_MIN = 70
        const val GOOD_MIN = 55
        const val FAIR_MIN = 40

        fun fromScore(score: Int): OfferMatchBand = when {
            score >= EXCELLENT_MIN -> EXCELLENT
            score >= STRONG_MIN -> STRONG
            score >= GOOD_MIN -> GOOD
            score >= FAIR_MIN -> FAIR
            else -> LOW
        }
    }
}

/**
 * The six weighted dimensions of the Stage 80 compatibility score. The weights are fixed and
 * documented here, they sum to 100, and every point in the total can be traced back to one of
 * them so the number on a card can always be opened up and read.
 */
enum class OfferMatchDimension(val label: String, val weight: Int) {
    CV_SKILLS_AND_EXPERIENCE("CV skills and experience", 35),
    MADAGASCAR_ELIGIBILITY("Eligibility from Madagascar", 25),
    REQUIRED_EXPERIENCE_AND_QUALIFICATIONS("Experience and qualifications required", 15),
    SECTOR_AND_ROLE_FIT("Sector and role fit", 10),
    VERIFIED_FRESHNESS("Verified publication freshness", 10),
    CONFIRMED_EXPAT_BENEFITS("Confirmed expatriation benefits", 5);

    companion object {
        /** The maximum achievable total; the sum of the six weights, asserted by a test. */
        val total: Int get() = entries.sumOf { it.weight }
    }
}

/**
 * One dimension's contribution to the total, with the sentence explaining how it was earned.
 * [points] is what the posting earned here and [maxPoints] the most it could earn, so the UI
 * shows "24 of 35" rather than an unexplained number.
 */
@Immutable
data class OfferMatchComponent(
    val dimension: OfferMatchDimension,
    val points: Int,
    val maxPoints: Int,
    val summary: String,
    val strength: EvidenceStrength,
) {
    val earnedShare: Float get() = if (maxPoints <= 0) 0f else points.toFloat() / maxPoints
}

/**
 * The complete, explainable Stage 80 compatibility assessment for one posting.
 *
 * It is deliberately *separate* from the app's own [Job.matchScore], from the eligibility
 * verdict and from the expatriate package score. This one answers a single question — "how
 * well does this recent posting fit my CV, and can I realistically apply from Madagascar?" —
 * and it carries its own hard stops so a high compatibility number can never hide a
 * disqualifying condition (a stated local-only restriction, an expired posting, or an
 * unconfirmed expired deadline).
 */
@Immutable
data class OfferMatch(
    val score: Int,
    val band: OfferMatchBand,
    val components: List<OfferMatchComponent>,
    /** CV skills the posting names and the profile shows. */
    val matchedSkills: List<String>,
    /** Skills the posting names that the profile does not show; never invented. */
    val missingSkills: List<String>,
    /** Requirements paired with whether the CV appears to meet them; null means "cannot tell". */
    val qualificationGaps: List<QualificationGap>,
    /** Plain-language reasons the score is what it is, strongest dimension first. */
    val reasons: List<String>,
    /**
     * The blockers that cap the score, in the app's own words. Empty when nothing caps it.
     * A non-empty list means [disqualified] is true and the band is [OfferMatchBand.LOW].
     */
    val hardStops: List<String>,
    val disqualified: Boolean,
    val eligibility: MadagascarEligibility,
    val freshness: Freshness,
    /** True when the source stated a real publication date (never inferred). */
    val hasVerifiedDate: Boolean,
) {
    /** Dimensions strongest first, so a panel can lead with what carries the score. */
    val rankedComponents: List<OfferMatchComponent>
        get() = components.sortedByDescending { it.points }

    companion object {
        /** The honest empty read, used before a posting has been assessed. */
        val EMPTY = OfferMatch(
            score = 0,
            band = OfferMatchBand.LOW,
            components = emptyList(),
            matchedSkills = emptyList(),
            missingSkills = emptyList(),
            qualificationGaps = emptyList(),
            reasons = emptyList(),
            hardStops = emptyList(),
            disqualified = false,
            eligibility = MadagascarEligibility.UNCLEAR,
            freshness = Freshness.UNKNOWN,
            hasVerifiedDate = false,
        )
    }
}

/** The sort applied to the ranked offers, so the choice is visible rather than implicit. */
enum class BestOfferSort(val label: String) {
    COMPATIBILITY("Best match"),
    FRESHNESS("Most recent"),
}

/**
 * Which postings the "My best offers" screen shows. Each filter is a statement about the
 * posting, and each is derived from a field the app already carries — none of them invents a
 * fact. [RECENT] is the only one that leans on a date, and it leans only on a *verified*
 * publication date, so an undated posting is never swept into "recent".
 */
enum class BestOfferFilter(val label: String) {
    ALL("All compatible"),
    RECENT("Recently published"),
    MADAGASCAR_OPEN("Open from Madagascar"),
    WITH_BENEFITS("Confirmed benefits");

    fun matches(offer: RankedOffer, now: Long): Boolean = when (this) {
        ALL -> true
        RECENT -> FreshnessWindow.FOURTEEN_DAYS.includes(offer.job.postedAtEpochMillis, now)
        MADAGASCAR_OPEN -> offer.job.applyableFromMadagascar
        WITH_BENEFITS -> offer.job.benefitEvidence.any { it.status == BenefitStatus.CONFIRMED }
    }
}

/**
 * One posting ranked for the "My best offers" screen: the posting itself, its explainable
 * compatibility read, and the position it holds. [rank] is 1-based and stable for a given
 * feed, so a card can show "#3" without the screen re-deriving the order.
 */
@Immutable
data class RankedOffer(
    val job: Job,
    val rank: Int,
    val match: OfferMatch,
    /** The source's own statement that the posting is still open, surfaced as a reassurance. */
    val availability: AvailabilityResult = AvailabilityResult.UNKNOWN,
) {
    val score: Int get() = match.score
    val band: OfferMatchBand get() = match.band
}
