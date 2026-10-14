package com.aiglobal.jobfinder.data.autoapply

import android.content.Context
import com.aiglobal.jobfinder.data.model.AutoApplyItem
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

/** The persisted document for the auto-apply queue. */
@Serializable
data class AutoApplyStoreData(
    val version: Int = 1,
    val enabled: Boolean = false,
    val threshold: Int = AutoApplyEngine.DEFAULT_THRESHOLD,
    val items: List<AutoApplyItem> = emptyList(),
)

/**
 * Disk-backed storage for the auto-apply queue and the user's on/off preference.
 * Atomic writes behind a mutex, same pattern as the other Stage 5 stores.
 */
class AutoApplyStore(
    context: Context,
    private val file: File = File(context.filesDir, "auto_apply.json"),
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val mutex = Mutex()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _threshold = MutableStateFlow(AutoApplyEngine.DEFAULT_THRESHOLD)
    val threshold: StateFlow<Int> = _threshold.asStateFlow()

    private val _items = MutableStateFlow<List<AutoApplyItem>>(emptyList())
    val items: StateFlow<List<AutoApplyItem>> = _items.asStateFlow()

    var hadPersistedDocument: Boolean = false
        private set

    suspend fun load() = withContext(Dispatchers.IO) {
        hadPersistedDocument = file.isFile
        val data = readDocument()
        _enabled.value = data.enabled
        _threshold.value = data.threshold
        _items.value = data.items
    }

    suspend fun setEnabled(enabled: Boolean) = mutate { it.copy(enabled = enabled) }

    suspend fun setThreshold(value: Int) = mutate { it.copy(threshold = value.coerceIn(40, 95)) }

    suspend fun replaceItems(items: List<AutoApplyItem>) = mutate { it.copy(items = items) }

    suspend fun updateItem(jobId: String, transform: (AutoApplyItem) -> AutoApplyItem) = mutate { data ->
        data.copy(items = data.items.map { if (it.jobId == jobId) transform(it) else it })
    }

    suspend fun removeItem(jobId: String) = mutate { data ->
        data.copy(items = data.items.filterNot { it.jobId == jobId })
    }

    private suspend fun mutate(transform: (AutoApplyStoreData) -> AutoApplyStoreData) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val updated = transform(
                AutoApplyStoreData(
                    enabled = _enabled.value,
                    threshold = _threshold.value,
                    items = _items.value,
                ),
            )
            writeDocument(updated)
            _enabled.value = updated.enabled
            _threshold.value = updated.threshold
            _items.value = updated.items
        }
    }

    private fun readDocument(): AutoApplyStoreData {
        if (!file.isFile) return AutoApplyStoreData()
        return runCatching { json.decodeFromString<AutoApplyStoreData>(file.readText()) }
            .onFailure { runCatching { file.delete() } }
            .getOrDefault(AutoApplyStoreData())
    }

    private fun writeDocument(data: AutoApplyStoreData) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(data))
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }
}
