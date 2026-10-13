package com.aiglobal.jobfinder.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aiglobal.jobfinder.data.cv.CvImportException
import com.aiglobal.jobfinder.data.cv.CvParseException
import com.aiglobal.jobfinder.data.model.CandidateField
import com.aiglobal.jobfinder.data.model.CvRecord
import com.aiglobal.jobfinder.data.model.CvStatus
import com.aiglobal.jobfinder.data.model.ProfileCompleteness
import com.aiglobal.jobfinder.data.repository.CvRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A recoverable problem to show the user, with an optional retry action. */
data class CvMessage(
    val text: String,
    val isError: Boolean = true,
    val retry: (() -> Unit)? = null,
)

data class CvUiState(
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val busyLabel: String = "",
    val record: CvRecord? = null,
    val completeness: ProfileCompleteness = ProfileCompleteness(0, emptyList()),
    val message: CvMessage? = null,
    val analysisStaysOnDevice: Boolean = true,
    val analyzerLabel: String = "",
) {
    val hasDocument: Boolean get() = record != null
    val isAnalyzed: Boolean get() = record?.status == CvStatus.ANALYZED
    val hasFailed: Boolean get() = record?.status == CvStatus.FAILED
}

/**
 * Drives the CV import and analysis flow. Import, parse and analyse each run on
 * background dispatchers inside the repository, so the UI thread only ever renders.
 */
class CvViewModel(private val repository: CvRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(CvUiState())
    val uiState: StateFlow<CvUiState> = _uiState.asStateFlow()

    private var analysisJob: Job? = null

    init {
        _uiState.update {
            it.copy(
                analysisStaysOnDevice = repository.analysisStaysOnDevice,
                analyzerLabel = repository.analyzerLabel,
            )
        }
        viewModelScope.launch {
            repository.record.collect { record ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        record = record,
                        completeness = repository.completeness(),
                    )
                }
            }
        }
    }

    /** Called with the result of the system file picker. */
    fun onFilePicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, busyLabel = "Importing your CV…", message = null) }
            try {
                repository.importFile(uri)
                _uiState.update { it.copy(isBusy = false, busyLabel = "") }
                analyze()
            } catch (e: CvImportException) {
                _uiState.update {
                    it.copy(isBusy = false, busyLabel = "", message = CvMessage(e.message ?: "Import failed."))
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isBusy = false, busyLabel = "", message = CvMessage("Import failed. Please try another file."))
                }
            }
        }
    }

    fun analyze() {
        if (_uiState.value.record == null) return
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            _uiState.update {
                it.copy(isBusy = true, busyLabel = "Analysing your CV…", message = null)
            }
            try {
                repository.analyzeImported()
                _uiState.update { it.copy(isBusy = false, busyLabel = "") }
            } catch (e: CvParseException) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyLabel = "",
                        message = CvMessage(e.message ?: "Analysis failed."),
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyLabel = "",
                        message = CvMessage("Analysis failed. Please try again."),
                    )
                }
            }
        }
    }

    fun updateField(field: CandidateField, value: String) {
        viewModelScope.launch { repository.updateField(field, value) }
    }

    fun updateList(field: CandidateField, values: List<String>) {
        viewModelScope.launch { repository.updateList(field, values) }
    }

    fun removeCv() {
        viewModelScope.launch { repository.remove() }
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }
}

class CvViewModelFactory(private val repository: CvRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = CvViewModel(repository) as T
}
