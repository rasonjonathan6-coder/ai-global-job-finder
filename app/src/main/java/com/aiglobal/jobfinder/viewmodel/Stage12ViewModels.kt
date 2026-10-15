package com.aiglobal.jobfinder.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aiglobal.jobfinder.data.cv.CvAdaptation
import com.aiglobal.jobfinder.data.cv.CvAdaptationEngine
import com.aiglobal.jobfinder.data.repository.CvRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The adapted-CV view for one destination, rebuilt whenever the CV changes. */
data class CvAdaptationUiState(
    val isLoading: Boolean = true,
    val adaptation: CvAdaptation = CvAdaptationEngine().adapt(null, ""),
)

/**
 * Drives the Stage 12 CV adaptation screen. The target country is the candidate's first
 * preferred country when the CV names one, so the screen opens on something useful rather
 * than an empty form; everything else is derived from the analysed CV.
 */
class CvAdaptationViewModel(
    cvRepository: CvRepository,
    private val engine: CvAdaptationEngine = CvAdaptationEngine(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(CvAdaptationUiState())
    val uiState: StateFlow<CvAdaptationUiState> = _uiState

    init {
        viewModelScope.launch {
            cvRepository.record.collect { record ->
                val country = record?.profile?.preferredCountries?.firstOrNull().orEmpty()
                _uiState.update {
                    it.copy(isLoading = false, adaptation = engine.adapt(record, country))
                }
            }
        }
    }
}

class CvAdaptationViewModelFactory(
    private val cvRepository: CvRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CvAdaptationViewModel(cvRepository) as T
}
