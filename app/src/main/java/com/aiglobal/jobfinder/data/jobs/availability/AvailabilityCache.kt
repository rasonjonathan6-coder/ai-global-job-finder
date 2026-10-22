package com.aiglobal.jobfinder.data.jobs.availability

import com.aiglobal.jobfinder.data.model.AvailabilityEvidence
import com.aiglobal.jobfinder.data.model.AvailabilityResult
import com.aiglobal.jobfinder.data.model.AvailabilitySignalType
import com.aiglobal.jobfinder.data.model.AvailabilityStatus

/**
 * A 24-hour store of availability checks, so the same posting is not re-probed on every
 * refresh. It exists to respect source quotas and terms of use: a link is worth reaching at
 * most once a day, and a verdict reached minutes ago is as good as one reached now.
 *
 * Design rules:
 *  - **Only settled verdicts are cached.** [AvailabilityStatus.UNCONFIRMED] results — a
 *    network error, a timeout, a CORS block — are *not* written, so a transient failure is
 *    never frozen for 24 hours. The next attempt tries again.
 *  - **A read is a hit only while the entry is younger than the TTL.** After that the entry
 *    is treated as absent and the caller re-checks.
 *  - **The clock is injected**, so the TTL is testable without waiting a day.
 *
 * Thread-safe: the underlying map is guarded, because refreshes and the UI can both reach it.
 */
class AvailabilityCache(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Optional persistence, so the 24 h window survives a restart. Null keeps it in memory. */
    private val store: AvailabilityCacheStore? = null,
) {

    private val entries = LinkedHashMap<String, AvailabilityResult>()

    init {
        // Restore persisted verdicts so a relaunch does not re-probe links already settled
        // within the window. Expired entries are dropped here exactly as they are on read.
        store?.read()?.forEach { (id, result) ->
            if (!result.status.isUnconfirmed && result.lastCheckedAtEpochMillis != null) {
                entries[id] = result
            }
        }
    }

    /**
     * Returns the cached result for [jobId] when it is a settled verdict younger than the
     * TTL, or null otherwise. An expired entry is dropped on read so it can never be served.
     */
    @Synchronized
    fun get(jobId: String): AvailabilityResult? {
        val entry = entries[jobId] ?: return null
        val checkedAt = entry.lastCheckedAtEpochMillis ?: return null
        if (entry.status.isUnconfirmed) {
            // Defensive: an unconfirmed result must never have been stored, but if one is
            // present it is not a hit, so a transient failure can never be frozen.
            entries.remove(jobId)
            return null
        }
        if (clock() - checkedAt >= ttlMillis) {
            entries.remove(jobId)
            return null
        }
        return entry
    }

    /** True when a fresh, settled verdict is cached for [jobId]. Convenience for callers. */
    fun isFresh(jobId: String): Boolean = get(jobId) != null

    /**
     * Stores [result] when it is a settled verdict. An [AvailabilityStatus.UNCONFIRMED]
     * result is deliberately not stored, so a network error, a timeout or a CORS block does
     * not become a 24-hour "answer".
     */
    @Synchronized
    fun put(jobId: String, result: AvailabilityResult) {
        if (result.status.isUnconfirmed) return
        if (result.lastCheckedAtEpochMillis == null) return
        entries[jobId] = result
        store?.write(entries)
    }

    /** Drops every entry, e.g. when the user asks to re-check now. */
    @Synchronized
    fun clear() {
        entries.clear()
        store?.write(entries)
    }

    /** Number of live entries; used by tests and diagnostics. */
    @Synchronized
    fun size(): Int = entries.size

    companion object {
        /** One day. A verdict younger than this is served without another request. */
        const val DEFAULT_TTL_MILLIS: Long = 24L * 60L * 60L * 1000L
    }
}

/**
 * The outcome of probing one application link. Kept apart from [AvailabilityResult] because
 * a probe can fail for reasons that say nothing about the vacancy, and the caller must be
 * able to tell a "closed" answer from "could not reach".
 */
sealed interface LinkProbeOutcome {
    /** The link answered with a positive "still open" marker. */
    data class Open(val detail: String) : LinkProbeOutcome

    /** The link answered with a definitive "no longer available" marker. */
    data class Closed(val detail: String) : LinkProbeOutcome

    /**
     * The link could not settle the question: a network error, a timeout, a CORS/redirect
     * block, an unexpected status, or a plain 200 with no positive marker. This is never a
     * claim that the posting is open or closed.
     */
    data class Unknown(val detail: String) : LinkProbeOutcome
}

/** Reaches one application link and reports what it observed. Injected so tests use no network. */
fun interface AvailabilityLinkProbe {
    suspend fun probe(url: String): LinkProbeOutcome
}

