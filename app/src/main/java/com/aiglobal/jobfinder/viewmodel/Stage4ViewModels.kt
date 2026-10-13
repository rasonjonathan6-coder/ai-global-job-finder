package com.aiglobal.jobfinder.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aiglobal.jobfinder.data.applications.ApplicationCenter
import com.aiglobal.jobfinder.data.model.ApplicationPipeline
import com.aiglobal.jobfinder.data.model.ApplicationRecord
import com.aiglobal.jobfinder.data.model.ApplicationStatus
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.CoverLetter
import com.aiglobal.jobfinder.data.model.ExpatGuide
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.LetterLanguage
import com.aiglobal.jobfinder.data.repository.CvRepository
import com.aiglobal.jobfinder.data.repository.JobRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One application, resolved from the pipeline. The cover letter and the destination's
 * settlement guide are pulled alongside it so the detail screen renders in one pass.
 */
data class ApplicationDetailUiState(
    val isLoading: Boolean = true,
    val record: ApplicationRecord? = null,
    val coverLetter: CoverLetter? = null,
    val expatGuide: ExpatGuide? = null,
    val nextAction: String = "",
) {
    val statusSteps: List<ApplicationStatus> get() = ApplicationPipeline.steps
}

class ApplicationDetailViewModel(
    private val applicationId: String,
    private val applicationCenter: ApplicationCenter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ApplicationDetailUiState())
    val uiState: StateFlow<ApplicationDetailUiState> = _uiState

    init {
        viewModelScope.launch {
            applicationCenter.applications.collect { apps ->
                val record = apps.firstOrNull { it.id == applicationId }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        record = record,
                        coverLetter = applicationCenter.coverLetter(record?.coverLetterId)
                            ?: record?.let { rec -> applicationCenter.coverLetterForJob(rec.jobId) },
                        expatGuide = record?.let { rec -> applicationCenter.expatGuide(rec.country) },
                        nextAction = record?.let { rec -> applicationCenter.nextAction(rec) } ?: "",
                    )
                }
            }
        }
    }

    fun setStatus(status: ApplicationStatus) =
        viewModelScope.launch { applicationCenter.setStatus(applicationId, status) }

    fun setInterviewAt(epochMillis: Long?) =
        viewModelScope.launch { applicationCenter.setInterviewAt(applicationId, epochMillis) }

    fun setFollowUpAt(epochMillis: Long?) =
        viewModelScope.launch { applicationCenter.setFollowUpAt(applicationId, epochMillis) }

    fun addNote(note: String) = viewModelScope.launch { applicationCenter.addNote(applicationId, note) }

    fun archive() = viewModelScope.launch { applicationCenter.archive(applicationId) }

    fun delete() = viewModelScope.launch { applicationCenter.deleteApplication(applicationId) }
}

class ApplicationDetailViewModelFactory(
    private val applicationId: String,
    private val applicationCenter: ApplicationCenter,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ApplicationDetailViewModel(applicationId, applicationCenter) as T
}

/** The cover-letter workspace: pick a language, generate, edit, copy. */
data class CoverLetterUiState(
    val isLoading: Boolean = true,
    val job: Job? = null,
    val profile: CandidateProfile = CandidateProfile(),
    val language: LetterLanguage = LetterLanguage.ENGLISH,
    val letter: CoverLetter? = null,
    val isGenerating: Boolean = false,
    val message: String? = null,
) {
    val hasCv: Boolean get() = profile.fullName.isNotBlank() || profile.skills.isNotEmpty()
}

class CoverLetterViewModel(
    private val jobId: String,
    private val repository: JobRepository,
    private val applicationCenter: ApplicationCenter,
    private val cvRepository: CvRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CoverLetterUiState())
    val uiState: StateFlow<CoverLetterUiState> = _uiState

    init {
        val job = repository.jobById(jobId)
        _uiState.update {
            it.copy(
                isLoading = job == null,
                job = job,
                language = job?.let { j -> LetterLanguage.forCountry(j.country) } ?: LetterLanguage.ENGLISH,
                letter = applicationCenter.coverLetterForJob(jobId),
            )
        }
        viewModelScope.launch {
            cvRepository.record.collect { record ->
                _uiState.update { it.copy(profile = record?.profile ?: CandidateProfile()) }
            }
        }
        viewModelScope.launch {
            applicationCenter.coverLetters.collect { letters ->
                val latest = letters.filter { it.jobId == jobId }.maxByOrNull { it.generatedAtEpochMillis }
                if (latest != null) _uiState.update { it.copy(letter = latest) }
            }
        }
    }

    fun selectLanguage(language: LetterLanguage) {
        _uiState.update { it.copy(language = language) }
    }

    /** Generates a fresh letter for the current language, tracking the job if needed. */
    fun generate() {
        val job = _uiState.value.job ?: return
        val profile = _uiState.value.profile
        if (profile.fullName.isBlank() && profile.skills.isEmpty()) {
            _uiState.update { it.copy(message = "Import and analyse your CV first, so the letter can use your real details.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true, message = null) }
            val letter = applicationCenter.generateCoverLetter(job, profile, _uiState.value.language)
            _uiState.update { it.copy(isGenerating = false, letter = letter) }
        }
    }

    fun updateBody(paragraphs: List<String>) {
        val id = _uiState.value.letter?.id ?: return
        viewModelScope.launch { applicationCenter.updateCoverLetterBody(id, paragraphs) }
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }
}

class CoverLetterViewModelFactory(
    private val jobId: String,
    private val repository: JobRepository,
    private val applicationCenter: ApplicationCenter,
    private val cvRepository: CvRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CoverLetterViewModel(jobId, repository, applicationCenter, cvRepository) as T
}

/** The settlement guide for a destination country. */
data class ExpatUiState(
    val isLoading: Boolean = true,
    val guide: ExpatGuide? = null,
)

class ExpatGuideViewModel(
    private val applicationCenter: ApplicationCenter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExpatUiState())
    val uiState: StateFlow<ExpatUiState> = _uiState

    fun load(country: String) {
        _uiState.update { it.copy(isLoading = false, guide = applicationCenter.expatGuide(country)) }
    }
}

class ExpatGuideViewModelFactory(
    private val applicationCenter: ApplicationCenter,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ExpatGuideViewModel(applicationCenter) as T
}
