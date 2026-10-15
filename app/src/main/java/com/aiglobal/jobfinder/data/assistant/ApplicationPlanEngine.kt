package com.aiglobal.jobfinder.data.assistant

import com.aiglobal.jobfinder.data.model.ApplicationPlan
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.CvTailoring
import com.aiglobal.jobfinder.data.model.DocumentRequirement
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobBenefitTypes
import com.aiglobal.jobfinder.data.model.LetterLanguage
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.PlanStep
import com.aiglobal.jobfinder.data.model.PlanStepKind
import com.aiglobal.jobfinder.data.model.PlanStepStatus
import com.aiglobal.jobfinder.data.model.ReadinessBlocker
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.ScamVerdict
import com.aiglobal.jobfinder.data.model.ShortlistEntry
import com.aiglobal.jobfinder.data.model.TailoringHighlight
import com.aiglobal.jobfinder.data.model.VisaSupport

/**
 * Turns the app's separate findings about one posting into a single, ordered application
 * plan: what is already in place, what is still to do, what must be cleared first, and the
 * one next action worth taking.
 *
 * The engine invents nothing. Every step is derived from evidence the app already holds —
 * the posting's own text, the readiness blockers from [ShortlistEngine], the candidate's CV
 * and the state of the other tools (cover letter, interview rehearsal, scam scan). Where a
 * fact is unknown, the step says so and asks the employer rather than assuming a refusal.
 */
