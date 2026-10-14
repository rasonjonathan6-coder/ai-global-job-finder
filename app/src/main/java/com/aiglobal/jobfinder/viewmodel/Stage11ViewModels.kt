package com.aiglobal.jobfinder.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aiglobal.jobfinder.data.expat.ShortlistEngine
import com.aiglobal.jobfinder.data.geo.CurrencyConverter
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.ExpatPreferences
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobSyncState
import com.aiglobal.jobfinder.data.model.ShortlistState
import com.aiglobal.jobfinder.data.repository.CvRepository
import com.aiglobal.jobfinder.data.repository.JobRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the Stage 11 expat shortlist.
 *
 * The shortlist is a pure derivation of three inputs that already exist elsewhere in the
 * app — the live feed, the analysed CV, and the user's expat priorities — so the view model
 * only wires them together and re-derives whenever any of them changes. Nothing is scored
 * here; [ShortlistEngine] owns the rules.
 */
class ShortlistViewModel(
    private val repository: JobRepository,
    private val cvRepository: CvRepository,
    private val expatPreferences: Flow<ExpatPreferences> = flowOf(ExpatPreferences()),
    private val engine: ShortlistEngine = ShortlistEngine(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShortlistState(isLoading = true))
    val uiState: StateFlow<ShortlistState> = _uiState

    init {
        viewModelScope.launch {
            combine(
                repository.jobs,
                expatPreferences,
                cvRepository.record,
                repository.syncState,
            ) { jobs, preferences, cvRecord, syncState ->
                val profile = cvRecord?.profile ?: CandidateProfile()
                val completeness = cvRepository.completeness().percent
                val state = engine.build(
                    jobs = jobs,
                    profile = profile,
                    preferences = preferences,
                    profileCompletenessPercent = completeness,
                    salaryEurPerYear = ::eurPerYear,
                )
                state.copy(
                    isLive = syncState.successCount > 0,
                    sourceNote = sourceNote(syncState, jobs.size),
                )
            }.collect { _uiState.value = it }
        }
        refresh()
    }

    /** Advertised salary normalised to EUR/year, or null when the source published none. */
    private fun eurPerYear(job: Job): Int? =
        if (job.salaryMin <= 0) null
        else CurrencyConverter.toEurPerYear(job.salaryMin, job.currencyCode, job.salaryPeriod)

    private fun sourceNote(syncState: JobSyncState, total: Int): String = when {
        total == 0 -> ""
        syncState.successCount > 0 && syncState.errorCount == 0 ->
            "Live from ${syncState.successCount} source${if (syncState.successCount == 1) "" else "s"}."
        syncState.successCount > 0 ->
            "${syncState.successCount} source(s) answered, ${syncState.errorCount} did not. Showing what came back."
        else -> "Showing cached postings — no source answered this run."
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repository.refresh()
            _uiState.update { it.copy(isLoading = false) }
        }
    }
}

class ShortlistViewModelFactory(
    private val repository: JobRepository,
    private val cvRepository: CvRepository,
    private val expatPreferences: Flow<ExpatPreferences> = flowOf(ExpatPreferences()),
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ShortlistViewModel(repository, cvRepository, expatPreferences) as T
}
