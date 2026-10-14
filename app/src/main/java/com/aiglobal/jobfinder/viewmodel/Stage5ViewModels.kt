package com.aiglobal.jobfinder.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aiglobal.jobfinder.data.alerts.JobAlertsCenter
import com.aiglobal.jobfinder.data.autoapply.AutoApplyCenter
import com.aiglobal.jobfinder.data.interview.InterviewCoach
import com.aiglobal.jobfinder.data.interview.InterviewPrepStore
import com.aiglobal.jobfinder.data.model.AlertFrequency
import com.aiglobal.jobfinder.data.model.AlertScope
import com.aiglobal.jobfinder.data.model.AnswerConfidence
import com.aiglobal.jobfinder.data.model.AnswerFeedback
import com.aiglobal.jobfinder.data.model.AutoApplyState
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.InterviewAttempt
import com.aiglobal.jobfinder.data.model.InterviewPrep
import com.aiglobal.jobfinder.data.model.InterviewReadiness
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobAlertRule
import com.aiglobal.jobfinder.data.model.JobAlertsState
import com.aiglobal.jobfinder.data.model.ScamReport
import com.aiglobal.jobfinder.data.repository.CvRepository
import com.aiglobal.jobfinder.data.repository.JobRepository
import com.aiglobal.jobfinder.data.scam.ScamDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- Interview coach

data class InterviewUiState(
    val isLoading: Boolean = true,
    val job: Job? = null,
    val prep: InterviewPrep? = null,
    val attempts: List<InterviewAttempt> = emptyList(),
    val selectedQuestionId: String? = null,
    val draftAnswer: String = "",
    val feedback: AnswerFeedback? = null,
    val confidence: AnswerConfidence = AnswerConfidence.GETTING_THERE,
) {
    val readiness: InterviewReadiness?
        get() = prep?.let { p ->
            InterviewReadiness(
                attempted = attempts.map { it.questionId }.distinct().count { id -> p.questions.any { it.id == id } },
                total = p.questions.size,
                confident = attempts.count { it.confidence == AnswerConfidence.CONFIDENT },
            )
        }

    val selectedQuestion get() = prep?.questions?.firstOrNull { it.id == selectedQuestionId }
}

class InterviewViewModel(
    private val jobId: String,
    private val repository: JobRepository,
    private val cvRepository: CvRepository,
    private val coach: InterviewCoach,
    private val store: InterviewPrepStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InterviewUiState())
    val uiState: StateFlow<InterviewUiState> = _uiState

    private var profile: CandidateProfile = CandidateProfile()

    init {
        viewModelScope.launch {
            cvRepository.record.collect { record ->
                profile = record?.profile ?: CandidateProfile()
                rebuild()
            }
        }
        viewModelScope.launch {
            store.load()
            store.attempts.collect { attempts -> _uiState.update { it.copy(attempts = attempts) } }
        }
        viewModelScope.launch {
            // A blank id means "coach me for my best current match", used by the AI tab.
            repository.jobs.collect { jobs ->
                val job = if (jobId.isBlank()) jobs.maxByOrNull { it.matchScore } else jobs.firstOrNull { it.id == jobId }
                _uiState.update { it.copy(isLoading = false, job = job) }
                rebuild()
            }
        }
    }

    private fun rebuild() {
        val job = _uiState.value.job ?: return
        _uiState.update { it.copy(prep = coach.prep(job, profile)) }
    }

    fun selectQuestion(id: String) = _uiState.update {
        it.copy(selectedQuestionId = id, draftAnswer = "", feedback = null)
    }

    fun onAnswerChange(value: String) = _uiState.update { it.copy(draftAnswer = value) }

    fun onConfidenceChange(value: AnswerConfidence) = _uiState.update { it.copy(confidence = value) }

    /** Grades the draft locally and records the attempt so readiness advances. */
    fun submitAnswer() {
        val state = _uiState.value
        val question = state.selectedQuestion ?: return
        val feedback = coach.feedback(question, state.draftAnswer)
        _uiState.update { it.copy(feedback = feedback) }
        viewModelScope.launch {
            store.put(InterviewAttempt(question.id, state.confidence, System.currentTimeMillis()))
        }
    }

    fun resetProgress() = viewModelScope.launch { store.clear() }
}

class InterviewViewModelFactory(
    private val jobId: String,
    private val repository: JobRepository,
    private val cvRepository: CvRepository,
    private val coach: InterviewCoach,
    private val store: InterviewPrepStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        InterviewViewModel(jobId, repository, cvRepository, coach, store) as T
}

// ---------------------------------------------------------------- Scam shield

data class ScamUiState(
    val isLoading: Boolean = true,
    val job: Job? = null,
    val report: ScamReport? = null,
    val message: String? = null,
)

class ScamShieldViewModel(
    private val jobId: String,
    private val repository: JobRepository,
    private val detector: ScamDetector,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScamUiState())
    val uiState: StateFlow<ScamUiState> = _uiState

    init {
        viewModelScope.launch {
            repository.jobs.collect { jobs ->
                val job = if (jobId.isBlank()) jobs.maxByOrNull { it.matchScore } else jobs.firstOrNull { it.id == jobId }
                _uiState.value = ScamUiState(
                    isLoading = false,
                    job = job,
                    report = job?.let { detector.scan(it) },
                    message = if (job == null) "This posting is no longer in your feed, so it cannot be scanned." else null,
                )
            }
        }
    }

    fun rescan() {
        val job = _uiState.value.job ?: return
        _uiState.update { it.copy(report = detector.scan(job)) }
    }
}

class ScamShieldViewModelFactory(
    private val jobId: String,
    private val repository: JobRepository,
    private val detector: ScamDetector,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ScamShieldViewModel(jobId, repository, detector) as T
}

// ---------------------------------------------------------------- Job alerts

class JobAlertsViewModel(private val center: JobAlertsCenter) : ViewModel() {

    val state: StateFlow<JobAlertsState> = center.state

    fun refresh() = viewModelScope.launch { center.refresh() }

    fun createRule(
        name: String,
        keywords: List<String>,
        countries: List<String>,
        scope: AlertScope,
        frequency: AlertFrequency,
        minMatchScore: Int,
    ) = viewModelScope.launch {
        center.addRule(
            JobAlertRule(
                id = center.newRuleId(),
                name = name.ifBlank { "Saved search" },
                keywords = keywords,
                countries = countries,
                scope = scope,
                frequency = frequency,
                minMatchScore = minMatchScore,
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    fun removeRule(id: String) = viewModelScope.launch { center.removeRule(id) }

    fun markRead(jobId: String) = viewModelScope.launch { center.markRead(jobId) }

    fun clearAll() = viewModelScope.launch { center.clearAll() }
}

class JobAlertsViewModelFactory(private val center: JobAlertsCenter) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = JobAlertsViewModel(center) as T
}

// ---------------------------------------------------------------- Auto-apply

class AutoApplyViewModel(private val center: AutoApplyCenter) : ViewModel() {

    val state: StateFlow<AutoApplyState> = center.state

    fun setEnabled(enabled: Boolean) = viewModelScope.launch { center.setEnabled(enabled) }

    fun setThreshold(value: Int) = viewModelScope.launch { center.setThreshold(value) }

    fun prepare() = viewModelScope.launch { center.prepare() }

    fun approve(jobId: String) = viewModelScope.launch { center.approve(jobId) }

    fun skip(jobId: String) = viewModelScope.launch { center.skip(jobId) }
}

class AutoApplyViewModelFactory(private val center: AutoApplyCenter) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AutoApplyViewModel(center) as T
}
