package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.provider.AdzunaCredentials
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaProvider
import com.aiglobal.jobfinder.data.jobs.provider.HttpJsonClient
import com.aiglobal.jobfinder.data.jobs.provider.JobProviderException
import com.aiglobal.jobfinder.data.jobs.provider.JsonFeed
import com.aiglobal.jobfinder.data.jobs.provider.ProviderErrorCategory
import com.aiglobal.jobfinder.data.jobs.provider.ProviderErrorClassifier
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.UnknownHostException

/**
 * Stage 10 — Adzuna configuration, response validation, error handling and job mapping.
 *
 * These run on the JVM through the [JsonFeed] seam, so the mapping and the failure
 * classification are exercised without a network call. The live request itself is
 * reported separately; nothing here claims a real request succeeded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage10AdzunaTest {

    /** Records the last request and replays a per-URL canned response. */
    private class FakeFeed(
        private val response: JSONObject = JSONObject(),
        private val onObject: (String) -> JSONObject = { response },
    ) : JsonFeed {
        var lastUrl: String? = null

        override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject {
            lastUrl = url
            return onObject(url)
        }

        override suspend fun objectPost(url: String, body: String, headers: Map<String, String>): JSONObject =
            onObject(url)
    }

    private fun provider(feed: JsonFeed, countries: List<String> = listOf("gb")) =
        AdzunaProvider(credentials = { AdzunaCredentials("id", "key") }, feed = feed, countries = countries)

    // ---- Configuration -----------------------------------------------------

    @Test
    fun credentialsRequireBothValuesAndTreatWhitespaceAsMissing() {
        assertTrue(AdzunaCredentials("id", "key").isConfigured)
        assertFalse(AdzunaCredentials("id", "").isConfigured)
        assertFalse(AdzunaCredentials("", "key").isConfigured)
        assertFalse(AdzunaCredentials("   ", "   ").isConfigured)
    }

    @Test
    fun theKeyIsSentAsAQueryParameterSoItMustBeRedactable() = runBlocking {
        val feed = FakeFeed(JSONObject("""{"results":[]}"""))
        provider(feed).fetch()
        // The key really does travel in the URL — this is why the redactor exists.
        assertTrue(feed.lastUrl!!.contains("app_key=key"))
    }

    // ---- Secret redaction --------------------------------------------------

    @Test
    fun redactionRemovesTheKeyFromAFailedUrlButKeepsTheHost() {
        val url = "https://api.adzuna.com/v1/api/jobs/gb/search/1?app_id=abc123&app_key=supersecret&results_per_page=5"
        val redacted = HttpJsonClient.redactUrl(url)
        assertFalse(redacted.contains("supersecret"))
        assertFalse(redacted.contains("abc123"))
        assertTrue(redacted.contains("app_key=***"))
        assertTrue(redacted.contains("results_per_page=5"))
        assertTrue(redacted.contains("https://api.adzuna.com"))
    }

    @Test
    fun redactionLeavesAPlainUrlUntouched() {
        val url = "https://remotive.com/api/remote-jobs"
        assertEquals(url, HttpJsonClient.redactUrl(url))
    }

    // ---- Error classification ---------------------------------------------

    @Test
    fun aRejectedKeyIsClassifiedAsAnAuthenticationFailure() {
        val error = IOException("HTTP 401 for https://api.adzuna.com/v1/api/jobs/gb/search/1?app_key=***")
        assertEquals(ProviderErrorCategory.AUTHENTICATION, ProviderErrorClassifier.classify("adzuna", error))
    }

    @Test
    fun adzunasOwnAuthFailCodeIsRecognised() {
        val error = JobProviderException(
            providerId = "adzuna",
            message = "Adzuna did not respond",
            cause = IOException("HTTP 401 AUTH_FAIL"),
        )
        assertEquals(ProviderErrorCategory.AUTHENTICATION, ProviderErrorClassifier.classify("adzuna", error))
    }

    @Test
    fun aThrottledRequestIsClassifiedAsRateLimited() {
        val error = IOException("HTTP 429 for https://api.adzuna.com/v1/api/jobs/gb/search/1")
        assertEquals(ProviderErrorCategory.RATE_LIMITED, ProviderErrorClassifier.classify("adzuna", error))
    }

    @Test
    fun anUnreachableHostIsClassifiedAsANetworkError() {
        val error = UnknownHostException("api.adzuna.com")
        assertEquals(ProviderErrorCategory.NETWORK, ProviderErrorClassifier.classify("adzuna", error))
    }

    @Test
    fun anUnparseableBodyIsClassifiedAsAnInvalidResponse() {
        val error = IOException("unexpected end of input")
        assertEquals(ProviderErrorCategory.INVALID_RESPONSE, ProviderErrorClassifier.classify("adzuna", error))
    }

    // ---- Response validation ----------------------------------------------

    @Test
    fun anEmptyResultSetIsSuccessNotAnError() = runBlocking {
        val feed = FakeFeed(JSONObject("""{"count":0,"results":[]}"""))
        assertTrue(provider(feed).fetch().isEmpty())
    }

    @Test
    fun aMalformedEntryIsSkippedRatherThanCrashingTheWholePage() = runBlocking {
        val json = JSONObject(
            """
            {"results":[
              null,
              {"id":"a1","title":"Fitter","redirect_url":"https://adzuna.com/a1"},
              {"title":"No id and no url"}
            ]}
            """.trimIndent(),
        )
        val jobs = provider(FakeFeed(json)).fetch()
        assertEquals(2, jobs.size)
        assertEquals("a1", jobs[0].externalId)
        // A blank id falls back to the redirect url so the posting stays deduplicable.
        assertEquals("", jobs[1].externalId)
    }

    @Test
    fun aNonJsonBodyFailsTheProviderWithAClassifiedError() = runBlocking {
        val feed = FakeFeed { throw org.json.JSONException("unexpected end of input") }
        try {
            provider(feed).fetch()
            fail("Expected a JobProviderException")
        } catch (e: JobProviderException) {
            assertEquals(ProviderErrorCategory.INVALID_RESPONSE, e.category)
        }
    }

    @Test
    fun whenEveryMarketFailsTheProviderReportsOneError() = runBlocking {
        val feed = FakeFeed { throw IOException("HTTP 503") }
        try {
            provider(feed, countries = listOf("gb", "ca")).fetch()
            fail("Expected a JobProviderException")
        } catch (e: JobProviderException) {
            assertEquals(ProviderErrorCategory.HTTP_ERROR, e.category)
        }
    }

    @Test
    fun aFailedMarketDoesNotHideTheMarketsThatAnswered() = runBlocking {
        val feed = FakeFeed { url ->
            if (url.contains("/gb/")) {
                JSONObject(
                    """{"results":[{"id":"gb1","title":"Maintenance Technician",
                       "redirect_url":"https://adzuna.com/gb1","company":{"display_name":"A"},
                       "location":{"display_name":"Leeds"}}]}""",
                )
            } else {
                throw IOException("HTTP 429")
            }
        }
        val jobs = provider(feed, countries = listOf("gb", "ca")).fetch()
        assertEquals(1, jobs.size)
        assertEquals("gb1", jobs.single().externalId)
    }

    // ---- Job mapping -------------------------------------------------------

    @Test
    fun theRequestCarriesTheKeywordAndThePageSize() = runBlocking {
        val feed = FakeFeed(JSONObject("""{"results":[]}"""))
        provider(feed, countries = listOf("gb", "ca")).fetch(listOf("Pump Technician"), limit = 40)
        // 40 across two markets → 20 per request.
        assertTrue(feed.lastUrl!!.contains("what=Pump+Technician") || feed.lastUrl!!.contains("what=Pump%20Technician"))
        assertTrue(feed.lastUrl!!.contains("results_per_page=20"))
    }

    @Test
    fun theMarketCurrencyIsAppliedToTheSalary() = runBlocking {
        val json = JSONObject(
            """{"results":[{"id":"au1","title":"Fitter","description":"Great role",
               "redirect_url":"https://adzuna.com/au1","company":{"display_name":"Acme"},
               "location":{"display_name":"Perth"},"salary_min":80000,"salary_max":90000}]}""",
        )
        val job = provider(FakeFeed(json), countries = listOf("au")).fetch().single()
        assertEquals("AUD", job.salaryCurrency)
        assertEquals(80000, job.salaryMin)
    }

    @Test
    fun aRemoteTitleIsFlaggedAsRemote() = runBlocking {
        val json = JSONObject(
            """{"results":[{"id":"r1","title":"Remote Maintenance Technician",
               "redirect_url":"https://adzuna.com/r1"}]}""",
        )
        assertTrue(provider(FakeFeed(json)).fetch().single().isRemote)
    }
}
