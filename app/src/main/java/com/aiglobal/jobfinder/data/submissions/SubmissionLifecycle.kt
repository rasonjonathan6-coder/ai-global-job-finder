package com.aiglobal.jobfinder.data.submissions

import com.aiglobal.jobfinder.data.model.ApplicationDraft
import com.aiglobal.jobfinder.data.model.DraftStatus

/**
 * Stage 49 — the application lifecycle, made explicit.
 *
 * The app has always prepared applications but never sent them. This names the states a real
 * submission would pass through, so the UI can distinguish them honestly and so a future,
 * authorized integration has a fixed vocabulary to move through. Nothing here enables a
 * send: [SUBMITTED_CONFIRMED] is unreachable while [OfficialSubmissionAudit.realSubmissionAvailable]
 * is false, and [SIMULATED_NOT_SENT] exists precisely so a simulation is never shown as sent.
 */
enum class SubmissionLifecycle(val label: String) {
    /** Prepared, not yet reviewed by the user. */
    DRAFT("Draft"),

    /** Prepared and waiting for the user's explicit, per-application approval. */
    PENDING_APPROVAL("Approval required"),

    /** The user approved the exact content currently shown. */
    APPROVED("Approved"),

    /** A send is in flight (or, today, a simulation is being recorded). */
    SUBMITTING("Submitting"),

    /** A real send was confirmed by the provider's own response. Unreachable while sending is off. */
    SUBMITTED_CONFIRMED("Sent and confirmed"),

    /** The provider rejected the send, and it did not create an application. */
    SUBMISSION_FAILED("Submission failed"),

    /** The outcome is genuinely unknown (timeout or ambiguous response). Never assumed either way. */
    SUBMISSION_UNKNOWN("Verification needed"),

    /**
     * The app walked the whole application through but sent nothing. This is the current,
     * honest end state for every application: it is *not* a sent application and must never
     * be presented as one.
     */
    SIMULATED_NOT_SENT("Simulated — not sent"),

    /** The user skipped the prepared application. */
    SKIPPED("Skipped"),

    /** The application could not proceed (duplicate, or the posting is gone). */
    BLOCKED("Blocked"),
    ;

    /** True only for a provider-confirmed real submission. */
    val isConfirmedSent: Boolean get() = this == SUBMITTED_CONFIRMED

    /** True when the app has not sent anything for this application. */
    val isNotSent: Boolean get() = this == SIMULATED_NOT_SENT || this == DRAFT || this == PENDING_APPROVAL

    companion object {
        /**
         * The lifecycle state of a prepared draft. A draft that has been recorded as simulated
         * reports [SIMULATED_NOT_SENT] — never [SUBMITTED_CONFIRMED] — which is what keeps the
         * screen from implying a send that did not happen.
         */
        fun of(status: DraftStatus): SubmissionLifecycle = when (status) {
            DraftStatus.DRAFT -> DRAFT
            DraftStatus.READY_FOR_REVIEW, DraftStatus.NEEDS_RESUBMISSION -> PENDING_APPROVAL
            DraftStatus.APPROVED -> APPROVED
            DraftStatus.SUBMISSION_PENDING -> SUBMITTING
            DraftStatus.SUBMISSION_SENT -> SUBMITTED_CONFIRMED
            DraftStatus.SUBMISSION_SIMULATED -> SIMULATED_NOT_SENT
            DraftStatus.SKIPPED -> SKIPPED
            DraftStatus.BLOCKED -> BLOCKED
        }
    }
}

/** The lifecycle state of a prepared draft, for the applications screen. */
val ApplicationDraft.lifecycle: SubmissionLifecycle
    get() = SubmissionLifecycle.of(status)
