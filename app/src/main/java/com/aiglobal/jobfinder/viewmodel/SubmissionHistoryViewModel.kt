package com.aiglobal.jobfinder.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aiglobal.jobfinder.data.submissions.SubmissionEntry
import com.aiglobal.jobfinder.data.submissions.SubmissionRegistry
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Read/write view of the application registry for the history screen. Every mutation is a
 * deliberate user action; none of them sends anything, and the only switch that could ever
 * matter for a real send is [setSendingEnabled], which stays off unless the user turns it on.
 */
class SubmissionHistoryViewModel(
    private val registry: SubmissionRegistry,
) : ViewModel() {

    val entries: StateFlow<List<SubmissionEntry>> = registry.entries

    val sendingEnabled: StateFlow<Boolean> = registry.sendingEnabled

    init {
        viewModelScope.launch { registry.load() }
    }

    fun setSendingEnabled(enabled: Boolean) = viewModelScope.launch { registry.setSendingEnabled(enabled) }

    fun confirmSame(key: String, comparedToKey: String) =
        viewModelScope.launch { registry.confirmSame(key, comparedToKey) }

    fun confirmDistinct(key: String) = viewModelScope.launch { registry.confirmDistinct(key) }

    fun forget(key: String) = viewModelScope.launch { registry.forget(key) }
}

class SubmissionHistoryViewModelFactory(
    private val registry: SubmissionRegistry,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = SubmissionHistoryViewModel(registry) as T
}
