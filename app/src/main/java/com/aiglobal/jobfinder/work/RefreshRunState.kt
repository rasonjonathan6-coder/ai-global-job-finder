package com.aiglobal.jobfinder.work

import android.content.Context
import androidx.compose.runtime.Immutable
import com.aiglobal.jobfinder.data.discovery.RefreshRunOutcome
import com.aiglobal.jobfinder.data.discovery.RefreshRunState
import com.aiglobal.jobfinder.data.jobs.provider.ProviderErrorCategory
import com.aiglobal.jobfinder.data.model.ProviderDiagnostics
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
import org.json.JSONObject
import java.io.File

/** What WorkManager should do with a finished run. */
enum class RefreshDecision {
    /** The run did its job; let the schedule continue. */
    SUCCEED,

    /** Ask WorkManager to run this attempt again later. */
    RETRY,

    /**
     * Stop retrying *this* attempt. For periodic work this is not "stop searching" —
     * WorkManager re-enqueues the next period with a fresh attempt counter — it is "do not
     * hammer the sources again right now".
     */
    GIVE_UP,
}

/** The decision for one finished run, plus the outcome to record (null for a plain retry). */
@Immutable
data class RefreshVerdict(
    val decision: RefreshDecision,
    val outcome: RefreshRunOutcome?,
)

/**
 * Turns a finished run's diagnostics and WorkManager attempt count into a decision and the
 * outcome worth recording, kept pure so the whole rule is testable without WorkManager.
 *
 * The two rules the audit found missing live here: the attempt cap (so a host that stays
 * unreachable cannot be re-queried forever) and the explicit "gave up" outcome (so the
 * silence is explainable rather than invisible).
 */
object RefreshRunClassifier {

    /**
     * @param diagnostics what the finished attempt recorded
     * @param runAttemptCount WorkManager's attempt counter *before* this run
     */
    fun classify(diagnostics: ProviderDiagnostics, runAttemptCount: Int): RefreshVerdict {
        // A read that produced anything usable, or a failure that cannot fix itself, is done.
        if (!RefreshRetryPolicy.shouldRetry(diagnostics)) {
            return RefreshVerdict(RefreshDecision.SUCCEED, RefreshRunOutcome.Succeeded)
        }
        return retryOrGiveUp(runAttemptCount, reasonFor(diagnostics))
    }

    /**
     * The verdict when the run threw instead of returning. A transient throw (I/O, timeout)
     * is retried under the same cap; anything else is a permanent error and fails outright.
     */
    fun forThrownError(runAttemptCount: Int, transient: Boolean): RefreshVerdict =
        if (transient && runAttemptCount + 1 < RefreshRetryPolicy.MAX_ATTEMPTS) {
            RefreshVerdict(RefreshDecision.RETRY, outcome = null)
        } else {
            val reason = if (transient) {
                "The search could not complete."
            } else {
                "The search stopped with an unexpected error."
            }
            RefreshVerdict(RefreshDecision.GIVE_UP, RefreshRunOutcome.GaveUp(reason))
        }

    /** Retries while under the cap; gives up — with [reason] — once it is reached. */
    private fun retryOrGiveUp(runAttemptCount: Int, reason: String): RefreshVerdict =
        // WorkManager's counter is the previous run's count; this run is one more.
        if (runAttemptCount + 1 < RefreshRetryPolicy.MAX_ATTEMPTS) {
            // A retry is not an outcome: the run has not finished, it has been re-queued.
            RefreshVerdict(RefreshDecision.RETRY, outcome = null)
        } else {
            RefreshVerdict(RefreshDecision.GIVE_UP, RefreshRunOutcome.GaveUp(reason))
        }

    /**
     * The plain reason the run gave up, named from what actually happened rather than a
     * generic "failed". A single category when the sources agree, otherwise the count.
     */
    fun reasonFor(diagnostics: ProviderDiagnostics): String {
        if (!diagnostics.networkAvailable) {
            return "No internet connection when the search ran."
        }
        val categories = diagnostics.providers
            .filter { it.status.isFailure }
            .mapNotNull { it.errorCategory }
            .toSet()
        return when {
            categories.size == 1 -> when (categories.first()) {
                ProviderErrorCategory.NETWORK -> "The job sources could not be reached."
                ProviderErrorCategory.TIMEOUT -> "The job sources took too long to answer."
                ProviderErrorCategory.HTTP_ERROR -> "A job source rejected the request."
                else -> "The search did not finish."
            }
            categories.isEmpty() -> "The search did not finish."
            else -> "Several job sources could not be reached."
        }
    }
}

/**
 * Persists the last background-run outcome so the Profile card can report it after a restart,
 * not only in the session that produced it.
 *
 * Storage is a single small JSON file, written atomically. It is deliberately tiny — a few
 * timestamps and a short reason — and a malformed file is discarded rather than allowed to
 * crash the app, so a failed write never wedges the next start.
 */
class RefreshRunStateStore(
    context: Context,
    private val file: File = File(context.filesDir, "refresh_run_state.json"),
) {

    private val mutex = Mutex()

    /**
     * The last outcome as a hot stream: the disk value first (so a restart shows the truth
     * immediately), then every subsequent [record]. A null first emission keeps consumers
     * from mistaking "not read yet" for "no failure".
     */
    private val state: MutableStateFlow<RefreshRunState?> = MutableStateFlow(null)

    fun observe(): Flow<RefreshRunState> = flow {
        if (state.value == null) state.value = read()
        emitAll(state.filterNotNull())
    }.distinctUntilChanged()

    suspend fun read(): RefreshRunState = withContext(Dispatchers.IO) {
        mutex.withLock { readFile() }
    }

    suspend fun record(outcome: RefreshRunOutcome, atEpochMillis: Long): RefreshRunState =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val next = RefreshRunState.after(readFile(), outcome, atEpochMillis)
                writeFile(next)
                state.value = next
                next
            }
        }

    private fun readFile(): RefreshRunState = runCatching {
        if (!file.isFile) return RefreshRunState()
        val root = JSONObject(file.readText())
        RefreshRunState(
            lastRunAtEpochMillis = root.optLongOrNull("lastRunAt"),
            lastSuccessAtEpochMillis = root.optLongOrNull("lastSuccessAt"),
            lastGiveUpAtEpochMillis = root.optLongOrNull("lastGiveUpAt"),
            retryExhausted = root.optBoolean("retryExhausted", false),
            reason = root.optString("reason").takeIf { it.isNotBlank() && !root.isNull("reason") },
        )
    }.onFailure { runCatching { file.delete() } }.getOrDefault(RefreshRunState())

    private fun writeFile(state: RefreshRunState) {
        runCatching {
            file.parentFile?.mkdirs()
            val root = JSONObject().apply {
                put("version", SCHEMA_VERSION)
                put("lastRunAt", state.lastRunAtEpochMillis ?: JSONObject.NULL)
                put("lastSuccessAt", state.lastSuccessAtEpochMillis ?: JSONObject.NULL)
                put("lastGiveUpAt", state.lastGiveUpAtEpochMillis ?: JSONObject.NULL)
                put("retryExhausted", state.retryExhausted)
                put("reason", state.reason ?: JSONObject.NULL)
            }
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key) || !has(key)) null else optLong(key)
