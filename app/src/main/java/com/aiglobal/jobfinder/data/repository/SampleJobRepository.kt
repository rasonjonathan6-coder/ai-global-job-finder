package com.aiglobal.jobfinder.data.repository

import com.aiglobal.jobfinder.data.expat.ExpatBenefitExtractor
import com.aiglobal.jobfinder.data.model.DashboardStats
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobSource
import com.aiglobal.jobfinder.data.model.JobSyncState
import com.aiglobal.jobfinder.data.model.ProviderDiagnostic
import com.aiglobal.jobfinder.data.model.ProviderDiagnostics
import com.aiglobal.jobfinder.data.model.ProviderStatus
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

    /**
     * Sample postings carry the same benefit evidence a live posting would. Deriving it
     * here (rather than hard-coding it) means the fixture and the real pipeline can never
     * drift: both run [ExpatBenefitExtractor] over the posting text.
     */
    private val benefitExtractor = ExpatBenefitExtractor()

    private val _jobs = MutableStateFlow(
        SampleData.jobs(now).map { job ->
            job.copy(
                benefitEvidence = benefitExtractor.extract(
                    title = job.title,
                    description = job.summary + " " + job.responsibilities.joinToString(" ") + " " + job.benefits.joinToString(". "),
                ),
            )
        },
    )
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

    override val diagnostics: Flow<ProviderDiagnostics> = MutableStateFlow(
        ProviderDiagnostics(
            providers = listOf(
                ProviderDiagnostic(
                    providerId = "arbeitnow",
                    name = "Arbeitnow",
                    homepage = "https://www.arbeitnow.com",
                    documentationUrl = "https://www.arbeitnow.com/blog/arbeitnow-api",
                    status = ProviderStatus.SUCCESS_WITH_RESULTS,
                    validJobCount = 3,
                    lastAttemptAtEpochMillis = now,
                    lastSuccessAtEpochMillis = now,
                    lastSuccessJobCount = 3,
                    lastDurationMillis = 420,
                ),
                ProviderDiagnostic(
                    providerId = "remotive",
                    name = "Remotive",
                    homepage = "https://remotive.com",
                    documentationUrl = "https://remotive.com/remote-jobs/api",
                    status = ProviderStatus.SUCCESS_WITH_RESULTS,
                    validJobCount = 3,
                    lastAttemptAtEpochMillis = now,
                    lastSuccessAtEpochMillis = now,
                    lastSuccessJobCount = 3,
                    lastDurationMillis = 380,
                ),
                ProviderDiagnostic(
                    providerId = "jobicy",
                    name = "Jobicy",
                    homepage = "https://jobicy.com",
                    documentationUrl = "https://jobicy.com/jobs-rss-feed",
                    status = ProviderStatus.SUCCESS_EMPTY,
                    validJobCount = 0,
                    lastAttemptAtEpochMillis = now,
                    lastSuccessAtEpochMillis = now,
                    lastDurationMillis = 260,
                    message = "The source answered and had no matching jobs.",
                ),
            ),
            networkAvailable = true,
            lastAttemptAtEpochMillis = now,
            lastSuccessAtEpochMillis = now,
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

    override suspend fun runLiveValidation() = refresh()

    override suspend fun toggleSaved(jobId: String) {
        _jobs.update { list -> list.map { if (it.id == jobId) it.copy(isSaved = !it.isSaved) else it } }
    }

    override fun jobById(id: String): Job? = _jobs.value.firstOrNull { it.id == id }

    override suspend fun setAutoApply(enabled: Boolean) {
        _profile.update { it.copy(autoApplyEnabled = enabled) }
    }
}
