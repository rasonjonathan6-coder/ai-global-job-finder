package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable

/** The kind of interview question, used to group the rehearsal list. */
enum class InterviewCategory(val label: String, val emoji: String) {
    OPENING("Opening", "👋"),
    EXPERIENCE("Your experience", "🧰"),
    TECHNICAL("Technical", "🔧"),
    BEHAVIOURAL("Behavioural", "🧠"),
    RELOCATION("Relocation & visa", "🛂"),
    LANGUAGE("Language check", "🗣️"),
    SALARY("Salary & contract", "💰"),
    ASK_THEM("Questions to ask them", "❓"),
}

/**
 * One rehearsal question, always tied to this posting and this CV.
 *
 * [structure] is the STAR-style skeleton the user should fill in, [grounding] lists the
 * CV/posting facts the answer can lean on, and [missingFacts] lists what the CV does not
 * yet provide — so the coach never invents an achievement the candidate did not have.
 */
@Immutable
data class InterviewQuestion(
    val id: String,
    val category: InterviewCategory,
    val prompt: String,
    val whyItMatters: String,
    val structure: List<String>,
    val grounding: List<String> = emptyList(),
    val missingFacts: List<String> = emptyList(),
)

/** The full rehearsal set for one application. */
@Immutable
data class InterviewPrep(
    val jobId: String,
    val jobTitle: String,
    val company: String,
    val country: String,
    val countryFlag: String,
    val questions: List<InterviewQuestion>,
    /** One-line pointers on what to prioritise, derived from the match gaps. */
    val focusAreas: List<String>,
    val generatedAtEpochMillis: Long,
) {
    val questionCount: Int get() = questions.size
    fun grouped(): Map<InterviewCategory, List<InterviewQuestion>> =
        questions.groupBy { it.category }.toSortedMap(compareBy { it.ordinal })
}

/** How the candidate rated their own attempt. Feeds the readiness score. */
enum class AnswerConfidence(val label: String) {
    NOT_READY("Needs work"),
    GETTING_THERE("Getting there"),
    CONFIDENT("Confident"),
}

/** A saved rehearsal attempt. Kept so the readiness score is derived, never guessed. */
@Immutable
@kotlinx.serialization.Serializable
data class InterviewAttempt(
    val questionId: String,
    val confidence: AnswerConfidence,
    val atEpochMillis: Long,
)

/**
 * Deterministic feedback on a written answer. Flags the things interviewers penalise:
 * answers that are too thin, have no evidence, or never mention an outcome.
 */
@Immutable
data class AnswerFeedback(
    val score: Int,
    val headline: String,
    val strengths: List<String>,
    val improvements: List<String>,
)

/** Progress through a rehearsal set, derived from the saved attempts. */
@Immutable
data class InterviewReadiness(
    val attempted: Int,
    val total: Int,
    val confident: Int,
) {
    val percent: Int get() = if (total == 0) 0 else (attempted * 100 / total)
    val isComplete: Boolean get() = total > 0 && attempted >= total
    val label: String get() = when {
        total == 0 -> "Not started"
        percent >= 100 && confident == total -> "Interview ready"
        percent >= 100 -> "Rehearsed — polish the weak answers"
        percent >= 50 -> "Half rehearsed"
        else -> "Just started"
    }
}
