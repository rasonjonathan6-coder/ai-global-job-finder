package com.aiglobal.jobfinder.data.interview

import com.aiglobal.jobfinder.data.model.AnswerConfidence
import com.aiglobal.jobfinder.data.model.AnswerFeedback
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.InterviewAttempt
import com.aiglobal.jobfinder.data.model.InterviewPrep
import com.aiglobal.jobfinder.data.model.InterviewQuestion
import com.aiglobal.jobfinder.data.model.InterviewReadiness
import com.aiglobal.jobfinder.data.model.Job

/**
 * The interview coach. Builds the rehearsal set for a posting and grades a written
 * answer against the same structure the question asked for.
 *
 * Grading is deliberately about shape, not content: the app cannot know whether an
 * anecdote is true, so it only checks whether the answer is long enough to be credible,
 * whether it names evidence, and whether it reaches a result. It never rewrites the
 * candidate's story.
 */
class InterviewCoach(
    private val bank: InterviewQuestionBank = InterviewQuestionBank(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun prep(job: Job, profile: CandidateProfile): InterviewPrep = InterviewPrep(
        jobId = job.id,
        jobTitle = job.title,
        company = job.company,
        country = job.country,
        countryFlag = job.countryFlag,
        questions = bank.questions(job, profile),
        focusAreas = bank.focusAreas(job, profile),
        generatedAtEpochMillis = clock(),
    )

    /** Grades one written answer. The score is 0–100 and the notes are all actionable. */
    fun feedback(question: InterviewQuestion, answer: String): AnswerFeedback {
        val text = answer.trim()
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val wordCount = words.size

        val strengths = mutableListOf<String>()
        val improvements = mutableListOf<String>()
        var score = 0

        // Length: enough to be credible, not so long it rambles.
        when {
            wordCount >= 60 -> { score += 35; strengths += "Your answer is detailed enough to be credible ($wordCount words)." }
            wordCount >= 30 -> { score += 25; strengths += "A solid length ($wordCount words); expand it with one more detail to be safe." }
            wordCount >= 12 -> { score += 12; improvements += "Only $wordCount words. Aim for 60–120 words so the interviewer gets a full picture." }
            else -> improvements += "This is too short to answer the question. Write at least a few sentences."
        }

        // Evidence: numbers and named tools/employers.
        val hasNumbers = NUMBER.containsMatchIn(text)
        val hasEvidenceWords = EVIDENCE_WORDS.any { text.contains(it, ignoreCase = true) }
        when {
            hasNumbers && hasEvidenceWords -> { score += 25; strengths += "You back the answer with specifics and figures." }
            hasNumbers || hasEvidenceWords -> { score += 15; improvements += "Add one more concrete detail — a number, a tool or an employer name." }
            else -> improvements += "No specifics detected. Name the tools you used and add one measurable result."
        }

        // Structure: the STAR pieces the question asked for.
        val matchedStructure = question.structure.count { step ->
            STRUCTURE_HINTS.any { hint -> text.contains(hint, ignoreCase = true) } && step.isNotBlank()
        }
        if (question.structure.isNotEmpty()) {
            when {
                matchedStructure >= 3 -> { score += 25; strengths += "You cover the situation, the action and the result." }
                matchedStructure == 2 -> { score += 14; improvements += "You cover two of the four parts. Add the missing result or situation." }
                else -> improvements += "Follow the ${question.structure.size}-part structure listed for this question."
            }
        } else {
            score += 15
        }

        // Outcome: does the answer actually land on a result?
        if (RESULT_WORDS.any { text.contains(it, ignoreCase = true) }) {
            score += 15
            strengths += "You state an outcome, which is what interviewers remember."
        } else {
            improvements += "Finish with the result: what changed because of what you did?"
        }

        val finalScore = score.coerceIn(0, 100)
        val headline = when {
            finalScore >= 80 -> "Strong answer — ready to say out loud."
            finalScore >= 60 -> "Good base — tighten the evidence and the ending."
            finalScore >= 35 -> "Workable, but thin. Add structure and specifics."
            else -> "Needs a rewrite. Follow the structure below step by step."
        }
        if (improvements.isEmpty()) improvements += "Nothing critical missing — rehearse it out loud and time yourself."

        return AnswerFeedback(
            score = finalScore,
            headline = headline,
            strengths = strengths,
            improvements = improvements,
        )
    }

    /** Derives progress from the saved attempts, so readiness is never guessed. */
    fun readiness(questions: List<InterviewQuestion>, attempts: List<InterviewAttempt>): InterviewReadiness {
        val attempted = attempts.map { it.questionId }.distinct().count { id -> questions.any { it.id == id } }
        val confident = attempts.count { it.confidence == AnswerConfidence.CONFIDENT }
        return InterviewReadiness(attempted = attempted, total = questions.size, confident = confident)
    }

    private companion object {
        val NUMBER = Regex("\\d")
        val EVIDENCE_WORDS = listOf("used", "built", "led", "managed", "installed", "repaired", "tested", "delivered", "team", "project")
        val STRUCTURE_HINTS = listOf(
            "situation", "task", "action", "result", "because", "so", "then", "after", "first", "finally", "when", "while",
        )
        val RESULT_WORDS = listOf("result", "outcome", "increased", "reduced", "saved", "improved", "delivered", "achieved", "so we", "which meant")
    }
}
