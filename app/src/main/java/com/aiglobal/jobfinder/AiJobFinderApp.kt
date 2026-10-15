package com.aiglobal.jobfinder

import android.app.Application
import com.aiglobal.jobfinder.data.alerts.JobAlertStore
import com.aiglobal.jobfinder.data.alerts.JobAlertsCenter
import com.aiglobal.jobfinder.data.ai.NvidiaJobAssistant
import com.aiglobal.jobfinder.data.applications.ApplicationCenter
import com.aiglobal.jobfinder.data.applications.ApplicationStore
import com.aiglobal.jobfinder.data.autoapply.AutoApplyCenter
import com.aiglobal.jobfinder.data.autoapply.AutoApplyStore
import com.aiglobal.jobfinder.data.cv.CvFileStore
import com.aiglobal.jobfinder.data.cv.CvParserRegistry
import com.aiglobal.jobfinder.data.cv.HeuristicCvAnalyzer
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
import com.aiglobal.jobfinder.data.jobs.provider.HimalayasProvider
import com.aiglobal.jobfinder.data.jobs.provider.HttpJsonFeed
import com.aiglobal.jobfinder.data.jobs.provider.JoobleProvider
import com.aiglobal.jobfinder.data.jobs.provider.JobicyProvider
import com.aiglobal.jobfinder.data.jobs.provider.RemotiveProvider
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.ProviderSettings
import com.aiglobal.jobfinder.data.net.NetworkMonitor
import com.aiglobal.jobfinder.data.prefs.UserPreferencesRepository
import com.aiglobal.jobfinder.data.repository.CvRepository
import com.aiglobal.jobfinder.data.repository.JobRepository
import com.aiglobal.jobfinder.data.repository.LiveJobRepository
import com.aiglobal.jobfinder.data.scam.ScamDetector
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
     * CV import and analysis. The analyser is on-device and deterministic, so the
     * candidate's document is never uploaded to a third party.
     */
    val cvRepository: CvRepository = CvRepository(
        context = application,
        fileStore = CvFileStore(application),
        parserRegistry = CvParserRegistry.default(),
        analyzer = HeuristicCvAnalyzer(),
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
    private val demoMode: Flow<Boolean> by lazy {
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
     * The remote boards need no credentials and cover "hire from anywhere" roles. Adzuna
     * is added on top because it reaches ordinary national vacancies — where
     * visa-sponsored work actually lives — but only once the user has entered its key.
     */
    protected open fun createJobRepository(): JobRepository = LiveJobRepository(
        providers = buildList {
            add(ArbeitnowProvider())
            add(RemotiveProvider())
            add(JobicyProvider())
            add(HimalayasProvider())
            add(adzunaProvider)
            add(
                JoobleProvider(
                    apiKey = { joobleApiKeyValue },
                    feed = HttpJsonFeed,
                ),
            )
        },
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
    )

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

    /** Safe auto-apply: prepares drafts and stops before submission. */
    val autoApplyCenter: AutoApplyCenter by lazy {
        AutoApplyCenter(
            repository = jobRepository,
            applicationCenter = applicationCenter,
            store = AutoApplyStore(application),
            candidateProfile = { candidateProfileValue() },
        )
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
    }

    /** Overridable so tests can install a container with a deterministic repository. */
    protected open fun createContainer(): AppContainer = AppContainer(this)
}
