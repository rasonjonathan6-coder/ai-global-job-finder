package com.aiglobal.jobfinder.data.ai

import com.aiglobal.jobfinder.data.model.AiLanguage
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.NvidiaConfig
import com.aiglobal.jobfinder.data.model.NvidiaResult
import com.aiglobal.jobfinder.data.model.NvidiaStatus
import com.aiglobal.jobfinder.data.model.VisaSupport

/** The kind of writing the user is asking the model to do. */
enum class AssistTask(
    val label: String,
    /** A short description shown under the button so the user knows what each one produces. */
    val blurb: String,
) {
    BEST_OFFER(
        label = "Pick my best offer",
        blurb = "Explains which of your matches to act on first, and why.",
    ),
    EXPLAIN_MATCH(
        label = "Explain my match",
        blurb = "Why this posting fits you, what is missing, and the one step to close the gap.",
    ),
    COVER_LETTER(
        label = "Draft a cover letter",
        blurb = "A letter grounded in your CV and this posting, in your chosen language.",
    ),
    CV_SUMMARY(
        label = "Improve my CV summary",
        blurb = "Rewrites your summary for this posting, without inventing anything.",
    ),
    INTERVIEW_PREP(
        label = "Prepare my interview",
        blurb = "Likely questions for this role and country, with notes built from your CV.",
    ),
    SCAM_REVIEW(
        label = "Review this posting for scams",
        blurb = "Checks the posting against the warning signs, and what to verify.",
    ),
    GAP_PLAN(
        label = "Plan to close my gaps",
        blurb = "Turns the missing requirements into a short, realistic action plan.",
    ),
}

/**
 * The assistant built on top of [NvidiaAiClient]. It owns the prompts and, critically, the
 * consent gate: no request is made unless the user has explicitly turned the assistant on.
 *
 * Every prompt is grounded in [GroundingContext] and instructs the model to use only those
 * facts and to say "not stated" rather than invent. The output is a draft the user edits and
 * sends themselves; nothing is ever submitted automatically. The language is the user's
 * choice ([AiLanguage]), French by default, so a Madagascar candidate reads it natively.
 */
