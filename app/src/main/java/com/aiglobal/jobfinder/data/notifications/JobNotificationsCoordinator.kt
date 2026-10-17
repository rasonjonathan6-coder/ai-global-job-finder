package com.aiglobal.jobfinder.data.notifications

import com.aiglobal.jobfinder.data.model.Job

/**
 * Runs the notification decision for a finished refresh: picks the strong, new matches,
 * posts them, and records that they were posted so they are never announced again.
 *
 * The pure decision lives in [JobNotificationPolicy]; this class only wires it to the
 * channel and the history store, so the "who gets notified" rule is testable on its own.
 */
class JobNotificationsCoordinator(
    private val center: JobNotificationCenter,
    private val history: NotificationHistoryStore,
    private val policy: JobNotificationPolicy = JobNotificationPolicy(),
) {

    /** Ensures the channel exists; safe to call at app start. */
    fun prepare() = center.ensureChannel()

    /**
     * @param jobs the current feed (already scored against the CV)
     * @param enabled the user's notification switch
     * @param minMatchScore the user's relevance threshold; postings below it stay silent
     * @param newJobIds when non-null, the smart-alert path is used: only postings the ledger
     *   reports as genuinely new may be announced, and each must also clear the quality gate
     *   (not a scam, not local-only, not unrelated, and still actionable). Left null for a
     *   manual pass, which keeps the Stage 20 behaviour of announcing any strong match.
     * @return the notifications posted, for diagnostics and tests
     */
    suspend fun notifyNewMatches(
        jobs: List<Job>,
        enabled: Boolean,
        minMatchScore: Int = JobNotificationPolicy.DEFAULT_MIN_MATCH,
        newJobIds: Set<String>? = null,
    ): List<JobNotification> {
        if (!enabled || jobs.isEmpty()) return emptyList()
        // The smart gate only ever narrows the set: a posting it rejects is one that would be
        // a bad interruption even though it scored well. Applied before the score bar so the
        // two rules stay independent and each is separately testable.
        val eligible = if (newJobIds == null) {
            jobs
        } else {
            SmartAlertPolicy.NEW_ONLY.filter(jobs, newJobIds)
        }
        if (eligible.isEmpty()) return emptyList()
        val already = history.notifiedIds()
        // The threshold is per-run, so the same coordinator serves a user who has moved it.
        val selected = JobNotificationPolicy(minMatchScore = minMatchScore).select(eligible, already)
        if (selected.isEmpty()) return emptyList()
        // Record first: a post that races a permission denial still must not repeat later.
        history.record(selected.map { it.jobId })
        center.post(selected)
        return selected
    }
}
