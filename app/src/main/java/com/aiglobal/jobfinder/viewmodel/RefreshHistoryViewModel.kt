package com.aiglobal.jobfinder.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aiglobal.jobfinder.data.discovery.RefreshHistory
import kotlinx.coroutines.flow.Flow

/**
 * Read-only view of the refresh history for the history screen. There is nothing to mutate:
 * a run is recorded by whoever performed it, and this screen only reports what happened.
 */
class RefreshHistoryViewModel(
    history: Flow<RefreshHistory>,
) : ViewModel() {

    val history: Flow<RefreshHistory> = history
}

class RefreshHistoryViewModelFactory(
    private val history: Flow<RefreshHistory>,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = RefreshHistoryViewModel(history) as T
}
