package com.aiglobal.jobfinder

import android.app.Application
import com.aiglobal.jobfinder.data.alerts.JobAlertStore
import com.aiglobal.jobfinder.data.alerts.JobAlertsCenter
import com.aiglobal.jobfinder.data.ai.NvidiaJobAssistant
import com.aiglobal.jobfinder.data.applications.ApplicationCenter
import com.aiglobal.jobfinder.data.applications.ApplicationDraftCenter
import com.aiglobal.jobfinder.data.applications.ApplicationDraftStore
import com.aiglobal.jobfinder.data.applications.ApplicationStore
import com.aiglobal.jobfinder.data.autoapply.AutoApplyCenter
import com.aiglobal.jobfinder.data.autoapply.AutoApplyStore
import com.aiglobal.jobfinder.data.cv.CvAiAnalyzer
import com.aiglobal.jobfinder.data.cv.CvFileStore
import com.aiglobal.jobfinder.data.cv.CvParserRegistry
import com.aiglobal.jobfinder.data.interview.InterviewCoach
import com.aiglobal.jobfinder.data.interview.InterviewPrepStore
import com.aiglobal.jobfinder.data.jobs.JobCache
import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaCountries
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaCredentials
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaProvider
import com.aiglobal.jobfinder.data.jobs.provider.ArbeitnowProvider
import com.aiglobal.jobfinder.data.jobs.provider.AshbyProvider
import com.aiglobal.jobfinder.data.jobs.provider.EuresProvider
import com.aiglobal.jobfinder.data.jobs.provider.GreenhouseProvider
import com.aiglobal.jobfinder.data.jobs.provider.HimalayasProvider
import com.aiglobal.jobfinder.data.jobs.provider.HttpJsonFeed
import com.aiglobal.jobfinder.data.jobs.provider.JoobleProvider
import com.aiglobal.jobfinder.data.jobs.provider.JobicyProvider
import com.aiglobal.jobfinder.data.jobs.provider.JobProvider
import com.aiglobal.jobfinder.data.jobs.provider.JobTechDevProvider
import com.aiglobal.jobfinder.data.jobs.provider.LandingJobsProvider
import com.aiglobal.jobfinder.data.jobs.provider.LeverProvider
import com.aiglobal.jobfinder.data.jobs.provider.ProviderAudit
import com.aiglobal.jobfinder.data.jobs.provider.ProviderAuditRegistry
import com.aiglobal.jobfinder.data.jobs.provider.RemoteOkProvider
import com.aiglobal.jobfinder.data.jobs.provider.RemotiveProvider
import com.aiglobal.jobfinder.data.jobs.provider.SmartRecruitersProvider
import com.aiglobal.jobfinder.data.jobs.provider.TheMuseProvider
import com.aiglobal.jobfinder.data.jobs.provider.WorkdayProvider
import com.aiglobal.jobfinder.data.jobs.provider.WorkingNomadsProvider
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.ProviderSettings
import com.aiglobal.jobfinder.data.net.NetworkMonitor
import com.aiglobal.jobfinder.data.notifications.JobNotificationCenter
import com.aiglobal.jobfinder.data.notifications.JobNotificationsCoordinator
import com.aiglobal.jobfinder.data.notifications.NotificationHistoryStore
import com.aiglobal.jobfinder.data.prefs.UserPreferencesRepository
import com.aiglobal.jobfinder.data.repository.CvRepository
import com.aiglobal.jobfinder.data.repository.JobRepository
import com.aiglobal.jobfinder.data.repository.LiveJobRepository
import com.aiglobal.jobfinder.data.scam.ScamDetector
import com.aiglobal.jobfinder.data.submissions.SubmissionRegistry
import com.aiglobal.jobfinder.data.submissions.SubmissionRegistryStore
import com.aiglobal.jobfinder.work.JobRefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/**
 * Lightweight manual dependency container. The graph is still small enough to keep
 * explicit without pulling in a DI framework.
 *
 * `open` so instrumentation and Robolectric tests can substitute a deterministic
 * repository without changing production wiring.
 */
open class AppContainer(application: Application) {
    private val application: Application = application
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val preferencesRepository: UserPreferencesRepository = UserPreferencesRepository(application)

