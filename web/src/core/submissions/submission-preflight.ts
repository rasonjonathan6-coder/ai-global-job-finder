import { draftIsApproved, draftNeedsResubmission, type ApplicationDraft } from "../models";

/** Why a real submission is not allowed right now. Each maps to a plain-language message. */
export enum SubmissionBlocker {
  NOT_APPROVED = "NOT_APPROVED",
  CONTENT_CHANGED = "CONTENT_CHANGED",
  NO_OFFICIAL_CHANNEL = "NO_OFFICIAL_CHANNEL",
  MISSING_REQUIRED_FIELDS = "MISSING_REQUIRED_FIELDS",
  CONSENT_NOT_GIVEN = "CONSENT_NOT_GIVEN",
  ALREADY_APPLIED = "ALREADY_APPLIED",
}

export const SUBMISSION_BLOCKER_MESSAGE: Record<SubmissionBlocker, string> = {
  [SubmissionBlocker.NOT_APPROVED]: "Approve this exact application before anything can be sent.",
  [SubmissionBlocker.CONTENT_CHANGED]: "The content changed since you approved it — review and approve again.",
  [SubmissionBlocker.NO_OFFICIAL_CHANNEL]:
    "No verified, authorized submission route is available, so nothing can be sent automatically.",
  [SubmissionBlocker.MISSING_REQUIRED_FIELDS]:
    "The application is missing required content (a CV summary and a cover letter).",
  [SubmissionBlocker.CONSENT_NOT_GIVEN]: "The employer's privacy consent has not been given for this application.",
  [SubmissionBlocker.ALREADY_APPLIED]:
    "This opening is already in your registry — a second application would be a duplicate.",
};

/**
 * The result of checking a prepared application against every rule that must hold before a
 * real send. `allowed` is true only when there are no blockers at all.
 */
export interface SubmissionPreflight {
  blockers: SubmissionBlocker[];
  allowed: boolean;
  primaryBlocker: SubmissionBlocker | null;
  explanation: string;
}

/**
 * Stage 49 — the pure gate that decides whether a real submission may proceed. Mirrors the
 * Kotlin `SubmissionPreflightCheck`.
 *
 * It holds no state and performs no I/O: it is a function of the draft, whether the official
 * route exists, and whether consent was given.
 */
export function evaluateSubmissionPreflight(
  draft: ApplicationDraft,
  /** Whether a verified, authorized official submission route exists. */
  realSubmissionAvailable: boolean,
  /** Whether the user gave the employer's required privacy consent for this application. */
  consentGiven: boolean,
  /** Whether this opening is already recorded (a second application would duplicate). */
  alreadyApplied: boolean,
): SubmissionPreflight {
  const blockers: SubmissionBlocker[] = [];
  // Order matters: the user resolves approval first, then authorization, then content, then
  // consent, then the duplicate check.
  if (!draftIsApproved(draft)) {
    blockers.push(draftNeedsResubmission(draft) ? SubmissionBlocker.CONTENT_CHANGED : SubmissionBlocker.NOT_APPROVED);
  }
  if (!realSubmissionAvailable) blockers.push(SubmissionBlocker.NO_OFFICIAL_CHANNEL);
  if (draft.cvSummary.trim() === "" || draft.coverLetterText.trim() === "") {
    blockers.push(SubmissionBlocker.MISSING_REQUIRED_FIELDS);
  }
  if (!consentGiven) blockers.push(SubmissionBlocker.CONSENT_NOT_GIVEN);
  if (alreadyApplied) blockers.push(SubmissionBlocker.ALREADY_APPLIED);

  const primaryBlocker = blockers.length > 0 ? blockers[0] : null;
  return {
    blockers,
    allowed: blockers.length === 0,
    primaryBlocker,
    explanation:
      primaryBlocker != null
        ? SUBMISSION_BLOCKER_MESSAGE[primaryBlocker]
        : "Ready to submit the approved application.",
  };
}
