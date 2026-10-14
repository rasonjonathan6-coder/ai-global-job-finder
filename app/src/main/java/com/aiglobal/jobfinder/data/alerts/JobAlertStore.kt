package com.aiglobal.jobfinder.data.alerts

import android.content.Context
import com.aiglobal.jobfinder.data.model.JobAlert
import com.aiglobal.jobfinder.data.model.JobAlertRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** The persisted document for saved searches and the alerts they produced. */
@Serializable
data class JobAlertStoreData(
    val version: Int = 1,
    val rules: List<JobAlertRule> = emptyList(),
    val alerts: List<JobAlert> = emptyList(),
)

/**
 * Disk-backed storage for saved searches and detected alerts. Writes are atomic and
 * serialised behind a mutex, matching [com.aiglobal.jobfinder.data.applications.ApplicationStore].
 */
class JobAlertStore(
    context: Context,
    private val file: File = File(context.filesDir, "job_alerts.json"),
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val mutex = Mutex()

    private val _rules = MutableStateFlow<List<JobAlertRule>>(emptyList())
    val rules: StateFlow<List<JobAlertRule>> = _rules.asStateFlow()

    private val _alerts = MutableStateFlow<List<JobAlert>>(emptyList())
    val alerts: StateFlow<List<JobAlert>> = _alerts.asStateFlow()

    var hadPersistedDocument: Boolean = false
        private set

    suspend fun load() = withContext(Dispatchers.IO) {
        hadPersistedDocument = file.isFile
        val data = readDocument()
        _rules.value = data.rules
        _alerts.value = data.alerts
    }

    suspend fun putRule(rule: JobAlertRule) = mutate { data ->
        data.copy(rules = data.rules.filterNot { it.id == rule.id } + rule)
    }

    suspend fun removeRule(id: String) = mutate { data ->
        data.copy(rules = data.rules.filterNot { it.id == id }, alerts = data.alerts.filterNot { it.ruleId == id })
    }

    /** Replaces the alert list for [ruleIds], keeping alerts from other rules untouched. */
    suspend fun replaceAlerts(ruleIds: Set<String>, alerts: List<JobAlert>) = mutate { data ->
        data.copy(alerts = data.alerts.filterNot { it.ruleId in ruleIds } + alerts)
    }

    suspend fun markRead(jobId: String) = mutate { data ->
        data.copy(alerts = data.alerts.map { if (it.jobId == jobId) it.copy(read = true) else it })
    }

    suspend fun clearAll() = mutate { data -> data.copy(alerts = emptyList()) }

    private suspend fun mutate(transform: (JobAlertStoreData) -> JobAlertStoreData) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val updated = transform(JobAlertStoreData(rules = _rules.value, alerts = _alerts.value))
            writeDocument(updated)
            _rules.value = updated.rules
            _alerts.value = updated.alerts
        }
    }

    private fun readDocument(): JobAlertStoreData {
        if (!file.isFile) return JobAlertStoreData()
        return runCatching { json.decodeFromString<JobAlertStoreData>(file.readText()) }
            .onFailure { runCatching { file.delete() } }
            .getOrDefault(JobAlertStoreData())
    }

    private fun writeDocument(data: JobAlertStoreData) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(data))
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }
}