class NvidiaJobAssistant(
    private val client: NvidiaAiClient = NvidiaAiClient(),
) {

    /** Validates the key by asking NVIDIA for the model list. No profile data is sent. */
    suspend fun testConnection(apiKey: String): NvidiaResult = client.validateKey(apiKey)

    /** Lists the model ids the account can use, so the user can pick a real one. */
    suspend fun discoverModels(apiKey: String): List<String> {
        val result = client.listModelIds(apiKey)
        return if (result.isSuccess) result.text.lines().filter { it.isNotBlank() } else emptyList()
    }

    /** Runs the cheapest real inference on the chosen model to prove it can be used. */
    suspend fun testModel(apiKey: String, modelId: String): NvidiaResult =
        client.testModel(apiKey, modelId)

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
        val (system, user) = promptFor(task, context, config.language)
        return client.complete(
            apiKey = config.apiKey,
            modelId = config.modelId,
            systemPrompt = system,
            userPrompt = user,
            maxTokens = tokensFor(task),
        )
    }

    /**
     * The system and user prompts for a task. Split out so the tests can assert the grounding
     * and the language instruction. The system prompt carries the "use only these facts" rule
     * and the requested output language; the user prompt carries the facts and the task.
     */
    internal fun promptFor(
        task: AssistTask,
        context: GroundingContext,
        language: AiLanguage = AiLanguage.FRENCH,
    ): Pair<String, String> {
        val system = "$SYSTEM_GROUNDED ${language.instruction}"
        val user = buildString {
            appendLine(context.candidateBlock())
            appendLine()
            appendLine(context.jobBlock())
            appendLine()
            appendLine(taskInstruction(task))
        }
        return system to user
    }

    /** The task-specific instruction appended after the facts. */
    private fun taskInstruction(task: AssistTask): String = when (task) {
        AssistTask.BEST_OFFER ->
            "The posting above is the single best match found for this candidate. In 3 short " +
                "paragraphs, explain why it is the strongest opportunity for them right now: the " +
                "fit, the realistic route in from Madagascar, and the first action to take this " +
                "week. Be concrete and do not invent facts."
        AssistTask.EXPLAIN_MATCH ->
            "Explain in 4 short paragraphs why this job is or is not a good fit. Name the " +
                "strengths, then the gaps, then one concrete step to close the biggest gap. Do " +
                "not invent experience the candidate does not have."
        AssistTask.COVER_LETTER ->
            "Write a professional cover letter of 250-320 words for this candidate and this " +
                "posting. Use only the facts above. Open with the role and the company, give one " +
                "paragraph on the most relevant experience, one on why this employer and country, " +
                "and close with availability to relocate and interview. Do not invent employers, " +
                "dates, degrees or certifications. If a fact is missing, leave it out rather than " +
                "guessing."
        AssistTask.CV_SUMMARY ->
            "Rewrite the candidate's professional summary as 3-4 sentences aimed at this posting. " +
                "Use only the facts above and keep it truthful. Do not add skills, employers or " +
                "qualifications that are not listed."
        AssistTask.INTERVIEW_PREP ->
            "Prepare this candidate for an interview for this role in this country. Give 6 likely " +
                "questions (mix technical, behavioural and visa/relocation), and for each a short " +
                "note on how this candidate should answer using only their real experience. Add " +
                "3 questions the candidate should ask the employer. Do not invent experience."
        AssistTask.SCAM_REVIEW ->
            "Review this posting for signs of a recruitment scam. Using only the facts above and " +
                "the app's own screening line, list any warning signs you can point to, say what " +
                "the candidate should verify before paying or sharing documents, and give a " +
                "one-line verdict. Do not accuse the employer of fraud; describe what to check."
        AssistTask.GAP_PLAN ->
            "The candidate is missing some of what this posting asks for. Write a realistic, " +
                "week-by-week plan (3 to 4 weeks) to close the most important gaps, using free or " +
                "low-cost steps a candidate in Madagascar can take. Do not promise a certification " +
                "or experience the candidate does not have; frame them as steps to take."
    }

    private fun tokensFor(task: AssistTask): Int = when (task) {
        AssistTask.BEST_OFFER -> 700
        AssistTask.EXPLAIN_MATCH -> 600
        AssistTask.COVER_LETTER -> 900
        AssistTask.CV_SUMMARY -> 400
        AssistTask.INTERVIEW_PREP -> 900
        AssistTask.SCAM_REVIEW -> 600
        AssistTask.GAP_PLAN -> 700
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
            if (job.visaSupport != VisaSupport.NOT_MENTIONED) {
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

    /**
     * The on-device fallback for every task, so a failed or unconfigured remote call still
     * leaves the user something honest and useful, and never a blank panel. Each one is
     * assembled from the posting's own fields; nothing is invented.
     */
    fun localFallback(task: AssistTask, job: Job, profile: CandidateProfile): String = when (task) {
        AssistTask.EXPLAIN_MATCH, AssistTask.BEST_OFFER -> localExplanation(job, profile)
        AssistTask.COVER_LETTER -> localCoverLetter(job, profile)
        AssistTask.CV_SUMMARY -> localSummary(job, profile)
        AssistTask.INTERVIEW_PREP -> localInterviewPrep(job)
        AssistTask.SCAM_REVIEW -> localScamReview(job)
        AssistTask.GAP_PLAN -> localGapPlan(job, profile)
    }

    private fun localCoverLetter(job: Job, profile: CandidateProfile): String = buildString {
        appendLine("On-device draft (no data was sent anywhere) — review and adapt before sending.")
        appendLine()
        appendLine("Dear ${job.company.ifBlank { "Hiring team" }} hiring team,")
        appendLine()
        append("I am applying for the ${job.title} role")
        if (job.country.isNotBlank()) append(" in ${job.country}")
        appendLine(".")
        if (profile.currentTitle.isNotBlank() || profile.skills.isNotEmpty()) {
            append("I am ")
            append(profile.currentTitle.ifBlank { "an experienced technician" })
            profile.yearsOfExperience?.let { append(" with $it years of experience") }
            if (profile.skills.isNotEmpty()) {
                append(", working with ${profile.skills.take(6).joinToString(", ")}")
            }
            appendLine(".")
        }
        if (job.matchedSkills.isNotEmpty()) {
            appendLine()
            appendLine("Your posting asks for ${job.matchedSkills.take(6).joinToString(", ")}, which I use in my current work.")
        }
        appendLine()
        appendLine("I am available to relocate and to interview at your convenience. Thank you for your consideration.")
        appendLine()
        append("Sincerely,")
        if (profile.fullName.isNotBlank()) {
            appendLine()
            append(profile.fullName)
        }
    }.trim()

    private fun localSummary(job: Job, profile: CandidateProfile): String = buildString {
        appendLine("On-device draft (no data was sent anywhere) — review before using.")
        appendLine()
        append(profile.currentTitle.ifBlank { "Experienced technician" })
        profile.yearsOfExperience?.let { append(" with $it years of experience") }
        append(".")
        if (profile.skills.isNotEmpty()) {
            append(" Skilled in ${profile.skills.take(6).joinToString(", ")}.")
        }
        if (job.matchedSkills.isNotEmpty()) {
            append(" Directly relevant to ${job.matchedSkills.take(4).joinToString(", ")}, as this ${job.title} role requires.")
        }
        if (profile.languages.isNotEmpty()) {
            append(" Languages: ${profile.languages.joinToString(", ") { it.displayLabel }}.")
        }
    }.trim()

    private fun localInterviewPrep(job: Job): String = buildString {
        appendLine("On-device preparation (no data was sent anywhere).")
        appendLine()
        appendLine("Likely questions for ${job.title}${if (job.country.isNotBlank()) " in ${job.country}" else ""}:")
        listOf(
            "Walk me through a fault you diagnosed and fixed on your own.",
            "Which equipment in this role have you worked on?",
            "How do you keep preventive maintenance on schedule?",
            "Describe a time you worked safely under pressure.",
            "Why do you want to move to ${job.country.ifBlank { "this country" }}?",
            "Are you ready to relocate, and when could you start?",
        ).forEach { appendLine("• $it") }
        if (job.missingSkills.isNotEmpty()) {
            appendLine()
            appendLine("Be ready to address: ${job.missingSkills.take(4).joinToString(", ")} — say honestly what you know and how you would learn the rest.")
        }
        appendLine()
        appendLine("Ask them:")
        listOf(
            "What does a typical day on this team look like?",
            "How is the role trained and inducted?",
            "What support is there for relocation and the work permit?",
        ).forEach { appendLine("• $it") }
    }.trim()

    private fun localScamReview(job: Job): String = buildString {
        appendLine("On-device screening (no data was sent anywhere).")
        appendLine()
        appendLine("This app's own screening rates this posting: ${job.scamRisk.name.lowercase()} risk.")
        appendLine()
        appendLine("Always check before applying:")
        listOf(
            "A legitimate employer never asks you to pay for a visa, training or equipment.",
            "Verify the company exists on its own official website, not only in the advert.",
            "Be wary of offers made only over chat apps or free email addresses.",
            "Never send passport or bank details before a written, verifiable offer.",
            "A salary far above the normal range for the role is a common lure.",
        ).forEach { appendLine("• $it") }
        if (job.sourceUrl.isNotBlank()) {
            appendLine()
            append("Confirm the link before you apply: ${job.sourceUrl}")
        }
    }.trim()

    private fun localGapPlan(job: Job, profile: CandidateProfile): String = buildString {
        appendLine("On-device plan (no data was sent anywhere).")
        appendLine()
        val gaps = job.missingSkills.take(5)
        if (gaps.isEmpty()) {
            append("Your CV already covers the skills this posting names. Keep your CV current and apply.")
        } else {
            appendLine("Steps to close the gaps this posting names:")
            gaps.forEachIndexed { index, gap ->
                appendLine("${index + 1}. $gap — find a free online course or a short practical task this week, and add the result to your CV.")
            }
            appendLine()
            append("Aim to have at least two of these in progress before you apply; state them honestly as \"in progress\".")
        }
        if (profile.yearsOfExperience == null) {
            appendLine()
            append("Also add your years of experience to your CV — the posting looks for experience and yours is not stated.")
        }
    }.trim()

    private companion object {
        const val SYSTEM_GROUNDED =
            "You are a careful career assistant for a job seeker in Madagascar applying for " +
                "international roles. Use only the facts given to you. Never invent employers, " +
                "dates, degrees, certifications or skills. When something is not stated, say " +
                "\"not stated\" instead of guessing. The user will review and edit everything " +
                "before sending it."

        /** Plain-language label for a visa verdict, matching the badge wording on the job card. */
        fun visaLabel(support: VisaSupport): String = when (support) {
            VisaSupport.FULL_SPONSORSHIP -> "full visa sponsorship"
            VisaSupport.RELOCATION_PACKAGE -> "relocation package"
            VisaSupport.SPONSORSHIP_AVAILABLE -> "sponsorship available"
            VisaSupport.LOCAL_ONLY -> "no sponsorship (local applicants only)"
            VisaSupport.NOT_MENTIONED -> "not stated in the posting"
        }
    }
}
