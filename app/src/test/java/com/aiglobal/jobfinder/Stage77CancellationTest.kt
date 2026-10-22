package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.JobCache
import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.provider.GreenhouseBoard
import com.aiglobal.jobfinder.data.jobs.provider.GreenhouseProvider
import com.aiglobal.jobfinder.data.jobs.provider.JobProviderException
import com.aiglobal.jobfinder.data.jobs.provider.JsonFeed
import com.aiglobal.jobfinder.data.jobs.provider.RemotiveProvider
import com.aiglobal.jobfinder.data.jobs.provider.runCatchingCancellable
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.ProviderStatus
import com.aiglobal.jobfinder.data.repository.LiveJobRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 77 — coroutine cancellation must never be swallowed.
 *
 * The audit found that a plain `runCatching` (and several `catch (t: Throwable)` blocks) around a
 * suspend call captured [CancellationException]. That turned the repository's own deadline into an
 * ordinary provider failure: the caller's `awaitAll` completed as if nothing had happened and the
 * in-flight network work kept running past its bound. These tests pin the corrected behaviour at
 * three levels — the shared helper, a provider, and the repository — and prove that an ordinary
 * failure is still classified as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage77CancellationTest {

    /** A feed that either throws at once or hangs, so a deadline can cut it off. */
    private class FakeFeed(
        private val error: Throwable? = null,
        private val hangMillis: Long = 0,
    ) : JsonFeed {
        private suspend fun answer(): JSONObject {
            if (hangMillis > 0) delay(hangMillis)
            error?.let { throw it }
            return JSONObject()
        }

        override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject = answer()

        override suspend fun arrayAt(url: String, headers: Map<String, String>): JSONArray {
            if (hangMillis > 0) delay(hangMillis)
            error?.let { throw it }
            return JSONArray()
        }

        override suspend fun objectPost(
            url: String,
            body: String,
            headers: Map<String, String>,
        ): JSONObject = answer()
    }

    private fun greenhouse(feed: JsonFeed): GreenhouseProvider =
        GreenhouseProvider(feed = feed, boards = listOf(GreenhouseBoard("test", "Test board")))

    private fun repository(provider: GreenhouseProvider): LiveJobRepository = LiveJobRepository(
        providers = listOf(provider),
        mapper = JobMapper(matchingEngine = MatchingEngine(), eligibilityEngine = EligibilityEngine()),
        candidateProfile = MutableStateFlow(CandidateProfile()),
        cvRecord = MutableStateFlow(null),
        cache = JobCache(File.createTempFile("stage77", "").let { f -> f.delete(); f.mkdirs(); f }),
        isOnline = { true },
        perProviderTotalTimeoutMillis = 500,
    )

    // ---- The shared helper ---------------------------------------------------------------

    @Test
    fun theHelperRethrowsCancellation() {
        var caught: Throwable? = null
        try {
            runCatchingCancellable<Unit> { throw CancellationException("cancelled") }
        } catch (t: Throwable) {
            caught = t
        }
        assertTrue("CancellationException must propagate, not become a Result.failure", caught is CancellationException)
    }

    @Test
    fun theHelperRethrowsTimeoutCancellation() = runBlocking {
        val timeout = try {
            withTimeout(1) { delay(1_000) }
            null
        } catch (t: TimeoutCancellationException) {
            t
        }
        var caught: Throwable? = null
        try {
            runCatchingCancellable<Unit> { throw timeout!! }
        } catch (t: Throwable) {
            caught = t
        }
        assertTrue(caught is TimeoutCancellationException)
    }

    @Test
    fun theHelperStillCapturesAnOrdinaryFailure() {
        val result = runCatchingCancellable { throw IOException("down") }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun theHelperReturnsSuccessUnchanged() {
        assertEquals(42, runCatchingCancellable { 42 }.getOrNull())
    }

    // ---- Providers: cancellation propagates, ordinary errors are still reported ----------

    @Test
    fun greenhouseRethrowsCancellationInsteadOfReportingASourceFailure() = runBlocking {
        val provider = greenhouse(FakeFeed(error = CancellationException("cancelled")))
        var caught: Throwable? = null
        try {
            provider.fetch(listOf("maintenance"), 5)
        } catch (t: Throwable) {
            caught = t
        }
        assertTrue(caught is CancellationException)
    }

    @Test
    fun greenhouseStillReportsAnOrdinarySourceFailure() = runBlocking {
        val provider = greenhouse(FakeFeed(error = IOException("down")))
        var caught: Throwable? = null
        try {
            provider.fetch(emptyList(), 5)
        } catch (t: Throwable) {
            caught = t
        }
        assertTrue(caught is JobProviderException)
    }

    @Test
    fun remotiveRethrowsCancellation() = runBlocking {
        val provider = RemotiveProvider(FakeFeed(error = CancellationException("cancelled")))
        var caught: Throwable? = null
        try {
            provider.fetch(listOf("x"), 5)
        } catch (t: Throwable) {
            caught = t
        }
        assertTrue(caught is CancellationException)
    }

    @Test
    fun remotiveStillWrapsAnOrdinaryFailure() = runBlocking {
        val provider = RemotiveProvider(FakeFeed(error = IOException("down")))
        var caught: Throwable? = null
        try {
            provider.fetch(listOf("x"), 5)
        } catch (t: Throwable) {
            caught = t
        }
        assertTrue(caught is JobProviderException)
    }

    // ---- End to end: a deadline stays a deadline -----------------------------------------

    @Test
    fun aDeadlineOnAHangingProviderSurfacesAsATimeoutNotAnOrdinaryError() = runBlocking {
        val provider = greenhouse(FakeFeed(hangMillis = 30_000))
        val thrown = try {
            withTimeout(300) { provider.fetch(emptyList(), 5) }
            null
        } catch (t: Throwable) {
            t
        }
        assertTrue(
            "the timeout's own cancellation must reach the caller, not be converted to JobProviderException",
            thrown is TimeoutCancellationException,
        )
    }

    @Test
    fun theRepositoryReportsAHangingProviderAsATimeout() = runBlocking {
        val repo = repository(greenhouse(FakeFeed(hangMillis = 30_000)))

        repo.awaitSettled()

        assertEquals(
            ProviderStatus.TIMEOUT,
            repo.diagnostics.first().providers.first().status,
        )
    }
}
