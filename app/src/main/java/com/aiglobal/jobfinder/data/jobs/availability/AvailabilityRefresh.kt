package com.aiglobal.jobfinder.data.jobs.availability

import com.aiglobal.jobfinder.data.model.AvailabilityResult
import com.aiglobal.jobfinder.data.model.Job

/**
 * Applies [AvailabilityVerifier] across a whole feed while respecting a request budget.
 *
 * Probing every link on every refresh would be abusive to the sources and slow, so at most
 * [probeBudget] link requests are spent within one [budgetWindowMillis] window. The window is
 * held *across* refreshes, not restarted by each one: a background refresh every few hours
 * would otherwise reset a per-refresh counter and reach far more links per day than the cap
 * intends. When a [store] is supplied the window also survives a restart, so the daily cap
 * cannot be multiplied by relaunching the app.
 *
 * Postings the verifier has already settled within the 24 h cache cost nothing; postings the
 * source's own flag already decided cost nothing; the rest are checked up to the budget. The
 * cache is consulted *before* the budget is spent, so a fresh verdict never consumes a slot
 * and a repeat refresh of the same feed consumes no requests at all.
 *
 * It never changes the underlying postings: it only attaches an [AvailabilityResult] to each,
 * so a failed probe leaves the posting exactly as the sources described it. A posting that
 * could not be probed because the budget was spent stays
 * [com.aiglobal.jobfinder.data.model.AvailabilityStatus.UNCONFIRMED] — never
 * [com.aiglobal.jobfinder.data.model.AvailabilityStatus.EXPIRED].
 */
class AvailabilityRefresh(
    private val verifier: AvailabilityVerifier,
    private val probeBudget: Int = DEFAULT_PROBE_BUDGET,
    /**
     * The window the [probeBudget] applies to. Defaults to 24 h so the cap is a *daily* limit,
     * matching the cache's own window and the sources' expectation of at most one reach per
     * posting per day.
     */
    private val budgetWindowMillis: Long = DEFAULT_BUDGET_WINDOW_MILLIS,
    /** Optional persistence, so the window survives a restart. Null keeps it in memory. */
    private val store: AvailabilityBudgetStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private var probesSpent: Int
    private var windowStartedAt: Long

    init {
        // Restore the persisted window so a relaunch does not reset the daily cap. A state whose
        // window has already elapsed is kept as-is and rolls over lazily on the first claim, so
        // the "spent" count is never misread as belonging to a fresh window.
        val restored = store?.read()
        windowStartedAt = restored?.windowStartedAtEpochMillis ?: clock()
        probesSpent = restored?.probesSpent?.coerceAtLeast(0) ?: 0
    }

    /**
     * Returns a map of job id to verified availability. Jobs whose verdict is unchanged from
     * the honest default are still included, so the caller can show "checked at" times.
     */
    suspend fun overlay(jobs: List<Job>): Map<String, AvailabilityResult> {
        val out = LinkedHashMap<String, AvailabilityResult>(jobs.size)
        for (job in jobs) {
            // Reuse a fresh settled verdict before touching the budget: a posting checked today
            // must not consume a slot, and the second refresh of the same feed spends nothing.
            val alreadyCached = verifier.cached(job.id) != null
            val allowProbe = !alreadyCached && verifier.canProbe(job) && claimProbe()
            out[job.id] = verifier.verify(job, allowProbe = allowProbe)
        }
        return out
    }

    /**
     * Claims one probe from the window budget, or returns false when the window is exhausted.
     *
     * The bound is strict and atomic: the count, the window roll-over and the persistence are
     * guarded together, so two refreshes racing for the last slot can never both win, and the
     * stored count always reflects the claimed slot.
     */
    @Synchronized
    private fun claimProbe(): Boolean {
        val now = clock()
        if (now - windowStartedAt >= budgetWindowMillis) {
            windowStartedAt = now
            probesSpent = 0
        }
        if (probesSpent >= probeBudget) return false
        probesSpent++
        store?.write(AvailabilityBudgetState(windowStartedAt, probesSpent))
        return true
    }

    companion object {
        /**
         * At most this many links are reached within one budget window. Small on purpose:
         * availability is settled mostly by the sources' own flags and the 24 h cache, and a
         * daily cap keeps the app a good citizen against every source's terms of use.
         */
        const val DEFAULT_PROBE_BUDGET: Int = 12

        /** One day: the budget is a daily cap, aligned with the availability cache's window. */
        const val DEFAULT_BUDGET_WINDOW_MILLIS: Long = 24L * 60L * 60L * 1000L
    }
}
