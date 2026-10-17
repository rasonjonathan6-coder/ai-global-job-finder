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
     * A factual description of every registered source: where its postings come from (live,
     * fixtures or mock), what its API supports, and how it is reached. Derived from the same
     * providers the feed runs, so it cannot drift from reality. Empty when the repository has
     * no provider list of its own (the fixture repository), so the UI shows nothing rather
     * than guessing. This states what is true of a source; it never claims a source answered.
     */
    val providerAudit: List<com.aiglobal.jobfinder.data.jobs.provider.ProviderAudit>
        get() = emptyList()

    /**
     * How the last search was run: which sources could narrow by keyword, which returned a
     * broad sample, and how many postings matched a target role. Empty until the first
     * attempt completes.
     */
    val coverage: Flow<com.aiglobal.jobfinder.data.jobs.search.SearchCoverage>

    /**
     * Re-reads every enabled source and records a fresh diagnostic for each. Safe to call
     * repeatedly: a second call while one is running is ignored rather than queued, and a
     * failed source never clears the postings already on screen.
     */
    suspend fun runLiveValidation()

    /**
     * What the incremental search found on the most recent read: postings the app had never
     * seen, postings whose content changed, and how many it now knows in total. Empty until
     * the first attempt completes.
     */
    val discovery: Flow<com.aiglobal.jobfinder.data.discovery.DiscoveryDelta>

    /**
     * The postings first seen since the user last reviewed them, plus whether a baseline has
     * been recorded. This is the same incremental signal as [discovery], projected for the Home
     * and Jobs surfaces: the initial market snapshot is never counted, and marking the list
     * reviewed clears it. Empty until the first attempt completes.
     */
    val newPostings: Flow<com.aiglobal.jobfinder.data.discovery.NewPostings>

    /**
     * Clears the "new postings" list by moving the reviewed watermark to now. Called when the
     * user acknowledges the new opportunities, so the badge does not nag about what they saw.
     */
    suspend fun markNewPostingsReviewed()

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
