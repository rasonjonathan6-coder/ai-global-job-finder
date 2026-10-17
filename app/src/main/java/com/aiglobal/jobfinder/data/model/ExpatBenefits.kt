package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable

/**
 * The kind of expat benefit a posting can document. This is deliberately separate from
 * [BenefitCategory] (which structures the per-country settlement guide): these are the
 * concrete things an employer states in a listing, and they are what the user configures
 * priorities for.
 */
@Immutable
data class BenefitType(
    val key: String,
    val label: String,
    val shortLabel: String,
    val emoji: String,
    /** A weight of 3 means the user cannot apply without it; 0 means they do not care. */
    val defaultPriority: Int,
)

/**
 * The fixed benefit vocabulary. Keys are stable identifiers persisted in DataStore, so
 * they must not be renamed without a migration.
 */
object JobBenefitTypes {
    val VISA_SPONSORSHIP = BenefitType("visa_sponsorship", "Visa sponsorship", "Visa", "🛂", defaultPriority = 3)
    /**
     * A work permit or work authorisation is a distinct thing from visa sponsorship: an
     * employer can help you obtain the right to work without sponsoring a visa, and the
     * candidate needs to see which of the two a listing actually promises. Kept separate
     * rather than folded into [VISA_SPONSORSHIP] so the two are never blurred.
     */
    val WORK_PERMIT = BenefitType("work_permit", "Work permit support", "Work permit", "📄", defaultPriority = 2)
    val RELOCATION_SUPPORT = BenefitType("relocation_support", "Relocation support", "Relocation", "📦", defaultPriority = 2)
    val ACCOMMODATION = BenefitType("accommodation", "Accommodation provided", "Housing", "🏠", defaultPriority = 2)
    val FLIGHTS_HOME = BenefitType("flights_home", "Annual flights home", "Flights", "✈️", defaultPriority = 1)
    val HEALTH_INSURANCE = BenefitType("health_insurance", "Health insurance", "Health cover", "🏥", defaultPriority = 1)
    /**
     * Paid leave / annual leave is a core contract term for an expat posting — often the
     * deciding factor for a rotation or a family move — so it is tracked as its own benefit
     * rather than left implicit. It is read from the posting text like the others.
     */
    val PAID_LEAVE = BenefitType("paid_leave", "Paid leave / annual leave", "Leave", "🌴", defaultPriority = 1)
    val MEALS = BenefitType("meals", "Meals provided", "Meals", "🍽️", defaultPriority = 0)
    val TRANSPORT = BenefitType("transport", "Transport / allowance", "Transport", "🚌", defaultPriority = 0)
    val LANGUAGE_TRAINING = BenefitType("language_training", "Language training", "Language", "🗣️", defaultPriority = 0)
    val FAMILY_SUPPORT = BenefitType("family_support", "Family / dependants support", "Family", "👨‍👩‍👧", defaultPriority = 1)

    /**
     * Special-case benefit: it is not read from the listing text but from the advertised
     * salary, so it is scored only when the user sets a minimum. It is kept out of
     * [all] so the extractor never tries to phrase-match it.
     */
    val MINIMUM_SALARY = BenefitType("minimum_salary", "Salary meets your minimum", "Salary", "💰", defaultPriority = 2)

    /** Stable display order for the benefits read from the posting text. */
    val all: List<BenefitType> = listOf(
        VISA_SPONSORSHIP,
        WORK_PERMIT,
        RELOCATION_SUPPORT,
        ACCOMMODATION,
        FLIGHTS_HOME,
        HEALTH_INSURANCE,
        PAID_LEAVE,
        FAMILY_SUPPORT,
        MEALS,
        TRANSPORT,
        LANGUAGE_TRAINING,
    )

    /** Everything the user can weight, including the salary gate. */
    val extractable: List<BenefitType> = all

    /** Every selectable benefit, used by the preference editor. */
    val selectable: List<BenefitType> = all + MINIMUM_SALARY

    fun byKey(key: String): BenefitType? = selectable.firstOrNull { it.key == key }
}

/**
 * What a posting actually says about one benefit.
 *
 * [UNKNOWN] and [NEEDS_VERIFICATION] are the important ones: they mean "the listing did
 * not tell us", not "the employer refuses". The UI must never present them as a negative
 * statement about the employer.
 */
enum class BenefitStatus(val label: String) {
    CONFIRMED("Documented"),
    NOT_OFFERED("Stated as not offered"),
    NEEDS_VERIFICATION("Mentioned — needs verification"),
    UNKNOWN("Not stated"),
}

/** How much one documented benefit is worth to the user. */
enum class BenefitPriority(val weight: Int, val label: String) {
    NOT_IMPORTANT(0, "Not important"),
    NICE_TO_HAVE(1, "Nice to have"),
    IMPORTANT(2, "Important"),
    ESSENTIAL(3, "Essential");

    companion object {
        fun fromWeight(weight: Int): BenefitPriority =
            entries.minByOrNull { kotlin.math.abs(it.weight - weight) } ?: IMPORTANT
    }
}

