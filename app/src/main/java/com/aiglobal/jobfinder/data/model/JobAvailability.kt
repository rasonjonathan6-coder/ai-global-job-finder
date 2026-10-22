package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Whether a posting can still be applied to, stated only as strongly as the evidence allows.
 *
 * The three outcomes are deliberately few, because the whole point of this axis is that a
 * candidate must be able to trust it at a glance:
 *
 *  - [OPEN] — a reliable indicator positively says the vacancy is still taking applicants:
 *    the source's own flag, or a link that answered with a positive "still open" marker.
 *  - [EXPIRED] — a reliable indicator positively says it is closed: the source's own flag,
 *    an employer deadline that has passed, or a link that answered "no longer available".
 *  - [UNCONFIRMED] — the app could not settle it. A network error, a timeout, a CORS block,
 *    a missing field, or a source that simply does not say all land here. This is the honest
 *    default and is never shown as a negative.
 *
 * Two rules the rest of the code must never break:
 *  1. **A plain HTTP 200 is not proof of openness.** A 200 only proves a page exists; it may
 *     be a landing page, a redirect or a stale shell. Only a positive marker promoted to a
 *     signal can turn [OPEN], and only the source's own flag is treated as such today.
 *  2. **A network error or timeout never means [EXPIRED].** Failing to reach a listing is a
 *     fact about this device's connection, not about the employer's vacancy.
 */
enum class AvailabilityStatus(val label: String) {
    OPEN("Open"),
    EXPIRED("Expired"),
    UNCONFIRMED("Unconfirmed");

    /** True when the app has positive evidence the offer is closed. */
    val isExpired: Boolean get() = this == EXPIRED

    /** True when the app has positive evidence the offer is live. */
    val isOpen: Boolean get() = this == OPEN

    /** True when the app could not settle it; shown as neutral, never as a rejection. */
    val isUnconfirmed: Boolean get() = this == UNCONFIRMED
}

/**
 * Which reliable indicator produced an [AvailabilityStatus]. Recorded so the UI can say
 * *why* a verdict was reached, and so a verdict is never displayed without its reason.
 */
enum class AvailabilitySignalType(val label: String) {
    /** The source's own "still accepting applicants" flag. The strongest indicator. */
    SOURCE_FLAG("Source flag"),
    /** A deadline the employer published, already behind us. */
    EMPLOYER_DEADLINE("Employer deadline"),
    /** The application link itself, probed and answered. Only when probing is allowed. */
    LINK_PROBE("Application link"),
    /** The app could not reach any reliable indicator. */
    NONE("No signal");

    /** True when this signal is strong enough to promote a posting to [AvailabilityStatus.OPEN]. */
    val canConfirmOpen: Boolean get() = this == SOURCE_FLAG || this == LINK_PROBE

    /** True when this signal is strong enough to demote a posting to [AvailabilityStatus.EXPIRED]. */
    val canConfirmExpired: Boolean get() = this == SOURCE_FLAG || this == EMPLOYER_DEADLINE || this == LINK_PROBE
}

/**
 * One reason behind an [AvailabilityResult]: which indicator spoke, what it said, and where
 * it can be checked. A result with no evidence is [AvailabilityStatus.UNCONFIRMED] by
 * construction, so nothing is ever asserted without one of these behind it.
 */
@Immutable
@Serializable
data class AvailabilityEvidence(
    val type: AvailabilitySignalType,
    /** A plain sentence for the candidate, never a raw status code on its own. */
    val detail: String,
    /** The employer's or board's own URL, so the claim can be checked; empty when none. */
    val sourceUrl: String = "",
)

/**
 * The availability read for one posting: the verdict, the reasons behind it, and when the
 * app last looked. Kept separate from [OfferVerification] (which is a multi-axis reliability
 * panel) and from the CV match, because "can I still apply?" is the single question a
 * candidate asks first and it must have its own, unambiguous answer.
 *
 * [lastCheckedAtEpochMillis] is always the moment of the check that produced this result,
 * so the UI can show "Checked today" or "Checked 3 days ago" and never present an old read
 * as a fresh one.
 */
@Immutable
@Serializable
data class AvailabilityResult(
    val status: AvailabilityStatus,
    val evidence: List<AvailabilityEvidence> = emptyList(),
    /** When this result was produced. Null only for a result that was never computed. */
    val lastCheckedAtEpochMillis: Long? = null,
) {
    val isExpired: Boolean get() = status.isExpired
    val isOpen: Boolean get() = status.isOpen
    val isUnconfirmed: Boolean get() = status.isUnconfirmed

    /** The first signal that decided the verdict, or [AvailabilitySignalType.NONE]. */
    val decisiveSignal: AvailabilitySignalType
        get() = evidence.firstOrNull()?.type ?: AvailabilitySignalType.NONE

    companion object {
        /**
         * The honest empty read: nothing was checked, so nothing is claimed. Used before a
         * posting has been assessed, so the UI shows "not confirmed" rather than a verdict.
         */
        val UNKNOWN = AvailabilityResult(
            status = AvailabilityStatus.UNCONFIRMED,
            evidence = emptyList(),
            lastCheckedAtEpochMillis = null,
        )
    }
}