/**
 * Verifies availability, using the cache to avoid needless requests and the engine for the
 * indicators that need no request at all.
 *
 * The order of decisions is the point of the class:
 *  1. A settled verdict younger than 24 h is served from cache — no request is made.
 *  2. Otherwise the source's own flag and the employer deadline decide. A flag is the
 *     source's own word and outranks everything, so if it is present the link is not probed
 *     at all (saving a request and respecting the source).
 *  3. Only when no flag is present, a probe is configured, and the posting carries a usable
 *     link is the link reached — and then a plain HTTP 200 is *not* enough: only a positive
 *     marker turns [AvailabilityStatus.OPEN]. A definitive "gone" marker turns it
 *     [AvailabilityStatus.EXPIRED]. Anything else stays [AvailabilityStatus.UNCONFIRMED].
 *
 * A settled result is cached for 24 h; an unconfirmed one never is.
 */
class AvailabilityVerifier(
    private val engine: OfferAvailabilityEngine = OfferAvailabilityEngine(),
    private val cache: AvailabilityCache = AvailabilityCache(),
    /** Null disables link probing entirely, leaving only the source's own indicators. */
    private val probe: AvailabilityLinkProbe? = null,
    /** Whether the posting's own text/fields permit reaching its link. Injected to stay pure. */
    private val linkIsUsable: (com.aiglobal.jobfinder.data.model.Job) -> Boolean = { it.sourceUrl.isNotBlank() },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** True when a fresh, settled verdict is already cached, so no request is needed. */
    fun cached(jobId: String): AvailabilityResult? = cache.get(jobId)

    /**
     * True when this posting could be probed: a probe is configured, the source gave no flag
     * (a flag is decisive and needs no request), and the posting carries a usable link.
     * The caller uses this to spend its per-refresh probe budget only where it would help.
     */
    fun canProbe(job: com.aiglobal.jobfinder.data.model.Job): Boolean =
        probe != null && job.openToApplicants == null && linkIsUsable(job)

    /**
     * Verifies availability. [allowProbe] lets the caller hold back link requests to respect
     * source quotas: when false, only the source's own indicators are read and the result is
     * whatever those support (never a link-derived verdict).
     */
    suspend fun verify(
        job: com.aiglobal.jobfinder.data.model.Job,
        allowProbe: Boolean = true,
    ): AvailabilityResult {
        val now = clock()

        // 1. A fresh settled verdict short-circuits everything: no request is made.
        cache.get(job.id)?.let { return it }

        val base = engine.assess(job, now)

        // 2. The source's own flag is decisive and needs no request.
        if (job.openToApplicants != null) {
            cache.put(job.id, base)
            return base
        }

        // 3. No flag: probe the link only when allowed, and only with a usable URL.
        val outcome = if (allowProbe && probe != null && linkIsUsable(job)) {
            probe.probe(job.sourceUrl)
        } else {
            LinkProbeOutcome.Unknown(
                "No application link was probed, so this app cannot confirm the vacancy is still open.",
            )
        }

        val result = mergeProbe(job, base, outcome, now)
        cache.put(job.id, result)
        return result
    }

    private fun mergeProbe(
        job: com.aiglobal.jobfinder.data.model.Job,
        base: AvailabilityResult,
        outcome: LinkProbeOutcome,
        now: Long,
    ): AvailabilityResult = when (outcome) {
        is LinkProbeOutcome.Open -> AvailabilityResult(
            status = AvailabilityStatus.OPEN,
            evidence = base.evidence + AvailabilityEvidence(
                type = AvailabilitySignalType.LINK_PROBE,
                detail = outcome.detail,
                sourceUrl = job.sourceUrl,
            ),
            lastCheckedAtEpochMillis = now,
        )
        is LinkProbeOutcome.Closed -> AvailabilityResult(
            status = AvailabilityStatus.EXPIRED,
            evidence = base.evidence + AvailabilityEvidence(
                type = AvailabilitySignalType.LINK_PROBE,
                detail = outcome.detail,
                sourceUrl = job.sourceUrl,
            ),
            lastCheckedAtEpochMillis = now,
        )
        is LinkProbeOutcome.Unknown -> base.copy(
            // A probe that could not settle it adds a "we tried and could not confirm" fact,
            // but it must never change the verdict to open or expired.
            evidence = base.evidence + AvailabilityEvidence(
                type = AvailabilitySignalType.NONE,
                detail = outcome.detail,
                sourceUrl = job.sourceUrl,
            ),
            lastCheckedAtEpochMillis = now,
        )
    }
}
