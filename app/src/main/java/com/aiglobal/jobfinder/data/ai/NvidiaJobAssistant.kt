package com.aiglobal.jobfinder.data.ai

import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.NvidiaConfig
import com.aiglobal.jobfinder.data.model.NvidiaResult
import com.aiglobal.jobfinder.data.model.NvidiaStatus

/** The kind of writing the user is asking the model to do. */
enum class AssistTask(val label: String) {
    EXPLAIN_MATCH("Explain my match"),
    COVER_LETTER("Draft a cover letter"),
    CV_SUMMARY("Improve my CV summary"),
}

/**
 * The assistant built on top of [NvidiaAiClient]. It owns the prompts and, critically, the
 * consent gate: no request is made unless the user has explicitly turned the assistant on.
 *
 * Every prompt is grounded in [GroundingContext] and instructs the model to use only those
 * facts and to say "not stated" rather than invent. The output is a draft the user edits and
 * sends themselves; nothing is ever submitted automatically.
 */
class NvidiaJobAssistant(
    private val client: NvidiaAiClient = NvidiaAiClient(),
) {

    /** Validates the key by asking NVIDIA for the model list. No profile data is sent. */
    suspend fun testConnection(apiKey: String): NvidiaResult = client.validateKey(apiKey)

    /**
     * Runs [task] for [job] against [profile]. Returns [NvidiaStatus.CONSENT_REQUIRED] when
     * consent has not been granted, and never sends anything in that case.
     */
    suspend fun run(
        config: NvidiaConfig,
        task: AssistTask,
        profile: CandidateProfile,
        job: Job,
    ): NvidiaResult {
        if (!config.consentGranted) {
            return NvidiaResult(
                status = NvidiaStatus.CONSENT_REQUIRED,
                message = "Turn on the AI assistant in Profile → AI assistant before your CV is sent.",
            )
        }
        if (!config.isConfigured) {
            return NvidiaResult(
                status = NvidiaStatus.NOT_CONFIGURED,
                message = "Add your NVIDIA API key in Profile → AI assistant.",
            )
        }
        val context = GroundingContext.of(profile, job)
        val (system, user) = promptFor(task, context)
        return client.complete(
            apiKey = config.apiKey,
            modelId = config.modelId,
            systemPrompt = system,
            userPrompt = user,
            maxTokens = tokensFor(task),
        )
    }

    /** The system and user prompts for a task. Split out so the tests can assert the grounding. */
    internal fun promptFor(task: AssistTask, context: GroundingContext): Pair<String, String> = when (task) {
        AssistTask.EXPLAIN_MATCH -> SYSTEM_GROUNDED to buildString {
            appendLine(context.candidateBlock())
            appendLine()
            appendLine(context.jobBlock())
            appendLine()
            appendLine("Explain in 4 short paragraphs why this job is or is not a good fit. Name the strengths, then the gaps, then one concrete step to close the biggest gap. Do not invent experience the candidate does not have.")
        }
        AssistTask.COVER_LETTER -> SYSTEM_GROUNDED to buildString {
            appendLine(context.candidateBlock())
            appendLine()
            appendLine(context.jobBlock())
            appendLine()
            appendLine("Write a professional cover letter of 250–320 words for this candidate and this posting. Use only the facts above. Open with the role and the company, give one paragraph on the most relevant experience, one on why this employer and country, and close with availability to relocate and interview. Do not invent employers, dates, degrees or certifications. If a fact is missing, leave it out rather than guessing.")
        }
        AssistTask.CV_SUMMARY -> SYSTEM_GROUNDED to buildString {
            appendLine(context.candidateBlock())
            appendLine()
            appendLine(context.jobBlock())
            appendLine()
            appendLine("Rewrite the candidate's professional summary as 3–4 sentences aimed at this posting. Use only the facts above and keep it truthful. Do not add skills, employers or qualifications that are not listed.")
        }
    }

    private fun tokensFor(task: AssistTask): Int = when (task) {
        AssistTask.EXPLAIN_MATCH -> 600
        AssistTask.COVER_LETTER -> 900
        AssistTask.CV_SUMMARY -> 400
    }

    /**
     * A deterministic, on-device explanation of the match. It is shown when the assistant is
     * off or the request fails, so the feature is never a dead end and the app never depends
     * on the network to explain a score it computed locally.
     */
    fun localExplanation(job: Job, profile: CandidateProfile): String {
        val strengths = buildList {
            if (job.matchedSkills.isNotEmpty()) {
                add("Your CV already shows ${job.matchedSkills.take(6).joinToString(", ")}, which this posting asks for.")
            }
            if (job.matchScore >= 75) add("The overall match is strong at ${job.matchScore}%.")
            if (job.visaSupport != com.aiglobal.jobfinder.data.model.VisaSupport.NOT_MENTIONED) {
                add("Visa / relocation: ${visaLabel(job.visaSupport)}.")
            }
        }
        val gaps = buildList {
            if (job.missingSkills.isNotEmpty()) {
                add("The posting also mentions ${job.missingSkills.take(6).joinToString(", ")}, which your CV does not show yet.")
            }
            if (profile.yearsOfExperience == null) add("Your CV does not state your years of experience.")
        }
        return buildString {
            appendLine("On-device explanation (no data was sent anywhere).")
            appendLine()
            appendLine("Match: ${job.matchScore}% for ${job.title} at ${job.company}.")
            if (strengths.isNotEmpty()) {
                appendLine()
                appendLine("Why it fits:")
                strengths.forEach { appendLine("• $it") }
            }
            if (gaps.isNotEmpty()) {
                appendLine()
                appendLine("What is missing or unclear:")
                gaps.forEach { appendLine("• $it") }
            }
            if (strengths.isEmpty() && gaps.isEmpty()) {
                appendLine()
                append("Your CV and this posting share few explicit signals; treat the score as a starting point.")
            }
        }.trim()
    }

    private companion object {
        const val SYSTEM_GROUNDED =
            "You are a careful career assistant for a job seeker in Madagascar applying for " +
                "international roles. Use only the facts given to you. Never invent employers, " +
                "dates, degrees, certifications or skills. When something is not stated, say " +
                "\"not stated\" instead of guessing. Write in clear, plain English. The user " +
                "will review and edit everything before sending it."

        /** Plain-language label for a visa verdict, matching the badge wording on the job card. */
        fun visaLabel(support: com.aiglobal.jobfinder.data.model.VisaSupport): String = when (support) {
            com.aiglobal.jobfinder.data.model.VisaSupport.FULL_SPONSORSHIP -> "full visa sponsorship"
            com.aiglobal.jobfinder.data.model.VisaSupport.RELOCATION_PACKAGE -> "relocation package"
            com.aiglobal.jobfinder.data.model.VisaSupport.SPONSORSHIP_AVAILABLE -> "sponsorship available"
            com.aiglobal.jobfinder.data.model.VisaSupport.LOCAL_ONLY -> "no sponsorship (local applicants only)"
            com.aiglobal.jobfinder.data.model.VisaSupport.NOT_MENTIONED -> "not stated in the posting"
        }
    }
}