    /**
     * CV import and analysis. The analysis runs on-device by default; when the user has saved
     * an NVIDIA key *and* granted consent, the optional [CvAiAnalyzer] sends the CV text to
     * NVIDIA for a richer, structured read, with the on-device result as a guaranteed fallback.
     */
    val cvRepository: CvRepository = CvRepository(
        context = application,
        fileStore = CvFileStore(application),
        parserRegistry = CvParserRegistry.default(application),
        analyzer = CvAiAnalyzer(configProvider = { nvidiaConfigValue }),
    )

    private val candidateProfileState = MutableStateFlow(CandidateProfile()).also { state ->
        appScope.launch {
            cvRepository.record.collect { record ->
                state.value = record?.profile ?: CandidateProfile()
            }
        }
    }

    /** The candidate profile that drives job scoring; empty until a CV is analysed. */
    val candidateProfile: Flow<CandidateProfile> = candidateProfileState

    val jobRepository: JobRepository by lazy { createJobRepository() }

    /**
     * What the deterministic test fixtures stand in for. In production the sample candidate
     * and sample applications are only ever shown in demo mode — never on a fresh install.
     * Tests that swap in fixture data can override this to keep the demo behaviour.
     */
    protected open val seedsSampleData: Boolean get() = false

    /**
     * Demo mode as this container sees it: the user's persisted choice, or the test seam
     * that forces fixtures on. Every fixture-aware component reads this one flow, so jobs,
     * applications and saved searches always agree on whether sample data is being served.
     */
    val demoMode: Flow<Boolean> by lazy {
        combine(preferencesRepository.demoMode, MutableStateFlow(seedsSampleData)) { persisted, seeded ->
            persisted || seeded
        }
    }

    /**
     * The application pipeline, cover-letter writer and expat guide. Persisted to
     * app-private storage; nothing here is uploaded anywhere.
     */
    val applicationCenter: ApplicationCenter by lazy {
        ApplicationCenter(
            repository = jobRepository,
            store = ApplicationStore(application),
            candidateProfile = candidateProfile,
            demoMode = demoMode,
        )
    }

    /**
     * Job feed. Real postings from public job boards, scored against the CV profile.
     * The cache directory is app-private, so cached postings never leave the device.
     *
     * The remote boards need no credentials and cover "hire from anywhere" roles. On top
     * of those, the official EURES feed and a set of employer applicant-tracking-system
     * boards (Workday, Greenhouse, Ashby) reach the industrial, mining and energy
     * vacancies where expat roles actually live. Adzuna is added for ordinary national
     * vacancies — where visa-sponsored work lives — but only once the user has entered
     * its key.
     */
    protected open fun createJobRepository(): JobRepository = LiveJobRepository(
        providers = buildJobProviders(),
        mapper = JobMapper(
            matchingEngine = MatchingEngine(),
            eligibilityEngine = EligibilityEngine(),
        ),
        candidateProfile = candidateProfile,
        cvRecord = cvRepository.record,
        cache = JobCache(File(application.filesDir, "job_feed")),
        isOnline = { NetworkMonitor(application).isOnline() },
        providerSettings = providerSettings,
        demoMode = demoMode,
        demoModeSetter = { enabled -> preferencesRepository.setDemoMode(enabled) },
        autoApplyFlow = preferencesRepository.autoApplyEnabled,
        autoApplySetter = { enabled -> preferencesRepository.setAutoApplyEnabled(enabled) },
        savedIdsFlow = preferencesRepository.savedJobIds,
        savedIdsSetter = { ids -> preferencesRepository.setSavedJobIds(ids) },
        seenLedger = jobSeenLedger,
        // Availability: the source's own flag and deadline settle most postings without a
        // request; the remaining links are reached at most once a day, cached across restarts.
        availabilityVerifier = com.aiglobal.jobfinder.data.jobs.availability.AvailabilityVerifier(
            cache = com.aiglobal.jobfinder.data.jobs.availability.AvailabilityCache(
                store = com.aiglobal.jobfinder.data.jobs.availability.AvailabilityCacheStore(
                    File(application.filesDir, "availability"),
                ),
            ),
            probe = com.aiglobal.jobfinder.data.jobs.availability.HttpAvailabilityLinkProbe(),
            // Only a link that passes the same inspector the Apply button uses is probed, so
            // the app never reaches an unusable or unvetted URL.
            linkIsUsable = { job ->
                com.aiglobal.jobfinder.data.apply.ApplicationUrlInspector().inspect(job).canOpen
            },
        ),
        // Persist the daily probe-budget window beside the availability cache, so the cap is
        // held across refreshes *and* across restarts rather than reset by each launch.
        availabilityBudgetStore = com.aiglobal.jobfinder.data.jobs.availability.AvailabilityBudgetStore(
            File(application.filesDir, "availability"),
        ),
    )

