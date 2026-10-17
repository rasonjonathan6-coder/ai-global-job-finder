package com.aiglobal.jobfinder.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aiglobal.jobfinder.data.expat.ShortlistEngine
import com.aiglobal.jobfinder.data.geo.CurrencyConverter
import com.aiglobal.jobfinder.data.jobs.ExpatRankingEngine
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.ExpatPreferences
import com.aiglobal.jobfinder.data.model.ExpatRankingState
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.repository.CvRepository
import com.aiglobal.jobfinder.data.repository.JobRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Drives the Stage 48 expatriate ranking — the screen the whole "opportunity / ranking"
 * foundation was built for.
 *
 * The ranking is a pure derivation of three inputs that already exist: the live feed, the
 * analysed CV and the user's expat priorities. It runs the same [ShortlistEngine] the
 * shortlist screen uses, then hands that shortlist to [ExpatRankingEngine], so the order
 * shown here can never disagree with the readiness the shortlist reports. Nothing is scored
 * here; both engines own their rules.
 */
class ExpatRankingViewModel(
    private val repository: JobRepository,
    private val cvRepository: CvRepository,
    private val expatPreferences: Flow<ExpatPreferences> = flowOf(ExpatPreferences()),
    private val shortlistEngine: ShortlistEngine = ShortlistEngine(),
    private val engine: ExpatRankingEngine = ExpatRankingEngine(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExpatRankingState())
    val uiState: StateFlow<ExpatRankingState> = _uiState

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
                engine.rank(shortlist, preferences)
            }.collect { _uiState.value = it }
        }
    }

    private fun eurPerYear(job: Job): Int? =
        if (job.salaryMin <= 0) null
        else CurrencyConverter.toEurPerYear(job.salaryMin, job.currencyCode, job.salaryPeriod)

    fun refresh() = viewModelScope.launch { repository.refresh() }
}

class ExpatRankingViewModelFactory(
    private val repository: JobRepository,
    private val cvRepository: CvRepository,
    private val expatPreferences: Flow<ExpatPreferences> = flowOf(ExpatPreferences()),
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ExpatRankingViewModel(repository, cvRepository, expatPreferences) as T
}
