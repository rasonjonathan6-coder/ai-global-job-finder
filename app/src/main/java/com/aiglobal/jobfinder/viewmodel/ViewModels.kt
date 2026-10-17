package com.aiglobal.jobfinder.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aiglobal.jobfinder.AppContainer
import com.aiglobal.jobfinder.data.expat.ExpatBenefitsEngine
import com.aiglobal.jobfinder.data.jobs.engine.InternationalFitEngine
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaCheckResult
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaCredentials
import com.aiglobal.jobfinder.data.model.ApplicationDraft
import com.aiglobal.jobfinder.data.model.ApplicationRecord
import com.aiglobal.jobfinder.data.model.ApplicationReminder
import com.aiglobal.jobfinder.data.model.ApplicationStatus
import com.aiglobal.jobfinder.data.model.BenefitPriority
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.DashboardStats
import com.aiglobal.jobfinder.data.model.ExpatBenefitsAssessment
import com.aiglobal.jobfinder.data.model.ExpatPreferences
import com.aiglobal.jobfinder.data.model.InternationalFitAssessment
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobSyncState
import com.aiglobal.jobfinder.data.model.NvidiaResult
import com.aiglobal.jobfinder.data.model.UserProfile
import com.aiglobal.jobfinder.data.model.isTerminal
import com.aiglobal.jobfinder.data.refresh.RefreshInterval
import com.aiglobal.jobfinder.data.refresh.RefreshPreferences
import com.aiglobal.jobfinder.ui.theme.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Holds the theme preference at the app root so it is available before any screen composes. */
class AppViewModel(private val container: AppContainer) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = container.preferencesRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.System)

    /** Adzuna app id/key, empty until the user saves them. */
    val adzunaCredentials: StateFlow<AdzunaCredentials> = container.preferencesRepository.adzunaCredentials
        .stateIn(viewModelScope, SharingStarted.Eagerly, AdzunaCredentials())

    /** Jooble API key, empty until the user saves it. */
    val joobleApiKey: StateFlow<String> = container.preferencesRepository.joobleApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Adzuna markets the user has chosen; empty means "use the provider defaults". */
    val adzunaCountries: StateFlow<Set<String>> = container.preferencesRepository.adzunaCountries
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** NVIDIA API key, model and consent for the optional drafting assistant. */
    val nvidiaConfig: StateFlow<com.aiglobal.jobfinder.data.model.NvidiaConfig> =
        container.nvidiaConfig
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                com.aiglobal.jobfinder.data.model.NvidiaConfig(),
            )

    /** True when saved credentials are encrypted at rest with the Android Keystore. */
    val credentialsEncrypted: Boolean get() = container.credentialsEncrypted

    /** The user's expat benefit priorities and gates, defaulting until they are stored. */
    val expatPreferences: StateFlow<ExpatPreferences> = container.preferencesRepository.expatPreferences
        .stateIn(viewModelScope, SharingStarted.Eagerly, ExpatPreferences.defaults())

    /** Automatic background-refresh settings. Drives the switch and interval in Profile. */
    val refreshPreferences: StateFlow<RefreshPreferences> =
        container.preferencesRepository.refreshPreferences
            .stateIn(viewModelScope, SharingStarted.Eagerly, RefreshPreferences())

    /** Whether new strong matches may raise a notification. Off until the user opts in. */
    val notificationsEnabled: StateFlow<Boolean> = container.preferencesRepository.notificationsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * The state of automatic discovery: when a search last ran and succeeded, how much is new,
     * and which sources are failing. Assembled from the repository's own diagnostics, so it
     * describes what actually happened rather than what was requested.
     */
    val discoverySummary: StateFlow<com.aiglobal.jobfinder.data.discovery.DiscoverySummary> =
        container.discoverySummary
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                com.aiglobal.jobfinder.data.discovery.DiscoverySummary(),
            )

    /** True while a user-initiated "search now" is in flight, so the button can show progress. */
    private val _searchingNow = kotlinx.coroutines.flow.MutableStateFlow(false)
    val searchingNow: StateFlow<Boolean> = _searchingNow.asStateFlow()

    /**
     * The postings discovered since the user last reviewed them. Shared by Home and Jobs, so
     * both surfaces show the same incremental list and neither can drift from the ledger.
     */
    val newPostings: StateFlow<com.aiglobal.jobfinder.data.discovery.NewPostings> =
        container.jobRepository.newPostings
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                com.aiglobal.jobfinder.data.discovery.NewPostings(),
            )

    /**
     * One-shot signal carrying the ids to reveal on the Jobs screen when the user taps the Home
     * "new opportunities" card. Null when nothing is pending, so the navigation is driven only
     * by an explicit tap and never by a background discovery.
     */
    private val _openNewPostings = kotlinx.coroutines.flow.MutableStateFlow<List<String>?>(null)
    val openNewPostings: StateFlow<List<String>?> = _openNewPostings.asStateFlow()

    /** Asks the Jobs tab to open filtered to these newly discovered postings. */
    fun requestOpenNewPostings(ids: List<String>) {
        _openNewPostings.value = ids.takeIf { it.isNotEmpty() }
    }

    /** Consumes the pending request so it fires at most once. */
    fun consumeOpenNewPostings() {
        _openNewPostings.value = null
    }

    /**
     * Clears the "new postings" list once the user has reviewed the new opportunities, so the
     * badge does not keep announcing what has already been seen.
     */
    fun markNewPostingsReviewed() {
        viewModelScope.launch { container.jobRepository.markNewPostingsReviewed() }
    }

    fun setAutoRefreshEnabled(enabled: Boolean) {
        viewModelScope.launch { container.preferencesRepository.setAutoRefreshEnabled(enabled) }
    }

    fun setRefreshInterval(interval: RefreshInterval) {
        viewModelScope.launch { container.preferencesRepository.setRefreshIntervalHours(interval.hours) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { container.preferencesRepository.setNotificationsEnabled(enabled) }
    }

    /** Moves the relevance bar a newly discovered posting must clear to raise an alert. */
    fun setAlertThreshold(threshold: com.aiglobal.jobfinder.data.refresh.AlertThreshold) {
        viewModelScope.launch { container.preferencesRepository.setAlertMinMatchScore(threshold.score) }
    }

    /**
     * Runs one search immediately and waits for it, so the settings section can show live
     * progress and an updated result. A second tap while one is running is ignored rather
     * than queued, matching the repository's own single-flight behaviour.
     */
    fun searchNow() {
        if (_searchingNow.value) return
        viewModelScope.launch {
            _searchingNow.value = true
            try {
                container.jobRepository.runLiveValidation()
            } finally {
                _searchingNow.value = false
            }
        }
    }

    /**
     * Asks WorkManager to run one refresh now, off the main thread and with the same network
     * constraint the scheduled run uses. The screen does not wait for it; the feed updates
     * when it lands, exactly as it does for a background run.
     */
    fun refreshNowInBackground() {
        container.jobRefreshScheduler.refreshNow()
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { container.preferencesRepository.setThemeMode(mode) }
    }

    fun saveAdzunaCredentials(appId: String, appKey: String) {
        viewModelScope.launch { container.preferencesRepository.setAdzunaCredentials(appId, appKey) }
    }

    fun saveJoobleApiKey(key: String) {
        viewModelScope.launch { container.preferencesRepository.setJoobleApiKey(key) }
    }

    /** Replaces the Adzuna market set. An empty set restores the provider defaults. */
    fun saveAdzunaCountries(countries: Set<String>) {
        viewModelScope.launch { container.preferencesRepository.setAdzunaCountries(countries) }
    }

    fun saveNvidiaApiKey(key: String) {
        viewModelScope.launch { container.preferencesRepository.setNvidiaApiKey(key) }
    }

    fun saveNvidiaModelId(modelId: String) {
        viewModelScope.launch { container.preferencesRepository.setNvidiaModelId(modelId) }
    }

    fun setNvidiaConsent(granted: Boolean) {
        viewModelScope.launch { container.preferencesRepository.setNvidiaConsent(granted) }
    }

    /**
     * Validates a candidate key against NVIDIA without touching the saved one, so the user
     * can confirm it works before committing. Returns the classified result to the caller.
     */
    suspend fun testNvidiaKey(candidateKey: String): NvidiaResult =
        container.aiAssistant.testConnection(candidateKey.trim())

    /**
     * Runs the cheapest real inference on the saved key and the saved model id, proving the
     * account can actually use the selected model — not merely that it appears in the list.
     */
    suspend fun testSelectedNvidiaModel(): NvidiaResult {
        val config = nvidiaConfig.value
        return container.aiAssistant.testModel(config.apiKey, config.modelId)
    }

    /** Runs the same inference check against an explicitly supplied key and model id. */
    suspend fun testNvidiaModel(candidateKey: String, modelId: String): NvidiaResult =
        container.aiAssistant.testModel(candidateKey.trim(), modelId.trim())

    /**
     * Lists the model ids the NVIDIA account can actually use, using the candidate key. No
     * profile data is sent — this is only the account's model catalog.
     */
    suspend fun discoverNvidiaModels(candidateKey: String): List<String> =
        container.aiAssistant.discoverModels(candidateKey.trim())

    /**
     * Runs a real Adzuna connection test with the saved credentials: the version endpoint
     * plus one small search in a configured market. Nothing is fabricated — an empty market
     * is reported as empty.
     */
    suspend fun testAdzunaConnection(): AdzunaCheckResult =
        container.adzunaProvider.checkConnection()

    /** Switches a job source on or off; the feed honours it on the next validation run. */
    fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        viewModelScope.launch { container.preferencesRepository.setSourceEnabled(sourceId, enabled) }
    }

    /** Sets how much one benefit matters to the user, and persists it. */
    fun setBenefitPriority(key: String, priority: BenefitPriority) {
        viewModelScope.launch {
            val current = expatPreferences.value.priorities.toMutableMap()
            current[key] = priority.weight
            container.preferencesRepository.setExpatPriorities(current)
        }
    }

    fun setExpatMinimumSalary(minimumEur: Int?) {
        viewModelScope.launch { container.preferencesRepository.setExpatMinimumSalary(minimumEur) }
    }

    fun setExpatMinScores(minExpatScore: Int, minMatchScore: Int) {
        viewModelScope.launch {
            container.preferencesRepository.setExpatMinScores(minExpatScore, minMatchScore)
        }
    }

    fun setRequireVisaSponsorship(required: Boolean) {
        viewModelScope.launch { container.preferencesRepository.setRequireVisaSponsorship(required) }
    }
}

class AppViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(container) as T
}

data class JobsUiState(
    val isLoading: Boolean = true,
    val jobs: List<Job> = emptyList(),
    val query: String = "",
    val savedOnly: Boolean = false,
    /** Only postings a Madagascar-based applicant can realistically apply to. */
    val eligibleOnly: Boolean = false,
    /** Only postings where the employer states visa sponsorship. */
    val sponsoredOnly: Boolean = false,
    /** Canonical country name from [com.aiglobal.jobfinder.data.geo.CountryCatalog]. */
    val country: String? = null,
    /** Provider id, e.g. "remotive". */
    val source: String? = null,
    val minMatch: Int = 0,
    val sort: JobSort = JobSort.BEST_MATCH,
    val syncState: JobSyncState = JobSyncState(),
    /**
     * How the last search was run: which sources could narrow by keyword and which only
     * returned a broad sample. Shown so a broad remote feed is never called a targeted search.
     */
    val coverage: com.aiglobal.jobfinder.data.jobs.search.SearchCoverage =
        com.aiglobal.jobfinder.data.jobs.search.SearchCoverage(),
    /** Expat Benefits Score per job id. Kept beside the CV match, never blended into it. */
    val expatAssessments: Map<String, ExpatBenefitsAssessment> = emptyMap(),
    /**
     * International Fit per job id. Folds the destination, the route in from Madagascar, the
     * language and the credential facts into one explainable read, while the documented
     * Expat Benefits Score stays a separate number beside it.
     */
    val internationalFit: Map<String, InternationalFitAssessment> = emptyMap(),
    /** User priorities and gates, mirrored here so the filter row can show them. */
    val expatPreferences: ExpatPreferences = ExpatPreferences(),
    /** Hide postings whose Expat Benefits Score is below this. 0 disables the filter. */
    val minExpatScore: Int = 0,
    /** When true, only postings whose employer documents visa sponsorship are shown. */
    val visaDocumentedOnly: Boolean = false,
    /**
     * When true, only postings whose International Fit band is strong or excellent are
     * shown. The band combines the role, skills, experience, destination and the route in
     * from Madagascar into one explainable read.
     */
    val strongInternationalFitOnly: Boolean = false,
    /**
     * When true, only postings whose title is the candidate's own trade (or the wider
     * maintenance family) are shown. The remote boards return broad samples, so this is
     * how the user sees the targeted result rather than an unrelated global feed.
     */
    val tradeOnly: Boolean = false,
    /**
     * When true, only postings whose employer *documents* visa sponsorship are shown. This
     * reads the verified visa state, so a posting that merely hints at a route in is not
     * presented as a documented one.
     */
    val verifiedVisaOnly: Boolean = false,
    /** When true, only postings whose employer documents accommodation are shown. */
    val verifiedAccommodationOnly: Boolean = false,
    /** When true, only postings whose employer documents annual flights home are shown. */
    val verifiedFlightsOnly: Boolean = false,
    /**
     * When true, only postings the source itself confirms are still accepting applicants are
     * shown. A posting that simply does not state it is excluded here — the filter narrows to
     * what is confirmed, it does not assume the rest are closed.
     */
    val openVacancyOnly: Boolean = false,
    /** When true, only postings retrieved recently enough to rely on are shown. */
    val recentlyVerifiedOnly: Boolean = false,
    /**
     * Ids the candidate has shortlisted. Mirrored here so a bookmark tap flips the card
     * instantly, even when the underlying feed has not re-emitted the saved flag yet.
     */
    val savedJobIds: Set<String> = emptySet(),
    /**
     * When true, only postings first seen since the user last reviewed the new opportunities
     * are shown. Reads the same ledger the Home card and Profile summary use, so "new" means
     * the same thing everywhere and a posting already known never appears as new.
     */
    val newOnly: Boolean = false,
    /** Ids of the postings the incremental search discovered since the last review. */
    val newJobIds: Set<String> = emptySet(),
    /**
     * When each posting was first seen by the app, keyed by id. Used to date "discovered"
     * honestly; a posting absent from this map has no recorded first sighting.
     */
    val firstSeenAtByJobId: Map<String, Long> = emptyMap(),
) {
    /** Countries present in the current result set, for the country filter. */
    val countries: List<String>
        get() = jobs.map { it.country }.distinct().sorted()

    /** Sources present in the current result set, for the source filter. */
    val sources: List<Pair<String, String>>
        get() = jobs.map { it.providerId to it.source }
            .distinctBy { it.first }
            .filter { it.first.isNotBlank() }
            .sortedBy { it.second }

    /** How many of the loaded postings pass the eligibility filter. */
    val eligibleCount: Int get() = jobs.count { it.applyableFromMadagascar }

    val hasActiveFilters: Boolean
        get() = savedOnly || eligibleOnly || sponsoredOnly || country != null ||
            source != null || minMatch > 0 || minExpatScore > 0 || visaDocumentedOnly ||
            strongInternationalFitOnly || tradeOnly || query.isNotBlank() ||
            verifiedVisaOnly || verifiedAccommodationOnly || verifiedFlightsOnly ||
            openVacancyOnly || recentlyVerifiedOnly || newOnly

    /** How many of the loaded postings were first seen since the last review. */
    val newCount: Int get() = jobs.count { newJobIds.contains(it.id) }

    /** True when a posting was discovered since the user last reviewed the new opportunities. */
    fun isNew(job: Job): Boolean = newJobIds.contains(job.id)

    /** When the app first saw a posting, or null when no first sighting is recorded. */
    fun firstSeenAt(job: Job): Long? = firstSeenAtByJobId[job.id]

    /** Expat Benefits Score for one posting, or the honest empty state when unknown. */
    fun expatScore(job: Job): Int? = expatAssessments[job.id]?.score

    fun expatAssessment(job: Job): ExpatBenefitsAssessment =
        expatAssessments[job.id] ?: ExpatBenefitsAssessment.EMPTY

    /** International Fit for one posting, or the honest empty state when not yet scored. */
    fun internationalFitOf(job: Job): InternationalFitAssessment =
        internationalFit[job.id] ?: InternationalFitAssessment.EMPTY

    /** True when the card should show its bookmark filled, from either source of truth. */
    fun isSaved(job: Job): Boolean = savedJobIds.contains(job.id) || job.isSaved

    val visibleJobs: List<Job>
        get() {
            var result = jobs
            if (newOnly) result = result.filter { newJobIds.contains(it.id) }
            if (savedOnly) result = result.filter { isSaved(it) }
            if (eligibleOnly) result = result.filter { it.applyableFromMadagascar }
            if (sponsoredOnly) result = result.filter {
                it.visaSupport == com.aiglobal.jobfinder.data.model.VisaSupport.FULL_SPONSORSHIP ||
                    it.visaSupport == com.aiglobal.jobfinder.data.model.VisaSupport.SPONSORSHIP_AVAILABLE
            }
            if (visaDocumentedOnly) result = result.filter { job ->
                job.benefitEvidence.any {
                    it.type.key == com.aiglobal.jobfinder.data.model.JobBenefitTypes.VISA_SPONSORSHIP.key &&
                        it.status == com.aiglobal.jobfinder.data.model.BenefitStatus.CONFIRMED
                }
            }
            if (tradeOnly) result = result.filter { it.isMaintenanceRelevant }
            if (strongInternationalFitOnly) result = result.filter {
                internationalFit[it.id]?.band?.isActionable == true
            }
            if (verifiedVisaOnly) result = result.filter {
                it.verification.visa == com.aiglobal.jobfinder.data.model.VerificationState.CONFIRMED
            }
            if (verifiedAccommodationOnly) result = result.filter {
                it.benefitEvidence.any { evidence ->
                    evidence.type.key == com.aiglobal.jobfinder.data.model.JobBenefitTypes.ACCOMMODATION.key &&
                        evidence.status == com.aiglobal.jobfinder.data.model.BenefitStatus.CONFIRMED
                }
            }
            if (verifiedFlightsOnly) result = result.filter {
                it.benefitEvidence.any { evidence ->
                    evidence.type.key == com.aiglobal.jobfinder.data.model.JobBenefitTypes.FLIGHTS_HOME.key &&
                        evidence.status == com.aiglobal.jobfinder.data.model.BenefitStatus.CONFIRMED
                }
            }
            if (openVacancyOnly) result = result.filter {
                it.verification.activity == com.aiglobal.jobfinder.data.model.VerificationState.CONFIRMED
            }
            if (recentlyVerifiedOnly) result = result.filter {
                it.verification.freshness == com.aiglobal.jobfinder.data.model.VerificationState.CONFIRMED
            }
            country?.let { selected -> result = result.filter { it.country == selected } }
            source?.let { selected -> result = result.filter { it.providerId == selected } }
            if (minMatch > 0) result = result.filter { it.matchScore >= minMatch }
            if (minExpatScore > 0) {
                result = result.filter { (expatAssessments[it.id]?.score ?: 0) >= minExpatScore }
            }

            val q = query.trim().lowercase()
            if (q.isNotBlank()) {
                result = result.filter {
                    it.title.lowercase().contains(q) ||
                        it.company.lowercase().contains(q) ||
                        it.country.lowercase().contains(q) ||
                        it.summary.lowercase().contains(q) ||
                        it.matchedSkills.any { skill -> skill.lowercase().contains(q) }
                }
            }

            return when (sort) {
                JobSort.BEST_MATCH -> result.sortedByDescending { it.matchScore }
                JobSort.NEWEST -> result.sortedByDescending { it.postedAtEpochMillis }
                JobSort.SALARY_HIGH -> result.sortedByDescending { eurPerYearOrZero(it) }
                JobSort.EXPAT_FIT -> result.sortedWith(expatFitOrder)
                JobSort.INTERNATIONAL_FIT -> result.sortedWith(internationalFitOrder)
            }
        }

    /**
     * The most applicable first: the documented Expat Benefits Score, then a real
     * Madagascar path (sponsored or eligible), then documented visa sponsorship, and only
     * then CV match and recency. Every step reads a recorded fact, so the order is
     * reproducible rather than a hunch.
     */
    private val expatFitOrder: Comparator<Job> =
        // A posting with a known dead end (closed, or an unusable link) is demoted below any
        // live posting, whatever its score, so a "best fit" list never leads with an offer the
        // candidate cannot act on. Unknown stays neutral: it is not treated as dead.
        compareByDescending<Job> { it.verification.isActionable }
            .thenByDescending { expatAssessments[it.id]?.score ?: -1 }
            .thenByDescending { it.eligibility.rank }
            .thenByDescending { job ->
                job.benefitEvidence.any {
                    it.type.key == com.aiglobal.jobfinder.data.model.JobBenefitTypes.VISA_SPONSORSHIP.key &&
                        it.status == com.aiglobal.jobfinder.data.model.BenefitStatus.CONFIRMED
                }
            }
            .thenByDescending { it.matchScore }
            .thenByDescending { it.postedAtEpochMillis ?: 0L }

    /**
     * The strongest international fit first, using recorded facts rather than a hunch. It
     * reads the International Fit Score, then the eligibility verdict and documented visa
     * sponsorship as tie-breakers, and only then CV match and recency — so a posting that
     * ranks high here can always be explained by the facts behind it.
     */
    private val internationalFitOrder: Comparator<Job> =
        compareByDescending<Job> { it.verification.isActionable }
            .thenByDescending { internationalFit[it.id]?.score ?: -1 }
            .thenByDescending { it.eligibility.rank }
            .thenByDescending { job ->
                job.benefitEvidence.any {
                    it.type.key == com.aiglobal.jobfinder.data.model.JobBenefitTypes.VISA_SPONSORSHIP.key &&
                        it.status == com.aiglobal.jobfinder.data.model.BenefitStatus.CONFIRMED
                }
            }
            .thenByDescending { it.matchScore }
            .thenByDescending { it.postedAtEpochMillis ?: 0L }

    private fun eurPerYearOrZero(job: Job): Int =
        if (job.salaryMin <= 0) 0
        else com.aiglobal.jobfinder.data.geo.CurrencyConverter
            .toEurPerYear(job.salaryMin, job.currencyCode, job.salaryPeriod) ?: 0
}

