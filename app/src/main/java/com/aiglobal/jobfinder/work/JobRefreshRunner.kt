package com.aiglobal.jobfinder.work

import com.aiglobal.jobfinder.data.notifications.JobNotificationPolicy
import com.aiglobal.jobfinder.data.notifications.JobNotificationsCoordinator
import com.aiglobal.jobfinder.data.model.ApplicationDraft
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
    /**
     * Drafts awaiting the user's approval. Read on the same run so an application prepared in
     * the background can raise its "ready for review" alert without a second schedule.
     */
    private val pendingDrafts: Flow<List<ApplicationDraft>> = flowOf(emptyList()),
) {

    /**
     * @return the notification ids posted this run (usually none).
     */
    suspend fun run(): List<String> {
        // Demo fixtures are not real opportunities, so they must never trigger a notification.
        if (demoMode.first()) return emptyList()

        repository.runLiveValidation()

        val jobs = repository.jobs.first()
        val enabled = notificationsEnabled.first()
        // The ledger already told the repository which ids were first seen on this read, so a
        // scheduled refresh can announce only genuinely new postings rather than every strong
        // match that still sits in the feed. The smart gate additionally drops the ones that
        // are not worth an interruption even when new.
        val newJobIds = repository.newPostings.first().jobIds.toSet()
        val posted = notifications.notifyNewMatches(
            jobs = jobs,
            enabled = enabled,
            minMatchScore = alertMinMatchScore.first(),
            newJobIds = newJobIds,
        )
        // Prepared applications get their own alert, on their own channel and history, so a
        // preparation nudge is never confused with a new-match alert. Never claims a send.
        notifications.notifyDraftReviews(drafts = pendingDrafts.first(), enabled = enabled)
        return posted.map { it.jobId }
    }
}
