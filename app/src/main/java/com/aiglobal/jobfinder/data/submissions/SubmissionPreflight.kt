package com.aiglobal.jobfinder.data.submissions

import com.aiglobal.jobfinder.data.model.ApplicationDraft

/** Why a real submission is not allowed right now. Each maps to a plain-language message. */
enum class SubmissionBlocker(val message: String) {
    NOT_APPROVED("Approve this exact application before anything can be sent."),
    CONTENT_CHANGED("The content changed since you approved it — review and approve again."),
    NO_OFFICIAL_CHANNEL("No verified, authorized submission route is available, so nothing can be sent automatically."),
    MISSING_REQUIRED_FIELDS("The application is missing required content (a CV summary and a cover letter)."),
    CONSENT_NOT_GIVEN("The employer's privacy consent has not been given for this application."),
    ALREADY_APPLIED("This opening is already in your registry — a second application would be a duplicate."),
}

/**
 * The result of checking a prepared application against every rule that must hold before a
 * real send. [allowed] is true only when there are no blockers at all, so a caller can never
 * send while any rule is unmet — the check is derived from the blockers, not set beside them.
 */
data class SubmissionPreflight(
    val blockers: List<SubmissionBlocker>,
) {
    val allowed: Boolean get() = blockers.isEmpty()

    /** The first blocker, in the order the user should resolve them, or null when allowed. */
    val primaryBlocker: SubmissionBlocker? get() = blockers.firstOrNull()

    /** A single honest sentence for the UI. */
    val explanation: String
        get() = primaryBlocker?.message ?: "Ready to submit the approved application."
}

/**
 * Stage 49 — the pure gate that decides whether a real submission may proceed.
 *
 * It deliberately holds no state and performs no I/O: it is a function of the draft, the
 * content the user reviewed, whether the official route exists, and whether consent was
 * given. That keeps the safety rules testable and keeps them from being quietly bypassed by
 * a caller that only checks some of them.
 */
object SubmissionPreflightCheck {

    fun evaluate(
        draft: ApplicationDraft,
        /** Whether a verified, authorized official submission route exists. */
        realSubmissionAvailable: Boolean,
        /** Whether the user gave the employer's required privacy consent for this application. */
        consentGiven: Boolean,
        /** Whether this opening is already recorded (a second application would duplicate). */
        alreadyApplied: Boolean,
    ): SubmissionPreflight {
        val blockers = buildList {
            // Order matters: the user resolves approval first, then authorization, then content,
            // then consent, then the duplicate check.
            if (!draft.isApproved) {
                add(if (draft.needsResubmission) SubmissionBlocker.CONTENT_CHANGED else SubmissionBlocker.NOT_APPROVED)
            }
            if (!realSubmissionAvailable) add(SubmissionBlocker.NO_OFFICIAL_CHANNEL)
            if (draft.cvSummary.isBlank() || draft.coverLetterText.isBlank()) {
                add(SubmissionBlocker.MISSING_REQUIRED_FIELDS)
            }
            if (!consentGiven) add(SubmissionBlocker.CONSENT_NOT_GIVEN)
            if (alreadyApplied) add(SubmissionBlocker.ALREADY_APPLIED)
        }
        return SubmissionPreflight(blockers)
    }
}
