package com.aiglobal.jobfinder.data.jobs.availability

/**
 * Turns what a link probe observed into a verdict, using only markers that reliably mean
 * "still open" or "no longer available".
 *
 * This is the rule that stops a plain HTTP 200 from being read as proof of openness: a 200
 * is *not* in either marker list, so on its own it stays [LinkProbeOutcome.Unknown]. Only a
 * body that positively advertises an application action promotes a posting to open, and only
 * a body that positively says the vacancy is gone demotes it to expired.
 *
 * Pure and string-based, so every marker rule is unit-tested without a network.
 */
object LinkProbeClassifier {

    /** Phrases that positively indicate the posting is still accepting applications. */
    private val OPEN_MARKERS = listOf(
        "apply for this job",
        "apply now",
        "apply for this position",
        "submit your application",
        "start your application",
        "we are hiring",
        "we're hiring",
    )

    /** Phrases that positively indicate the vacancy is no longer available. */
    private val CLOSED_MARKERS = listOf(
        "no longer accepting applications",
        "no longer available",
        "this position has been filled",
        "this job has been filled",
        "position closed",
        "job posting has expired",
        "this job is closed",
        "applications are closed",
        "this role is no longer",
        "vacancy has been filled",
        "job not found",
        "this posting is no longer",
    )

    /**
     * Classifies a probe result. [statusCode] is the HTTP status; [body] is a (possibly
     * truncated) response body, lower-cased by the caller or here.
     *
     * A "closed" marker is checked before an "open" marker, because a page can carry both a
     * stale "Apply" button and an explicit "position filled" notice; when the two conflict,
     * the safer reading is the closed one.
     */
    fun classify(statusCode: Int, body: String): LinkProbeOutcome {
        val haystack = body.lowercase()

        // 404/410 are the only status codes that are themselves a reliable "gone" signal.
        if (statusCode == 404 || statusCode == 410) {
            return LinkProbeOutcome.Closed("The application link answered HTTP $statusCode: the posting is gone.")
        }

        // 401/403/429 mean the request was refused or throttled, not that the vacancy closed.
        if (statusCode == 401 || statusCode == 403 || statusCode == 429) {
            return LinkProbeOutcome.Unknown(
                "The application link answered HTTP $statusCode, so this app could not read it (blocked or rate-limited).",
            )
        }

        // Any other non-success status is inconclusive: it says nothing about the vacancy.
        if (statusCode !in 200..299) {
            return LinkProbeOutcome.Unknown(
                "The application link answered HTTP $statusCode, which does not confirm whether the vacancy is open.",
            )
        }

        // A success status still needs a positive marker; a 200 alone proves only that a page exists.
        if (CLOSED_MARKERS.any { it in haystack }) {
            return LinkProbeOutcome.Closed("The application page states the vacancy is no longer available.")
        }
        if (OPEN_MARKERS.any { it in haystack }) {
            return LinkProbeOutcome.Open("The application page still offers an active application action.")
        }
        return LinkProbeOutcome.Unknown(
            "The application link is reachable but does not state whether the vacancy is still open.",
        )
    }
}
