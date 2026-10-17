package com.aiglobal.jobfinder.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aiglobal.jobfinder.data.jobs.provider.ProviderAudit
import com.aiglobal.jobfinder.data.model.ProviderDiagnostics
import com.aiglobal.jobfinder.data.repository.JobRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** State of the live-search validation screen. */
data class SourceDiagnosticsUiState(
    val diagnostics: ProviderDiagnostics = ProviderDiagnostics(),
    /** True while a validation run is in flight, driven from the repository. */
    val isRunning: Boolean = false,
    /** A user-facing note, e.g. after a manual run completes. */
    val note: String? = null,
    /**
     * What each registered source is — live, fixture or mock, what its API supports, and how
     * it is reached. A documentation fact, not a live measurement, so it is available before
     * any run and never claims a source answered. Keyed for quick lookup by provider id.
     */
    val audit: List<ProviderAudit> = emptyList(),
) {
    fun auditFor(providerId: String): ProviderAudit? = audit.firstOrNull { it.id == providerId }
}

/**
 * Drives the live-search validation screen. It only ever shows what the repository
 * actually recorded: a run reports each source's real outcome, and starting a run while
 * one is already going is a no-op rather than a queue.
 */
class SourceDiagnosticsViewModel(
    private val repository: JobRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SourceDiagnosticsUiState(audit = repository.providerAudit))
    val uiState: StateFlow<SourceDiagnosticsUiState> = _uiState.asStateFlow()

    /** Latest snapshot, kept so a run can describe its own outcome without re-collecting. */
    private var latest: ProviderDiagnostics = ProviderDiagnostics()

    init {
        viewModelScope.launch {
            repository.diagnostics.collect { diagnostics ->
                latest = diagnostics
                _uiState.update { it.copy(diagnostics = diagnostics, isRunning = diagnostics.isRefreshing) }
            }
        }
    }

    fun runValidation() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRunning = true, note = null) }
            repository.runLiveValidation()
            val result = latest
            _uiState.update {
                it.copy(
                    isRunning = false,
                    note = when {
                        result.successCount == 0 && result.failureCount > 0 ->
                            "No source answered. The list on screen is the last good result."
                        result.successCount > 0 && result.failureCount > 0 ->
                            "${result.successCount} source(s) answered, ${result.failureCount} failed. Kept the good results."
                        result.successCount > 0 ->
                            "${result.successCount} source(s) answered with ${result.totalValidJobs} usable postings."
                        else -> "Validation finished with nothing to report."
                    },
                )
            }
        }
    }

    fun dismissNote() = _uiState.update { it.copy(note = null) }
}

class SourceDiagnosticsViewModelFactory(
    private val repository: JobRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SourceDiagnosticsViewModel(repository) as T
}
