package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable

/** What an evidence item is about. */
enum class EvidenceKind(val label: String) {
    ELIGIBILITY("Eligibility"),
    VISA("Visa / sponsorship"),
    RELOCATION("Relocation"),
    SALARY("Salary"),
    REQUIREMENT("Requirement"),
    BENEFIT("Benefit"),
}

/**
 * How much weight an evidence item carries. The distinction between [DOCUMENTED] and
 * [INFERRED] is the whole point: a claim read from the employer's own words is shown
 * differently from a heuristic the app worked out, and an unknown is never dressed up as
 * a fact.
 */
enum class EvidenceStrength(val label: String) {
    /** Stated by the employer, quoted from the posting. */
    DOCUMENTED("Documented"),
    /** The employer explicitly says it is not offered. */
    REFUSED("Stated as not offered"),
    /** The app worked it out from signals; the posting does not say it outright. */
    INFERRED("Inferred"),
    /** Mentioned but not committed to; needs checking. */
    NEEDS_VERIFICATION("Needs verification"),
    /** The posting does not say. Shown so the gap is visible, not hidden. */
    UNKNOWN("Not stated"),
}

/**
 * One verifiable statement about a posting: the finding, the sentence it came from, and a
 * link back to the source so the user can read it in context. Nothing is shown to the user
 * without one of these behind it.
 */
@Immutable
data class Evidence(
    val kind: EvidenceKind,
    val strength: EvidenceStrength,
    /** Short headline, e.g. "Visa sponsorship". */
    val label: String,
    /** The employer's own words, or a plain explanation when there are none. */
    val detail: String,
    /** Where the finding can be checked. The posting URL, or empty when there is none. */
    val sourceUrl: String = "",
) {
    /** True when the finding rests on the employer's own text. */
    val isDocumented: Boolean get() = strength == EvidenceStrength.DOCUMENTED
}

/** One requirement from a posting paired with whether the candidate's profile meets it. */
@Immutable
data class QualificationGap(
    val label: String,
    val evidence: String,
    /** True when the app could confirm the candidate meets it; null when it cannot tell. */
    val met: Boolean?,
)

/**
 * A qualification the posting appears to require. [evidence] is the sentence it was read
 * from, so the user can check the employer's own words instead of trusting the app.
 */
@Immutable
data class JobRequirement(
    val label: String,
    /** The sentence that states the requirement. */
    val evidence: String,
)

/** What a posting appears to require, extracted from its own text. */
@Immutable
data class JobRequirements(
    val minYears: Int? = null,
    val certifications: List<JobRequirement> = emptyList(),
    /** Licence/permit requirements that are common for trades roles abroad. */
    val licences: List<JobRequirement> = emptyList(),
) {
    val isEmpty: Boolean
        get() = minYears == null && certifications.isEmpty() && licences.isEmpty()
}
