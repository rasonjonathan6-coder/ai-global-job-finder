package com.aiglobal.jobfinder.data.submissions

import androidx.compose.runtime.Immutable
import com.aiglobal.jobfinder.data.model.Job
import kotlinx.serialization.Serializable

/**
 * Identity of an application, used to stop the same opening being applied to twice.
 *
 * The key is deliberately provider-agnostic and independent of the app's own job id:
 * the same posting reaches the app from several boards (Adzuna, Jooble, EURES, an
 * employer ATS), each with its own id and URL, and those ids change between releases.
 * Employer + normalised title + country is the strongest stable signal available offline,
 * and it matches the rule the feed already uses to collapse cross-source duplicates, so a
 * record tracked under one source is recognised when the same job arrives under another.
 *
 * Two different roles at the same large employer stay distinct because the title is part
 * of the key; the country component keeps "Technician" at a global employer's two sites
 * from collapsing into one.
 */
@Serializable
@Immutable
data class SubmissionKey(
    val normalizedCompany: String,
    val normalizedTitle: String,
    val normalizedCountry: String,
) {
    /** Canonical value stored in the registry and used as the dedup index. */
    val value: String get() = "$normalizedCompany|$normalizedTitle|$normalizedCountry"

    companion object {
        /** Normalises one component: case-folded, punctuation stripped, single spaces. */
        fun normalise(value: String): String =
            value.lowercase()
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()

        /** Derives the key for a posting. Falls back to the title when the employer is blank. */
        fun of(job: Job): SubmissionKey = of(
            company = job.company.ifBlank { job.title },
            title = job.title,
            country = job.country,
        )

        fun of(company: String, title: String, country: String): SubmissionKey = SubmissionKey(
            normalizedCompany = normalise(company),
            normalizedTitle = normalise(title),
            normalizedCountry = normalise(country),
        )
    }
}

/**
 * The kind of application route a posting offers. Real sending is only ever considered for
 * a channel the app can actually drive; everything else is the user's own job in a browser.
 *
 * [realSendSupported] is the compatibility gate: it is false for [EMPLOYER_PORTAL] and
 * [EMAIL], because those need a signed-in session or the user's mailbox that the app does
 * not — and must not — hold. The app will therefore never send an application for those
 * channels, regardless of any setting, until a real, verified integration exists.
 */
@Serializable
enum class SubmissionChannel(
    val label: String,
    val realSendSupported: Boolean,
    /** Plain-language reason shown to the user when a real send is refused. */
    val compatibilityNote: String,
) {
    /** A public HTTP endpoint that accepts a structured application. Not yet implemented. */
    ATS_API(
        label = "Employer API",
        realSendSupported = false,
        compatibilityNote = "No verified employer API integration is available, so the app will not send it.",
    ),
    EMPLOYER_PORTAL(
        label = "Employer portal",
        realSendSupported = false,
        compatibilityNote = "This employer needs you signed in on their own portal. The app cannot do that for you.",
    ),
    EMAIL(
        label = "Email",
        realSendSupported = false,
        compatibilityNote = "Sending by email would need access to your mailbox. The app will never ask for your email password.",
    ),
    UNKNOWN(
        label = "Unverified channel",
        realSendSupported = false,
        compatibilityNote = "The application route could not be verified, so nothing can be sent automatically.",
    );

    companion object {
        /**
         * Picks the route a posting offers, using only evidence in the posting. Anything
         * not recognisable is [UNKNOWN] rather than a guess, so the app never claims it can
         * send somewhere it cannot.
         */
        fun infer(job: Job, sourceUrl: String = job.sourceUrl): SubmissionChannel {
            val url = sourceUrl.lowercase()
            if (url.isBlank()) return UNKNOWN
            val host = url.substringAfter("://").substringBefore('/')
            val atsHosts = listOf(
                "greenhouse.io", "lever.co", "ashbyhq.com", "myworkdayjobs.com",
                "workday.com", "smartrecruiters.com", "workable.com", "bamboohr.com",
            )
            return when {
                atsHosts.any { host.endsWith(it) || host.contains(it) } -> ATS_API
                url.startsWith("mailto:") -> EMAIL
                host.isNotBlank() && host.contains('.') -> EMPLOYER_PORTAL
                else -> UNKNOWN
            }
        }
    }
}

