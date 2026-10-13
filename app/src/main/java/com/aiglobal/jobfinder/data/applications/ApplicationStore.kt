package com.aiglobal.jobfinder.data.applications

import android.content.Context
import com.aiglobal.jobfinder.data.model.ApplicationRecord
import com.aiglobal.jobfinder.data.model.CoverLetter
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

/** The persisted document. One file holds both lists so a single atomic write is enough. */
@Serializable
data class ApplicationStoreData(
    val version: Int = SCHEMA_VERSION,
    val applications: List<ApplicationRecord> = emptyList(),
    val coverLetters: List<CoverLetter> = emptyList(),
)

internal const val SCHEMA_VERSION = 1

/**
 * Disk-backed storage for the application pipeline and generated cover letters.
 *
 * Writes are atomic (temp file then rename) and serialised behind a mutex, so a process
 * death mid-write cannot leave a half-written document behind. Nothing is uploaded.
 */
class ApplicationStore(
    context: Context,
    private val file: File = File(context.filesDir, "applications.json"),
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val mutex = Mutex()

    private val _applications = MutableStateFlow<List<ApplicationRecord>>(emptyList())
    val applications: StateFlow<List<ApplicationRecord>> = _applications.asStateFlow()

    private val _coverLetters = MutableStateFlow<List<CoverLetter>>(emptyList())
    val coverLetters: StateFlow<List<CoverLetter>> = _coverLetters.asStateFlow()

    /** True when a document was found on disk during [load]. Used to decide whether to seed. */
    var hadPersistedDocument: Boolean = false
        private set

    /** Reads the document into memory. Safe to call more than once. */
    suspend fun load() = withContext(Dispatchers.IO) {
        hadPersistedDocument = file.isFile
        val data = readDocument()
        _applications.value = data.applications
        _coverLetters.value = data.coverLetters
    }

    suspend fun putApplication(record: ApplicationRecord) = mutate { data ->
        val without = data.applications.filterNot { it.id == record.id }
        data.copy(applications = without + record)
    }

    suspend fun putCoverLetter(letter: CoverLetter) = mutate { data ->
        val without = data.coverLetters.filterNot { it.id == letter.id }
        data.copy(coverLetters = without + letter)
    }

    suspend fun removeApplication(id: String) = mutate { data ->
        data.copy(applications = data.applications.filterNot { it.id == id })
    }

    suspend fun removeCoverLetter(id: String) = mutate { data ->
        data.copy(coverLetters = data.coverLetters.filterNot { it.id == id })
    }

    /** Applies [transform] to the record with [id] and persists the result. */
    suspend fun updateApplication(id: String, transform: (ApplicationRecord) -> ApplicationRecord) =
        mutate { data ->
            data.copy(
                applications = data.applications.map { if (it.id == id) transform(it) else it },
            )
        }

    suspend fun updateCoverLetter(id: String, transform: (CoverLetter) -> CoverLetter) = mutate { data ->
        data.copy(coverLetters = data.coverLetters.map { if (it.id == id) transform(it) else it })
    }

    private suspend fun mutate(transform: (ApplicationStoreData) -> ApplicationStoreData) =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val current = ApplicationStoreData(
                    applications = _applications.value,
                    coverLetters = _coverLetters.value,
                )
                val updated = transform(current)
                writeDocument(updated)
                _applications.value = updated.applications
                _coverLetters.value = updated.coverLetters
            }
        }

    private fun readDocument(): ApplicationStoreData {
        if (!file.isFile) return ApplicationStoreData()
        return runCatching { json.decodeFromString<ApplicationStoreData>(file.readText()) }
            .onFailure { runCatching { file.delete() } }
            .getOrDefault(ApplicationStoreData())
    }

    private fun writeDocument(data: ApplicationStoreData) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(data))
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }
}
