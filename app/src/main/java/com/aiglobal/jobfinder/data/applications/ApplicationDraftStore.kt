package com.aiglobal.jobfinder.data.applications

import android.content.Context
import com.aiglobal.jobfinder.data.model.ApplicationDraft
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

/** The persisted document. A version field so a future schema change can migrate cleanly. */
@Serializable
data class ApplicationDraftStoreData(
    val version: Int = DRAFT_SCHEMA_VERSION,
    val drafts: List<ApplicationDraft> = emptyList(),
)

internal const val DRAFT_SCHEMA_VERSION = 1

/**
 * Disk-backed storage for prepared application drafts and their approval history.
 *
 * Follows the same discipline as [ApplicationStore]: writes are atomic (temp file then
 * rename) and serialised behind a mutex, so a process death mid-write cannot leave a
 * half-written document behind. Nothing is uploaded.
 */
class ApplicationDraftStore(
    context: Context,
    private val file: File = File(context.filesDir, "application_drafts.json"),
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val mutex = Mutex()

    private val _drafts = MutableStateFlow<List<ApplicationDraft>>(emptyList())
    val drafts: StateFlow<List<ApplicationDraft>> = _drafts.asStateFlow()

    /** True when a document was found on disk during [load]. */
    var hadPersistedDocument: Boolean = false
        private set

    /** Reads the document into memory. Safe to call more than once. */
    suspend fun load() = withContext(Dispatchers.IO) {
        hadPersistedDocument = file.isFile
        _drafts.value = readDocument().drafts
    }

    suspend fun putDraft(draft: ApplicationDraft) = mutate { current ->
        current.filterNot { it.id == draft.id } + draft
    }

    /** Applies [transform] to the draft with [id] and persists the result. */
    suspend fun updateDraft(id: String, transform: (ApplicationDraft) -> ApplicationDraft) =
        mutate { current ->
            current.map { if (it.id == id) transform(it) else it }
        }

    suspend fun removeDraft(id: String) = mutate { current -> current.filterNot { it.id == id } }

    private suspend fun mutate(transform: (List<ApplicationDraft>) -> List<ApplicationDraft>) =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val updated = transform(_drafts.value)
                writeDocument(ApplicationDraftStoreData(drafts = updated))
                _drafts.value = updated
            }
        }

    private fun readDocument(): ApplicationDraftStoreData {
        if (!file.isFile) return ApplicationDraftStoreData()
        return runCatching { json.decodeFromString<ApplicationDraftStoreData>(file.readText()) }
            .onFailure { runCatching { file.delete() } }
            .getOrDefault(ApplicationDraftStoreData())
    }

    private fun writeDocument(data: ApplicationDraftStoreData) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(data))
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }
}