/** Sort orders offered on the Jobs screen. */
enum class JobSort(val label: String) {
    BEST_MATCH("Best match"),
    NEWEST("Newest"),
    SALARY_HIGH("Highest salary"),

    /** Most applicable from Madagascar first, using recorded facts rather than a guess. */
    EXPAT_FIT("Expat fit"),

    /** Strongest international fit first, combining role, skills, route and destination. */
    INTERNATIONAL_FIT("International fit"),
}

class JobsViewModel(
    private val repository: com.aiglobal.jobfinder.data.repository.JobRepository,
    private val expatPreferences: kotlinx.coroutines.flow.Flow<ExpatPreferences> = kotlinx.coroutines.flow.flowOf(ExpatPreferences()),
    private val expatEngine: ExpatBenefitsEngine = ExpatBenefitsEngine(),
    private val candidateProfile: kotlinx.coroutines.flow.Flow<CandidateProfile> =
        kotlinx.coroutines.flow.flowOf(CandidateProfile()),
    private val fitEngine: InternationalFitEngine = InternationalFitEngine(),
) : ViewModel() {

    private val uiStateInternal = kotlinx.coroutines.flow.MutableStateFlow(JobsUiState())

    val uiState: StateFlow<JobsUiState> = uiStateInternal

    init {
        viewModelScope.launch {
            repository.syncState.collect { sync ->
                uiStateInternal.update { it.copy(syncState = sync) }
            }
        }
        viewModelScope.launch {
            repository.coverage.collect { coverage ->
                uiStateInternal.update { it.copy(coverage = coverage) }
            }
        }
        // The incremental discovery list drives the "New" filter and the NEW badge, so it is
        // collected from the same ledger the Home card reads — the two can never disagree.
        viewModelScope.launch {
            repository.newPostings.collect { newPostings ->
                uiStateInternal.update {
                    it.copy(
                        newJobIds = newPostings.jobIds.toSet(),
                        firstSeenAtByJobId = newPostings.discoveredAtByJobId,
                    )
                }
            }
        }
        // Re-score every posting whenever the feed or the user's priorities change, so the
        // Expat Benefits Score always reflects the current postings and the current wishes.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                repository.jobs,
                expatPreferences,
                candidateProfile,
            ) { jobs, prefs, profile -> Triple(jobs, prefs, profile) }
                .collect { (jobs, prefs, profile) ->
                    val assessments = jobs.associate { job ->
                        job.id to expatEngine.assess(
                            benefits = job.benefitEvidence,
                            preferences = prefs,
                            salaryEurPerYear = eurPerYear(job),
                        )
                    }
                    uiStateInternal.update {
                        it.copy(
                            jobs = jobs,
                            expatPreferences = prefs,
                            expatAssessments = assessments,
                            internationalFit = jobs.associate { job ->
                                job.id to fitEngine.assess(
                                    job = job,
                                    profile = profile,
                                    expat = assessments.getValue(job.id),
                                )
                            },
                        )
                    }
                }
        }
        refresh()
    }

    /** Advertised salary normalised to EUR/year, or null when the source published none. */
    private fun eurPerYear(job: Job): Int? =
        if (job.salaryMin <= 0) null
        else com.aiglobal.jobfinder.data.geo.CurrencyConverter
            .toEurPerYear(job.salaryMin, job.currencyCode, job.salaryPeriod)

    fun refresh() {
        viewModelScope.launch {
            uiStateInternal.update { it.copy(isLoading = true) }
            repository.refresh()
            uiStateInternal.update { it.copy(isLoading = false) }
        }
    }

    fun onQueryChange(value: String) = uiStateInternal.update { it.copy(query = value) }

    fun toggleSavedFilter() = uiStateInternal.update { it.copy(savedOnly = !it.savedOnly) }

    fun toggleEligibleFilter() = uiStateInternal.update { it.copy(eligibleOnly = !it.eligibleOnly) }

    fun toggleSponsoredFilter() = uiStateInternal.update { it.copy(sponsoredOnly = !it.sponsoredOnly) }

    fun onCountryChange(value: String?) = uiStateInternal.update { it.copy(country = value) }

    fun onSourceChange(value: String?) = uiStateInternal.update { it.copy(source = value) }

    fun onSortChange(value: JobSort) = uiStateInternal.update { it.copy(sort = value) }

    fun clearFilters() = uiStateInternal.update {
        it.copy(
            query = "",
            savedOnly = false,
            eligibleOnly = false,
            sponsoredOnly = false,
            country = null,
            source = null,
            minMatch = 0,
            minExpatScore = 0,
            visaDocumentedOnly = false,
            strongInternationalFitOnly = false,
            tradeOnly = false,
            verifiedVisaOnly = false,
            verifiedAccommodationOnly = false,
            verifiedFlightsOnly = false,
            openVacancyOnly = false,
            recentlyVerifiedOnly = false,
            newOnly = false,
        )
    }

    /** Shows only postings first seen since the user last reviewed the new opportunities. */
    fun toggleNewFilter() = uiStateInternal.update { it.copy(newOnly = !it.newOnly) }

    /** Turns the "new" filter on or off explicitly, used when opening from the Home card. */
    fun setNewOnly(enabled: Boolean) = uiStateInternal.update { it.copy(newOnly = enabled) }

    fun onMinExpatScoreChange(value: Int) = uiStateInternal.update { it.copy(minExpatScore = value) }

    fun toggleVisaDocumentedFilter() =
        uiStateInternal.update { it.copy(visaDocumentedOnly = !it.visaDocumentedOnly) }

    /** Shows only postings whose International Fit band is strong or excellent. */
    fun toggleStrongInternationalFitFilter() =
        uiStateInternal.update { it.copy(strongInternationalFitOnly = !it.strongInternationalFitOnly) }

    /** Shows only postings whose title is the candidate's own trade. */
    fun toggleTradeOnlyFilter() = uiStateInternal.update { it.copy(tradeOnly = !it.tradeOnly) }

    /** Shows only postings whose employer documents visa sponsorship. */
    fun toggleVerifiedVisaFilter() =
        uiStateInternal.update { it.copy(verifiedVisaOnly = !it.verifiedVisaOnly) }

    /** Shows only postings whose employer documents accommodation. */
    fun toggleVerifiedAccommodationFilter() =
        uiStateInternal.update { it.copy(verifiedAccommodationOnly = !it.verifiedAccommodationOnly) }

    /** Shows only postings whose employer documents annual flights home. */
    fun toggleVerifiedFlightsFilter() =
        uiStateInternal.update { it.copy(verifiedFlightsOnly = !it.verifiedFlightsOnly) }

    /** Shows only postings the source itself confirms are still accepting applicants. */
    fun toggleOpenVacancyFilter() =
        uiStateInternal.update { it.copy(openVacancyOnly = !it.openVacancyOnly) }

    /** Shows only postings retrieved recently enough to rely on. */
    fun toggleRecentlyVerifiedFilter() =
        uiStateInternal.update { it.copy(recentlyVerifiedOnly = !it.recentlyVerifiedOnly) }

    /**
     * Flips the saved flag locally first so the bookmark reacts immediately, then persists.
     * The feed re-emits with the authoritative flag shortly after.
     */
    fun toggleSave(jobId: String) {
        uiStateInternal.update { state ->
            val next = if (state.savedJobIds.contains(jobId)) state.savedJobIds - jobId
            else state.savedJobIds + jobId
            state.copy(savedJobIds = next)
        }
        viewModelScope.launch { repository.toggleSaved(jobId) }
    }
}

