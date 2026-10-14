package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable

/** Which way a scam signal points. */
enum class ScamSignalTone { DANGER, CAUTION, REASSURANCE }

/** One concrete observation about a posting, always quotable and always explained. */
@Immutable
data class ScamSignal(
    val label: String,
    val detail: String,
    /** The exact text in the posting that triggered this, or empty for structural checks. */
    val evidence: String,
    val tone: ScamSignalTone,
)

/** How safe a posting looks after scanning. Ordered so it can be compared directly. */
enum class ScamVerdict(val label: String, val emoji: String) {
    LIKELY_SAFE("Likely safe", "✅"),
    NEEDS_CAUTION("Check before applying", "⚠️"),
    HIGH_RISK("High scam risk", "⛔"),
}

/**
 * The result of scanning one posting. [verdict] is derived from the signals, and the
 * signals are shown verbatim so the user can judge for themselves — the app advises,
 * it never blocks.
 */
@Immutable
data class ScamReport(
    val jobId: String,
    val jobTitle: String,
    val company: String,
    val risk: ScamRisk,
    val verdict: ScamVerdict,
    val signals: List<ScamSignal>,
    val checkedAtEpochMillis: Long,
) {
    val dangers: List<ScamSignal> get() = signals.filter { it.tone == ScamSignalTone.DANGER }
    val cautions: List<ScamSignal> get() = signals.filter { it.tone == ScamSignalTone.CAUTION }
    val reassurances: List<ScamSignal> get() = signals.filter { it.tone == ScamSignalTone.REASSURANCE }
    /** A 0–100 confidence that the posting is legitimate. Never shown as a guarantee. */
    val safetyScore: Int
        get() = (100 - dangers.size * 28 - cautions.size * 11).coerceIn(0, 100)
}
