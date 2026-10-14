package com.aiglobal.jobfinder.data.interview

import android.content.Context
import com.aiglobal.jobfinder.data.model.InterviewAttempt
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

@Serializable
data class InterviewStoreData(
    val version: Int = 1,
    val attempts: List<InterviewAttempt> = emptyList(),
)

/**
 * Disk-backed storage for rehearsal attempts, keyed by question id. Persisting them is
 * what lets the readiness score mean something across sessions rather than resetting
 * every time the screen is reopened.
 */
class InterviewPrepStore(
    context: Context,
    private val file: File = File(context.filesDir, "interview_attempts.json"),
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val mutex = Mutex()

    private val _attempts = MutableStateFlow<List<InterviewAttempt>>(emptyList())
    val attempts: StateFlow<List<InterviewAttempt>> = _attempts.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val data = runCatching { json.decodeFromString<InterviewStoreData>(file.readText()) }
            .onFailure { runCatching { file.delete() } }
            .getOrDefault(InterviewStoreData())
        _attempts.value = data.attempts
    }

    suspend fun put(attempt: InterviewAttempt) = mutate { data ->
        data.copy(attempts = data.attempts.filterNot { it.questionId == attempt.questionId } + attempt)
    }

    suspend fun clear() = mutate { it.copy(attempts = emptyList()) }

    private suspend fun mutate(transform: (InterviewStoreData) -> InterviewStoreData) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val updated = transform(InterviewStoreData(attempts = _attempts.value))
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(updated))
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            _attempts.value = updated.attempts
        }
    }
}
