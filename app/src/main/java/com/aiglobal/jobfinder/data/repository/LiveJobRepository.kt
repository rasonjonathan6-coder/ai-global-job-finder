package com.aiglobal.jobfinder.data.repository

import com.aiglobal.jobfinder.data.jobs.JobCache
import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.engine.RecordValidationEngine
import com.aiglobal.jobfinder.data.jobs.engine.ValidationSummary
import com.aiglobal.jobfinder.data.jobs.provider.JobProvider
import com.aiglobal.jobfinder.data.jobs.provider.JobProviderException
import com.aiglobal.jobfinder.data.jobs.provider.ProviderErrorCategory
import com.aiglobal.jobfinder.data.jobs.provider.ProviderErrorClassifier
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.DashboardStats
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobSource
import com.aiglobal.jobfinder.data.model.JobSyncState
import com.aiglobal.jobfinder.data.model.ProviderDiagnostic
import com.aiglobal.jobfinder.data.model.ProviderDiagnostics
import com.aiglobal.jobfinder.data.model.ProviderSettings
import com.aiglobal.jobfinder.data.model.ProviderStatus
import com.aiglobal.jobfinder.data.model.SourceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Aggregates the live job providers and validates what they return.
 *
 * Design rules, in the order they matter:
 *  - Nothing is asserted that was not observed. A source that was never tried reports
 *    "not tried", not "failed"; a source with no key reports "not configured", not "down".
 *  - A failed source never clears the postings already on screen. The last good list is
 *    kept and the failure is reported alongside it.
 *  - Every record is validated and de-duplicated before it can reach the UI, and the
 *    number and reason of anything dropped is recorded per source.
 *  - Re-scoring happens whenever the profile changes, so importing a CV reorders the list
 *    without another network call.
 */
