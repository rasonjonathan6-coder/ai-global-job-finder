package com.aiglobal.jobfinder.data.jobs.availability

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The persisted probe-budget window: when the current window started and how many link probes
 * it has already spent.
 */
@Serializable
data class AvailabilityBudgetState(
    val windowStartedAtEpochMillis: Long,
    val probesSpent: Int,
)

/**
 * Persists the availability probe-budget window so the daily cap survives an app restart,
 * exactly as [AvailabilityCacheStore] persists the 24 h verdicts. Without this, a relaunch
 * would reset the window and let a user — or the background worker — exceed the cap across
 * restarts, which is the very behaviour the cap exists to prevent.
 *
 * Design rules, mirroring [AvailabilityCacheStore]:
 *  - **Atomic writes.** The whole state is written to a temp file and renamed, so a crash
 *    mid-write cannot leave a half-written file that would be misread as a spent budget.
 *  - **Storage errors degrade, never crash.** A missing, unreadable or corrupt file reads as
 *    "no persisted state", so the refresh falls back to an in-memory budget rather than failing.
 */
class AvailabilityBudgetStore(private val directory: File) {

    private val file: File get() = File(directory, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Loads the persisted window, or null when there is nothing readable. */
    fun read(): AvailabilityBudgetState? {
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString<AvailabilityBudgetState>(file.readText())
        }.onFailure { runCatching { file.delete() } }
            .getOrNull()
    }

    /** Writes [state] atomically. A write failure is swallowed: the budget still works in memory. */
    fun write(state: AvailabilityBudgetState) {
        runCatching {
            directory.mkdirs()
            val temp = File(directory, "$FILE_NAME.tmp")
            temp.writeText(json.encodeToString(state))
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }
    }

    companion object {
        private const val FILE_NAME = "availability_budget.json"
    }
}
