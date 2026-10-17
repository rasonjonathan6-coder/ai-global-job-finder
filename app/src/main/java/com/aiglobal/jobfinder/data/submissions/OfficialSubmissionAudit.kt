package com.aiglobal.jobfinder.data.submissions

import com.aiglobal.jobfinder.data.model.Job

/**
 * Stage 49 — the official application-submission audit.
 *
 * This records, as facts with sources, what the app would need before it could send a real
 * application through a provider's own API, and whether those requirements are met today.
 * It is deliberately data, not a claim: the UI reads it to explain *why* a route is not
 * available instead of guessing, and the submission gate reads the same record so the two
 * can never disagree.
 *
 * The audit below is the result of reading SmartRecruiters' current official documentation
 * (developers.smartrecruiters.com, "Application API", page updated 2026-03-25). It is
 * written down here rather than asserted in prose so a test can hold it to the documented
 * endpoints, and so a future stage that actually satisfies the prerequisites has one place
 * to flip the availability flag.
 */
object OfficialSubmissionAudit {

    /** The one provider whose official documentation exposes an application-submission API. */
    const val SMART_RECRUITERS_PROVIDER_ID = "smartrecruiters"

    const val SMART_RECRUITERS_DOCS = "https://developers.smartrecruiters.com/docs/application-api"

    /** The endpoints the official documentation exposes, quoted verbatim from it. */
    val SMART_RECRUITERS_ENDPOINTS: List<String> = listOf(
        "GET /postings/:uuid/configuration",
        "POST /postings/:uuid/candidates",
        "GET /postings/:uuid/candidates/:candidateId/status",
    )

    /**
     * Every requirement that must hold before the app could drive this API, each with the
     * reason it is not met today. A prerequisite is [met] only when it genuinely holds.
     */
    val SMART_RECRUITERS_PREREQUISITES: List<SubmissionPrerequisite> = listOf(
        SubmissionPrerequisite(
            id = "authorized-account",
            title = "An authorized SmartRecruiters account",
            detail = "The Application API is protected. Access needs either a SmartRecruiters " +
                "customer's API credential (created in the customer's Credential Manager) or an " +
                "onboarded partner application registered with SmartRecruiters and acting on a " +
                "customer's behalf.",
            met = false,
            howToSatisfy = "Become a SmartRecruiters customer or complete partner registration " +
                "(the Marketplace onboarding route for apps distributed to customers).",
        ),
        SubmissionPrerequisite(
            id = "oauth-scope",
            title = "OAuth 2.0 client with the candidate_applications_manage scope",
            detail = "The documentation states that, when using OAuth 2.0, the scope " +
                "candidate_applications_manage is required to access the Application API. The app " +
                "holds no such client and no such scope today.",
            met = false,
            howToSatisfy = "Obtain an OAuth 2.0 client from SmartRecruiters with that scope granted.",
        ),
        SubmissionPrerequisite(
            id = "client-secret-custody",
            title = "A place to keep the client secret that Android cannot give",
            detail = "An OAuth client secret cannot be protected inside an APK: anything shipped " +
                "on the device can be extracted. The official design for this is a server that " +
                "holds the secret and signs the calls. The app has no such backend.",
            met = false,
            howToSatisfy = "Stand up a minimal secure backend that holds the client secret and " +
                "performs the submission on the app's behalf.",
        ),
        SubmissionPrerequisite(
            id = "candidate-consent",
            title = "GDPR consent and privacy-policy presentation",
            detail = "The documentation makes it mandatory to present the employer's privacy " +
                "policy and collect a consent decision (consentDecisions) before an application " +
                "is created, and to separate and label diversity questions. The app can present " +
                "these, but only once it can actually fetch them and send an application.",
            met = false,
            howToSatisfy = "Wire GET /postings/:uuid/configuration and render its questions, " +
                "privacy policy and consent scopes before submitting.",
        ),
        SubmissionPrerequisite(
            id = "posting-uuid",
            title = "A stable posting UUID per vacancy",
            detail = "Every endpoint is keyed by the posting UUID from SmartRecruiters' Posting " +
                "API. The app's feed carries the posting id, but a submission must resolve the " +
                "canonical UUID and re-check the posting is still open before sending.",
            met = false,
            howToSatisfy = "Resolve and verify the posting UUID immediately before submission.",
        ),
        SubmissionPrerequisite(
            id = "rate-limits",
            title = "Respecting adaptive throttling (8 concurrent, 128s timeouts)",
            detail = "The API uses adaptive throttling: at most 8 concurrent calls, requests may " +
                "take up to 128 seconds, and over-limit calls return HTTP 429. A submission path " +
                "must pace itself and never retry a create blindly.",
            met = false,
            howToSatisfy = "Pace requests, honour 429, and treat a timeout as unknown — never " +
                "resend a create that may have succeeded.",
        ),
    )

    /**
     * True only when every prerequisite genuinely holds. It is derived, so it cannot be set
     * independently of the list and cannot claim readiness the list contradicts.
     */
    val realSubmissionAvailable: Boolean
        get() = SMART_RECRUITERS_PREREQUISITES.all { it.met }

    /** The prerequisites that are still unmet, in the order they must be solved. */
    val unmetPrerequisites: List<SubmissionPrerequisite>
        get() = SMART_RECRUITERS_PREREQUISITES.filterNot { it.met }

    /**
     * The submission channel a posting would use if a verified route existed. Today every
     * route resolves to a channel the app cannot drive, so this is what the UI labels.
     */
    fun channelFor(job: Job): SubmissionChannel = SubmissionChannel.infer(job)

    /**
     * The single official place to apply for [job] by hand, when the app cannot send. This is
     * the employer's own posting page — never a fabricated endpoint.
     */
    fun officialApplyUrl(job: Job): String? = job.sourceUrl.takeIf { it.isNotBlank() }

    /**
     * A short, honest status line for the applications screen: whether the app can send, and
     * if not, the one thing that most blocks it.
     */
    fun statusSummary(): String = if (realSubmissionAvailable) {
        "A verified submission route is available."
    } else {
        "No verified submission route is available. Applications are prepared here and you " +
            "submit them on the employer's official form."
    }
}

/** One requirement for a real, API-driven submission, with its current state and remedy. */
data class SubmissionPrerequisite(
    val id: String,
    val title: String,
    val detail: String,
    val met: Boolean,
    /** What the user (or a future stage) must do to satisfy it. */
    val howToSatisfy: String,
)
