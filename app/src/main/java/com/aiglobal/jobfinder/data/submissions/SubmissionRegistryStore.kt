package com.aiglobal.jobfinder.data.submissions

import android.content.Context
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

/** The persisted document: the sending policy plus one entry per opening applied to. */
@Serializable
data class SubmissionRegistryData(
    val version: Int = SUBMISSION_SCHEMA_VERSION,
    val sendingEnabled: Boolean = false,
    val entries: List<SubmissionEntry> = emptyList(),
)

internal const val SUBMISSION_SCHEMA_VERSION = 1

/**
 * Disk-backed storage for the application registry.
 *
 * Writes are atomic (temp file then rename) and serialised behind a mutex, the same
 * pattern the other stores use, so a process death mid-write cannot leave a half-written
 * document behind and the registry cannot be corrupted while a send is being recorded.
 * Nothing here is uploaded anywhere.
 */
class SubmissionRegistryStore(
    context: Context,
    private val file: File = File(context.filesDir, "submissions.json"),
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val mutex = Mutex()

    private val _sendingEnabled = MutableStateFlow(false)
    val sendingEnabled: StateFlow<Boolean> = _sendingEnabled.asStateFlow()

    private val _entries = MutableStateFlow<List<SubmissionEntry>>(emptyList())
    val entries: StateFlow<List<SubmissionEntry>> = _entries.asStateFlow()

    /** True when a document was found on disk during [load]. */
    var hadPersistedDocument: Boolean = false
        private set

    /** Reads the document into memory. Safe to call more than once. */
    suspend fun load() = withContext(Dispatchers.IO) {
        hadPersistedDocument = file.isFile
        val data = readDocument()
        _sendingEnabled.value = data.sendingEnabled
        _entries.value = data.entries
    }

    suspend fun setSendingEnabled(enabled: Boolean) = mutate { it.copy(sendingEnabled = enabled) }

    /**
     * Inserts or replaces the entry for [entry.key]. The registry guarantees one entry per
     * opening, so this is a replace-by-key rather than an append.
     */
    suspend fun putEntry(entry: SubmissionEntry) = mutate { data ->
        val without = data.entries.filterNot { it.key == entry.key }
        data.copy(entries = without + entry)
    }

    suspend fun removeEntry(key: String) = mutate { data ->
        data.copy(entries = data.entries.filterNot { it.key == key })
    }

    private suspend fun mutate(transform: (SubmissionRegistryData) -> SubmissionRegistryData) =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val current = SubmissionRegistryData(
                    sendingEnabled = _sendingEnabled.value,
                    entries = _entries.value,
                )
                val updated = transform(current)
                writeDocument(updated)
                _sendingEnabled.value = updated.sendingEnabled
                _entries.value = updated.entries
            }
        }

    private fun readDocument(): SubmissionRegistryData {
        if (!file.isFile) return SubmissionRegistryData()
        return runCatching { json.decodeFromString<SubmissionRegistryData>(file.readText()) }
            .onFailure { runCatching { file.delete() } }
            .getOrDefault(SubmissionRegistryData())
    }

    private fun writeDocument(data: SubmissionRegistryData) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(data))
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }
}
