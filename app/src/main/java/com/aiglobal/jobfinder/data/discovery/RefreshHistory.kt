package com.aiglobal.jobfinder.data.discovery

import android.content.Context
import androidx.compose.runtime.Immutable
import com.aiglobal.jobfinder.data.jobs.provider.ProviderErrorCategory
import com.aiglobal.jobfinder.data.model.ProviderDiagnostics
import com.aiglobal.jobfinder.data.model.ProviderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What asked for a refresh. Kept to the two real causes so the history cannot invent a third. */
enum class RefreshTrigger(val label: String) {
    MANUAL("Manual"),
    SCHEDULED("Scheduled"),
}

/**
 * How one refresh run ended, as observed — never as hoped.
 *
 * [NOT_ATTEMPTED] is deliberately distinct from [FAILED]: when a tap arrives while another
 * search is already running, nothing was tried, so recording it as a failure would be a lie.
 */
enum class RefreshOutcome(val label: String) {
    SUCCEEDED("Succeeded"),
    PARTIAL("Partly succeeded"),
    FAILED("Failed"),
    RATE_LIMITED("Rate limited"),
    OFFLINE("No connection"),
    NOT_ATTEMPTED("Already running"),
}

/** One source's recorded state at the end of a run. Only a real attempt produces an entry. */
@Immutable
data class RefreshProviderResult(
    val providerId: String,
    val name: String,
    val status: ProviderStatus,
) {
    val succeeded: Boolean get() = status.isSuccess
    val failed: Boolean get() = status.isFailure
}

/**
 * One completed refresh run: when it started and ended, what triggered it, how it ended, the
 * per-source outcome, and the incremental counts *only when they were genuinely measured*.
 *
 * The counts are nullable on purpose. A run that was never attempted, or one that never got a
 * usable read, has no honest "new postings" figure to show, so it stores none rather than a
 * misleading zero.
 */
@Immutable
data class RefreshRunRecord(
    /** Stable identity for list keys; the start time is unique enough for a personal app. */
    val id: Long,
    val trigger: RefreshTrigger,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val outcome: RefreshOutcome,
    /** A short, plain-language reason for a non-success outcome, or null when it succeeded. */
    val reason: String? = null,
    val providers: List<RefreshProviderResult> = emptyList(),
    /** Postings first seen by this run, or null when the read did not measure it. */
    val newCount: Int? = null,
    /** Known postings whose content changed in this run, or null when not measured. */
    val updatedCount: Int? = null,
    /** Records the source de-duplication dropped, or null when not measured. */
    val duplicateCount: Int? = null,
) {
    /** True when the run produced a usable read, wholly or partly. */
    val succeeded: Boolean
        get() = outcome == RefreshOutcome.SUCCEEDED || outcome == RefreshOutcome.PARTIAL

    val durationMillis: Long get() = (endedAtEpochMillis - startedAtEpochMillis).coerceAtLeast(0)

    val successCount: Int get() = providers.count { it.succeeded }
    val failureCount: Int get() = providers.count { it.failed }
}

/**
 * The persisted run history, newest first, capped so it cannot grow without bound. Kept small
 * and free of anything personal: it holds timings, triggers, outcomes and provider *names* —
 * never CV content, credentials or search terms.
 */
@Immutable
data class RefreshHistory(
    val runs: List<RefreshRunRecord> = emptyList(),
) {
    /** The most recent run of any kind, or null when nothing has run yet. */
    val lastAttempt: RefreshRunRecord? get() = runs.firstOrNull()

    /**
     * The most recent run that actually produced a usable read. Deliberately distinct from
     * [lastAttempt]: a run can be attempted and fail, and the last *success* must still be
     * reportable rather than being overwritten by the failure.
     */
    val lastSuccess: RefreshRunRecord? get() = runs.firstOrNull { it.succeeded }

    fun prepend(record: RefreshRunRecord): RefreshHistory =
        copy(runs = (listOf(record) + runs).take(MAX_RUNS))

    companion object {
        /** Roughly a couple of weeks of twice-daily runs; enough to see a pattern. */
        const val MAX_RUNS = 20
    }
}

