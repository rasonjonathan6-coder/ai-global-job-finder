package com.aiglobal.jobfinder.work

import com.aiglobal.jobfinder.data.notifications.JobNotificationPolicy
import com.aiglobal.jobfinder.data.notifications.JobNotificationsCoordinator
import com.aiglobal.jobfinder.data.repository.JobRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

/**
 * The single unit of work a background refresh performs: read the feeds, then decide whether
 * any new strong match is worth a notification. Extracted from [JobRefreshWorker] so the
 * policy can be exercised on the JVM without WorkManager.
 */
class JobRefreshRunner(
    private val repository: JobRepository,
    private val notifications: JobNotificationsCoordinator,
    private val notificationsEnabled: Flow<Boolean>,
    /** The user's alert relevance bar, read fresh each run so a changed setting takes effect. */
    private val alertMinMatchScore: Flow<Int> = flowOf(JobNotificationPolicy.DEFAULT_MIN_MATCH),
    private val demoMode: Flow<Boolean>,
) {

    /**
     * @return the notification ids posted this run (usually none).
     */
    suspend fun run(): List<String> {
        // Demo fixtures are not real opportunities, so they must never trigger a notification.
        if (demoMode.first()) return emptyList()

        repository.runLiveValidation()

        val jobs = repository.jobs.first()
        // The ledger already told the repository which ids were first seen on this read, so a
        // scheduled refresh can announce only genuinely new postings rather than every strong
        // match that still sits in the feed. The smart gate additionally drops the ones that
        // are not worth an interruption even when new.
        val newJobIds = repository.newPostings.first().jobIds.toSet()
        val posted = notifications.notifyNewMatches(
            jobs = jobs,
            enabled = notificationsEnabled.first(),
            minMatchScore = alertMinMatchScore.first(),
            newJobIds = newJobIds,
        )
        return posted.map { it.jobId }
    }
}
