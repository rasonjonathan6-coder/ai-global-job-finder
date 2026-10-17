package com.aiglobal.jobfinder.data.submissions

/**
 * Stage 49 — how a *future, authorized* official submission response would be classified.
 *
 * This is a contract, deliberately not wired to any network. It exists so the reliability
 * rules the brief requires are written down as code a test can hold to, rather than as
 * prose: a success is claimed only on an explicit provider confirmation, an ambiguous or
 * timed-out response is never turned into a success, and a conflict is treated as "verify",
 * never as "retry" — because a blind retry of a create is exactly how a duplicate is born.
 *
 * The three outcomes map onto [SubmissionLifecycle] as:
 *  - [OfficialSubmissionOutcome.Confirmed] → SUBMITTED_CONFIRMED (only ever on a real id),
 *  - [OfficialSubmissionOutcome.Failed]    → SUBMISSION_FAILED (definitely not created),
 *  - [OfficialSubmissionOutcome.Unknown]   → SUBMISSION_UNKNOWN (must be verified, never resent).
 */
sealed interface OfficialSubmissionOutcome {
    /** The provider confirmed an application was created; [remoteApplicationId] is its id. */
    data class Confirmed(val remoteApplicationId: String) : OfficialSubmissionOutcome

    /** The provider definitively did not create an application (a rejected request). */
    data class Failed(val reason: String) : OfficialSubmissionOutcome

    /**
     * The outcome cannot be known from the response (timeout, 5xx, or a 2xx without an id).
     * Nothing is assumed: the application must be verified, and never blindly resent.
     */
    data class Unknown(val reason: String) : OfficialSubmissionOutcome
}

object OfficialSubmissionClassifier {

    /**
     * Classifies one create-application response.
     *
     * @param httpStatus the HTTP status, or null when the request did not complete (timeout
     *   or transport failure), which is [OfficialSubmissionOutcome.Unknown].
     * @param remoteApplicationId the id the provider returned in its body, when it did. A 2xx
     *   without an id is ambiguous and stays [OfficialSubmissionOutcome.Unknown].
     */
    fun classify(httpStatus: Int?, remoteApplicationId: String?): OfficialSubmissionOutcome {
        val id = remoteApplicationId?.trim().orEmpty()
        return when {
            httpStatus == null ->
                OfficialSubmissionOutcome.Unknown("No response from the provider — the application may or may not exist.")

            httpStatus in 200..299 && id.isNotEmpty() ->
                OfficialSubmissionOutcome.Confirmed(id)

            httpStatus in 200..299 ->
                OfficialSubmissionOutcome.Unknown("The provider accepted the request but returned no application id.")

            // A conflict means an application for this candidate/job may already exist. Treat
            // it as unknown and verify — resending could create a duplicate.
            httpStatus == 409 ->
                OfficialSubmissionOutcome.Unknown("The provider reported a conflict — an application may already exist.")

            // Rate limited: the request was not processed. Not a failure of the application.
            httpStatus == 429 ->
                OfficialSubmissionOutcome.Failed("The provider is rate limiting this client; the request was not processed.")

            httpStatus in 500..599 ->
                OfficialSubmissionOutcome.Unknown("The provider returned a server error — the application may have been created.")

            httpStatus in 400..499 ->
                OfficialSubmissionOutcome.Failed("The provider rejected the application (HTTP $httpStatus).")

            else ->
                OfficialSubmissionOutcome.Unknown("Unexpected response (HTTP $httpStatus).")
        }
    }

    /** The lifecycle state this outcome would produce. */
    fun lifecycleOf(outcome: OfficialSubmissionOutcome): SubmissionLifecycle = when (outcome) {
        is OfficialSubmissionOutcome.Confirmed -> SubmissionLifecycle.SUBMITTED_CONFIRMED
        is OfficialSubmissionOutcome.Failed -> SubmissionLifecycle.SUBMISSION_FAILED
        is OfficialSubmissionOutcome.Unknown -> SubmissionLifecycle.SUBMISSION_UNKNOWN
    }
}
