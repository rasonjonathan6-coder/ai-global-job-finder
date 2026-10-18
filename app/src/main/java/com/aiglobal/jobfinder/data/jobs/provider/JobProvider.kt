package com.aiglobal.jobfinder.data.jobs.provider

import androidx.compose.runtime.Immutable
import com.aiglobal.jobfinder.data.model.SalaryPeriod

/**
 * One posting exactly as a source reported it, before any matching or eligibility
 * work. Providers only translate a feed into this shape; they never score, guess or
 * enrich. Everything downstream is computed from these fields, so a field that the
 * source did not send stays null rather than being filled in.
 */
@Immutable
data class RawJob(
    val providerId: String,
    val providerName: String,
    val externalId: String,
    val title: String,
    val company: String,
    /** The location exactly as published, e.g. "Berlin", "France", "Worldwide". */
    val location: String,
    val descriptionHtml: String,
    val url: String,
    val tags: List<String> = emptyList(),
    val employmentType: String? = null,
    val postedAtEpochMillis: Long? = null,
    /**
     * The application deadline the source itself published, when it published one. Null means
     * the source stated no deadline — which is not the same as "no deadline exists", so a
     * missing value never causes a rejection. A stated deadline that has already passed is
     * positive evidence the posting is no longer worth applying to, and is treated as such.
     */
    val expiresAtEpochMillis: Long? = null,
    /** When the app fetched this posting, so an offline cache can report its real age. */
    val retrievedAtEpochMillis: Long? = null,
    val isRemote: Boolean = false,
    val salaryMin: Int? = null,
    val salaryMax: Int? = null,
    val salaryCurrency: String? = null,
    val salaryPeriod: SalaryPeriod? = null,
    /**
     * True when the source gave no pay unit and it was inferred from the amount. The figure is
     * still shown, but it is not treated as a confirmed period for conversion purposes.
     */
    val salaryPeriodInferred: Boolean = false,
    /**
     * True when the source's stated unit is impossible for the amount — an annual salary
     * labelled "per hour". The figure is kept so the UI can say "salary to verify", but it is
     * never shown as a plain number or converted, because the unit is not trustworthy.
     */
    val salaryUnitUncertain: Boolean = false,
    /**
     * True only when the source *itself* says the posting is closed to new applicants
     * (Workday's `canApply = false`). This is a fact the employer declared, not an
     * inference from age, so a closed posting is dropped rather than shown as if it were
     * still open. Providers whose feed cannot state this leave it false — absence of the
     * flag never means "closed".
     */
    val closedToNewApplicants: Boolean = false,
    /**
     * The source's own statement that the posting is *open* to new applicants, when it
     * says so (Workday's `canApply = true`). Null means the source did not state it — which
     * is not the same as "closed". Kept separate from [closedToNewApplicants] so the app can
     * tell "the employer confirmed it is open" apart from "the employer said nothing".
     */
    val openToApplicants: Boolean? = null,
)

/**
 * Broad classification of a source, used for the "official source" trust signals.
 *
 *  - [REMOTE_BOARD] remote-first boards.
 *  - [GOVERNMENT] public employment services and other official state/EU feeds.
 *  - [AGGREGATOR] broad multi-employer search engines.
 *  - [EMPLOYER_BOARD] a single employer's own applicant-tracking-system careers site.
 *    These are the most trustworthy feed for expat and industrial roles because the
 *    posting is the employer's own and applies directly, with no middle party.
 */
enum class ProviderKind { REMOTE_BOARD, GOVERNMENT, AGGREGATOR, EMPLOYER_BOARD }

/**
 * Where a provider's postings actually come from, so the app never presents a fixture or a
 * mock as if it were a live feed.
 *
 *  - [LIVE] — postings are read from the source's real API over the network at fetch time.
 *  - [FIXTURES] — a deterministic, hand-curated set shipped with the app (demo mode). Real
 *    employers and real wording, but not fetched now.
 *  - [MOCK] — synthetic records a test or a stub returned; never real openings.
 *  - [DEMO] — curated sample data served so the UI can be explored without a CV or a network.
 *
 * This is a fact about the provider, declared by the provider itself, so the diagnostics
 * screen can say plainly whether what the user sees is live.
 */
enum class DataSourceKind(val label: String) {
    LIVE("Live"),
    FIXTURES("Fixtures"),
    MOCK("Mock"),
    DEMO("Demo"),
}

/**
 * What a source's API actually supports. These are facts about the provider, established
 * from its documentation and from what the feed does with a parameter, and they drive the
 * search strategy: a provider that cannot filter server-side is fetched once and filtered
 * locally, and the UI is told its results are a broad sample rather than a targeted search.
 */
