package com.aiglobal.jobfinder.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aiglobal.jobfinder.data.applications.ApplicationCenter
import com.aiglobal.jobfinder.data.assistant.ApplicationPlanEngine
import com.aiglobal.jobfinder.data.assistant.ExpatIntelligenceEngine
import com.aiglobal.jobfinder.data.expat.ShortlistEngine
import com.aiglobal.jobfinder.data.geo.CurrencyConverter
import com.aiglobal.jobfinder.data.interview.InterviewCoach
import com.aiglobal.jobfinder.data.interview.InterviewPrepStore
import com.aiglobal.jobfinder.data.model.ApplicationPlan
import com.aiglobal.jobfinder.data.model.ApplicationStatus
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.ExpatIntelligence
import com.aiglobal.jobfinder.data.model.ExpatPreferences
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.isTerminal
import com.aiglobal.jobfinder.data.model.stepIndex
import com.aiglobal.jobfinder.data.repository.CvRepository
import com.aiglobal.jobfinder.data.repository.JobRepository
import com.aiglobal.jobfinder.data.scam.ScamDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Drives the per-posting Application Assistant.
 *
 * The plan is a pure derivation of things the app already knows — the posting, the CV, the
 * user's priorities, the application pipeline and the interview rehearsal — so this view
 * model only wires them together and re-derives whenever any of them changes. No rule lives
 * here; [ApplicationPlanEngine] owns the plan and [ShortlistEngine] owns the readiness score.
 */
data class ApplicationPlanUiState(
    val isLoading: Boolean = true,
    val job: Job? = null,
    val plan: ApplicationPlan? = null,
    val message: String? = null,
)

class ApplicationPlanViewModel(
    private val jobId: String,
    private val repository: JobRepository,
    private val cvRepository: CvRepository,
    private val applicationCenter: ApplicationCenter,
    private val interviewCoach: InterviewCoach,
    private val interviewStore: InterviewPrepStore,
    private val scamDetector: ScamDetector,
    private val expatPreferences: Flow<ExpatPreferences> = flowOf(ExpatPreferences()),
    private val shortlistEngine: ShortlistEngine = ShortlistEngine(),
    private val planEngine: ApplicationPlanEngine = ApplicationPlanEngine(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(ApplicationPlanUiState())
    val uiState: StateFlow<ApplicationPlanUiState> = _uiState

    init {
        viewModelScope.launch { interviewStore.load() }
        viewModelScope.launch {
            combine(
                repository.jobs,
                cvRepository.record,
                expatPreferences,
                applicationCenter.applications,
                interviewStore.attempts,
            ) { jobs, cvRecord, preferences, applications, attempts ->
                val job = if (jobId.isBlank()) {
                    jobs.maxByOrNull { it.matchScore }
                } else {
                    jobs.firstOrNull { it.id == jobId }
                }
                if (job == null) {
                    return@combine ApplicationPlanUiState(
                        isLoading = false,
                        message = "This posting is no longer in your feed. Refresh the Jobs tab to reload it.",
                    )
                }

                val profile = cvRecord?.profile ?: CandidateProfile()
                val completeness = cvRepository.completeness().percent
                val entry = shortlistEngine.assess(
                    job = job,
                    profile = profile,
                    preferences = preferences,
                    profileCompletenessPercent = completeness,
                    salaryEurPerYear = eurPerYear(job),
                )
                val prep = interviewCoach.prep(job, profile)
                val attempted = attempts.count { attempt -> prep.questions.any { it.id == attempt.questionId } }
                val record = applications.firstOrNull { it.jobId == job.id }
                val hasApplied = record != null &&
                    record.status.stepIndex >= ApplicationStatus.APPLIED.stepIndex &&
                    !record.status.isTerminal

                ApplicationPlanUiState(
                    isLoading = false,
                    job = job,
                    plan = planEngine.build(
                        job = job,
                        entry = entry,
                        profileCompletenessPercent = completeness,
                        hasCoverLetter = applicationCenter.coverLetterForJob(job.id) != null,
                        interviewAttempted = attempted,
                        interviewTotal = prep.questions.size,
                        scamVerdict = scamDetector.scan(job).verdict,
                        hasApplied = hasApplied,
                    ),
                )
            }.collect { _uiState.value = it }
        }
    }

    /** Advertised salary normalised to EUR/year, or null when the source published none. */
    private fun eurPerYear(job: Job): Int? =
        if (job.salaryMin <= 0) null
        else CurrencyConverter.toEurPerYear(job.salaryMin, job.currencyCode, job.salaryPeriod)

    fun refresh() = viewModelScope.launch { repository.refresh() }
}

class ApplicationPlanViewModelFactory(
    private val jobId: String,
    private val repository: JobRepository,
    private val cvRepository: CvRepository,
    private val applicationCenter: ApplicationCenter,
    private val interviewCoach: InterviewCoach,
    private val interviewStore: InterviewPrepStore,
    private val scamDetector: ScamDetector,
    private val expatPreferences: Flow<ExpatPreferences> = flowOf(ExpatPreferences()),
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ApplicationPlanViewModel(
            jobId = jobId,
            repository = repository,
            cvRepository = cvRepository,
            applicationCenter = applicationCenter,
            interviewCoach = interviewCoach,
            interviewStore = interviewStore,
            scamDetector = scamDetector,
            expatPreferences = expatPreferences,
        ) as T
}

/**
 * Drives the whole-feed Expat Intelligence digest. Like the shortlist it summarises, it is
 * a pure derivation of the feed, the CV and the user's priorities, so it re-derives whenever
 * any of them changes.
 */
data class ExpatIntelligenceUiState(
    val isLoading: Boolean = true,
    val intelligence: ExpatIntelligence? = null,
)

class ExpatIntelligenceViewModel(
    private val repository: JobRepository,
    private val cvRepository: CvRepository,
    private val expatPreferences: Flow<ExpatPreferences> = flowOf(ExpatPreferences()),
    private val shortlistEngine: ShortlistEngine = ShortlistEngine(),
    private val engine: ExpatIntelligenceEngine = ExpatIntelligenceEngine(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExpatIntelligenceUiState())
    val uiState: StateFlow<ExpatIntelligenceUiState> = _uiState

    init {
        viewModelScope.launch {
            combine(
                repository.jobs,
                cvRepository.record,
                expatPreferences,
            ) { jobs, cvRecord, preferences ->
                val profile = cvRecord?.profile ?: CandidateProfile()
                val completeness = cvRepository.completeness().percent
                val shortlist = shortlistEngine.build(
                    jobs = jobs,
                    profile = profile,
                    preferences = preferences,
                    profileCompletenessPercent = completeness,
                    salaryEurPerYear = ::eurPerYear,
                )
                ExpatIntelligenceUiState(isLoading = false, intelligence = engine.build(shortlist))
            }.collect { _uiState.value = it }
        }
    }

    private fun eurPerYear(job: Job): Int? =
        if (job.salaryMin <= 0) null
        else CurrencyConverter.toEurPerYear(job.salaryMin, job.currencyCode, job.salaryPeriod)

    fun refresh() = viewModelScope.launch { repository.refresh() }
}

class ExpatIntelligenceViewModelFactory(
    private val repository: JobRepository,
    private val cvRepository: CvRepository,
    private val expatPreferences: Flow<ExpatPreferences> = flowOf(ExpatPreferences()),
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ExpatIntelligenceViewModel(repository, cvRepository, expatPreferences) as T
}