/**
 * Turns a finished run into the honest outcome, reason and measured counts a [RefreshRunRecord]
 * carries. Pure, so every rule is testable without a repository or WorkManager.
 */
object RefreshOutcomeClassifier {

    /** The outcome plus the fields the record should carry for it. */
    @Immutable
    data class Classification(
        val outcome: RefreshOutcome,
        val reason: String? = null,
        val newCount: Int? = null,
        val updatedCount: Int? = null,
        val duplicateCount: Int? = null,
    )

    /**
     * @param attempted false when the call was refused because a search was already running
     * @param diagnostics what the repository recorded for the attempt
     * @param delta the incremental read as the repository reports it now
     */
    fun classify(
        attempted: Boolean,
        diagnostics: ProviderDiagnostics,
        delta: DiscoveryDelta,
    ): Classification {
        if (!attempted) {
            return Classification(
                outcome = RefreshOutcome.NOT_ATTEMPTED,
                reason = "A search was already running, so this one was not started.",
            )
        }
        if (!diagnostics.networkAvailable) {
            return Classification(
                outcome = RefreshOutcome.OFFLINE,
                reason = "No internet connection when the search ran.",
            )
        }

        val successes = diagnostics.providers.count { it.status.isSuccess }
        val failures = diagnostics.providers.filter { it.status.isFailure }
        val rateLimited = failures.any { it.errorCategory == ProviderErrorCategory.RATE_LIMITED }
        // Counts are only real when the read produced something: a failed read leaves the
        // repository's delta untouched, so attributing its numbers to this run would be wrong.
        val measured = successes > 0
        val newCount = if (measured) delta.newJobIds.size else null
        val updatedCount = if (measured) delta.updatedJobIds.size else null
        val duplicateCount = if (measured) diagnostics.totalDuplicatesRemoved else null

        return when {
            failures.isEmpty() && successes == 0 -> Classification(
                outcome = RefreshOutcome.FAILED,
                reason = "The search could not reach any job source.",
                duplicateCount = duplicateCount,
            )
            rateLimited && successes == 0 -> Classification(
                outcome = RefreshOutcome.RATE_LIMITED,
                reason = "A job source is temporarily refusing requests. The app will wait before trying again.",
                duplicateCount = duplicateCount,
            )
            failures.isEmpty() -> Classification(
                outcome = RefreshOutcome.SUCCEEDED,
                newCount = newCount,
                updatedCount = updatedCount,
                duplicateCount = duplicateCount,
            )
            successes > 0 -> Classification(
                outcome = RefreshOutcome.PARTIAL,
                reason = if (rateLimited) {
                    "${failures.size} of ${successes + failures.size} sources could not be reached, " +
                        "and one is temporarily refusing requests."
                } else {
                    "${failures.size} of ${successes + failures.size} sources could not be reached."
                },
                newCount = newCount,
                updatedCount = updatedCount,
                duplicateCount = duplicateCount,
            )
            else -> Classification(
                outcome = RefreshOutcome.FAILED,
                reason = reasonFor(failures.mapNotNull { it.errorCategory }.toSet()),
                duplicateCount = duplicateCount,
            )
        }
    }

    /** A plain reason for a total failure, named from the categories that actually occurred. */
    private fun reasonFor(categories: Set<ProviderErrorCategory>): String = when {
        categories == setOf(ProviderErrorCategory.NETWORK) -> "The job sources could not be reached."
        categories == setOf(ProviderErrorCategory.TIMEOUT) -> "The job sources took too long to answer."
        categories == setOf(ProviderErrorCategory.AUTHENTICATION) ->
            "A job source rejected the saved credentials."
        categories.isEmpty() -> "The search did not finish."
        else -> "The job sources could not be reached."
    }
}

