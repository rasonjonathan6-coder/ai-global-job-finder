package com.aiglobal.jobfinder.data.repository

import com.aiglobal.jobfinder.data.jobs.JobCache
import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.engine.RecordValidationEngine
import com.aiglobal.jobfinder.data.jobs.engine.ValidationSummary
import com.aiglobal.jobfinder.data.jobs.provider.HtmlText
import com.aiglobal.jobfinder.data.jobs.provider.JobProvider
import com.aiglobal.jobfinder.data.jobs.provider.JobProviderException
import com.aiglobal.jobfinder.data.jobs.provider.ProviderErrorCategory
import com.aiglobal.jobfinder.data.jobs.provider.ProviderErrorClassifier
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.jobs.search.RoleClassifier
import com.aiglobal.jobfinder.data.jobs.search.SearchCoverage
import com.aiglobal.jobfinder.data.jobs.search.SearchStrategy
import com.aiglobal.jobfinder.data.jobs.search.TargetSearch
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** Persisted auto-apply preference; the flow reflects it back after a restart. */
    private val autoApplyFlow: Flow<Boolean> = MutableStateFlow(false),
    /** Persists the auto-apply choice. */
    private val autoApplySetter: suspend (Boolean) -> Unit = {},
    /** Persisted set of bookmarked posting ids. */
    private val savedIdsFlow: Flow<Set<String>> = MutableStateFlow(emptySet()),
    /** Persists the bookmarked ids. */
    private val savedIdsSetter: suspend (Set<String>) -> Unit = {},
    /**
     * Remembers which postings have already been seen, so a scheduled read can report what is
     * genuinely new rather than treating the whole feed as news. Optional: when absent, the
     * repository still works but reports no incremental delta.
     */
    private val seenLedger: com.aiglobal.jobfinder.data.discovery.JobSeenLedger? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val perProviderLimit: Int = 60,
    /** Hard ceiling on a single source, so one slow feed cannot hold up the whole refresh. */
    private val perProviderTimeoutMillis: Long = 20_000,
) : JobRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val syncMutex = Mutex()
    /** Serialises the read-modify-write of the persisted user state, so two quick toggles cannot race. */
    private val userStateMutex = Mutex()

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

    /**
     * How the last search was actually run: which sources can narrow by keyword and which
     * only returned a broad sample, plus how many postings matched a target role. The UI
     * reads this so it never calls a broad remote feed a targeted search.
     */
    private val _coverage = MutableStateFlow(SearchCoverage())
    override val coverage: StateFlow<SearchCoverage> = _coverage.asStateFlow()

    /**
     * The incremental-search delta from the most recent read. Kept as a StateFlow so the
     * Profile summary can render the last delta immediately, before any new read completes.
     */
    private val _discovery = MutableStateFlow(com.aiglobal.jobfinder.data.discovery.DiscoveryDelta())
    override val discovery: StateFlow<com.aiglobal.jobfinder.data.discovery.DiscoveryDelta> =
        _discovery.asStateFlow()

    /**
     * The postings first seen since the user last reviewed them. Derived from the same ledger
     * as [discovery], so the Home and Jobs surfaces show exactly the incremental signal the
     * Profile summary describes — never the whole feed on a fresh install.
     */
    private val _newPostings = MutableStateFlow(com.aiglobal.jobfinder.data.discovery.NewPostings())
    override val newPostings: StateFlow<com.aiglobal.jobfinder.data.discovery.NewPostings> =
        _newPostings.asStateFlow()

    /**
     * Per-source counts of postings that matched a target role on the last attempt. Kept
     * beside the feed (not derived from the displayed, filtered list) so the coverage report
     * describes the search, not the current filter.
     */
    private var relevantCountByProvider: Map<String, Int> = emptyMap()
    private var totalCountByProvider: Map<String, Int> = emptyMap()

    private val scoredJobs: StateFlow<List<Job>> =
        combine(rawJobs, candidateProfile) { raw, profile ->
            raw.map { mapper.toJob(it, profile) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The keyword plan for providers that support server-side search, derived from the
     * candidate's target roles and their own titles. Providers without server-side search
     * ignore it and are fetched once, then filtered locally against the target roles.
     */
    private var searchKeywordPlan: List<String> = emptyList()

    /**
     * Flips to true once each startup restore has applied its first persisted value. The
     * public read flows ([jobs], [profile]) await this so a caller that reads immediately
     * after construction — a relaunch — sees the restored bookmarks and auto-apply choice
     * rather than the empty in-memory default. On the very first emission the value is false
     * for a single scheduling step, which keeps this race-free without dropping any update.
     */
    private val userStateReady = MutableStateFlow(false)

    /**
     * Emits only once [userStateReady] is true. Because a StateFlow replays its current
     * value to a new collector, a reader that subscribes after the flag has already turned
     * true still receives the emission immediately, so the gate cannot be missed.
     */
    private val restoredGate: Flow<Unit> = userStateReady.filter { it }.map { }

    override val jobs: Flow<List<Job>> =
        combine(scoredJobs, savedIds, demoMode, restoredGate) { jobList, saved, demo, _ ->
            val base = if (demo) sampleJobs else jobList
            base.map { if (saved.contains(it.id)) it.copy(isSaved = true) else it }
        }

    private val seedNow = clock()

    /** Auto-apply preference; kept in sync with the persisted flow so it survives a restart. */
    private val autoApplyEnabled = MutableStateFlow(false)

    /**
     * Set once each startup restore has applied its first value. The persisted-flow
     * collectors are the only thing that seeds the in-memory state on launch, so a toggle
     * issued before they have delivered would read the empty default and save the wrong
     * state (for example re-saving a bookmark the user just removed). A write therefore
     * waits for both flags; afterwards the local state is authoritative, so a later
     * collector emission is ignored rather than allowed to clobber a fresh write.
     */
    @Volatile
    private var savedIdsRestored = false

    @Volatile
    private var autoApplyRestored = false

    /**
     * The display profile is derived from the imported CV — never invented. A fresh
     * install therefore has an empty profile and the Profile screen shows its empty state.
     * In demo mode the curated sample profile is served instead.
     */
    override val profile: Flow<com.aiglobal.jobfinder.data.model.UserProfile> =
        combine(cvRecord, autoApplyEnabled, demoMode, restoredGate) { record, auto, demo, _ ->
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
            // The seed value is just the initial default, not a mode change: the init
            // coroutine below already refreshes on startup. Refreshing on it too would fire a
            // second, redundant attempt on every launch (and leave the state flipping between
            // LOADING and IDLE), so only a genuine change *into* live mode re-refreshes.
            demoMode.drop(1).collect { enabled ->
                demoEnabled = enabled
                // Leaving demo mode must not strand the user with an empty list.
                if (!enabled) runLiveValidation()
            }
        }
        scope.launch {
            candidateProfile.collect { profile ->
                searchKeywordPlan = TargetSearch.keywordPlan(
                    profileTitles = (listOf(profile.currentTitle) + profile.previousTitles + profile.preferredRoles)
                        .filter { it.isNotBlank() },
                )
            }
        }
        scope.launch {
            providerSettings.collect { incoming -> applySettings(incoming) }
        }
        // Restore the bookmarked postings and the auto-apply choice the user made earlier,
        // so a restart does not silently forget them. Only the first value is applied: after
        // that the local state is authoritative, so a late emission of the pre-write value
        // cannot undo a toggle the user just made. A refresh still re-reads the store.
        scope.launch {
            savedIdsFlow.collect { stored ->
                if (!savedIdsRestored) {
                    savedIds.value = stored
                    savedIdsRestored = true
                }
            }
        }
        scope.launch {
            autoApplyFlow.collect { enabled ->
                if (!autoApplyRestored) {
                    autoApplyEnabled.value = enabled
                    autoApplyRestored = true
                }
            }
        }
        // Open the read gate as soon as both restores have applied, or at the cap so a store
        // that never emits cannot leave the screens waiting on their skeleton forever.
        scope.launch {
            withTimeoutOrNull(RESTORE_WAIT_MS) {
                while (!(savedIdsRestored && autoApplyRestored)) delay(RESTORE_POLL_MS)
            }
            userStateReady.value = true
        }
        scope.launch { runLiveValidation() }
        // Emit the persisted "new postings" right away, so the Home and Jobs surfaces are
        // correct on a cold start instead of showing zero until the next search completes.
        scope.launch { _newPostings.value = seenLedger?.newPostings() ?: _newPostings.value }
    }

    override suspend fun refresh() = runLiveValidation()

    override suspend fun markNewPostingsReviewed() {
        seenLedger?.markReviewed()
        _newPostings.value = seenLedger?.newPostings() ?: _newPostings.value
    }

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

            // A source the user switched off, or one that needs credentials and has none,
            // is described by the dormant rows below and is never queried: it cannot
            // succeed, and querying it would add a second row for the same provider id
            // (a failure row plus a dormant row), which the diagnostics list keys on.
            val enabled = providers.filter { !isDisabled(it.id) && hasCredentials(it) }
            val results = coroutineScope {
                enabled.map { provider ->
                    async {
                        val startedAt = clock()
                        val plan = SearchStrategy.planFor(provider, searchKeywordPlan)
                        val fetched = mutableListOf<RawJob>()
                        var failure: Throwable? = null
                        // A keyword-capable source is asked for each target role in turn and
                        // the results are pooled; a source without keyword search is fetched
                        // once with no keywords and filtered locally afterwards.
                        for (keywords in plan.keywordSets) {
                            val outcome = runCatching {
                                withTimeoutOrNull(perProviderTimeoutMillis) {
                                    provider.fetch(keywords = keywords, limit = perProviderLimit)
                                } ?: throw ProviderTimeoutException()
                            }
                            outcome.getOrNull()?.let { fetched += it }
                            if (outcome.isFailure && failure == null) failure = outcome.exceptionOrNull()
                            // Stop early once we have enough from this source.
                            if (fetched.size >= perProviderLimit * plan.keywordSets.size) break
                        }
                        val success = fetched.isNotEmpty() || failure == null
                        ProviderOutcome(
                            provider = provider,
                            fetched = if (success) fetched else null,
                            error = if (success) null else failure,
                            durationMillis = (clock() - startedAt).coerceAtLeast(0),
                            serverSideSearch = plan.serverSideSearch,
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
    private suspend fun finishAttempt(attemptAt: Long, results: List<ProviderOutcome>, online: Boolean) {
        val diagnostics = mutableListOf<ProviderDiagnostic>()
        val collected = mutableListOf<RawJob>()
        var anySuccess = false

        for (outcome in results) {
            val provider = outcome.provider
            val fetched = outcome.fetched
            if (fetched != null) {
                anySuccess = true
                collected += fetched
                diagnostics += successDiagnostic(provider, fetched, outcome.durationMillis, attemptAt, outcome.serverSideSearch)
            } else {
                val category = classifyFailure(provider, outcome.error)
                diagnostics += failureDiagnostic(
                    provider, category, outcome.error, outcome.durationMillis, attemptAt, outcome.serverSideSearch,
                )
            }
        }

        // Every source gets a row, so the screen is complete even when nothing was queried.
        // When the attempt never reached the network, results is empty and the rows must
        // still name each source as waiting — otherwise the diagnostics table goes blank
        // exactly when the user most needs to know the feed is only showing cached data.
        diagnostics += if (online) dormantDiagnostics() else offlineDiagnostics()

        val dedup = validationEngine.deduplicate(collected, now = attemptAt)
        applyValidationCounts(diagnostics, dedup.summaries)

        // Count how many of the postings that actually arrived matched a target role, per
        // source. This is what lets the coverage report describe the search honestly: a
        // source that returned forty software jobs and no maintenance role is recorded as
        // retrieved-but-not-relevant, not hidden.
        val relevantByProvider = mutableMapOf<String, Int>()
        val totalByProvider = mutableMapOf<String, Int>()
        dedup.kept.forEach { raw ->
            totalByProvider[raw.providerId] = (totalByProvider[raw.providerId] ?: 0) + 1
            val relevance = RoleClassifier.classify(
                title = raw.title,
                descriptionHead = HtmlText.toPlainText(raw.descriptionHtml).take(400),
            )
            if (relevance.isMaintenanceFamily) {
                relevantByProvider[raw.providerId] = (relevantByProvider[raw.providerId] ?: 0) + 1
            }
        }
        relevantCountByProvider = relevantByProvider
        totalCountByProvider = totalByProvider
        _coverage.value = SearchStrategy.coverageFrom(
            diagnostics = diagnostics.filter { !it.status.isDormant || it.enabled },
            relevantByProvider = relevantByProvider,
            totalByProvider = totalByProvider,
        )

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
            // Incremental search: record what arrived and note what is genuinely new. A read
            // that returned nothing usable must not advance the ledger, or the next good read
            // would present its whole feed as already seen.
            seenLedger?.let { ledger ->
                _discovery.value = ledger.observe(stamped)
                _newPostings.value = ledger.newPostings()
            }
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
        serverSideSearch: Boolean,
    ): ProviderDiagnostic {
        val status = if (fetched.isEmpty()) ProviderStatus.SUCCESS_EMPTY else ProviderStatus.SUCCESS_WITH_RESULTS
        return ProviderDiagnostic(
            providerId = provider.id,
            name = provider.name,
            homepage = provider.homepage,
            documentationUrl = provider.documentationUrl,
            status = status,
            enabled = true,
            serverSideSearch = serverSideSearch,
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
        serverSideSearch: Boolean,
    ): ProviderDiagnostic = ProviderDiagnostic(
        providerId = provider.id,
        name = provider.name,
        homepage = provider.homepage,
        documentationUrl = provider.documentationUrl,
        status = ProviderStatus.forErrorCategory(category),
        enabled = true,
        serverSideSearch = serverSideSearch,
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

    /**
     * Rows for an attempt that never reached the network. Each source keeps the honest
     * distinction between a source the user switched off, one with no key saved yet, and
     * one that is simply waiting for a connection — none of which is a failure. This is
     * what keeps the diagnostics table populated offline instead of collapsing to nothing.
     */
    private fun offlineDiagnostics(): List<ProviderDiagnostic> = providers.map { provider ->
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
            else -> (previous ?: blankDiagnostic(provider)).copy(
                status = ProviderStatus.WAITING_FOR_NETWORK,
                enabled = true,
                message = "No connection right now; this source will be read on the next refresh.",
            )
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
        serverSideSearch = provider.capabilities.serverSideKeyword,
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

    override suspend fun toggleSaved(jobId: String) = userStateMutex.withLock {
        awaitUserStateRestored()
        val next = savedIds.value.let { current ->
            if (current.contains(jobId)) current - jobId else current + jobId
        }
        savedIds.value = next
        savedIdsSetter(next)
    }

    override fun jobById(id: String): Job? =
        (if (demoEnabled) sampleJobs else scoredJobs.value).firstOrNull { it.id == id }

    override suspend fun setAutoApply(enabled: Boolean) = userStateMutex.withLock {
        awaitUserStateRestored()
        autoApplyEnabled.value = enabled
        autoApplySetter(enabled)
    }

    /**
     * Waits for the persisted-flow collectors to deliver their first value before a write.
     * Without this, a toggle issued before startup finishes reads the empty default and
     * saves the wrong state over the user's real one. If the store never emits (a broken
     * or absent file), the write proceeds after the cap rather than hanging forever.
     */
    private suspend fun awaitUserStateRestored() {
        if (savedIdsRestored && autoApplyRestored) return
        withTimeoutOrNull(RESTORE_WAIT_MS) {
            while (!(savedIdsRestored && autoApplyRestored)) delay(RESTORE_POLL_MS)
        }
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
        val serverSideSearch: Boolean,
    )

    private companion object {
        /** How long a user write waits for the startup restore before proceeding anyway. */
        const val RESTORE_WAIT_MS = 5_000L
        const val RESTORE_POLL_MS = 5L
    }
}

/** Internal marker so a per-source timeout is classified as a timeout, not a network error. */
private class ProviderTimeoutException : RuntimeException("The source did not answer in time")
