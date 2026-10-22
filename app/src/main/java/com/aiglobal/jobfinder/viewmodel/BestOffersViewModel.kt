package com.aiglobal.jobfinder.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aiglobal.jobfinder.data.jobs.offer.OfferMatchRanking
import com.aiglobal.jobfinder.data.model.BestOfferFilter
import com.aiglobal.jobfinder.data.model.BestOfferSort
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.CvStatus
import com.aiglobal.jobfinder.data.model.JobSyncState
import com.aiglobal.jobfinder.data.model.RankedOffer
import com.aiglobal.jobfinder.data.model.SourceState
import com.aiglobal.jobfinder.data.repository.CvRepository
import com.aiglobal.jobfinder.data.repository.JobRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the "My best offers" screen needs, including the flags that let it pick the
 * right state without re-deriving anything. The screen only renders what is here.
 */
data class BestOffersUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val hasCv: Boolean = false,
    val cvAnalysed: Boolean = false,
    val profileName: String = "",
    /** The ranked offers for the current sort and filter, strongest first. */
    val offers: List<RankedOffer> = emptyList(),
    /** How many compatible postings exist in total, before the active filter narrows them. */
    val totalCompatible: Int = 0,
    val sort: BestOfferSort = BestOfferSort.COMPATIBILITY,
    val filter: BestOfferFilter = BestOfferFilter.ALL,
    val syncState: JobSyncState = JobSyncState(),
    /**
     * The most recent successful read across all sources, or null when no read has ever
     * succeeded. It is the same value [JobSyncState.lastSyncedAtEpochMillis] carries, so the
     * "last refreshed" line reflects the repository's own record and never a local guess.
     */
    val lastSyncedAtEpochMillis: Long? = null,
    /** True when the user has acknowledged a partial failure on the current screen. */
    val partialAcknowledged: Boolean = false,
) {
    private val successSources: Int get() = syncState.successCount
    private val errorSources: Int get() = syncState.errorCount
    private val idleSources: Int get() = syncState.sources.count { it.state == SourceState.IDLE }

    /**
     * True when every source failed and nothing usable came back, so an empty list is an
     * outage rather than a genuine "no offers". A source the user switched off, one with no
     * key saved, or one simply waiting for a connection reports [SourceState.IDLE] (dormant),
     * never ERROR, so those never count here and an offline launch is not called an outage.
     */
    val allSourcesFailed: Boolean
        get() = syncState.sources.isNotEmpty() && successSources == 0 && idleSources == 0 &&
            errorSources > 0

    /**
     * True when some sources answered and some failed. The screen says so honestly instead of
     * claiming either a full success or a full failure.
     */
    val partialRefresh: Boolean
        get() = successSources > 0 && errorSources > 0

    /**
     * The offers on screen are the ones kept from a previous successful read while the latest
     * attempt brought nothing usable back. The repository sets [JobSyncState.showingCachedOnly]
     * for exactly this case — an outage or an offline launch with a retained list — so the
     * screen warns instead of presenting the kept offers as current.
     */
    val showingStaleResults: Boolean
        get() = offers.isNotEmpty() && syncState.showingCachedOnly

    val hasOffers: Boolean get() = offers.isNotEmpty()

    /** One honest sentence describing how the most recent read went. Null when nothing to say. */
    val refreshNotice: String?
        get() = when {
            isRefreshing -> null
            showingStaleResults ->
                "The last search brought nothing usable back, so these offers are the ones we " +
                    "already had. They may not be current."
            allSourcesFailed ->
                "No source answered just now, so there is nothing to rank. Check your connection and try again."
            partialRefresh -> "Some sources could not be reached ($errorSources of " +
                "${syncState.sources.size}), so this list may be missing recent postings."
            else -> null
        }

    /** True when [refreshNotice] should be shown as a warning rather than quiet information. */
    val refreshNoticeIsWarning: Boolean
        get() = showingStaleResults || allSourcesFailed || (partialRefresh && !partialAcknowledged)
}

/**
 * Drives the Stage 80 "My best offers" section.
 *
 * The ranking is a pure derivation of two inputs that already exist — the live feed (already
 * scored against the CV by [JobRepository]) and the analysed CV profile — handed to
 * [OfferMatchRanking]. Nothing is fetched or scored here; the engine owns the rules, so this
 * list can never disagree with the compatibility shown elsewhere.
 */
class BestOffersViewModel(
    private val repository: JobRepository,
    private val cvRepository: CvRepository,
    private val ranking: OfferMatchRanking = OfferMatchRanking(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BestOffersUiState())
    val uiState: StateFlow<BestOffersUiState> = _uiState

    private val sort = MutableStateFlow(BestOfferSort.COMPATIBILITY)
    private val filter = MutableStateFlow(BestOfferFilter.ALL)

    init {
        viewModelScope.launch {
            combine(
                repository.jobs,
                cvRepository.record,
                repository.syncState,
                sort,
                filter,
            ) { jobs, record, sync, selectedSort, selectedFilter ->
                val profile = record?.profile ?: CandidateProfile()
                val now = clock()
                val analysed = record != null &&
                    (record.status == CvStatus.ANALYZED || record.status == CvStatus.PARTIAL)
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        hasCv = record != null,
                        cvAnalysed = analysed,
                        profileName = profile.fullName,
                        offers = if (analysed) {
                            ranking.rank(jobs, profile, now, selectedSort, selectedFilter)
                        } else {
                            emptyList()
                        },
                        totalCompatible = if (analysed) {
                            ranking.count(jobs, profile, now, BestOfferFilter.ALL)
                        } else {
                            0
                        },
                        sort = selectedSort,
                        filter = selectedFilter,
                        syncState = sync,
                        // The repository already records the last successful read; the screen
                        // shows that value rather than a timestamp taken at render time.
                        lastSyncedAtEpochMillis = sync.lastSyncedAtEpochMillis,
                    )
                }
            }.collect { }
        }
    }

    fun setSort(value: BestOfferSort) {
        sort.value = value
    }

    fun setFilter(value: BestOfferFilter) {
        filter.value = value
    }

    /**
     * Re-reads every source through the repository's existing budget and cancellation rules.
     * The outcome is not inferred from whether the call threw: the repository records each
     * source's result in [JobSyncState], so the screen can tell a clean success from a partial
     * failure and from a total outage — and a failure never clears the offers already on screen.
     */
    fun refresh() {
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, partialAcknowledged = false) }
            runCatching { repository.refresh() }
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    /** Dismisses the partial-failure notice once the user has read it. */
    fun acknowledgePartialRefresh() {
        _uiState.update { it.copy(partialAcknowledged = true) }
    }
}

class BestOffersViewModelFactory(
    private val repository: JobRepository,
    private val cvRepository: CvRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        BestOffersViewModel(repository, cvRepository) as T
}
