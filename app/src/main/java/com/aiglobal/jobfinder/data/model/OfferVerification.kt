package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * How reliably one posting can be acted on. Every value is a *stated* outcome, never a
 * guess, and the vocabulary is deliberately the same across all three axes so the UI reads
 * consistently:
 *
 *  - [CONFIRMED] — a fact read from the employer's own text or from the source's own flag.
 *  - [POSSIBLE] — a real signal that stops short of a promise.
 *  - [UNKNOWN] — the app cannot tell, because the evidence is missing. This is the honest
 *    default and is never shown as a negative.
 *  - [EXPIRED] — the app has positive evidence the vacancy is no longer taking applicants.
 *  - [INACCESSIBLE] — the app has positive evidence the posting cannot be reached.
 *  - [NOT_OFFERED] — the employer explicitly states it is *not* provided. Kept apart from
 *    [UNKNOWN] on purpose: "the posting does not mention sponsorship" and "the employer
 *    states it does not sponsor" are different facts, and only the second is a refusal.
 *
 * The critical distinction the whole file enforces: **a successful HTTP response does not
 * prove a vacancy is open, and a working URL does not prove Madagascar applicants are
 * eligible.** Those are separate questions with separate answers.
 */
enum class VerificationState(val label: String) {
    CONFIRMED("Confirmed"),
    POSSIBLE("Possible"),
    UNKNOWN("Unknown"),
    EXPIRED("Expired"),
    INACCESSIBLE("Inaccessible"),
    NOT_OFFERED("Not offered");

    /** True when the app has no evidence either way. Used to keep "unknown" neutral. */
    val isUnknown: Boolean get() = this == UNKNOWN

    /** True when the app has positive evidence the offer is no longer actionable. */
    val isNegative: Boolean get() = this == EXPIRED || this == INACCESSIBLE
}

/**
 * The kind of fact behind one [VerificationFact]. Mirrors the axes the spec asks to be
 * tracked separately, so a single list can carry them all without blurring them.
 */
enum class VerificationAspect(val label: String) {
    SOURCE("Source"),
    APPLICATION_LINK("Application link"),
    VACANCY_ACTIVITY("Vacancy activity"),
    VISA("Visa / sponsorship"),
    RELOCATION("Relocation"),
    FRESHNESS("Verification age"),
}

/**
 * One verifiable statement about a posting: what was checked, what was found, and how
 * strongly it is known. Nothing is shown to the user without one of these behind it, and a
 * finding the app could not make is carried as [EvidenceStrength.UNKNOWN] rather than
 * dropped, so the gap is visible instead of hidden.
 */
@Immutable
@Serializable
data class VerificationFact(
    val aspect: VerificationAspect,
    val strength: EvidenceStrength,
    /** Short headline, e.g. "Visa sponsorship stated". */
    val label: String,
    /** The employer's own words, or a plain explanation when there are none. */
    val detail: String,
    /** Where the finding can be checked: the employer's own posting URL, or empty. */
    val sourceUrl: String = "",
) {
    val isDocumented: Boolean get() = strength == EvidenceStrength.DOCUMENTED
}

/**
 * The full verification read for one posting. It is deliberately separate from
 * [Job.matchScore], [Job.eligibility], [InternationalFitAssessment] and
 * [ExpatBenefitsAssessment]: those answer "does my CV fit?", "can I apply?", "is it worth my
 * time?" and "which benefits does the employer document?". This one answers a different
 * question the candidate must settle before spending effort: **"is this listing still real
 * and reachable, and what did the app actually check?"**
 *
 * Two rules keep it honest:
 *  1. Silence is [VerificationState.UNKNOWN], not a rejection. A posting that never mentions
 *     visa sponsorship is not an employer refusing it.
 *  2. A status is only ever a fact the app observed. Nothing is upgraded to [CONFIRMED] on
 *     the strength of a guess, and nothing is downgraded to [EXPIRED] without evidence.
 */
@Immutable
@Serializable
data class OfferVerification(
    /** Whether the vacancy appears to be taking applicants. */
    val activity: VerificationState,
    /** Whether the application link is present and plausibly usable. */
    val link: VerificationState,
    /** Whether the employer documents a route in for a Madagascar applicant. */
    val visa: VerificationState,
    /** Whether the employer documents relocation support (a separate axis from the visa). */
    val relocation: VerificationState,
    /** When the app last retrieved this posting from the source, or null when never. */
    val lastRetrievedAtEpochMillis: Long?,
    /** Whether the last retrieval is recent enough to rely on. */
    val freshness: VerificationState,
    /** Every finding, official ones first, so the panel can be read top-down. */
    val facts: List<VerificationFact>,
    /** Facts that stand in the way, phrased for the candidate ("Before you apply"). */
    val caveats: List<String>,
    /** One honest sentence summarising the whole read. */
    val summary: String,
) {
    /**
     * True when the app has positive evidence the offer cannot be acted on: the source says
     * it is closed, or the application link is unusable. A known dead end is never allowed
     * to outrank a live posting in a "best fit" sort.
     */
    val isActionable: Boolean get() = !activity.isNegative && !link.isNegative

    /** True when the app has no evidence at all, so the UI shows "unknown" rather than a tick. */
    val isEntirelyUnknown: Boolean
        get() = activity.isUnknown && link.isUnknown && visa.isUnknown && relocation.isUnknown

    companion object {
        /**
         * The honest empty read: nothing was observed, so nothing is claimed. Used before a
         * posting has been assessed and for records restored from an older cache.
         */
        val UNKNOWN = OfferVerification(
            activity = VerificationState.UNKNOWN,
            link = VerificationState.UNKNOWN,
            visa = VerificationState.UNKNOWN,
            relocation = VerificationState.UNKNOWN,
            lastRetrievedAtEpochMillis = null,
            freshness = VerificationState.UNKNOWN,
            facts = emptyList(),
            caveats = emptyList(),
            summary = "This posting has not been checked yet. Open it and confirm the details with the employer.",
        )
    }
}