class ApplicationPlanEngine(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun build(
        job: Job,
        entry: ShortlistEntry,
        profileCompletenessPercent: Int,
        hasCoverLetter: Boolean,
        interviewAttempted: Int = 0,
        interviewTotal: Int = 0,
        scamVerdict: ScamVerdict? = null,
        hasApplied: Boolean = false,
    ): ApplicationPlan {
        val tailoring = tailoringFor(job)
        val routeSummary = routeSummaryFor(job)

        val steps = listOfNotNull(
            profileStep(entry, profileCompletenessPercent),
            visaStep(job, entry),
            tailorStep(job, tailoring, entry),
            coverLetterStep(job, hasCoverLetter),
            scamStep(job, scamVerdict, entry),
            interviewStep(interviewAttempted, interviewTotal),
            submitStep(job, hasApplied),
            followUpStep(hasApplied),
        ).sortedBy { it.kind.ordinal }

        val nextAction = steps.firstOrNull { it.isBlocked && it.action.isNotBlank() }?.action
            ?: steps.firstOrNull { it.status == PlanStepStatus.TO_DO && it.action.isNotBlank() }?.action
            ?: "Everything on the checklist is done — submit the application and follow up."

        return ApplicationPlan(
            jobId = job.id,
            jobTitle = job.title,
            company = job.company,
            country = job.country,
            countryFlag = job.countryFlag,
            matchScore = job.matchScore,
            readinessScore = entry.readinessScore,
            readiness = entry.readiness,
            steps = steps,
            tailoring = tailoring,
            documents = documentsFor(job),
            routeSummary = routeSummary,
            nextAction = nextAction,
            hasCoverLetter = hasCoverLetter,
            scamVerdict = scamVerdict,
            generatedAtEpochMillis = clock(),
        )
    }

    // ------------------------------------------------------------------ steps

    private fun profileStep(entry: ShortlistEntry, completeness: Int): PlanStep {
        val done = completeness >= PROFILE_TARGET &&
            entry.blockers.none { it.isProfileGap && it.points >= NAME_GAP_POINTS }
        return PlanStep(
            kind = PlanStepKind.PROFILE,
            status = if (done) PlanStepStatus.DONE else PlanStepStatus.TO_DO,
            title = if (done) "Profile $completeness% complete" else "Finish your profile ($completeness%)",
            detail = if (done) {
                "Your CV carries enough detail for the app to tailor this application accurately."
            } else {
                "A fuller profile makes the match and the CV tailoring more trustworthy. " +
                    "Add the missing CV sections before you rely on the score."
            },
            action = if (done) "" else "Import or complete your CV in Profile.",
            weight = blockersFor(entry) { it.isProfileGap },
        )
    }

    private fun visaStep(job: Job, entry: ShortlistEntry): PlanStep {
        val visaWeight = blockersFor(entry) { !it.isProfileGap && it.label.contains("eligib", true) }
        return when (job.eligibility) {
            MadagascarEligibility.RESTRICTED -> PlanStep(
                kind = PlanStepKind.VISA_ROUTE,
                status = PlanStepStatus.BLOCKED,
                title = "No route in from Madagascar",
                detail = "The posting is limited to local applicants, so there is no way to apply from Madagascar.",
                action = "Set this posting aside and look for one open to international applicants.",
                weight = maxOf(visaWeight, MIN_BLOCK_WEIGHT),
                official = true,
            )
            MadagascarEligibility.VISA_SPONSORED -> {
                val documented = documentsVisaSponsorship(job)
                PlanStep(
                    kind = PlanStepKind.VISA_ROUTE,
                    status = if (documented) PlanStepStatus.DONE else PlanStepStatus.TO_DO,
                    title = if (documented) "Visa sponsorship stated" else "Confirm the visa terms",
                    detail = if (documented) {
                        "The employer states a route in (visa sponsorship or relocation), quoted from the posting."
                    } else {
                        "The posting mentions sponsorship but does not spell out the conditions. " +
                            "Confirm who pays the fees and whether dependants are covered."
                    },
                    action = if (documented) "" else "Ask the employer for the visa terms in writing.",
                    weight = if (documented) 0 else maxOf(visaWeight, VISA_CONFIRM_WEIGHT),
                    official = true,
                )
            }
            MadagascarEligibility.ELIGIBLE -> PlanStep(
                kind = PlanStepKind.VISA_ROUTE,
                status = PlanStepStatus.DONE,
                title = "Open to applicants from Madagascar",
                detail = "The posting is open to applicants already in Madagascar.",
                weight = 0,
                official = true,
            )
            MadagascarEligibility.UNCLEAR -> PlanStep(
                kind = PlanStepKind.VISA_ROUTE,
                status = PlanStepStatus.TO_DO,
                title = "Eligibility not stated",
                detail = "The posting does not say whether it accepts applicants from Madagascar. " +
                    "That is missing information, not a refusal.",
                action = "Email the employer and ask before you invest time in the application.",
                weight = maxOf(visaWeight, ELIGIBILITY_UNKNOWN_WEIGHT),
                official = false,
            )
        }
    }

    private fun tailorStep(job: Job, tailoring: CvTailoring, entry: ShortlistEntry): PlanStep {
        val matchWeight = blockersFor(entry) { it.label.contains("CV match", true) }
        val needsWork = tailoring.addressSkills.isNotEmpty()
        val detail = buildString {
            if (tailoring.leadSkills.isNotEmpty()) {
                append("Lead with ")
                append(tailoring.leadSkills.take(4).joinToString(", ") { it.skill })
                append('.')
            }
            if (needsWork) {
                if (isNotEmpty()) append(' ')
                append("This posting also lists ")
                append(tailoring.addressSkills.take(4).joinToString(", "))
                append(", which your CV does not yet show.")
            }
            if (isEmpty()) {
                append("Your CV already covers everything this posting lists.")
            }
        }
        return PlanStep(
            kind = PlanStepKind.TAILOR_CV,
            status = if (needsWork) PlanStepStatus.TO_DO else PlanStepStatus.DONE,
            title = if (needsWork) "Tailor your CV to this posting" else "CV already lines up",
            detail = detail,
            action = if (needsWork) "Adapt your CV for ${job.company} so it leads with the skills above." else "",
            weight = matchWeight,
        )
    }

    private fun coverLetterStep(job: Job, hasCoverLetter: Boolean): PlanStep {
        val language = LetterLanguage.forCountry(job.country)
        return PlanStep(
            kind = PlanStepKind.COVER_LETTER,
            status = if (hasCoverLetter) PlanStepStatus.DONE else PlanStepStatus.TO_DO,
            title = if (hasCoverLetter) "Cover letter ready" else "Write a cover letter",
            detail = if (hasCoverLetter) {
                "A letter for this posting has been generated from your CV and is ready to review."
            } else {
                "A short, tailored letter raises reply rates and shows you read the posting."
            },
            action = if (hasCoverLetter) "" else "Generate a ${language.label} cover letter for this posting.",
            weight = if (hasCoverLetter) 0 else COVER_LETTER_WEIGHT,
        )
    }

    private fun scamStep(job: Job, verdict: ScamVerdict?, entry: ShortlistEntry): PlanStep {
        val effective = verdict ?: when (job.scamRisk) {
            ScamRisk.HIGH -> ScamVerdict.HIGH_RISK
            ScamRisk.MEDIUM -> ScamVerdict.NEEDS_CAUTION
            ScamRisk.LOW -> ScamVerdict.LIKELY_SAFE
        }
        val weight = blockersFor(entry) { it.label.contains("scam", true) || it.label.contains("checking", true) }
        return when (effective) {
            ScamVerdict.HIGH_RISK -> PlanStep(
                kind = PlanStepKind.SCAM_CHECK,
                status = PlanStepStatus.BLOCKED,
                title = "Scam shield flagged this posting",
                detail = "Several risk signals were found. Do not share personal details or pay any fee.",
                action = "Open the scam check and read every signal before you continue.",
                weight = maxOf(weight, SCAM_BLOCK_WEIGHT),
            )
            ScamVerdict.NEEDS_CAUTION -> PlanStep(
                kind = PlanStepKind.SCAM_CHECK,
                status = PlanStepStatus.TO_DO,
                title = "Some details need checking",
                detail = "The scam shield found minor caution signals. Verify the employer independently.",
                action = "Confirm the employer is real before you send documents.",
                weight = maxOf(weight, SCAM_CAUTION_WEIGHT),
            )
            ScamVerdict.LIKELY_SAFE -> PlanStep(
                kind = PlanStepKind.SCAM_CHECK,
                status = PlanStepStatus.DONE,
                title = "No scam signals found",
                detail = "The scam shield found no risk signals. It advises, it never guarantees.",
                weight = 0,
            )
        }
    }

    private fun interviewStep(attempted: Int, total: Int): PlanStep {
        if (total == 0) {
            return PlanStep(
                kind = PlanStepKind.INTERVIEW_PREP,
                status = PlanStepStatus.NOT_NEEDED,
                title = "Interview prep",
                detail = "Open the interview coach to build a rehearsal set for this posting.",
                action = "",
            )
        }
        val done = attempted >= total
        return PlanStep(
            kind = PlanStepKind.INTERVIEW_PREP,
            status = if (done) PlanStepStatus.DONE else PlanStepStatus.TO_DO,
            title = if (done) "Interview rehearsed" else "Rehearse for the interview",
            detail = "$attempted of $total rehearsal questions practised for this posting.",
            action = if (done) "" else "Practise the remaining interview questions.",
            weight = if (done) 0 else INTERVIEW_WEIGHT,
        )
    }

    private fun submitStep(job: Job, hasApplied: Boolean): PlanStep = PlanStep(
        kind = PlanStepKind.SUBMIT,
        status = if (hasApplied) PlanStepStatus.DONE else PlanStepStatus.TO_DO,
        title = if (hasApplied) "Application submitted" else "Submit the application",
        detail = if (hasApplied) {
            "This application is marked as sent in your pipeline."
        } else {
            "Apply on the employer's own site or portal. The app never submits for you."
        },
        action = if (hasApplied) "" else "Open the posting and send your application.",
        official = job.sourceUrl.isNotBlank(),
    )

    private fun followUpStep(hasApplied: Boolean): PlanStep = PlanStep(
        kind = PlanStepKind.FOLLOW_UP,
        status = if (hasApplied) PlanStepStatus.TO_DO else PlanStepStatus.NOT_NEEDED,
        title = "Follow up",
        detail = if (hasApplied) {
            "Send a short follow-up about a week after applying if you have not heard back."
        } else {
            "The follow-up reminder appears once you have applied."
        },
        action = if (hasApplied) "Set a follow-up reminder in Applications." else "",
    )

    // ------------------------------------------------------------------ tailoring & documents

    private fun tailoringFor(job: Job): CvTailoring {
        val lead = job.matchedSkills
            .distinct()
            .take(MAX_LEAD_SKILLS)
            .map { TailoringHighlight(skill = it, detail = "Already on your CV and asked for by this posting.") }

        val address = job.missingSkills.distinct().take(MAX_ADDRESS_SKILLS)

        val keywords = buildList {
            addAll(job.matchedSkills)
            addAll(job.missingSkills)
            job.requirements.certifications.forEach { add(it.label) }
            job.requirements.licences.forEach { add(it.label) }
            job.matchedTargetRole?.let { add(it) }
        }.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_KEYWORDS)

        val note = when {
            lead.isNotEmpty() && address.isNotEmpty() ->
                "Mirror the posting's own wording for ${lead.size} of your skills, and address " +
                    "${address.size} it lists that your CV does not yet show."
            lead.isNotEmpty() ->
                "Your CV already names the skills this posting asks for. Mirror the posting's wording."
            address.isNotEmpty() ->
                "This posting lists skills your CV does not show yet. Add them only if you genuinely have them."
            else -> "This posting does not list specific skills the app can compare against."
        }

        return CvTailoring(
            jobTitle = job.title,
            leadSkills = lead,
            addressSkills = address,
            keywords = keywords,
            note = note,
        )
    }

    private fun documentsFor(job: Job): List<DocumentRequirement> = buildList {
        add(
            DocumentRequirement(
                label = "CV (updated for this posting)",
                detail = "Your analysed CV, adjusted to lead with the skills above.",
                required = true,
            ),
        )
        add(
            DocumentRequirement(
                label = "Cover letter",
                detail = "A short letter written for ${job.company}.",
                required = true,
            ),
        )
        add(
            DocumentRequirement(
                label = "Passport / travel document",
                detail = "Valid for at least six months beyond your intended start date.",
                required = true,
            ),
        )
        job.requirements.certifications.forEach { requirement ->
            add(
                DocumentRequirement(
                    label = requirement.label,
                    detail = "Certification the posting lists: \"${requirement.evidence}\"",
                    required = true,
                ),
            )
        }
        job.requirements.licences.forEach { requirement ->
            add(
                DocumentRequirement(
                    label = requirement.label,
                    detail = "Licence or permit the posting lists: \"${requirement.evidence}\"",
                    required = true,
                ),
            )
        }
        add(
            DocumentRequirement(
                label = "Education certificates",
                detail = "Scans of your diplomas, translated to the employer's language if needed.",
                required = false,
            ),
        )
        add(
            DocumentRequirement(
                label = "References",
                detail = "Two people who can speak to your maintenance work.",
                required = false,
            ),
        )
    }.distinctBy { it.label }

    // ------------------------------------------------------------------ helpers

    private fun routeSummaryFor(job: Job): String {
        val official = job.eligibilityReasons.filter { it.official }
        val stated = official.firstOrNull()?.let { " \"${it.detail}\"" }.orEmpty()
        return when (job.eligibility) {
            MadagascarEligibility.VISA_SPONSORED ->
                "The employer states a route in for international applicants.$stated"
            MadagascarEligibility.ELIGIBLE ->
                "This posting is open to applicants from Madagascar.$stated"
            MadagascarEligibility.RESTRICTED ->
                "The posting is limited to local applicants, so there is no route in from Madagascar."
            MadagascarEligibility.UNCLEAR ->
                "The posting does not state whether it accepts applicants from Madagascar. " +
                    "Treat this as unknown and ask the employer."
        } + visaSupportNote(job.visaSupport)
    }

    private fun visaSupportNote(support: VisaSupport): String = when (support) {
        VisaSupport.FULL_SPONSORSHIP -> " It advertises full sponsorship."
        VisaSupport.RELOCATION_PACKAGE -> " It advertises a relocation package."
        VisaSupport.SPONSORSHIP_AVAILABLE -> " It says sponsorship is available."
        VisaSupport.LOCAL_ONLY -> ""
        VisaSupport.NOT_MENTIONED -> ""
    }

    private fun documentsVisaSponsorship(job: Job): Boolean =
        job.benefitEvidence.any {
            it.type.key == JobBenefitTypes.VISA_SPONSORSHIP.key && it.status == BenefitStatus.CONFIRMED
        }

    private fun blockersFor(entry: ShortlistEntry, predicate: (ReadinessBlocker) -> Boolean): Int =
        entry.blockers.filter(predicate).sumOf { it.points }

    private companion object {
        const val PROFILE_TARGET = 80
        const val NAME_GAP_POINTS = 6
        const val MIN_BLOCK_WEIGHT = 45
        const val VISA_CONFIRM_WEIGHT = 8
        const val ELIGIBILITY_UNKNOWN_WEIGHT = 27
        const val COVER_LETTER_WEIGHT = 10
        const val SCAM_BLOCK_WEIGHT = 20
        const val SCAM_CAUTION_WEIGHT = 8
        const val INTERVIEW_WEIGHT = 5
        const val MAX_LEAD_SKILLS = 6
        const val MAX_ADDRESS_SKILLS = 8
        const val MAX_KEYWORDS = 12
    }
}