    /**
     * The registered job sources, built once and shared with the feed. Kept as its own
     * function so the audit can describe exactly the same providers the feed runs, and the
     * two cannot drift.
     */
    open fun buildJobProviders(): List<JobProvider> = buildList {
        add(ArbeitnowProvider())
        add(RemotiveProvider())
        add(JobicyProvider())
        add(HimalayasProvider())
        // Official European Employment Services feed: government vacancies across the
        // EEA and Switzerland, with full descriptions inline.
        add(EuresProvider(feed = HttpJsonFeed))
        // Employer applicant-tracking-system boards. These are the employer's own
        // careers sites, which is where expat industrial, mining and energy roles
        // actually live and where an application goes directly to the employer.
        add(WorkdayProvider(feed = HttpJsonFeed))
        add(GreenhouseProvider(feed = HttpJsonFeed))
        add(AshbyProvider(feed = HttpJsonFeed))
        add(LeverProvider(feed = HttpJsonFeed))
        // SmartRecruiters is keyless and supports server-side keyword search, so the
        // inspection, testing and industrial-service employers it hosts (SGS, Wabtec,
        // Konecranes, Bosch) can be narrowed to the candidate's own trades across the
        // Gulf and Africa rather than returning a broad sample.
        add(SmartRecruitersProvider(feed = HttpJsonFeed))
        add(adzunaProvider)
        add(
            JoobleProvider(
                apiKey = { joobleApiKeyValue },
                feed = HttpJsonFeed,
            ),
        )
        // Key-free public boards added to widen coverage beyond the aggregators above.
        // The Muse is swept per country so the feed is narrowed rather than a global sample;
        // JobTech Dev (the Swedish public employment service) is the one source here with a
        // real server-side keyword search, so the candidate's own trade is asked for directly.
        add(TheMuseProvider(feed = HttpJsonFeed))
        add(RemoteOkProvider(feed = HttpJsonFeed))
        add(WorkingNomadsProvider(feed = HttpJsonFeed))
        add(LandingJobsProvider(feed = HttpJsonFeed))
        add(JobTechDevProvider(feed = HttpJsonFeed))
    }

    /**
     * A factual description of every registered source — where its postings come from, what
     * its API supports, and how it is reached — derived from the same providers the feed
     * runs. It states what is true of each source, never that a source answered just now.
     */
    val providerAudit: List<ProviderAudit> by lazy { ProviderAuditRegistry.audit(buildJobProviders()) }

    /**
     * Which sources are switched off, and which credential-gated sources have keys saved.
     * The repository uses this to tell "not configured" apart from "failed", so a source
     * the user has not set up never appears as broken.
     */
    val providerSettings: Flow<ProviderSettings> = combine(
        preferencesRepository.disabledSourceIds,
        preferencesRepository.adzunaCredentials,
        preferencesRepository.joobleApiKey,
    ) { disabled: Set<String>, adzuna: AdzunaCredentials, jooble: String ->
        ProviderSettings(
            disabledIds = disabled,
            configuredCredentialIds = buildSet {
                if (adzuna.isConfigured) add("adzuna")
                if (jooble.isNotBlank()) add("jooble")
            },
        )
    }

    /** Last-known Adzuna credentials, read synchronously by the provider factory. */
    @Volatile
    private var adzunaCredentialsValue: AdzunaCredentials = AdzunaCredentials()

    /** Last-known Adzuna markets, read synchronously by the provider factory. */
    @Volatile
    private var adzunaCountriesValue: Set<String> = emptySet()

    /** Last-known Jooble API key, read synchronously by the provider factory. */
    @Volatile
    private var joobleApiKeyValue: String = ""

    /**
     * Last-known NVIDIA key/model/consent, read synchronously when a CV analysis or a draft
     * starts. The consent gate inside [CvAiAnalyzer] means an unconfigured value sends nothing.
     */
    @Volatile
    private var nvidiaConfigValue: com.aiglobal.jobfinder.data.model.NvidiaConfig =
        com.aiglobal.jobfinder.data.model.NvidiaConfig()

