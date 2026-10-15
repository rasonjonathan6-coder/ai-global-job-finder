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
     * @return the notifications posted, for diagnostics and tests
     */
    suspend fun notifyNewMatches(jobs: List<Job>, enabled: Boolean): List<JobNotification> {
        if (!enabled || jobs.isEmpty()) return emptyList()
        val already = history.notifiedIds()
        val selected = policy.select(jobs, already)
        if (selected.isEmpty()) return emptyList()
        // Record first: a post that races a permission denial still must not repeat later.
        history.record(selected.map { it.jobId })
        center.post(selected)
        return selected
    }
}
