package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable

/**
 * One kind of work in a per-posting application plan. The order of the enum is the order
 * the plan is presented in: everything that gets the application ready comes before the
 * submission, and the follow-up sits after it.
 */
enum class PlanStepKind(val label: String, val emoji: String) {
    PROFILE("Your profile", "📄"),
    VISA_ROUTE("Route in from Madagascar", "🛂"),
    TAILOR_CV("Tailor your CV", "✂️"),
    COVER_LETTER("Cover letter", "✉️"),
    SCAM_CHECK("Safety check", "🛡️"),
    INTERVIEW_PREP("Interview prep", "🎤"),
    SUBMIT("Submit the application", "🚀"),
    FOLLOW_UP("Follow up", "⏰"),
}

/** Where one plan step stands right now. */
enum class PlanStepStatus(val label: String) {
    DONE("Done"),
    TO_DO("To do"),
    BLOCKED("Blocked"),
    NOT_NEEDED("Not needed yet"),
}

/**
 * One actionable step for a single posting. [detail] is always derived from something the
 * app actually observed — a quoted posting fact, the CV, or the user's own profile — and
 * [weight] is how much of the readiness score this step is holding back, so the list can be
 * read as a checklist ordered by impact. Nothing is invented: a step with nothing to say
 * still says so plainly.
 */
@Immutable
data class PlanStep(
    val kind: PlanStepKind,
    val status: PlanStepStatus,
    val title: String,
    val detail: String,
    /** The single thing to do next, in plain language. Empty when nothing is required. */
    val action: String = "",
    /** Readiness points this step is currently withholding; 0 when it is already satisfied. */
    val weight: Int = 0,
    /** True when the step rests on a fact quoted from the posting rather than an inference. */
    val official: Boolean = false,
) {
    val isDone: Boolean get() = status == PlanStepStatus.DONE
    val isBlocked: Boolean get() = status == PlanStepStatus.BLOCKED
    val isActionable: Boolean get() = status == PlanStepStatus.TO_DO || status == PlanStepStatus.BLOCKED
}

/** A skill the CV already lists that this posting rewards, shown as something to lead with. */
@Immutable
data class TailoringHighlight(
    val skill: String,
    val detail: String,
)

/**
 * Per-posting CV tailoring. Distinct from the country-convention adaptation: this is about
 * which of the candidate's *own* existing skills to foreground for this employer, and which
 * of the posting's listed skills the CV does not yet show. It never rewrites history.
 */
@Immutable
data class CvTailoring(
    val jobTitle: String,
    /** Skills the CV already has that the posting also asks for, strongest first. */
    val leadSkills: List<TailoringHighlight>,
    /** Skills the posting lists that the CV does not show; a prompt to add or address. */
    val addressSkills: List<String>,
    /** Exact terms from the posting worth mirroring in the CV, for keyword matching. */
    val keywords: List<String>,
    val note: String,
) {
    val hasContent: Boolean get() = leadSkills.isNotEmpty() || addressSkills.isNotEmpty()
}

/** A document to have ready before applying, with why it matters and whether it is expected. */
@Immutable
data class DocumentRequirement(
    val label: String,
    val detail: String,
    val required: Boolean,
)

/**
 * The whole per-posting application plan: the ordered checklist, the CV tailoring, the
 * documents to prepare and the one next action. It is a pure derivation of evidence the app
 * already holds, so it can be rebuilt every time the feed or the CV changes.
 */
@Immutable
data class ApplicationPlan(
    val jobId: String,
    val jobTitle: String,
    val company: String,
    val country: String,
    val countryFlag: String,
    val matchScore: Int,
    val readinessScore: Int,
    val readiness: ApplicationReadiness,
    val steps: List<PlanStep>,
    val tailoring: CvTailoring,
    val documents: List<DocumentRequirement>,
    /** One sentence on the route in from Madagascar, honest about what the posting states. */
    val routeSummary: String,
    /** The single most useful next action, or a closing sentence when the plan is complete. */
    val nextAction: String,
    val hasCoverLetter: Boolean,
    val scamVerdict: ScamVerdict?,
    val generatedAtEpochMillis: Long,
) {
    /** Steps that count towards progress; "not needed yet" steps are excluded. */
    val countableSteps: List<PlanStep> get() = steps.filter { it.status != PlanStepStatus.NOT_NEEDED }
    val doneCount: Int get() = countableSteps.count { it.isDone }
    val totalCount: Int get() = countableSteps.size
    val progressPercent: Int
        get() = if (totalCount == 0) 100 else (doneCount * 100 / totalCount)

    /** Everything still standing between the user and a sent application. */
    val openSteps: List<PlanStep> get() = steps.filter { it.isActionable }.sortedByDescending { it.weight }

    /** A hard stop, quoted from the posting or the profile, that must be cleared first. */
    val blockers: List<PlanStep> get() = steps.filter { it.isBlocked }

    /**
     * True when nothing is blocked and the application essentials (CV, route, safety, a
     * cover letter) are in place. It is a readiness statement, not a promise of an offer.
     */
    val isReadyToApply: Boolean
        get() = blockers.isEmpty() && hasCoverLetter && tailoring.note.isNotBlank()

    /** The single next step to take, or null when the plan is fully done. */
    val nextStep: PlanStep?
        get() = steps.firstOrNull { it.isBlocked } ?: steps.firstOrNull { it.status == PlanStepStatus.TO_DO }

    val hasProfile: Boolean get() = tailoring.jobTitle.isNotBlank()
}
