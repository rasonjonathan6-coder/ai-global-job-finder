package com.aiglobal.jobfinder.data.repository

import com.aiglobal.jobfinder.data.jobs.JobCache
import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.provider.JobProvider
import com.aiglobal.jobfinder.data.jobs.provider.JobProviderException
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.DashboardStats
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobSource
import com.aiglobal.jobfinder.data.model.JobSyncState
import com.aiglobal.jobfinder.data.model.SourceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Aggregates the live job providers.
 *
 * - All providers are queried concurrently; one failing source never blocks the others.
 * - Results are cached so the app opens with content and keeps working offline.
 * - Postings are re-scored whenever the candidate profile changes, so importing a CV
 *   immediately reorders the list without another network call.
 *
 * Applications and the dashboard greeting are still seeded content; Stage 4 owns the
 * application pipeline. Only the job feed is live in Stage 3.
 */
class LiveJobRepository(
    private val providers: List<JobProvider>,
    private val mapper: JobMapper,
    private val candidateProfile: Flow<CandidateProfile>,
    private val cache: JobCache,
    private val clock: () -> Long = System::currentTimeMillis,
    private val perProviderLimit: Int = 60,
) : JobRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val syncMutex = Mutex()

    private val rawJobs = MutableStateFlow<List<RawJob>>(emptyList())
    private val savedIds = MutableStateFlow<Set<String>>(emptySet())

    private val _syncState = MutableStateFlow(
        JobSyncState(sources = providers.map { JobSource(it.id, it.name, it.homepage, SourceState.IDLE) }),
    )
    override val syncState: StateFlow<JobSyncState> = _syncState.asStateFlow()

    private val scoredJobs: StateFlow<List<Job>> =
        combine(rawJobs, candidateProfile) { raw, profile ->
            raw.map { mapper.toJob(it, profile) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val jobs: Flow<List<Job>> = combine(scoredJobs, savedIds) { jobList, saved ->
        jobList.map { if (saved.contains(it.id)) it.copy(isSaved = true) else it }
    }

    private val seedNow = clock()
    private val _profile = MutableStateFlow(SampleData.profile(seedNow))
    override val profile: Flow<com.aiglobal.jobfinder.data.model.UserProfile> = _profile.asStateFlow()

    override val stats: Flow<DashboardStats> =
        jobs.map { jobList ->
            DashboardStats(
                matchingJobs = jobList.count { it.matchScore >= 60 },
                newOpportunities = jobList.count { it.isNew },
                applications = 0,
                interviews = 0,
            )
        }.stateIn(scope, SharingStarted.Eagerly, DashboardStats(0, 0, 0, 0))

    init {
        // Serve the cache immediately so the first frame has content, then refresh.
        val cached = cache.read()
        if (cached.isNotEmpty()) rawJobs.value = cached
        scope.launch { refresh() }
    }

    override suspend fun refresh() {
        if (!syncMutex.tryLock()) return
        try {
            _syncState.update { it.copy(isRefreshing = true) }
            markAllLoading()

            val results = coroutineScope {
                providers.map { provider ->
                    async {
                        runCatching { provider.fetch(limit = perProviderLimit) }
                            .fold(
                                onSuccess = { provider to Result.success(it) },
                                onFailure = { provider to Result.failure(it) },
                            )
                    }
                }.awaitAll()
            }

            val collected = mutableListOf<RawJob>()
            var anySuccess = false
            val sourceStates = mutableListOf<JobSource>()
            for ((provider, result) in results) {
                result.onSuccess { fetched ->
                    anySuccess = true
                    collected += fetched
                    sourceStates += JobSource(
                        id = provider.id,
                        name = provider.name,
                        homepage = provider.homepage,
                        state = SourceState.SUCCESS,
                        jobCount = fetched.size,
                    )
                }.onFailure { error ->
                    sourceStates += JobSource(
                        id = provider.id,
                        name = provider.name,
                        homepage = provider.homepage,
                        state = SourceState.ERROR,
                        message = (error as? JobProviderException)?.message
                            ?: error.message
                            ?: "Could not reach this source",
                    )
                }
            }

            val deduped = dedupe(collected)
            if (anySuccess && deduped.isNotEmpty()) {
                rawJobs.value = deduped
                cache.write(deduped)
            }

            _syncState.update {
                it.copy(
                    isRefreshing = false,
                    lastSyncedAtEpochMillis = if (anySuccess) clock() else it.lastSyncedAtEpochMillis,
                    sources = sourceStates,
                    showingCachedOnly = !anySuccess && rawJobs.value.isNotEmpty(),
                )
            }
        } finally {
            syncMutex.unlock()
        }
    }

    override suspend fun toggleSaved(jobId: String) {
        savedIds.update { current -> if (current.contains(jobId)) current - jobId else current + jobId }
    }

    override fun jobById(id: String): Job? = scoredJobs.value.firstOrNull { it.id == id }

    override suspend fun setAutoApply(enabled: Boolean) {
        _profile.update { it.copy(autoApplyEnabled = enabled) }
    }

    private fun markAllLoading() {
        _syncState.update { state ->
            state.copy(sources = state.sources.map { it.copy(state = SourceState.LOADING) })
        }
    }

    /** Drops postings the same employer lists twice across sources. */
    private fun dedupe(jobs: List<RawJob>): List<RawJob> {
        val seen = HashSet<String>()
        return jobs.filter { job ->
            seen.add("${job.company.lowercase().trim()}|${job.title.lowercase().trim()}")
        }
    }
}