class JobsViewModelFactory(
    private val repository: com.aiglobal.jobfinder.data.repository.JobRepository,
    private val expatPreferences: kotlinx.coroutines.flow.Flow<ExpatPreferences> =
        kotlinx.coroutines.flow.flowOf(ExpatPreferences()),
    private val candidateProfile: kotlinx.coroutines.flow.Flow<CandidateProfile> =
        kotlinx.coroutines.flow.flowOf(CandidateProfile()),
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        JobsViewModel(repository, expatPreferences, candidateProfile = candidateProfile) as T
}

data class HomeUiState(
    val isLoading: Boolean = true,
    val profile: UserProfile? = null,
    val stats: DashboardStats = DashboardStats(0, 0, 0, 0),
    val bestMatches: List<Job> = emptyList(),
    /**
     * Postings first seen since the user last reviewed the new opportunities, newest first.
     * Sourced from the incremental discovery ledger, so the initial market snapshot is never
     * shown here — only genuine incremental finds.
     */
    val newOpportunities: List<Job> = emptyList(),
    /** How many of [totalJobs] were first seen since the last review. */
    val newPostingCount: Int = 0,
    /** Whether the first read has recorded a baseline, so "no new postings" is meaningful. */
    val discoveryBaselineEstablished: Boolean = false,
    /**
     * The state of automatic discovery: when a search last ran and succeeded, how much is new,
     * and which sources are failing. Mirrored from the repository so the Home card and the
     * Profile summary describe the same event.
     */
    val discoverySummary: com.aiglobal.jobfinder.data.discovery.DiscoverySummary =
        com.aiglobal.jobfinder.data.discovery.DiscoverySummary(),
    val totalJobs: Int = 0,
    val applications: List<ApplicationRecord> = emptyList(),
    val reminders: List<ApplicationReminder> = emptyList(),
    /** Per-source sync status, so an empty feed can be explained honestly. */
    val syncState: JobSyncState = JobSyncState(),
    /**
     * Expat Benefits Score per posting, keyed by job id. Kept separate from the CV match so
     * the dashboard never conflates "my CV fits" with "the package is good".
     */
    val expatAssessments: Map<String, ExpatBenefitsAssessment> = emptyMap(),
) {
    /** Application counts, derived so the dashboard and the pipeline never disagree. */
    val activeApplications: Int get() = applications.count {
        it.status != ApplicationStatus.SAVED && !it.status.isTerminal
    }

    val interviewCount: Int get() = applications.count { it.status == ApplicationStatus.INTERVIEW }

    /** Due or overdue reminders, the ones worth surfacing on the dashboard. */
    val dueReminders: List<ApplicationReminder> get() = reminders.filter { it.due }

    /** The Expat Benefits Score for a posting, or null when the posting documents none. */
    fun expatScore(job: Job): Int? = expatAssessments[job.id]?.takeIf { it.hasEvidence }?.score
}

