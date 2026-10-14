package com.aiglobal.jobfinder.data.alerts

import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobAlert
import com.aiglobal.jobfinder.data.model.JobAlertRule
import com.aiglobal.jobfinder.data.model.JobAlertsState
import com.aiglobal.jobfinder.data.repository.JobRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Automatic job alerts: saved searches evaluated against the live feed.
 *
 * A rule is stored; the alerts it produces are recomputed every time the feed changes or
 * the user refreshes, so a new posting is surfaced without any background service and a
 * deleted rule never leaves an orphan alert behind.
 */
class JobAlertsCenter(
    private val repository: JobRepository,
    private val store: JobAlertStore,
    private val engine: JobAlertEngine = JobAlertEngine(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isRefreshing = MutableStateFlow(false)

    val state: StateFlow<JobAlertsState> =
        combine(store.rules, store.alerts, _isRefreshing) { rules, alerts, refreshing ->
            JobAlertsState(rules = rules, alerts = alerts.sortedByDescending { it.detectedAtEpochMillis }, isRefreshing = refreshing)
        }.stateIn(scope, SharingStarted.Eagerly, JobAlertsState())

    init {
        scope.launch {
            store.load()
            if (!store.hadPersistedDocument) seedDefaultRules()
            refresh()
        }
        // Any feed change re-evaluates the rules, so alerts track the live data.
        scope.launch {
            repository.jobs.collect { jobs -> evaluate(jobs) }
        }
    }

    suspend fun refresh() {
        _isRefreshing.value = true
        try {
            repository.refresh()
            evaluate(currentJobs())
        } finally {
            _isRefreshing.value = false
        }
    }

    suspend fun addRule(rule: JobAlertRule) {
        store.putRule(rule)
        evaluate(currentJobs())
    }

    suspend fun removeRule(id: String) = store.removeRule(id)

    suspend fun markRead(jobId: String) = store.markRead(jobId)

    suspend fun clearAll() = store.clearAll()

    fun newRuleId(): String = idFactory()

    private suspend fun evaluate(jobs: List<Job>) {
        val rules = store.rules.value.filter { it.enabled }
        val alertIds = rules.map { it.id }.toSet()
        if (alertIds.isEmpty()) return
        val alerts = engine.alertsFor(rules, jobs, clock())
        // Preserve the read flag so refreshing never marks an opened alert as unread again.
        val previouslyRead = store.alerts.value.filter { it.read }.map { it.jobId }.toSet()
        store.replaceAlerts(alertIds, alerts.map { if (it.jobId in previouslyRead) it.copy(read = true) else it })
    }

    private suspend fun currentJobs(): List<Job> = repository.jobs.first()

    private suspend fun seedDefaultRules() {
        val now = clock()
        store.putRule(
            JobAlertRule(
                id = idFactory(),
                name = "Visa-sponsored roles",
                scope = com.aiglobal.jobfinder.data.model.AlertScope.VISA_FRIENDLY,
                minMatchScore = 60,
                frequency = com.aiglobal.jobfinder.data.model.AlertFrequency.DAILY,
                createdAtEpochMillis = now,
            ),
        )
        store.putRule(
            JobAlertRule(
                id = idFactory(),
                name = "Strong matches anywhere",
                scope = com.aiglobal.jobfinder.data.model.AlertScope.STRONG_ONLY,
                minMatchScore = 75,
                frequency = com.aiglobal.jobfinder.data.model.AlertFrequency.DAILY,
                createdAtEpochMillis = now,
            ),
        )
    }
}
