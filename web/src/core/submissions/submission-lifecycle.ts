import { DraftStatus } from "../models";

/**
 * Stage 49 — the application lifecycle, made explicit. Mirrors the Kotlin `SubmissionLifecycle`.
 *
 * The app has always prepared applications but never sent them. This names the states a real
 * submission would pass through, so the UI can distinguish them honestly. Nothing here enables
 * a send: `SUBMITTED_CONFIRMED` is unreachable while no channel supports a real send, and
 * `SIMULATED_NOT_SENT` exists precisely so a simulation is never shown as sent.
 */
export enum SubmissionLifecycle {
  /** Prepared, not yet reviewed by the user. */
  DRAFT = "DRAFT",
  /** Prepared and waiting for the user's explicit, per-application approval. */
  PENDING_APPROVAL = "PENDING_APPROVAL",
  /** The user approved the exact content currently shown. */
  APPROVED = "APPROVED",
  /** A send is in flight (or, today, a simulation is being recorded). */
  SUBMITTING = "SUBMITTING",
  /** A real send was confirmed by the provider's own response. Unreachable while sending is off. */
  SUBMITTED_CONFIRMED = "SUBMITTED_CONFIRMED",
  /** The provider rejected the send, and it did not create an application. */
  SUBMISSION_FAILED = "SUBMISSION_FAILED",
  /** The outcome is genuinely unknown (timeout or ambiguous response). Never assumed either way. */
  SUBMISSION_UNKNOWN = "SUBMISSION_UNKNOWN",
  /** The app walked the whole application through but sent nothing. */
  SIMULATED_NOT_SENT = "SIMULATED_NOT_SENT",
  /** The user skipped the prepared application. */
  SKIPPED = "SKIPPED",
  /** The application could not proceed (duplicate, or the posting is gone). */
  BLOCKED = "BLOCKED",
}

export const SUBMISSION_LIFECYCLE_LABEL: Record<SubmissionLifecycle, string> = {
  [SubmissionLifecycle.DRAFT]: "Draft",
  [SubmissionLifecycle.PENDING_APPROVAL]: "Approval required",
  [SubmissionLifecycle.APPROVED]: "Approved",
  [SubmissionLifecycle.SUBMITTING]: "Submitting",
  [SubmissionLifecycle.SUBMITTED_CONFIRMED]: "Sent and confirmed",
  [SubmissionLifecycle.SUBMISSION_FAILED]: "Submission failed",
  [SubmissionLifecycle.SUBMISSION_UNKNOWN]: "Verification needed",
  [SubmissionLifecycle.SIMULATED_NOT_SENT]: "Simulated — not sent",
  [SubmissionLifecycle.SKIPPED]: "Skipped",
  [SubmissionLifecycle.BLOCKED]: "Blocked",
};

/** True only for a provider-confirmed real submission. */
export function submissionLifecycleIsConfirmedSent(state: SubmissionLifecycle): boolean {
  return state === SubmissionLifecycle.SUBMITTED_CONFIRMED;
}

/** True when the app has not sent anything for this application. */
export function submissionLifecycleIsNotSent(state: SubmissionLifecycle): boolean {
  return (
    state === SubmissionLifecycle.SIMULATED_NOT_SENT ||
    state === SubmissionLifecycle.DRAFT ||
    state === SubmissionLifecycle.PENDING_APPROVAL
  );
}

/**
 * The lifecycle state of a prepared draft. A draft recorded as simulated reports
 * `SIMULATED_NOT_SENT` — never `SUBMITTED_CONFIRMED` — which is what keeps the screen from
 * implying a send that did not happen.
 */
export function submissionLifecycleOf(status: DraftStatus): SubmissionLifecycle {
  switch (status) {
    case DraftStatus.DRAFT:
      return SubmissionLifecycle.DRAFT;
    case DraftStatus.READY_FOR_REVIEW:
    case DraftStatus.NEEDS_RESUBMISSION:
      return SubmissionLifecycle.PENDING_APPROVAL;
    case DraftStatus.APPROVED:
      return SubmissionLifecycle.APPROVED;
    case DraftStatus.SUBMISSION_PENDING:
      return SubmissionLifecycle.SUBMITTING;
    case DraftStatus.SUBMISSION_SENT:
      return SubmissionLifecycle.SUBMITTED_CONFIRMED;
    case DraftStatus.SUBMISSION_SIMULATED:
      return SubmissionLifecycle.SIMULATED_NOT_SENT;
    case DraftStatus.SKIPPED:
      return SubmissionLifecycle.SKIPPED;
    case DraftStatus.BLOCKED:
      return SubmissionLifecycle.BLOCKED;
  }
}