class HomeViewModel(
    private val repository: com.aiglobal.jobfinder.data.repository.JobRepository,
    private val applicationCenter: com.aiglobal.jobfinder.data.applications.ApplicationCenter,
    private val expatPreferences: kotlinx.coroutines.flow.Flow<ExpatPreferences> = kotlinx.coroutines.flow.flowOf(ExpatPreferences()),
    private val expatEngine: ExpatBenefitsEngine = ExpatBenefitsEngine(),
    /**
     * The plain-language state of automatic discovery, so the Home card can say when a search
     * last ran and how much is new without re-deriving it from the diagnostics.
     */
    private val discoverySummary: kotlinx.coroutines.flow.Flow<com.aiglobal.jobfinder.data.discovery.DiscoverySummary> =
        kotlinx.coroutines.flow.flowOf(com.aiglobal.jobfinder.data.discovery.DiscoverySummary()),
) : ViewModel() {

    private val _uiState = kotlinx.coroutines.flow.MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState

    init {
        viewModelScope.launch {
            repository.profile.collect { profile -> _uiState.update { it.copy(profile = profile) } }
        }
        viewModelScope.launch {
            repository.stats.collect { stats -> _uiState.update { it.copy(stats = stats) } }
        }
        viewModelScope.launch {
            repository.syncState.collect { sync -> _uiState.update { it.copy(syncState = sync) } }
        }
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                repository.jobs,
                expatPreferences,
                repository.newPostings,
            ) { jobs, prefs, newPostings -> Triple(jobs, prefs, newPostings) }
                .collect { (jobs, prefs, newPostings) ->
                    val newIds = newPostings.jobIds.toSet()
                    _uiState.update {
                        it.copy(
                            bestMatches = jobs.sortedByDescending { job -> job.matchScore }.take(4),
                            // Newest discovery first, so the freshest find leads the rail.
                            newOpportunities = newPostings.jobIds.mapNotNull { id -> jobs.firstOrNull { it.id == id } },
                            newPostingCount = jobs.count { job -> newIds.contains(job.id) },
                            discoveryBaselineEstablished = newPostings.baselineEstablished,
                            totalJobs = jobs.size,
                            expatAssessments = jobs.associate { job ->
                                job.id to expatEngine.assess(
                                    benefits = job.benefitEvidence,
                                    preferences = prefs,
                                    salaryEurPerYear = homeEurPerYear(job),
                                )
                            },
                        )
                    }
                }
        }
        viewModelScope.launch {
            discoverySummary.collect { summary -> _uiState.update { it.copy(discoverySummary = summary) } }
        }
        viewModelScope.launch {
            applicationCenter.applications.collect { apps ->
                _uiState.update {
                    it.copy(
                        applications = apps,
                        stats = it.stats.copy(
                            applications = apps.count { record -> record.status != ApplicationStatus.SAVED && !record.status.isTerminal },
                            interviews = apps.count { record -> record.status == ApplicationStatus.INTERVIEW },
                        ),
                    )
                }
            }
        }
        viewModelScope.launch {
            applicationCenter.reminders.collect { reminders ->
                _uiState.update { it.copy(reminders = reminders) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repository.refresh()
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun toggleSave(jobId: String) = viewModelScope.launch { repository.toggleSaved(jobId) }

    /** Advertised salary normalised to EUR/year, or null when the source published none. */
    private fun homeEurPerYear(job: Job): Int? =
        if (job.salaryMin <= 0) null
        else com.aiglobal.jobfinder.data.geo.CurrencyConverter
            .toEurPerYear(job.salaryMin, job.currencyCode, job.salaryPeriod)
}

class HomeViewModelFactory(
    private val repository: com.aiglobal.jobfinder.data.repository.JobRepository,
    private val applicationCenter: com.aiglobal.jobfinder.data.applications.ApplicationCenter,
    private val expatPreferences: kotlinx.coroutines.flow.Flow<ExpatPreferences> = kotlinx.coroutines.flow.flowOf(ExpatPreferences()),
    private val discoverySummary: kotlinx.coroutines.flow.Flow<com.aiglobal.jobfinder.data.discovery.DiscoverySummary> =
        kotlinx.coroutines.flow.flowOf(com.aiglobal.jobfinder.data.discovery.DiscoverySummary()),
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        HomeViewModel(repository, applicationCenter, expatPreferences, discoverySummary = discoverySummary) as T
}

data class ApplicationsUiState(
    val isLoading: Boolean = true,
    val applications: List<ApplicationRecord> = emptyList(),
    val reminders: List<ApplicationReminder> = emptyList(),
    val selectedStatus: com.aiglobal.jobfinder.data.model.ApplicationStatus? = null,
    /** Prepared applications waiting on the user's approval, newest first. */
    val draftsAwaitingApproval: List<ApplicationDraft> = emptyList(),
) {
    /** Applications that match the active stage filter, newest activity first. */
    val visible: List<ApplicationRecord>
        get() = applications
            .filter { selectedStatus == null || it.status == selectedStatus }
            .sortedByDescending { it.updatedAtEpochMillis }

    val activeCount: Int get() = applications.count {
        it.status == ApplicationStatus.APPLIED || it.status == ApplicationStatus.INTERVIEW
    }

    val interviewCount: Int get() = applications.count { it.status == ApplicationStatus.INTERVIEW }

    val dueReminders: List<ApplicationReminder> get() = reminders.filter { it.due }
}

class ApplicationsViewModel(
    private val repository: com.aiglobal.jobfinder.data.repository.JobRepository,
    private val applicationCenter: com.aiglobal.jobfinder.data.applications.ApplicationCenter,
    /** The approval queue. Optional so a screenshot fixture can omit it. */
    private val draftCenter: com.aiglobal.jobfinder.data.applications.ApplicationDraftCenter? = null,
) : ViewModel() {

    private val _uiState = kotlinx.coroutines.flow.MutableStateFlow(ApplicationsUiState())
    val uiState: StateFlow<ApplicationsUiState> = _uiState

    init {
        viewModelScope.launch {
            applicationCenter.applications.collect { apps ->
                _uiState.update { it.copy(applications = apps) }
            }
        }
        viewModelScope.launch {
            applicationCenter.reminders.collect { reminders ->
                _uiState.update { it.copy(reminders = reminders) }
            }
        }
        draftCenter?.let { center ->
            viewModelScope.launch {
                center.awaitingApproval.collect { drafts ->
                    _uiState.update { it.copy(draftsAwaitingApproval = drafts) }
                }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repository.refresh()
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun selectStatus(status: com.aiglobal.jobfinder.data.model.ApplicationStatus?) =
        _uiState.update { it.copy(selectedStatus = status) }

    fun advance(applicationId: String, status: ApplicationStatus) =
        viewModelScope.launch { applicationCenter.setStatus(applicationId, status) }

    fun archive(applicationId: String) = viewModelScope.launch { applicationCenter.archive(applicationId) }

    /** Approves the exact content of a prepared draft, so it can then be submitted. */
    fun approveDraft(draftId: String) = viewModelScope.launch { draftCenter?.approve(draftId) }

    /** Removes a prepared draft from the queue without applying. */
    fun skipDraft(draftId: String) = viewModelScope.launch { draftCenter?.skip(draftId) }
}

class ApplicationsViewModelFactory(
    private val repository: com.aiglobal.jobfinder.data.repository.JobRepository,
    private val applicationCenter: com.aiglobal.jobfinder.data.applications.ApplicationCenter,
    private val draftCenter: com.aiglobal.jobfinder.data.applications.ApplicationDraftCenter? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ApplicationsViewModel(repository, applicationCenter, draftCenter) as T
}

class ProfileViewModel(
    private val repository: com.aiglobal.jobfinder.data.repository.JobRepository,
    demoMode: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false),
) : ViewModel() {

    val profile: StateFlow<UserProfile?> = repository.profile
        .map { it as UserProfile? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether the optional demo fixtures are being served instead of the live feeds. */
    val demoMode: StateFlow<Boolean> = demoMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setDemoMode(enabled: Boolean) = viewModelScope.launch { repository.setDemoMode(enabled) }

    /** Per-source health, shown in the Job sources diagnostics section. */
    val diagnostics: StateFlow<com.aiglobal.jobfinder.data.model.ProviderDiagnostics> = repository.diagnostics
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.aiglobal.jobfinder.data.model.ProviderDiagnostics())

    fun setAutoApply(enabled: Boolean) = viewModelScope.launch { repository.setAutoApply(enabled) }

    fun runValidation() = viewModelScope.launch { repository.runLiveValidation() }
}

class ProfileViewModelFactory(
    private val repository: com.aiglobal.jobfinder.data.repository.JobRepository,
    private val demoMode: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false),
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ProfileViewModel(repository, demoMode) as T
}

/** What the AI workspace needs to describe the current state honestly. */
data class AiUiState(
    val hasCv: Boolean = false,
    val skillCount: Int = 0,
    val strongMatchCount: Int = 0,
    /** True when a key is saved, consent is on, and there is a job to work on. */
    val assistantReady: Boolean = false,
    /** True when a key has been saved (regardless of consent). */
    val assistantConfigured: Boolean = false,
    /** True when the user has consented to sending profile facts to NVIDIA. */
    val assistantConsent: Boolean = false,
    /** The human-readable model name, for the panel subtitle. */
    val modelLabel: String = "",
    /** True while a request is in flight. */
    val assistantRunning: Boolean = false,
    /** The last successful output, already labelled as remote or on-device. */
    val assistantOutput: String = "",
    val assistantOutputTitle: String = "",
    /** True when [assistantOutput] came from NVIDIA; false when it is the local fallback. */
    val assistantOutputIsRemote: Boolean = false,
    /** A classified, plain-language failure message, empty when the last run was fine. */
    val assistantError: String = "",
)

class AiViewModel(
    private val repository: com.aiglobal.jobfinder.data.repository.JobRepository,
    cvRepository: com.aiglobal.jobfinder.data.repository.CvRepository,
    private val aiAssistant: com.aiglobal.jobfinder.data.ai.NvidiaJobAssistant =
        com.aiglobal.jobfinder.data.ai.NvidiaJobAssistant(),
    nvidiaConfig: kotlinx.coroutines.flow.Flow<com.aiglobal.jobfinder.data.model.NvidiaConfig> =
        kotlinx.coroutines.flow.flowOf(com.aiglobal.jobfinder.data.model.NvidiaConfig()),
) : ViewModel() {

    private val _uiState = kotlinx.coroutines.flow.MutableStateFlow(AiUiState())
    val uiState: StateFlow<AiUiState> = _uiState

    private var profile: com.aiglobal.jobfinder.data.model.CandidateProfile =
        com.aiglobal.jobfinder.data.model.CandidateProfile()

    /** The best-matching posting, which the assistant acts on. Null until the feed loads. */
    private var bestJob: Job? = null

    init {
        viewModelScope.launch {
            repository.jobs.collect { jobs ->
                bestJob = jobs.maxByOrNull { it.matchScore }
                _uiState.update {
                    it.copy(
                        strongMatchCount = jobs.count { job -> job.matchScore >= 80 },
                        assistantReady = it.assistantConfigured && it.assistantConsent && bestJob != null,
                    )
                }
            }
        }
        viewModelScope.launch {
            cvRepository.record.collect { record ->
                val parsed = record?.profile
                profile = parsed ?: com.aiglobal.jobfinder.data.model.CandidateProfile()
                _uiState.update {
                    it.copy(
                        hasCv = parsed != null && (parsed.fullName.isNotBlank() || parsed.skills.isNotEmpty()),
                        skillCount = parsed?.skills?.size ?: 0,
                    )
                }
            }
        }
        viewModelScope.launch {
            nvidiaConfig.collect { config ->
                latestConfig = config
                _uiState.update {
                    it.copy(
                        assistantConfigured = config.isConfigured,
                        assistantConsent = config.consentGranted,
                        modelLabel = com.aiglobal.jobfinder.data.model.NvidiaModels.labelFor(config.modelId),
                        assistantReady = config.isConfigured && config.consentGranted && bestJob != null,
                    )
                }
            }
        }
    }

    /**
     * Runs one assistant task on the best-matching posting. When the remote model is not
     * available it degrades to the on-device explanation for [AssistTask.EXPLAIN_MATCH],
     * so the panel always shows something useful and never implies remote output it lacks.
     */
    fun runAssistant(task: com.aiglobal.jobfinder.data.ai.AssistTask) {
        val job = bestJob
        if (job == null) {
            _uiState.update { it.copy(assistantError = "No matching posting yet. Pull to refresh the feed.") }
            return
        }
        _uiState.update { it.copy(assistantRunning = true, assistantError = "", assistantOutput = "") }
        viewModelScope.launch {
            val config = currentConfig()
            val result = aiAssistant.run(config, task, profile, job)
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        assistantRunning = false,
                        assistantOutput = result.text,
                        assistantOutputTitle = "${task.label} — ${job.title}",
                        assistantOutputIsRemote = true,
                    )
                }
            } else {
                val fallback = if (task == com.aiglobal.jobfinder.data.ai.AssistTask.EXPLAIN_MATCH) {
                    aiAssistant.localExplanation(job, profile)
                } else {
                    ""
                }
                _uiState.update {
                    it.copy(
                        assistantRunning = false,
                        assistantError = result.message,
                        assistantOutput = fallback,
                        assistantOutputTitle = if (fallback.isBlank()) "" else "${task.label} — on-device",
                        assistantOutputIsRemote = false,
                    )
                }
            }
        }
    }

    /** The most recent config, read from the flow's latest emission. */
    private var latestConfig: com.aiglobal.jobfinder.data.model.NvidiaConfig =
        com.aiglobal.jobfinder.data.model.NvidiaConfig()

    private fun currentConfig(): com.aiglobal.jobfinder.data.model.NvidiaConfig = latestConfig
}

class AiViewModelFactory(
    private val repository: com.aiglobal.jobfinder.data.repository.JobRepository,
    private val cvRepository: com.aiglobal.jobfinder.data.repository.CvRepository,
    private val aiAssistant: com.aiglobal.jobfinder.data.ai.NvidiaJobAssistant =
        com.aiglobal.jobfinder.data.ai.NvidiaJobAssistant(),
    private val nvidiaConfig: kotlinx.coroutines.flow.Flow<com.aiglobal.jobfinder.data.model.NvidiaConfig> =
        kotlinx.coroutines.flow.flowOf(com.aiglobal.jobfinder.data.model.NvidiaConfig()),
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AiViewModel(repository, cvRepository, aiAssistant, nvidiaConfig) as T
}

private fun <T> kotlinx.coroutines.flow.MutableStateFlow<T>.update(transform: (T) -> T) {
    value = transform(value)
}
