package com.aiglobal.jobfinder.data.jobs.availability

import com.aiglobal.jobfinder.data.model.AvailabilityResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists availability verdicts so the 24-hour window survives an app restart. Without this
 * the cache would only limit requests within one session, and a relaunch would re-probe every
 * link — exactly the behaviour that wears out a source's goodwill.
 *
 * It stores only settled verdicts (the cache never holds an unconfirmed one) and writes the
 * whole map atomically, so a crash mid-write cannot corrupt the file.
 */
class AvailabilityCacheStore(private val directory: File) {

    private val file: File get() = File(directory, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Loads the persisted verdicts, or an empty map when there is nothing readable. */
    fun read(): Map<String, AvailabilityResult> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, AvailabilityResult>>(file.readText())
        }.onFailure { runCatching { file.delete() } }
            .getOrDefault(emptyMap())
    }

    /** Writes [entries] atomically. A write failure is swallowed: the cache still works in memory. */
    fun write(entries: Map<String, AvailabilityResult>) {
        runCatching {
            directory.mkdirs()
            val temp = File(directory, "$FILE_NAME.tmp")
            temp.writeText(json.encodeToString<Map<String, AvailabilityResult>>(entries))
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }
    }

    companion object {
        private const val FILE_NAME = "availability_cache.json"
    }
}
