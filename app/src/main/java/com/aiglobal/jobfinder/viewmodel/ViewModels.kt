package com.aiglobal.jobfinder.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aiglobal.jobfinder.AppContainer
import com.aiglobal.jobfinder.data.model.ApplicationRecord
import com.aiglobal.jobfinder.data.model.ApplicationReminder
import com.aiglobal.jobfinder.data.model.ApplicationStatus
import com.aiglobal.jobfinder.data.model.DashboardStats
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobSyncState
import com.aiglobal.jobfinder.data.model.UserProfile
import com.aiglobal.jobfinder.data.model.isTerminal
import com.aiglobal.jobfinder.ui.theme.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Holds the theme preference at the app root so it is available before any screen composes. */
class AppViewModel(private val container: AppContainer) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = container.preferencesRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.System)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { container.preferencesRepository.setThemeMode(mode) }
    }
}

class AppViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(container) as T
}

data class JobsUiState(
    val isLoading: Boolean = true,
    val jobs: List<Job> = emptyList(),
    val query: String = "",
    val savedOnly: Boolean = false,
    /** Only postings a Madagascar-based applicant can realistically apply to. */
    val eligibleOnly: Boolean = false,
    /** Only postings where the employer states visa sponsorship. */
    val sponsoredOnly: Boolean = false,
    /** Canonical country name from [com.aiglobal.jobfinder.data.geo.CountryCatalog]. */
    val country: String? = null,
    /** Provider id, e.g. "remotive". */
    val source: String? = null,
    val minMatch: Int = 0,
    val sort: JobSort = JobSort.BEST_MATCH,
    val syncState: JobSyncState = JobSyncState(),
) {
    /** Countries present in the current result set, for the country filter. */
    val countries: List<String>
        get() = jobs.map { it.country }.distinct().sorted()

    /** Sources present in the current result set, for the source filter. */
    val sources: List<Pair<String, String>>
        get() = jobs.map { it.providerId to it.source }
            .distinctBy { it.first }
            .filter { it.first.isNotBlank() }
            .sortedBy { it.second }

    /** How many of the loaded postings pass the eligibility filter. */
    val eligibleCount: Int get() = jobs.count { it.applyableFromMadagascar }

    val hasActiveFilters: Boolean
        get() = savedOnly || eligibleOnly || sponsoredOnly || country != null ||
            source != null || minMatch > 0 || query.isNotBlank()

    val visibleJobs: List<Job>
        get() {
            var result = jobs
            if (savedOnly) result = result.filter { it.isSaved }
            if (eligibleOnly) result = result.filter { it.applyableFromMadagascar }
            if (sponsoredOnly) result = result.filter {
                it.visaSupport == com.aiglobal.jobfinder.data.model.VisaSupport.FULL_SPONSORSHIP ||
                    it.visaSupport == com.aiglobal.jobfinder.data.model.VisaSupport.SPONSORSHIP_AVAILABLE
            }
            country?.let { selected -> result = result.filter { it.country == selected } }
            source?.let { selected -> result = result.filter { it.providerId == selected } }
            if (minMatch > 0) result = result.filter { it.matchScore >= minMatch }

            val q = query.trim().lowercase()
            if (q.isNotBlank()) {
                result = result.filter {
                    it.title.lowercase().contains(q) ||
                        it.company.lowercase().contains(q) ||
                        it.country.lowercase().contains(q) ||
                        it.summary.lowercase().contains(q) ||
                        it.matchedSkills.any { skill -> skill.lowercase().contains(q) }
                }
            }

            return when (sort) {
                JobSort.BEST_MATCH -> result.sortedByDescending { it.matchScore }
                JobSort.NEWEST -> result.sortedByDescending { it.postedAtEpochMillis }
                JobSort.SALARY_HIGH -> result.sortedByDescending { eurPerYearOrZero(it) }
            }
        }

    private fun eurPerYearOrZero(job: Job): Int =
        if (job.salaryMin <= 0) 0
        else com.aiglobal.jobfinder.data.geo.CurrencyConverter
            .toEurPerYear(job.salaryMin, job.currencyCode, job.salaryPeriod) ?: 0
}