class LiveJobRepository(
    private val providers: List<JobProvider>,
    private val mapper: JobMapper,
    private val candidateProfile: Flow<CandidateProfile>,
    /** The imported CV record; the display profile is derived from it, never invented. */
    private val cvRecord: Flow<com.aiglobal.jobfinder.data.model.CvRecord?> =
        MutableStateFlow(null),
    private val cache: JobCache,
    private val validationEngine: RecordValidationEngine = RecordValidationEngine(),
    /** Returns true when the device currently has a usable connection. */
    private val isOnline: () -> Boolean = { true },
    providerSettings: Flow<ProviderSettings> = MutableStateFlow(ProviderSettings()),
    /** When true, the repository serves the deterministic sample fixtures instead of the feeds. */
    private val demoMode: Flow<Boolean> = MutableStateFlow(false),
    /** Persists the demo-mode choice; the flow above then reflects it back. */
    private val demoModeSetter: suspend (Boolean) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val perProviderLimit: Int = 60,
    /** Hard ceiling on a single source, so one slow feed cannot hold up the whole refresh. */
    private val perProviderTimeoutMillis: Long = 20_000,
) : JobRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val syncMutex = Mutex()

    private val rawJobs = MutableStateFlow<List<RawJob>>(emptyList())
    private val savedIds = MutableStateFlow<Set<String>>(emptySet())
    private val settings = MutableStateFlow(ProviderSettings())

    /** Latest demo-mode value, kept in sync with [demoMode] so the refresh path can read it. */
    @Volatile
    private var demoEnabled: Boolean = false

    /** The curated fixtures, used only while demo mode is on. */
    private val sampleJobs: List<Job> by lazy { SampleData.jobs(clock()) }

    private val _syncState = MutableStateFlow(
        JobSyncState(sources = providers.map { JobSource(it.id, it.name, it.homepage, SourceState.IDLE) }),
    )
    override val syncState: StateFlow<JobSyncState> = _syncState.asStateFlow()

    private val _diagnostics = MutableStateFlow(ProviderDiagnostics(providers = baselineDiagnostics()))
    override val diagnostics: StateFlow<ProviderDiagnostics> = _diagnostics.asStateFlow()

    private val scoredJobs: StateFlow<List<Job>> =
        combine(rawJobs, candidateProfile) { raw, profile ->
            raw.map { mapper.toJob(it, profile) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Search terms for the providers that support server-side search. Adzuna and Jooble
     * match on a keyword, so sending the candidate's own title (falling back to a target
     * role) turns a generic national feed into relevant vacancies. Providers without
     * server-side search ignore this.
     */
    private var searchKeywords: List<String> = emptyList()

    override val jobs: Flow<List<Job>> =
        combine(scoredJobs, savedIds, demoMode) { jobList, saved, demo ->
            val base = if (demo) sampleJobs else jobList
            base.map { if (saved.contains(it.id)) it.copy(isSaved = true) else it }
        }

    private val seedNow = clock()

    /** Auto-apply preference; the repository's job of persisting it is unchanged from before. */
    private val autoApplyEnabled = MutableStateFlow(false)

    /**
     * The display profile is derived from the imported CV — never invented. A fresh
     * install therefore has an empty profile and the Profile screen shows its empty state.
     * In demo mode the curated sample profile is served instead.
     */
    override val profile: Flow<com.aiglobal.jobfinder.data.model.UserProfile> =
        combine(cvRecord, autoApplyEnabled, demoMode) { record, auto, demo ->
            if (demo) SampleData.profile(seedNow)
            else com.aiglobal.jobfinder.data.model.buildUserProfile(record, auto)
        }

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
        scope.launch {
            demoMode.collect { enabled ->
                demoEnabled = enabled
                // Leaving demo mode must not strand the user with an empty list.
                if (!enabled) runLiveValidation()
            }
        }
        scope.launch {
            candidateProfile.collect { profile ->
                searchKeywords = profile.currentTitle.takeIf { it.isNotBlank() }?.let { listOf(it) }
                    ?: profile.preferredRoles.take(1)
            }
        }
        scope.launch {
            providerSettings.collect { incoming -> applySettings(incoming) }
        }
        scope.launch { runLiveValidation() }
    }

    override suspend fun refresh() = runLiveValidation()

    /**
     * Reads every enabled source, records a diagnostic for each, validates the records and
     * keeps the last good list when nothing usable comes back.
     */
    override suspend fun runLiveValidation() {
        if (!syncMutex.tryLock()) return
        val attemptAt = clock()
        try {
            val online = isOnline()
            _diagnostics.update { it.copy(isRefreshing = true, networkAvailable = online) }
            _syncState.update { it.copy(isRefreshing = true) }
            markAllLoading(online)

            if (!online) {
                // No connection: report "waiting for network" for every enabled source
                // rather than a failure, and leave the cached list untouched.
                finishAttempt(attemptAt, results = emptyList(), online = false)
                return
            }

            val enabled = providers.filter { !isDisabled(it.id) }
            val results = coroutineScope {
                enabled.map { provider ->
                    async {
                        val startedAt = clock()
                        val outcome = runCatching {
                            withTimeoutOrNull(perProviderTimeoutMillis) {
                                provider.fetch(keywords = searchKeywords, limit = perProviderLimit)
                            } ?: throw ProviderTimeoutException()
                        }
                        ProviderOutcome(
                            provider = provider,
                            fetched = outcome.getOrNull(),
                            error = outcome.exceptionOrNull(),
                            durationMillis = (clock() - startedAt).coerceAtLeast(0),
                        )
                    }
                }.awaitAll()
            }

            finishAttempt(attemptAt, results = results, online = true)
        } finally {
            _diagnostics.update { it.copy(isRefreshing = false) }
            _syncState.update { it.copy(isRefreshing = false) }
            syncMutex.unlock()
        }
    }

    /** Validates, de-duplicates and stores the outcome of one full attempt. */
    private fun finishAttempt(attemptAt: Long, results: List<ProviderOutcome>, online: Boolean) {
        val diagnostics = mutableListOf<ProviderDiagnostic>()
        val collected = mutableListOf<RawJob>()
        var anySuccess = false

        for (outcome in results) {
            val provider = outcome.provider
            val fetched = outcome.fetched
            if (fetched != null) {
                anySuccess = true
                collected += fetched
                diagnostics += successDiagnostic(provider, fetched, outcome.durationMillis, attemptAt)
            } else {
                val category = classifyFailure(provider, outcome.error)
                diagnostics += failureDiagnostic(provider, category, outcome.error, outcome.durationMillis, attemptAt)
            }
        }

        // Disabled and unconfigured sources still get a row, so the screen is complete.
        diagnostics += dormantDiagnostics()

        val dedup = validationEngine.deduplicate(collected, now = attemptAt)
        applyValidationCounts(diagnostics, dedup.summaries)

        val sourceStates = diagnostics.map { diagnostic ->
            JobSource(
                id = diagnostic.providerId,
                name = diagnostic.name,
                homepage = diagnostic.homepage,
                state = sourceStateFor(diagnostic),
                jobCount = diagnostic.validJobCount,
                message = diagnostic.message,
            )
        }

        // Keep the last good list when the attempt produced nothing usable. Stamp the
        // retrieval time on each record so a later offline launch reports the true age
        // instead of presenting cached postings as freshly fetched.
        if (anySuccess && dedup.kept.isNotEmpty()) {
            val stamped = dedup.kept.map { if (it.retrievedAtEpochMillis == null) it.copy(retrievedAtEpochMillis = attemptAt) else it }
            rawJobs.value = stamped
            cache.write(stamped)
        }

        val lastSuccess = if (anySuccess) attemptAt else _diagnostics.value.lastSuccessAtEpochMillis
        _diagnostics.update {
            it.copy(
                providers = diagnostics.sortedBy { d -> d.name },
                isRefreshing = false,
                networkAvailable = online,
                lastAttemptAtEpochMillis = attemptAt,
                lastSuccessAtEpochMillis = lastSuccess,
                showingCachedOnly = !anySuccess && rawJobs.value.isNotEmpty(),
            )
        }
        _syncState.update {
            it.copy(
                isRefreshing = false,
                lastSyncedAtEpochMillis = lastSuccess,
                sources = sourceStates,
                showingCachedOnly = !anySuccess && rawJobs.value.isNotEmpty(),
            )
        }
    }

    private fun successDiagnostic(
        provider: JobProvider,
        fetched: List<RawJob>,
        durationMillis: Long,
        attemptAt: Long,
    ): ProviderDiagnostic {
        val status = if (fetched.isEmpty()) ProviderStatus.SUCCESS_EMPTY else ProviderStatus.SUCCESS_WITH_RESULTS
        return ProviderDiagnostic(
            providerId = provider.id,
            name = provider.name,
            homepage = provider.homepage,
            documentationUrl = provider.documentationUrl,
            status = status,
            enabled = true,
            requiresCredentials = provider.requiresCredentials,
            credentialsConfigured = hasCredentials(provider),
            validJobCount = fetched.size,
            lastAttemptAtEpochMillis = attemptAt,
            lastSuccessAtEpochMillis = attemptAt,
            lastSuccessJobCount = fetched.size,
            lastDurationMillis = durationMillis,
            message = if (fetched.isEmpty()) "The source answered and had no matching jobs." else null,
        )
    }

    private fun failureDiagnostic(
        provider: JobProvider,
        category: ProviderErrorCategory,
        error: Throwable?,
        durationMillis: Long,
        attemptAt: Long,
    ): ProviderDiagnostic = ProviderDiagnostic(
        providerId = provider.id,
        name = provider.name,
        homepage = provider.homepage,
        documentationUrl = provider.documentationUrl,
        status = ProviderStatus.forErrorCategory(category),
        enabled = true,
        requiresCredentials = provider.requiresCredentials,
        credentialsConfigured = hasCredentials(provider),
        lastAttemptAtEpochMillis = attemptAt,
        lastDurationMillis = durationMillis,
        errorCategory = category,
        message = (error as? JobProviderException)?.message ?: category.explanation,
    )

    /** Rows for sources that were deliberately not queried, so the screen has no gaps. */
    private fun dormantDiagnostics(): List<ProviderDiagnostic> = providers.mapNotNull { provider ->
        val previous = _diagnostics.value.providers.firstOrNull { it.providerId == provider.id }
        when {
            isDisabled(provider.id) -> (previous ?: blankDiagnostic(provider)).copy(
                status = ProviderStatus.DISABLED,
                enabled = false,
                message = "You switched this source off.",
            )
            provider.requiresCredentials && !hasCredentials(provider) ->
                (previous ?: blankDiagnostic(provider)).copy(
                    status = ProviderStatus.NOT_CONFIGURED,
                    enabled = true,
                    requiresCredentials = true,
                    credentialsConfigured = false,
                    errorCategory = ProviderErrorCategory.NOT_CONFIGURED,
                    message = ProviderErrorCategory.NOT_CONFIGURED.explanation,
                )
            else -> null
        }
    }

    private fun classifyFailure(provider: JobProvider, error: Throwable?): ProviderErrorCategory {
        if (error == null) return ProviderErrorCategory.UNKNOWN
        if (error is ProviderTimeoutException) return ProviderErrorCategory.TIMEOUT
        return ProviderErrorClassifier.classify(provider.id, error)
    }

    private fun applyValidationCounts(
        diagnostics: MutableList<ProviderDiagnostic>,
        summaries: List<ValidationSummary>,
    ) {
        for (index in diagnostics.indices) {
            val summary = summaries.firstOrNull { it.providerId == diagnostics[index].providerId } ?: continue
            val current = diagnostics[index]
            val status = if (current.status == ProviderStatus.SUCCESS_WITH_RESULTS && summary.hasRejections) {
                ProviderStatus.PARTIAL_SUCCESS
            } else {
                current.status
            }
            diagnostics[index] = current.copy(
                status = status,
                validJobCount = summary.acceptedCount,
                malformedRejectedCount = (summary.rejectedCount - summary.duplicateCount).coerceAtLeast(0),
                duplicateRemovedCount = summary.duplicateCount,
                message = current.message ?: partialMessage(summary),
            )
        }
    }

    private fun partialMessage(summary: ValidationSummary): String? {
        if (!summary.hasRejections) return null
        val rejected = summary.rejectedCount - summary.duplicateCount
        val parts = buildList {
            if (rejected > 0) add("$rejected record${if (rejected == 1) "" else "s"} did not pass validation")
            if (summary.duplicateCount > 0) {
                add("${summary.duplicateCount} duplicate${if (summary.duplicateCount == 1) "" else "s"} removed")
            }
        }
        return parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
    }

    private fun sourceStateFor(diagnostic: ProviderDiagnostic): SourceState = when {
        diagnostic.status.isSuccess -> SourceState.SUCCESS
        diagnostic.status.isDormant -> SourceState.IDLE
        else -> SourceState.ERROR
    }

    private fun baselineDiagnostics(): List<ProviderDiagnostic> = providers.map { blankDiagnostic(it) }

    private fun blankDiagnostic(provider: JobProvider): ProviderDiagnostic = ProviderDiagnostic(
        providerId = provider.id,
        name = provider.name,
        homepage = provider.homepage,
        documentationUrl = provider.documentationUrl,
        status = if (provider.requiresCredentials && !hasCredentials(provider)) {
            ProviderStatus.NOT_CONFIGURED
        } else {
            ProviderStatus.IDLE
        },
        enabled = !isDisabled(provider.id),
        requiresCredentials = provider.requiresCredentials,
        credentialsConfigured = !provider.requiresCredentials || hasCredentials(provider),
        message = if (provider.requiresCredentials && !hasCredentials(provider)) {
            ProviderErrorCategory.NOT_CONFIGURED.explanation
        } else {
            null
        },
    )

    private fun markAllLoading(online: Boolean) {
        _diagnostics.update { state ->
            state.copy(
                providers = state.providers.map { provider ->
                    when {
                        !provider.enabled -> provider.copy(status = ProviderStatus.DISABLED)
                        provider.needsSetup -> provider.copy(status = ProviderStatus.NOT_CONFIGURED)
                        !online -> provider.copy(status = ProviderStatus.WAITING_FOR_NETWORK)
                        else -> provider.copy(status = ProviderStatus.IN_PROGRESS)
                    }
                },
            )
        }
        _syncState.update { state ->
            state.copy(sources = state.sources.map { it.copy(state = SourceState.LOADING) })
        }
    }

    private fun applySettings(incoming: ProviderSettings) {
        settings.value = incoming
        _diagnostics.update { state ->
            state.copy(
                providers = state.providers.map { provider ->
                    val disabled = incoming.disabledIds.contains(provider.providerId)
                    val credentialsOk = !provider.requiresCredentials ||
                        incoming.configuredCredentialIds.contains(provider.providerId)
                    when {
                        disabled -> provider.copy(enabled = false, status = ProviderStatus.DISABLED)
                        !credentialsOk -> provider.copy(
                            enabled = true,
                            credentialsConfigured = false,
                            status = ProviderStatus.NOT_CONFIGURED,
                            errorCategory = ProviderErrorCategory.NOT_CONFIGURED,
                            message = ProviderErrorCategory.NOT_CONFIGURED.explanation,
                        )
                        else -> provider.copy(
                            enabled = true,
                            credentialsConfigured = true,
                            status = if (provider.status == ProviderStatus.NOT_CONFIGURED ||
                                provider.status == ProviderStatus.DISABLED
                            ) {
                                ProviderStatus.IDLE
                            } else {
                                provider.status
                            },
                        )
                    }
                },
            )
        }
    }

    private fun isDisabled(providerId: String): Boolean = settings.value.disabledIds.contains(providerId)

    private fun hasCredentials(provider: JobProvider): Boolean =
        !provider.requiresCredentials || settings.value.configuredCredentialIds.contains(provider.id)

    override suspend fun toggleSaved(jobId: String) {
        savedIds.update { current -> if (current.contains(jobId)) current - jobId else current + jobId }
    }

    override fun jobById(id: String): Job? =
        (if (demoEnabled) sampleJobs else scoredJobs.value).firstOrNull { it.id == id }

    override suspend fun setAutoApply(enabled: Boolean) {
        autoApplyEnabled.value = enabled
    }

    override suspend fun setDemoMode(enabled: Boolean) {
        demoModeSetter(enabled)
    }

    /** One provider's attempt, carried from the concurrent fetch to the recording step. */
    private data class ProviderOutcome(
        val provider: JobProvider,
        val fetched: List<RawJob>?,
        val error: Throwable?,
        val durationMillis: Long,
    )
}

/** Internal marker so a per-source timeout is classified as a timeout, not a network error. */
private class ProviderTimeoutException : RuntimeException("The source did not answer in time")
