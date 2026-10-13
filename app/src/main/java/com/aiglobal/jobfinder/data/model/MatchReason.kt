package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable

/** Direction of a single matching signal. */
enum class MatchSignal { POSITIVE, NEUTRAL, NEGATIVE }

/**
 * One reason behind the match score. The UI shows these verbatim so the number is
 * never a black box: every point of the score can be traced to a signal.
 */
@Immutable
data class MatchReason(
    val label: String,
    val detail: String,
    val signal: MatchSignal,
    /** Points this signal contributed (positive) or withheld (negative). */
    val weight: Int,
)

/**
 * One reason behind an eligibility verdict. [official] marks a fact taken from the
 * posting or the employer's own wording; everything else is a heuristic and is
 * labelled as such so the user never mistakes an inference for a guarantee.
 */
@Immutable
data class EligibilityReason(
    val label: String,
    val detail: String,
    val official: Boolean,
)

/** What a matching signal needs in order to be evaluated. */
@Immutable
data class MatchWeights(
    val skills: Int = 34,
    val title: Int = 24,
    val experience: Int = 16,
    val location: Int = 12,
    val language: Int = 8,
    val recency: Int = 6,
) {
    val total: Int get() = skills + title + experience + location + language + recency
}