/** Sort orders offered on the Jobs screen. */
enum class JobSort(val label: String) {
    BEST_MATCH("Best match"),
    NEWEST("Newest"),
    SALARY_HIGH("Highest salary"),
}

class JobsViewModel(private val repository: com.aiglobal.jobfinder.data.repository.JobRepository) : ViewModel() {

    private val uiStateInternal = kotlinx.coroutines.flow.MutableStateFlow(JobsUiState())

    val uiState: StateFlow<JobsUiState> = uiStateInternal

    init {
        viewModelScope.launch {
            repository.jobs.collect { jobs ->
                uiStateInternal.update { it.copy(jobs = jobs) }
            }
        }
        viewModelScope.launch {
            repository.syncState.collect { sync ->
                uiStateInternal.update { it.copy(syncState = sync) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            uiStateInternal.update { it.copy(isLoading = true) }
            repository.refresh()
            uiStateInternal.update { it.copy(isLoading = false) }
        }
    }

    fun onQueryChange(value: String) = uiStateInternal.update { it.copy(query = value) }

    fun toggleSavedFilter() = uiStateInternal.update { it.copy(savedOnly = !it.savedOnly) }

    fun toggleEligibleFilter() = uiStateInternal.update { it.copy(eligibleOnly = !it.eligibleOnly) }

    fun toggleSponsoredFilter() = uiStateInternal.update { it.copy(sponsoredOnly = !it.sponsoredOnly) }

    fun onCountryChange(value: String?) = uiStateInternal.update { it.copy(country = value) }

    fun onSourceChange(value: String?) = uiStateInternal.update { it.copy(source = value) }

    fun onSortChange(value: JobSort) = uiStateInternal.update { it.copy(sort = value) }

    fun clearFilters() = uiStateInternal.update {
        it.copy(
            query = "",
            savedOnly = false,
            eligibleOnly = false,
            sponsoredOnly = false,
            country = null,
            source = null,
            minMatch = 0,
        )
    }

    fun toggleSaved(jobId: String) = viewModelScope.launch { repository.toggleSaved(jobId) }
}

class JobsViewModelFactory(private val repository: com.aiglobal.jobfinder.data.repository.JobRepository) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = JobsViewModel(repository) as T
}

data class HomeUiState(
    val isLoading: Boolean = true,
    val profile: UserProfile? = null,
    val stats: DashboardStats = DashboardStats(0, 0, 0, 0),
    val bestMatches: List<Job> = emptyList(),
    val newOpportunities: List<Job> = emptyList(),
    val applications: List<ApplicationRecord> = emptyList(),
    val reminders: List<ApplicationReminder> = emptyList(),
) {
    /** Application counts, derived so the dashboard and the pipeline never disagree. */
    val activeApplications: Int get() = applications.count {
        it.status != ApplicationStatus.SAVED && !it.status.isTerminal
    }

    val interviewCount: Int get() = applications.count { it.status == ApplicationStatus.INTERVIEW }

    /** Due or overdue reminders, the ones worth surfacing on the dashboard. */
    val dueReminders: List<ApplicationReminder> get() = reminders.filter { it.due }
}

class HomeViewModel(
    private val repository: com.aiglobal.jobfinder.data.repository.JobRepository,
    private val applicationCenter: com.aiglobal.jobfinder.data.applications.ApplicationCenter,
) : ViewModel() {

    private val _uiState = kotlinx.coroutines.flow.MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState

    init {
        viewModelScope.launch {
            repository.profile.collect { profile -> _uiState.update { it.copy(profile = profile) } }
        }
        viewModelScope.launch {
            repository.stats.collect { stats -> _uiState.update { it.copy(stats = stats) } }
        }
        viewModelScope.launch {
            repository.jobs.collect { jobs ->
                _uiState.update {
                    it.copy(
                        bestMatches = jobs.sortedByDescending { job -> job.matchScore }.take(4),
                        newOpportunities = jobs.filter { job -> job.isNew },
                    )
                }
            }
        }
        viewModelScope.launch {
            applicationCenter.applications.collect { apps ->
                _uiState.update {
                    it.copy(
                        applications = apps,
                        stats = it.stats.copy(
                            applications = apps.count { record -> record.status != ApplicationStatus.SAVED && !record.status.isTerminal },
                            interviews = apps.count { record -> record.status == ApplicationStatus.INTERVIEW },
                        ),
                    )
                }
            }
        }
        viewModelScope.launch {
            applicationCenter.reminders.collect { reminders ->
                _uiState.update { it.copy(reminders = reminders) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repository.refresh()
            _uiState.update { it.copy(isLoading = false) }
        }
    }
}

class HomeViewModelFactory(
    private val repository: com.aiglobal.jobfinder.data.repository.JobRepository,
    private val applicationCenter: com.aiglobal.jobfinder.data.applications.ApplicationCenter,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(repository, applicationCenter) as T
}

data class ApplicationsUiState(
    val isLoading: Boolean = true,
    val applications: List<ApplicationRecord> = emptyList(),
    val reminders: List<ApplicationReminder> = emptyList(),
    val selectedStatus: com.aiglobal.jobfinder.data.model.ApplicationStatus? = null,
) {
    /** Applications that match the active stage filter, newest activity first. */
    val visible: List<ApplicationRecord>
        get() = applications
            .filter { selectedStatus == null || it.status == selectedStatus }
            .sortedByDescending { it.updatedAtEpochMillis }

    val activeCount: Int get() = applications.count {
        it.status == ApplicationStatus.APPLIED || it.status == ApplicationStatus.INTERVIEW
    }

    val interviewCount: Int get() = applications.count { it.status == ApplicationStatus.INTERVIEW }

    val dueReminders: List<ApplicationReminder> get() = reminders.filter { it.due }
}

class ApplicationsViewModel(
    private val repository: com.aiglobal.jobfinder.data.repository.JobRepository,
    private val applicationCenter: com.aiglobal.jobfinder.data.applications.ApplicationCenter,
) : ViewModel() {

    private val _uiState = kotlinx.coroutines.flow.MutableStateFlow(ApplicationsUiState())
    val uiState: StateFlow<ApplicationsUiState> = _uiState

    init {
        viewModelScope.launch {
            applicationCenter.applications.collect { apps ->
                _uiState.update { it.copy(applications = apps) }
            }
        }
        viewModelScope.launch {
            applicationCenter.reminders.collect { reminders ->
                _uiState.update { it.copy(reminders = reminders) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repository.refresh()
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun selectStatus(status: com.aiglobal.jobfinder.data.model.ApplicationStatus?) =
        _uiState.update { it.copy(selectedStatus = status) }

    fun advance(applicationId: String, status: ApplicationStatus) =
        viewModelScope.launch { applicationCenter.setStatus(applicationId, status) }

    fun archive(applicationId: String) = viewModelScope.launch { applicationCenter.archive(applicationId) }
}

class ApplicationsViewModelFactory(
    private val repository: com.aiglobal.jobfinder.data.repository.JobRepository,
    private val applicationCenter: com.aiglobal.jobfinder.data.applications.ApplicationCenter,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ApplicationsViewModel(repository, applicationCenter) as T
}

class ProfileViewModel(
    private val repository: com.aiglobal.jobfinder.data.repository.JobRepository,
) : ViewModel() {

    val profile: StateFlow<UserProfile?> = repository.profile
        .map { it as UserProfile? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setAutoApply(enabled: Boolean) = viewModelScope.launch { repository.setAutoApply(enabled) }
}

class ProfileViewModelFactory(
    private val repository: com.aiglobal.jobfinder.data.repository.JobRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ProfileViewModel(repository) as T
}

private fun <T> kotlinx.coroutines.flow.MutableStateFlow<T>.update(transform: (T) -> T) {
    value = transform(value)
}