/** What the registry did with a request. */
@Serializable
enum class SubmissionOutcome(val label: String, val simulated: Boolean) {
    /** A real application was sent. Reserved for a future, verified integration. */
    SENT("Sent", simulated = false),

    /** The app walked the whole flow but sent nothing — the default and current behaviour. */
    DRY_RUN("Simulated (nothing sent)", simulated = true),

    /** The same opening was already recorded; the second attempt was refused. */
    BLOCKED_DUPLICATE("Already applied — blocked", simulated = true),

    /** Another send of this same opening is in flight; the concurrent attempt was refused. */
    BLOCKED_CONCURRENT("Another send is in progress — blocked", simulated = true),

    /** A real send was requested but sending is switched off. */
    BLOCKED_NOT_ENABLED("Sending is switched off — blocked", simulated = true),

    /** A real send was requested but the channel cannot be driven by the app. */
    BLOCKED_INCOMPATIBLE("Channel not supported — blocked", simulated = true),

    /**
     * The posting's identity is close to one already recorded, but not provably the same.
     * Nothing is sent and nothing is merged: the attempt is parked for the user to confirm.
     */
    NEEDS_VERIFICATION("Needs your verification", simulated = true),
}

/**
 * One recorded application attempt. The registry keeps exactly one entry per
 * [SubmissionKey]; a blocked attempt never overwrites the receipt of the real one.
 */
@Serializable
@Immutable
data class SubmissionEntry(
    val key: String,
    val jobId: String,
    val jobTitle: String,
    val company: String,
    val country: String,
    /** The source record id (`providerId:externalId`) the identity was built from. */
    val providerRecordId: String = "",
    /** The canonical posting URL the identity was built from, tracking stripped. */
    val canonicalUrl: String = "",
    /** The source the application was actually made through, for attribution. */
    val source: String,
    val channel: SubmissionChannel,
    val outcome: SubmissionOutcome,
    /** True only when [outcome] is [SubmissionOutcome.SENT]. */
    val sent: Boolean,
    /** Every source this opening has been seen from, so the duplicate is explainable. */
    val seenSources: List<String>,
    val firstRequestedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val reason: String,
    /**
     * When the identity was ambiguous, the fingerprint of the close-but-not-identical entry
     * the user is being asked to compare against. Blank otherwise.
     */
    val pendingReview: String = "",
    /**
     * Extra identity signals (source record ids and canonical URLs) this entry is known by,
     * after the user confirmed that a near-identical posting was the same opening. They are
     * indexed alongside the primary signals so the confirmed duplicate is recognised later.
     */
    val aliases: List<String> = emptyList(),
) {
    /** True when a real application has already gone out — the hard stop for a resend. */
    val isCommitted: Boolean get() = sent

    /** True when the user must confirm whether this is the same posting or a new one. */
    val awaitsVerification: Boolean get() = outcome == SubmissionOutcome.NEEDS_VERIFICATION
}

/** The registry's answer to "may this be applied to?". */
@Immutable
data class SubmissionDecision(
    val key: SubmissionKey,
    val outcome: SubmissionOutcome,
    /** True when the request was recorded; false when it was refused. */
    val recorded: Boolean,
    val channel: SubmissionChannel,
    val reason: String,
) {
    val isDuplicate: Boolean
        get() = outcome == SubmissionOutcome.BLOCKED_DUPLICATE || outcome == SubmissionOutcome.BLOCKED_CONCURRENT
}

/**
 * A request to apply. [allowRealSend] is a per-call opt-in and defaults to false, so the
 * simulation path is the one taken unless a caller deliberately asks for a real send — and
 * even then the registry still requires the global flag and a compatible channel.
 */
@Immutable
data class SubmissionRequest(
    val job: Job,
    val channel: SubmissionChannel = SubmissionChannel.infer(job),
    val allowRealSend: Boolean = false,
)

/**
 * The user's standing policy for sending. Persisted; [sendingEnabled] is false on a fresh
 * install and can only become true by an explicit activation. Turning it on still does not
 * make the app send: every channel must also be [SubmissionChannel.realSendSupported].
 */
@Serializable
@Immutable
data class SubmissionPolicy(
    val sendingEnabled: Boolean = false,
) {
    companion object {
        /** The safe default: nothing is ever sent. */
        val DISABLED = SubmissionPolicy(sendingEnabled = false)
    }
}
