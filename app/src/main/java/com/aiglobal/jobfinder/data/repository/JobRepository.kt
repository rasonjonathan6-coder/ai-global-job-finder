package com.aiglobal.jobfinder.data.repository

import com.aiglobal.jobfinder.data.model.DashboardStats
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobSyncState
import com.aiglobal.jobfinder.data.model.ProviderDiagnostics
import com.aiglobal.jobfinder.data.model.UserProfile
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for the app's data.
 *
 * [LiveJobRepository] reads real postings from job providers, caches them, and scores
 * them against the candidate profile. [SampleJobRepository] remains as a deterministic
 * fixture for screenshot tests.
 *
 * The application pipeline lives in [com.aiglobal.jobfinder.data.applications.ApplicationCenter],
 * not here: tracking applications is a different concern from fetching jobs, and keeping
 * one owner avoids two competing copies of the same records.
 */
interface JobRepository {
    val jobs: Flow<List<Job>>
    val profile: Flow<UserProfile>
    val stats: Flow<DashboardStats>

    /** Per-source sync status, so a failed feed is visible rather than silently empty. */
    val syncState: Flow<JobSyncState>

    /**
     * Per-source diagnostics from the last real attempt: status, counts, timing and the
     * classified reason for any failure. Distinct from [syncState], which only carries the
     * coarse state the list screen needs.
     */
    val diagnostics: Flow<ProviderDiagnostics>

    /**
     * Re-reads every enabled source and records a fresh diagnostic for each. Safe to call
     * repeatedly: a second call while one is running is ignored rather than queued, and a
     * failed source never clears the postings already on screen.
     */
    suspend fun runLiveValidation()

    suspend fun refresh()
    suspend fun toggleSaved(jobId: String)
    fun jobById(id: String): Job?
    suspend fun setAutoApply(enabled: Boolean)

    /**
     * Switches the repository between the live feeds and the deterministic demo fixtures.
     * Off by default; an explicit user choice, so a fresh install never shows sample data.
     */
    suspend fun setDemoMode(enabled: Boolean)
}
