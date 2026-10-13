package com.aiglobal.jobfinder

import android.app.Application
import com.aiglobal.jobfinder.data.applications.ApplicationCenter
import com.aiglobal.jobfinder.data.applications.ApplicationStore
import com.aiglobal.jobfinder.data.cv.CvFileStore
import com.aiglobal.jobfinder.data.cv.CvParserRegistry
import com.aiglobal.jobfinder.data.cv.HeuristicCvAnalyzer
import com.aiglobal.jobfinder.data.jobs.JobCache
import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.provider.ArbeitnowProvider
import com.aiglobal.jobfinder.data.jobs.provider.JobicyProvider
import com.aiglobal.jobfinder.data.jobs.provider.RemotiveProvider
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.prefs.UserPreferencesRepository
import com.aiglobal.jobfinder.data.repository.CvRepository
import com.aiglobal.jobfinder.data.repository.JobRepository
import com.aiglobal.jobfinder.data.repository.LiveJobRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** The candidate profile that drives job scoring; empty until a CV is analysed. */
    val candidateProfile: Flow<CandidateProfile> =
        MutableStateFlow(CandidateProfile()).also { state ->
            appScope.launch {
                cvRepository.record.collect { record ->
                    state.value = record?.profile ?: CandidateProfile()
                }
            }
        }

    val jobRepository: JobRepository by lazy { createJobRepository() }

    /**
     * The application pipeline, cover-letter writer and expat guide. Persisted to
     * app-private storage; nothing here is uploaded anywhere.
     */
    val applicationCenter: ApplicationCenter by lazy {
        ApplicationCenter(
            repository = jobRepository,
            store = ApplicationStore(application),
            candidateProfile = candidateProfile,
        )
    }

    /**
     * Job feed. Real postings from public job boards, scored against the CV profile.
     * The cache directory is app-private, so cached postings never leave the device.
     */
    protected open fun createJobRepository(): JobRepository = LiveJobRepository(
        providers = listOf(ArbeitnowProvider(), RemotiveProvider(), JobicyProvider()),
        mapper = JobMapper(
            matchingEngine = MatchingEngine(),
            eligibilityEngine = EligibilityEngine(),
        ),
        candidateProfile = candidateProfile,
        cache = JobCache(File(application.filesDir, "job_feed")),
    )
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
