package com.aiglobal.jobfinder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aiglobal.jobfinder.data.discovery.DiscoveryDelta
import com.aiglobal.jobfinder.data.discovery.DiscoverySummary
import com.aiglobal.jobfinder.data.discovery.RefreshRunOutcome
import com.aiglobal.jobfinder.data.discovery.RefreshRunState
import com.aiglobal.jobfinder.data.jobs.provider.ProviderErrorCategory
import com.aiglobal.jobfinder.data.model.ProviderDiagnostic
import com.aiglobal.jobfinder.data.model.ProviderDiagnostics
import com.aiglobal.jobfinder.data.model.ProviderStatus
import com.aiglobal.jobfinder.work.RefreshDecision
import com.aiglobal.jobfinder.work.RefreshRetryPolicy
import com.aiglobal.jobfinder.work.RefreshRunClassifier
import com.aiglobal.jobfinder.work.RefreshRunStateStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Stage 55 — the background search has to stop, and it has to say when it stopped.
 *
 * The audit of the scheduled refresh found two honesty gaps:
 *  - WorkManager reschedules a retry without resetting its attempt counter, so an unreachable
 *    host could be re-queried forever. The attempt cap is what makes the retry finite.
 *  - When the background run did give up, nothing recorded it: the Profile card kept showing
 *    the last good feed as if automatic discovery were working. The run state is what makes
 *    the silence explainable.
 *
 * These tests pin the cap, the "gave up" outcome, its persistence, and the one line of copy
 * that surfaces it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage55BackgroundTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun tempFile(tag: String): File =
        File(context.cacheDir, "run-state-$tag-${System.nanoTime()}.json").also { it.delete() }

    private fun diag(
        id: String,
        status: ProviderStatus,
        category: ProviderErrorCategory? = null,
    ) = ProviderDiagnostic(
        providerId = id,
        name = id.replaceFirstChar { it.uppercase() },
        homepage = "https://example.com",
        documentationUrl = "https://example.com/docs",
        status = status,
        errorCategory = category,
    )

    private fun allTransientFailure() = ProviderDiagnostics(
        providers = listOf(diag("adzuna", ProviderStatus.TIMEOUT, ProviderErrorCategory.TIMEOUT)),
    )

    // ------------------------------------------------------------- attempt cap

    @Test
    fun aUsableReadSucceedsOnTheFirstTry() {
        val verdict = RefreshRunClassifier.classify(
            ProviderDiagnostics(providers = listOf(diag("adzuna", ProviderStatus.SUCCESS_WITH_RESULTS))),
            runAttemptCount = 0,
        )
        assertEquals(RefreshDecision.SUCCEED, verdict.decision)
        assertEquals(RefreshRunOutcome.Succeeded, verdict.outcome)
    }

    @Test
    fun aPermanentFailureSucceedsImmediatelyWithoutRetrying() {
        // A rejected credential cannot fix itself, so there is nothing to retry — the run is
        // simply done, and it is not a "gave up" that the user needs to hear about.
        val verdict = RefreshRunClassifier.classify(
            ProviderDiagnostics(
                providers = listOf(
                    diag("adzuna", ProviderStatus.AUTHENTICATION_FAILED, ProviderErrorCategory.AUTHENTICATION),
                ),
            ),
            runAttemptCount = 0,
        )
        assertEquals(RefreshDecision.SUCCEED, verdict.decision)
        assertEquals(RefreshRunOutcome.Succeeded, verdict.outcome)
    }

    @Test
    fun aTransientFailureRetriesWhileUnderTheCap() {
        // First run (counter 0) → attempt 1 of 3 → retry, with no outcome recorded yet.
        val first = RefreshRunClassifier.classify(allTransientFailure(), runAttemptCount = 0)
        assertEquals(RefreshDecision.RETRY, first.decision)
        assertNull("a re-queued run has not finished, so it has no outcome", first.outcome)

        // Second run (counter 1) → attempt 2 of 3 → still retrying.
        val second = RefreshRunClassifier.classify(allTransientFailure(), runAttemptCount = 1)
        assertEquals(RefreshDecision.RETRY, second.decision)
        assertNull(second.outcome)
    }

    @Test
    fun aTransientFailureStopsAtTheCapInsteadOfRetryingForever() {
        // Third run (counter 2) → attempt 3 of 3 → the cap: stop, and record that we stopped.
        val verdict = RefreshRunClassifier.classify(allTransientFailure(), runAttemptCount = 2)
        assertEquals(RefreshDecision.GIVE_UP, verdict.decision)
        assertTrue(verdict.outcome is RefreshRunOutcome.GaveUp)
    }

    @Test
    fun theCapIsReachedAtTheDeclaredMaximum() {
        assertEquals(3, RefreshRetryPolicy.MAX_ATTEMPTS)
        // At every count below the cap the run is asked to retry; at the cap it gives up.
        for (count in 0 until RefreshRetryPolicy.MAX_ATTEMPTS - 1) {
            assertEquals(
                "count $count must retry",
                RefreshDecision.RETRY,
                RefreshRunClassifier.classify(allTransientFailure(), count).decision,
            )
        }
        assertEquals(
            RefreshDecision.GIVE_UP,
            RefreshRunClassifier.classify(allTransientFailure(), RefreshRetryPolicy.MAX_ATTEMPTS - 1).decision,
        )
    }

    // -------------------------------------------------- thrown-error handling

    @Test
    fun aTransientThrowRetriesUntilTheCapThenGivesUp() {
        assertEquals(RefreshDecision.RETRY, RefreshRunClassifier.forThrownError(0, transient = true).decision)
        assertEquals(RefreshDecision.RETRY, RefreshRunClassifier.forThrownError(1, transient = true).decision)
        val last = RefreshRunClassifier.forThrownError(2, transient = true)
        assertEquals(RefreshDecision.GIVE_UP, last.decision)
        assertTrue(last.outcome is RefreshRunOutcome.GaveUp)
    }

    @Test
    fun anUnexpectedThrowNeverRetries() {
        val verdict = RefreshRunClassifier.forThrownError(0, transient = false)
        assertEquals(RefreshDecision.GIVE_UP, verdict.decision)
        assertTrue(verdict.outcome is RefreshRunOutcome.GaveUp)
    }

    // ---------------------------------------------------- honest give-up reason

    @Test
    fun theReasonNamesWhatActuallyHappened() {
        assertEquals(
            "No internet connection when the search ran.",
            RefreshRunClassifier.reasonFor(ProviderDiagnostics(networkAvailable = false)),
        )
        assertEquals(
            "The job sources could not be reached.",
            RefreshRunClassifier.reasonFor(
                ProviderDiagnostics(providers = listOf(diag("a", ProviderStatus.NETWORK_ERROR, ProviderErrorCategory.NETWORK))),
            ),
        )
        assertEquals(
            "The job sources took too long to answer.",
            RefreshRunClassifier.reasonFor(
                ProviderDiagnostics(providers = listOf(diag("a", ProviderStatus.TIMEOUT, ProviderErrorCategory.TIMEOUT))),
            ),
        )
        assertEquals(
            "Several job sources could not be reached.",
            RefreshRunClassifier.reasonFor(
                ProviderDiagnostics(
                    providers = listOf(
                        diag("a", ProviderStatus.NETWORK_ERROR, ProviderErrorCategory.NETWORK),
                        diag("b", ProviderStatus.TIMEOUT, ProviderErrorCategory.TIMEOUT),
                    ),
                ),
            ),
        )
    }

    // ------------------------------------------------------- run state folding

    @Test
    fun aSuccessClearsAnEarlierGiveUp() {
        val gaveUp = RefreshRunState.after(
            RefreshRunState(),
            RefreshRunOutcome.GaveUp("The job sources could not be reached."),
            atEpochMillis = 1_000,
        )
        assertTrue(gaveUp.retryExhausted)
        assertEquals("The job sources could not be reached.", gaveUp.reason)

        val recovered = RefreshRunState.after(gaveUp, RefreshRunOutcome.Succeeded, atEpochMillis = 2_000)
        assertFalse("a later success must clear the stale warning", recovered.retryExhausted)
        assertNull(recovered.reason)
        assertEquals(2_000L, recovered.lastSuccessAtEpochMillis)
    }

    @Test
    fun aGiveUpKeepsTheLastSuccessTimestamp() {
        val previous = RefreshRunState(lastSuccessAtEpochMillis = 500)
        val next = RefreshRunState.after(
            previous,
            RefreshRunOutcome.GaveUp("No internet connection when the search ran."),
            atEpochMillis = 9_000,
        )
        assertEquals(500L, next.lastSuccessAtEpochMillis)
        assertEquals(9_000L, next.lastGiveUpAtEpochMillis)
    }

    // ------------------------------------------------------ run state storage

    @Test
    fun theRunStateSurvivesANewStoreInstance() = runBlocking {
        val file = tempFile("persist")
        val store = RefreshRunStateStore(context, file)
        store.record(RefreshRunOutcome.GaveUp("The job sources took too long to answer."), 4_000)

        val reopened = RefreshRunStateStore(context, file)
        val read = reopened.read()
        assertTrue(read.retryExhausted)
        assertEquals("The job sources took too long to answer.", read.reason)
        assertEquals(4_000L, read.lastGiveUpAtEpochMillis)
    }

    @Test
    fun aMalformedStateFileIsDiscardedRatherThanCrashing() = runBlocking {
        val file = tempFile("broken").apply { writeText("{ not json") }
        val store = RefreshRunStateStore(context, file)
        assertEquals(RefreshRunState(), store.read())
    }

    @Test
    fun theObservedStateStartsWithDiskAndFollowsRecords() = runBlocking {
        val file = tempFile("observe")
        val store = RefreshRunStateStore(context, file)
        store.record(RefreshRunOutcome.GaveUp("The job sources could not be reached."), 1_000)
        assertEquals("The job sources could not be reached.", store.observe().first().reason)
    }

    // ------------------------------------------------------- the surfaced line

    @Test
    fun theSummaryCarriesTheBackgroundGiveUpHonestly() {
        val summary = DiscoverySummary.from(
            diagnostics = ProviderDiagnostics(),
            delta = DiscoveryDelta(),
            runState = RefreshRunState(
                retryExhausted = true,
                reason = "The job sources could not be reached.",
            ),
        )
        assertTrue(summary.retryExhausted)
        assertEquals("The job sources could not be reached.", summary.backgroundFailureReason)
    }

    @Test
    fun aSummaryWithoutARunStateClaimsNoBackgroundFailure() {
        val summary = DiscoverySummary.from(ProviderDiagnostics(), DiscoveryDelta())
        assertFalse(summary.retryExhausted)
        assertNull(summary.backgroundFailureReason)
    }
}