/**
 * One benefit finding, carrying the evidence it was derived from. [evidence] is the
 * sentence (or phrase) in the posting that produced the status, so the user can read the
 * employer's own words instead of trusting a score.
 *
 * [source] and [verifiedAtEpochMillis] record *where* and *when* that evidence was read.
 * They exist so a benefit can be re-checked later: a claim read from a listing months ago
 * is not the same as one read today, and a stale claim must be shown as stale rather than
 * presented as current. Both default to "not recorded" so a caller that has no provenance
 * (a hand-built test finding, say) is honest about the gap instead of inventing one.
 */
@Immutable
data class BenefitEvidence(
    val type: BenefitType,
    val status: BenefitStatus,
    val evidence: String,
    /** Where the evidence came from, e.g. the source name; blank when not recorded. */
    val source: String = "",
    /** When the evidence was read from the source, in epoch millis; null when not recorded. */
    val verifiedAtEpochMillis: Long? = null,
) {
    /** True when the finding rests on something the posting actually said. */
    val isDocumented: Boolean
        get() = status == BenefitStatus.CONFIRMED ||
            status == BenefitStatus.NOT_OFFERED ||
            status == BenefitStatus.NEEDS_VERIFICATION

    /** True when the finding carries enough provenance to be re-checked later. */
    val hasProvenance: Boolean
        get() = source.isNotBlank() && verifiedAtEpochMillis != null

    /**
     * True when the evidence is older than [staleDays]. A finding with no recorded
     * verification date is not stale — it is unverifiable, which is a different thing the
     * caller must handle separately (see [hasProvenance]).
     */
    fun isStale(now: Long, staleDays: Int = DEFAULT_STALE_DAYS): Boolean {
        val verified = verifiedAtEpochMillis ?: return false
        if (verified > now) return false
        return (now - verified) / DAY_MS > staleDays
    }

    companion object {
        const val DEFAULT_STALE_DAYS = 30
        private const val DAY_MS = 86_400_000L
    }
}

/**
 * The candidate's own expat priorities. These drive the Expat Benefits Score: the score
 * measures how well a posting's *documented* benefits line up with what this user cares
 * about, and nothing else.
 */
@Immutable
data class ExpatPreferences(
    /** Benefit key → priority weight (0..3). Missing keys fall back to the type default. */
    val priorities: Map<String, Int> = emptyMap(),
    /** Postings below this advertised salary are flagged. Null means "no minimum set". */
    val minimumSalaryEur: Int? = null,
    /** Countries the user wants to target. Empty means "no country preference". */
    val targetCountries: Set<String> = emptySet(),
    /** Hide postings whose Expat Benefits Score is below this. 0 disables the filter. */
    val minExpatScore: Int = 0,
    /** Hide postings whose CV match score is below this. 0 disables the filter. */
    val minMatchScore: Int = 0,
    /** When true, only postings where the employer documents visa sponsorship are shown. */
    val requireVisaSponsorship: Boolean = false,
) {
    fun priorityOf(type: BenefitType): BenefitPriority =
        BenefitPriority.fromWeight(priorities[type.key] ?: type.defaultPriority)

    /** Only the benefits the user actually cares about, used for the breakdown. */
    val weightedTypes: List<BenefitType>
        get() = JobBenefitTypes.all.filter { priorityOf(it).weight > 0 }

    companion object {
        /** The starting point before the user changes anything. */
        fun defaults(): ExpatPreferences = ExpatPreferences(
            priorities = JobBenefitTypes.all.associate { it.key to it.defaultPriority },
        )
    }
}

/** How one benefit contributed to the Expat Benefits Score. */
@Immutable
data class BenefitScoreComponent(
    val type: BenefitType,
    val priority: BenefitPriority,
    val status: BenefitStatus,
    val evidence: String,
    /** 1.0 documented, 0.5 mentioned-but-unverified, 0.0 not offered. */
    val credit: Float,
    /** Priority weight this component could contribute if documented. */
    val potentialPoints: Int,
    /** Priority weight × credit, the points actually earned. */
    val earnedPoints: Int,
)

/**
 * The full Expat Benefits Score result. It is kept deliberately separate from the CV
 * match score and the eligibility verdict: a posting can match the CV perfectly and still
 * document few benefits, and the two numbers must never be conflated.
 */
@Immutable
data class ExpatBenefitsAssessment(
    /** 0..100 over the benefits the user cares about. */
    val score: Int,
    /** Documented evidence coverage: how much of the listing actually told us something. */
    val evidenceCoverage: Int,
    val components: List<BenefitScoreComponent>,
    /** One-line, honest explanation of the number. */
    val explanation: String,
    /** Benefits the user marked essential that the posting does not document. */
    val unmetEssentials: List<BenefitType>,
    /** True when coverage is too low for the score to be trusted. */
    val lowEvidence: Boolean,
) {
    /** True when the posting documented at least one benefit; otherwise the score is meaningless. */
    val hasEvidence: Boolean
        get() = components.any { it.status != BenefitStatus.UNKNOWN }

    companion object {
        val EMPTY = ExpatBenefitsAssessment(
            score = 0,
            evidenceCoverage = 0,
            components = emptyList(),
            explanation = "Import and analyse your CV, then open a posting to score its benefits.",
            unmetEssentials = emptyList(),
            lowEvidence = true,
        )
    }
}