/**
 * Persists the run history as one small JSON file, written atomically and capped. A malformed
 * file is discarded rather than allowed to crash the app, and nothing personal is ever written
 * — only timings, triggers, outcomes and provider names.
 */
class RefreshHistoryStore(
    context: Context,
    private val file: File = File(context.filesDir, "refresh_history.json"),
) {

    private val mutex = Mutex()

    private val state: MutableStateFlow<RefreshHistory?> = MutableStateFlow(null)

    fun observe(): Flow<RefreshHistory> = flow {
        if (state.value == null) state.value = read()
        emitAll(state.filterNotNull())
    }.distinctUntilChanged()

    suspend fun read(): RefreshHistory = withContext(Dispatchers.IO) {
        mutex.withLock { readFile() }
    }

    /** Adds one finished run to the front of the history and returns the new history. */
    suspend fun record(record: RefreshRunRecord): RefreshHistory = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = readFile().prepend(record)
            writeFile(next)
            state.value = next
            next
        }
    }

    private fun readFile(): RefreshHistory = runCatching {
        if (!file.isFile) return RefreshHistory()
        val root = JSONObject(file.readText())
        val runs = root.optJSONArray("runs") ?: JSONArray()
        RefreshHistory(
            runs = (0 until runs.length()).mapNotNull { index ->
                runs.optJSONObject(index)?.toRecord()
            },
        )
    }.onFailure { runCatching { file.delete() } }.getOrDefault(RefreshHistory())

    private fun writeFile(history: RefreshHistory) {
        runCatching {
            file.parentFile?.mkdirs()
            val runs = JSONArray()
            history.runs.forEach { runs.put(it.toJson()) }
            val root = JSONObject().apply {
                put("version", SCHEMA_VERSION)
                put("runs", runs)
            }
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }
    }

    private fun RefreshRunRecord.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("trigger", trigger.name)
        put("startedAt", startedAtEpochMillis)
        put("endedAt", endedAtEpochMillis)
        put("outcome", outcome.name)
        put("reason", reason ?: JSONObject.NULL)
        put("new", newCount ?: JSONObject.NULL)
        put("updated", updatedCount ?: JSONObject.NULL)
        put("duplicates", duplicateCount ?: JSONObject.NULL)
        val array = JSONArray()
        providers.forEach { provider ->
            array.put(
                JSONObject().apply {
                    put("id", provider.providerId)
                    put("name", provider.name)
                    put("status", provider.status.name)
                },
            )
        }
        put("providers", array)
    }

    private fun JSONObject.toRecord(): RefreshRunRecord? {
        val id = optLongOrNull("id") ?: return null
        val startedAt = optLongOrNull("startedAt") ?: return null
        val endedAt = optLongOrNull("endedAt") ?: startedAt
        val trigger = enumOrNull<RefreshTrigger>(optString("trigger")) ?: return null
        val outcome = enumOrNull<RefreshOutcome>(optString("outcome")) ?: return null
        val providers = optJSONArray("providers")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val providerId = entry.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val status = enumOrNull<ProviderStatus>(entry.optString("status"))
                    ?: ProviderStatus.IDLE
                RefreshProviderResult(
                    providerId = providerId,
                    name = entry.optString("name").ifBlank { providerId },
                    status = status,
                )
            }
        } ?: emptyList()
        return RefreshRunRecord(
            id = id,
            trigger = trigger,
            startedAtEpochMillis = startedAt,
            endedAtEpochMillis = endedAt,
            outcome = outcome,
            reason = optString("reason").takeIf { it.isNotBlank() && !isNull("reason") },
            providers = providers,
            newCount = optIntOrNull("new"),
            updatedCount = optIntOrNull("updated"),
            duplicateCount = optIntOrNull("duplicates"),
        )
    }

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}

private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
    if (name.isBlank()) null else enumValues<T>().firstOrNull { it.name == name }

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key) || !has(key)) null else optLong(key)

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key) || !has(key)) null else optInt(key)