    /**
     * The Adzuna provider instance, shared with the feed and used directly by the in-app
     * connection test so a "Test connection" tap exercises the same request path the feed
     * uses (real endpoints, real parsing) rather than a separate approximation. It is lazy so
     * it is built on first use, after the country collector has populated the last-known set.
     */
    val adzunaProvider: AdzunaProvider by lazy {
        AdzunaProvider(
            credentials = { adzunaCredentialsValue },
            feed = HttpJsonFeed,
            countries = adzunaCountriesValue.toList().ifEmpty { AdzunaCountries.defaults },
            pagesPerCountry = 2,
        )
    }

    init {
        appScope.launch {
            preferencesRepository.adzunaCredentials.collect { adzunaCredentialsValue = it }
        }
        appScope.launch {
            preferencesRepository.adzunaCountries.collect { adzunaCountriesValue = it }
        }
        appScope.launch {
            preferencesRepository.joobleApiKey.collect { joobleApiKeyValue = it }
        }
        appScope.launch {
            preferencesRepository.nvidiaConfig.collect { nvidiaConfigValue = it }
        }
    }

    // ---------------------------------------------------------------- Stage 5

    /** Scam detection. Pure, on-device, and never uploads the posting anywhere. */
    val scamDetector: ScamDetector by lazy { ScamDetector() }

    /** The AI interview coach. Generates the rehearsal set from the CV and the posting. */
    val interviewCoach: InterviewCoach by lazy { InterviewCoach() }

    /** Rehearsal attempts, persisted so the readiness score survives a restart. */
    val interviewPrepStore: InterviewPrepStore by lazy { InterviewPrepStore(application) }

    /** Saved searches evaluated against the live feed. */
    val jobAlertsCenter: JobAlertsCenter by lazy {
        JobAlertsCenter(
            repository = jobRepository,
            store = JobAlertStore(application),
            demoMode = demoMode,
        )
    }

    /**
     * The persistent application registry: one entry per opening (employer + title +
     * country), so the same job cannot be applied to twice even when it arrives from
     * several boards or the app restarts. Real sending is off by default and, with no
     * verified sender wired in, only the simulation path is reachable.
     */
    val submissionRegistry: SubmissionRegistry by lazy {
        SubmissionRegistry(store = SubmissionRegistryStore(application))
    }

    /** Safe auto-apply: prepares drafts and stops before submission. */
    val autoApplyCenter: AutoApplyCenter by lazy {
        AutoApplyCenter(
            repository = jobRepository,
            applicationCenter = applicationCenter,
            store = AutoApplyStore(application),
            candidateProfile = { candidateProfileValue() },
            submissionRegistry = submissionRegistry,
        )
    }

    /**
     * The application approval queue: prepares a full application, waits for the user's
     * explicit approval of the exact content, and only then submits it. Real sending stays
     * off, so a submission is always simulated. The draft's content hash is what makes the
     * approval meaningful — a later edit revokes it.
     */
    val applicationDraftCenter: ApplicationDraftCenter by lazy {
        ApplicationDraftCenter(
            repository = jobRepository,
            store = ApplicationDraftStore(application),
            submissionRegistry = submissionRegistry,
        )
    }

    /** Explainable ranking of the shortlist for an applicant in Madagascar. */
    val expatRankingEngine: com.aiglobal.jobfinder.data.jobs.ExpatRankingEngine by lazy {
        com.aiglobal.jobfinder.data.jobs.ExpatRankingEngine()
    }

    /** Explainable expatriate opportunity assessment for one posting. */
    val expatOpportunityEngine: com.aiglobal.jobfinder.data.expat.ExpatOpportunityEngine by lazy {
        com.aiglobal.jobfinder.data.expat.ExpatOpportunityEngine()
    }

    // ---------------------------------------------------------------- Stage 20

    /**
     * New-match notifications: the pure decision (which postings, and the honest wording)
     * plus the history that stops a job being announced twice. The channel is created here
     * so the system settings entry exists before the first notification.
     */
    val jobNotificationsCoordinator: JobNotificationsCoordinator by lazy {
        JobNotificationsCoordinator(
            center = JobNotificationCenter(application),
            history = NotificationHistoryStore(application),
            draftReviewHistory = NotificationHistoryStore(
                application,
                file = File(application.filesDir, "notified_draft_reviews.txt"),
            ),
        )
    }

    /** The one periodic background refresh. Created here; the schedule follows the preference. */
    val jobRefreshScheduler: JobRefreshScheduler by lazy { JobRefreshScheduler(application) }