@Immutable
data class ProviderCapabilities(
    /** The API accepts a free-text keyword and narrows the feed by it. */
    val serverSideKeyword: Boolean = false,
    /** The API can be bounded to a country (or is queried per country). */
    val countryFilter: Boolean = false,
    /** The API supports paging beyond the first page. */
    val pagination: Boolean = false,
    /** The API can filter by employment type (contract, full-time, …). */
    val employmentTypeFilter: Boolean = false,
    /** The API returns an application link (almost always the employer's own posting). */
    val applicationLinks: Boolean = true,
    /** The API is documented as an authorized application-submission endpoint. */
    val submissionEndpoint: Boolean = false,
    /**
     * Whether this source's postings expose an application route the app itself can drive
     * end-to-end. False everywhere today: no verified submission integration exists, so
     * every application is prepared and then simulated rather than sent. Kept on the
     * capability record so the diagnostics screen can state it per source instead of
     * implying a capability the app does not have.
     */
    val realSubmissionSupported: Boolean = false,
) {
    /**
     * True when a keyword sent to this source genuinely narrows the feed. When false, the
     * source returns a broad sample and the app must say so instead of implying a targeted
     * search happened.
     */
    val supportsTargetedSearch: Boolean get() = serverSideKeyword
}

/**
 * A single job source. Implementations are stateless and network-bound; the
 * repository decides when to call them and how to combine the results.
 */
interface JobProvider {
    /** Stable id, used to key per-source sync status. */
    val id: String

    /** Display name shown on the job card and in source filters. */
    val name: String

    /** Public homepage, shown as the attribution link. */
    val homepage: String

    val kind: ProviderKind

    /**
     * Where this provider's postings come from. Defaults to [DataSourceKind.LIVE] because
     * every registered production provider reads its source's real API; a fixture, mock or
     * demo source must override it so the UI never calls it live.
     */
    val dataSource: DataSourceKind get() = DataSourceKind.LIVE

    /**
     * Official API or developer-documentation page for this source. Shown in the
     * diagnostics screen so the user can read the source's own terms and limits.
     */
    val documentationUrl: String get() = homepage

    /** True when the source stays dormant until the user saves credentials. */
    val requiresCredentials: Boolean get() = false

    /**
     * True when the source supports server-side keyword search, so sending the
     * candidate's own title narrows the feed instead of being ignored.
     */
    val supportsServerSideSearch: Boolean get() = capabilities.serverSideKeyword

    /**
     * What this source's API actually supports. Defaults to "no server-side filtering",
     * which is the honest default for a feed whose parameters are not documented; concrete
     * providers override it with what their API really does.
     */
    val capabilities: ProviderCapabilities get() = ProviderCapabilities()

    /**
     * Fetches the current postings.
     *
     * @param keywords lower-cased search terms; a provider may ignore them when the
     * feed does not support server-side search, since the client filters anyway.
     * @param limit a soft cap on how many postings to request.
     */
    suspend fun fetch(keywords: List<String> = emptyList(), limit: Int = 60): List<RawJob>
}

/** Raised by a provider when the feed is unreachable or malformed. */
class JobProviderException(
    val providerId: String,
    message: String,
    cause: Throwable? = null,
    /**
     * Explicit reason, set when the provider itself can tell what went wrong (for
     * example "no key configured" or "HTTP 401"). When null, [ProviderErrorClassifier]
     * infers the category from [cause] and the message.
     */
    val category: ProviderErrorCategory? = null,
) : Exception(message, cause)

/**
 * Why a provider attempt did not return usable postings. Kept coarse and stable so the
 * diagnostics screen and the sync banner can describe the failure in plain words
 * instead of showing a stack trace.
 */
enum class ProviderErrorCategory(val label: String, val explanation: String) {
    NOT_CONFIGURED(
        "Not configured",
        "This source needs credentials before it can be searched. Add them in Profile → Job sources.",
    ),
    AUTHENTICATION(
        "Authentication failed",
        "The source rejected the saved credentials. Check that the key is correct and still active.",
    ),
    RATE_LIMITED(
        "Rate limited",
        "The source is temporarily refusing requests. Wait a little before trying again.",
    ),
    TIMEOUT(
        "Timed out",
        "The source did not answer in time. It may be slow or unreachable right now.",
    ),
    NETWORK(
        "Network error",
        "The source could not be reached. Check your internet connection and try again.",
    ),
    INVALID_RESPONSE(
        "Invalid response",
        "The source replied, but its response could not be understood. Nothing was changed.",
    ),
    HTTP_ERROR(
        "Request rejected",
        "The source answered with an error status instead of job data.",
    ),
    UNKNOWN(
        "Unknown failure",
        "The source failed for a reason the app could not classify.",
    ),
}
