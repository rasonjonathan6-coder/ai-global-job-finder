package com.aiglobal.jobfinder.data.repository

import com.aiglobal.jobfinder.data.model.DashboardStats
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobSource
import com.aiglobal.jobfinder.data.model.JobSyncState
import com.aiglobal.jobfinder.data.model.SourceState
import com.aiglobal.jobfinder.data.model.UserProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Deterministic fixture used by screenshot and navigation tests. Keeps the UI
 * reproducible without a network while exposing the same interface as
 * [LiveJobRepository], so tests exercise the real screen code paths.
 */
class SampleJobRepository(
    private val clock: () -> Long = System::currentTimeMillis,
    private val refreshDelayMillis: Long = 900L,
) : JobRepository {

    private val now = clock()

    private val _jobs = MutableStateFlow(SampleData.jobs(now))
    override val jobs: Flow<List<Job>> = _jobs.asStateFlow()

    private val _profile = MutableStateFlow(SampleData.profile(now))
    override val profile: Flow<UserProfile> = _profile.asStateFlow()

    override val syncState: Flow<JobSyncState> = MutableStateFlow(
        JobSyncState(
            sources = listOf(
                JobSource("arbeitnow", "Arbeitnow", "https://www.arbeitnow.com", SourceState.SUCCESS, 3),
                JobSource("remotive", "Remotive", "https://remotive.com", SourceState.SUCCESS, 3),
                JobSource("jobicy", "Jobicy", "https://jobicy.com", SourceState.SUCCESS, 2),
            ),
            lastSyncedAtEpochMillis = now,
        ),
    ).asStateFlow()

    override val stats: Flow<DashboardStats> = MutableStateFlow(
        DashboardStats(
            matchingJobs = _jobs.value.count { it.matchScore >= 60 },
            newOpportunities = _jobs.value.count { it.isNew },
            applications = 0,
            interviews = 0,
        ),
    ).asStateFlow()

    /** Mimics the latency of a real job-board query so loading UI is exercised. */
    override suspend fun refresh() {
        if (refreshDelayMillis > 0) delay(refreshDelayMillis)
    }

    override suspend fun toggleSaved(jobId: String) {
        _jobs.update { list -> list.map { if (it.id == jobId) it.copy(isSaved = !it.isSaved) else it } }
    }

    override fun jobById(id: String): Job? = _jobs.value.firstOrNull { it.id == id }

    override suspend fun setAutoApply(enabled: Boolean) {
        _profile.update { it.copy(autoApplyEnabled = enabled) }
    }
}