    /**
     * The incremental-search ledger: what the app has already seen, so a scheduled run can
     * tell a genuinely new opening apart from one it has shown before. Shared by the
     * repository (which records each read) and the Profile summary (which reports the delta).
     */
    val jobSeenLedger: com.aiglobal.jobfinder.data.discovery.JobSeenLedger by lazy {
        com.aiglobal.jobfinder.data.discovery.JobSeenLedger(application)
    }

    /**
     * The last background-run outcome (did the scheduled search succeed, or give up?), kept on
     * disk so the discovery card can report it after a restart, not only in the session that
     * produced it. The worker records into it; the summary reads from it.
     */
    val refreshRunStateStore: com.aiglobal.jobfinder.work.RefreshRunStateStore by lazy {
        com.aiglobal.jobfinder.work.RefreshRunStateStore(application)
    }

    /**
     * The history of refresh runs, manual and scheduled alike, so the user can see when the
     * search last ran, whether it succeeded, and which sources answered — instead of only the
     * latest outcome. Small, on-device, and free of anything personal.
     */
    val refreshHistoryStore: com.aiglobal.jobfinder.data.discovery.RefreshHistoryStore by lazy {
        com.aiglobal.jobfinder.data.discovery.RefreshHistoryStore(application)
    }

    /**
     * The one entry point for a refresh, whether a person tapped "Refresh now" or the worker
     * woke up. It enforces one-at-a-time runs, retries a manual run under the same finite
     * policy a scheduled one follows, and records every finished run into the history.
     */
    val refreshRunCoordinator: com.aiglobal.jobfinder.work.RefreshRunCoordinator by lazy {
        com.aiglobal.jobfinder.work.RefreshRunCoordinator(
            repository = jobRepository,
            historyStore = refreshHistoryStore,
        )
    }

    /** The recorded run history, newest first. Read once on start, then hot. */
    val refreshHistory: Flow<com.aiglobal.jobfinder.data.discovery.RefreshHistory> by lazy {
        refreshHistoryStore.observe()
    }

    /**
     * The plain-language state of automatic discovery, assembled from the repository's
     * diagnostics, the last incremental delta, and the last background-run outcome, so the
     * settings section can show when a search last ran and succeeded, how much is new, which
     * sources are failing, and — honestly — when the background search gave up.
     */
    val discoverySummary: Flow<com.aiglobal.jobfinder.data.discovery.DiscoverySummary> by lazy {
        combine(
            jobRepository.diagnostics,
            jobRepository.discovery,
            refreshRunStateStore.observe(),
        ) { diagnostics, delta, runState ->
            com.aiglobal.jobfinder.data.discovery.DiscoverySummary.from(diagnostics, delta, runState)
        }
    }

    /** The current CV-derived profile, read synchronously for the auto-apply prep pass. */
    private fun candidateProfileValue(): CandidateProfile = candidateProfileState.value

    // ---------------------------------------------------------------- Stage 16

    /**
     * The optional AI drafting assistant, backed by NVIDIA's hosted model. It is the only
     * component in the app that can send data off the device, and it does so only after the
     * user saves a key, turns it on, and grants consent — enforced in [NvidiaJobAssistant].
     */
    val aiAssistant: NvidiaJobAssistant by lazy { NvidiaJobAssistant() }

    /** NVIDIA API key, model and consent, encrypted at rest in [UserPreferencesRepository]. */
    val nvidiaConfig: Flow<com.aiglobal.jobfinder.data.model.NvidiaConfig> =
        preferencesRepository.nvidiaConfig

    /** True when the persisted credential store is encrypted (the Keystore is available). */
    val credentialsEncrypted: Boolean get() = preferencesRepository.credentialsEncrypted
}

open class AiJobFinderApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        // Keep the periodic refresh in step with the user's preference, and make sure the
        // notification channel exists before the first match is found. Both are cheap and
        // idempotent, so running them on every start is safe.
        val appContainer = container
        appContainer.jobNotificationsCoordinator.prepare()
        // Load the application registry at start so the Profile badge and the history screen
        // reflect the persisted entries immediately, before either screen is opened.
        appScopeForScheduling().launch { appContainer.submissionRegistry.load() }
        appScopeForScheduling().launch {
            appContainer.preferencesRepository.refreshPreferences.collect { preferences ->
                appContainer.jobRefreshScheduler.apply(preferences)
            }
        }
    }

    private fun appScopeForScheduling(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Overridable so tests can install a container with a deterministic repository. */
    protected open fun createContainer(): AppContainer = AppContainer(this)
}
