import { SubmissionLifecycle } from "./submission-lifecycle";

/**
 * Stage 49 — how a *future, authorized* official submission response would be classified.
 * Mirrors the Kotlin `OfficialSubmissionClassifier`.
 *
 * This is a contract, deliberately not wired to any network: a success is claimed only on an
 * explicit provider confirmation, an ambiguous or timed-out response is never turned into a
 * success, and a conflict is treated as "verify", never as "retry".
 */
export type OfficialSubmissionOutcome =
  | { kind: "CONFIRMED"; remoteApplicationId: string }
  | { kind: "FAILED"; reason: string }
  | { kind: "UNKNOWN"; reason: string };

/**
 * Classifies one create-application response.
 *
 * @param httpStatus the HTTP status, or null when the request did not complete (timeout or
 *   transport failure), which is `UNKNOWN`.
 * @param remoteApplicationId the id the provider returned in its body, when it did. A 2xx
 *   without an id is ambiguous and stays `UNKNOWN`.
 */
export function classifySubmission(
  httpStatus: number | null,
  remoteApplicationId: string | null,
): OfficialSubmissionOutcome {
  const id = (remoteApplicationId ?? "").trim();
  if (httpStatus == null) {
    return { kind: "UNKNOWN", reason: "No response from the provider — the application may or may not exist." };
  }
  if (httpStatus >= 200 && httpStatus <= 299 && id !== "") {
    return { kind: "CONFIRMED", remoteApplicationId: id };
  }
  if (httpStatus >= 200 && httpStatus <= 299) {
    return { kind: "UNKNOWN", reason: "The provider accepted the request but returned no application id." };
  }
  // A conflict means an application for this candidate/job may already exist. Treat it as
  // unknown and verify — resending could create a duplicate.
  if (httpStatus === 409) {
    return { kind: "UNKNOWN", reason: "The provider reported a conflict — an application may already exist." };
  }
  // Rate limited: the request was not processed. Not a failure of the application.
  if (httpStatus === 429) {
    return { kind: "FAILED", reason: "The provider is rate limiting this client; the request was not processed." };
  }
  if (httpStatus >= 500 && httpStatus <= 599) {
    return { kind: "UNKNOWN", reason: "The provider returned a server error — the application may have been created." };
  }
  if (httpStatus >= 400 && httpStatus <= 499) {
    return { kind: "FAILED", reason: `The provider rejected the application (HTTP ${httpStatus}).` };
  }
  return { kind: "UNKNOWN", reason: `Unexpected response (HTTP ${httpStatus}).` };
}

/** The lifecycle state this outcome would produce. */
export function submissionLifecycleOf(outcome: OfficialSubmissionOutcome): SubmissionLifecycle {
  switch (outcome.kind) {
    case "CONFIRMED":
      return SubmissionLifecycle.SUBMITTED_CONFIRMED;
    case "FAILED":
      return SubmissionLifecycle.SUBMISSION_FAILED;
    case "UNKNOWN":
      return SubmissionLifecycle.SUBMISSION_UNKNOWN;
  }
}
