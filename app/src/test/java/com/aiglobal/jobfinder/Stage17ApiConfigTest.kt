package com.aiglobal.jobfinder

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.aiglobal.jobfinder.data.ai.NvidiaAiClient
import com.aiglobal.jobfinder.data.ai.NvidiaHttpResponse
import com.aiglobal.jobfinder.data.ai.NvidiaJobAssistant
import com.aiglobal.jobfinder.data.ai.NvidiaTransport
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaCheckStatus
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaCredentials
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaProvider
import com.aiglobal.jobfinder.data.jobs.provider.JsonFeed
import com.aiglobal.jobfinder.data.model.NvidiaModels
import com.aiglobal.jobfinder.data.model.NvidiaStatus
import com.aiglobal.jobfinder.data.prefs.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage 17 — configuring and really testing the NVIDIA and Adzuna APIs from the app.
 *
 * The connection tests are exercised through the same seams the providers use ([JsonFeed]
 * for Adzuna, [NvidiaTransport] for NVIDIA), so every assertion is about what the app would
 * send and how it reads the reply, with no live call. Nothing here claims a real request
 * succeeded; the one opt-in live probe is separate and skipped by default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage17ApiConfigTest {

    /** Replays a canned body per URL, and counts the calls so a retry can be detected. */
    private class FakeFeed(private val onObject: (String) -> JSONObject) : JsonFeed {
        val calls = AtomicInteger()
        val urls = mutableListOf<String>()
        override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject {
            calls.incrementAndGet()
            urls += url
            return onObject(url)
        }
        override suspend fun objectPost(url: String, body: String, headers: Map<String, String>): JSONObject =
            onObject(url)
        override suspend fun arrayAt(url: String, headers: Map<String, String>): JSONArray =
            throw UnsupportedOperationException("array feed not used")
    }

    private class FakeTransport(private val response: NvidiaHttpResponse) : NvidiaTransport {
        var calls = 0
        override suspend fun chatCompletions(apiKey: String, body: String, timeoutMillis: Int): NvidiaHttpResponse {
            calls++
            return response
        }
        override suspend fun listModels(apiKey: String, timeoutMillis: Int): NvidiaHttpResponse {
            calls++
            return response
        }
    }

    private fun version() = JSONObject("""{"api_version":"1.0.0"}""")
    private fun search(title: String? = "Maintenance Technician", count: Int = 42) = JSONObject(
        buildString {
            append("""{"count":$count,"results":[""")
            if (title != null) append("""{"title":"$title"}""")
            append("]}")
        },
    )

    private fun adzuna(
        feed: JsonFeed,
        countries: List<String> = listOf("gb"),
        credentials: AdzunaCredentials = AdzunaCredentials("id", "key"),
    ) = AdzunaProvider(credentials = { credentials }, feed = feed, countries = countries)

    // ---- Adzuna connection test -------------------------------------------

    @Test
    fun adzunaCheckSucceedsAndReportsWhatTheMarketActuallyReturned() = runBlocking {
        val feed = FakeFeed { url -> if (url.contains("/version")) version() else search() }
        val result = adzuna(feed).checkConnection()
        assertTrue(result.isSuccess)
        assertEquals(AdzunaCheckStatus.OK, result.status)
        assertEquals("gb", result.market)
        assertEquals("Maintenance Technician", result.sampleTitle)
        assertEquals(42, result.totalResults)
        assertTrue(result.message.contains("API v1.0.0"))
    }

    @Test
    fun adzunaCheckWithoutCredentialsNeverHitsTheNetwork() = runBlocking {
        val feed = FakeFeed { throw AssertionError("the network must not be touched") }
        val result = adzuna(feed, credentials = AdzunaCredentials()).checkConnection()
        assertEquals(AdzunaCheckStatus.NOT_CONFIGURED, result.status)
        assertEquals(0, feed.calls.get())
    }

    @Test
    fun adzunaCheckReportsARejectedKeyAsInvalidCredentialsNotAsABreak() = runBlocking {
        val feed = FakeFeed { throw IOException("HTTP 401 for https://api.adzuna.com") }
        val result = adzuna(feed).checkConnection()
        assertEquals(AdzunaCheckStatus.INVALID_CREDENTIALS, result.status)
        assertTrue(result.message.contains("rejected the credentials"))
    }

    @Test
    fun adzunaCheckStopsOnARejectedKeyInsteadOfRetryingEveryMarket() = runBlocking {
        val feed = FakeFeed { throw IOException("HTTP 401 for https://api.adzuna.com") }
        adzuna(feed, countries = listOf("gb", "us", "ca")).checkConnection()
        // The key is wrong for the account, so one attempt is enough; three would be noise.
        assertEquals(1, feed.calls.get())
    }

    @Test
    fun adzunaCheckFallsThroughToTheNextMarketWhenTheFirstIsUnavailable() = runBlocking {
        val feed = FakeFeed { url ->
            when {
                url.contains("/version") -> version()
                url.contains("/us/") -> throw IOException("HTTP 503 for https://api.adzuna.com")
                else -> search()
            }
        }
        val result = adzuna(feed, countries = listOf("us", "gb")).checkConnection()
        assertTrue(result.isSuccess)
        assertEquals("gb", result.market)
    }

    @Test
    fun adzunaCheckDistinguishesRateLimitingFromATimeout() = runBlocking {
        val limited = adzuna(FakeFeed { throw IOException("HTTP 429 for https://api.adzuna.com") }).checkConnection()
        assertEquals(AdzunaCheckStatus.RATE_LIMITED, limited.status)
        val slow = adzuna(FakeFeed { throw SocketTimeoutException("timeout") }).checkConnection()
        assertEquals(AdzunaCheckStatus.TIMEOUT, slow.status)
    }

    @Test
    fun adzunaCheckReportsAnUnreadableBodyAsAnInvalidResponse() = runBlocking {
        val feed = FakeFeed { url ->
            if (url.contains("/version")) version() else throw org.json.JSONException("not json")
        }
        val result = adzuna(feed).checkConnection()
        assertEquals(AdzunaCheckStatus.INVALID_RESPONSE, result.status)
    }

    @Test
    fun adzunaCheckNeverFabricatesAPostingForAnEmptyMarket() = runBlocking {
        val feed = FakeFeed { url ->
            if (url.contains("/version")) version() else JSONObject("""{"count":0,"results":[]}""")
        }
        val result = adzuna(feed).checkConnection()
        assertTrue(result.isSuccess)
        assertNull(result.sampleTitle)
        assertEquals(0, result.totalResults)
        assertTrue(result.message.contains("no ads"))
    }

    @Test
    fun adzunaCheckSendsTheKeyAsAQueryParameterOnBothCalls() = runBlocking {
        val feed = FakeFeed { url -> if (url.contains("/version")) version() else search() }
        adzuna(feed).checkConnection()
        assertEquals(2, feed.urls.size)
        assertTrue(feed.urls.all { it.contains("app_id=id") && it.contains("app_key=key") })
    }

    // ---- NVIDIA model discovery -------------------------------------------

    @Test
    fun modelDiscoveryReturnsTheAccountsIdsSortedAndDeduped() = runBlocking {
        val body = """{"data":[{"id":"meta/llama-3.3-70b-instruct"},{"id":"meta/llama-3.1-8b-instruct"},""" +
            """{"id":"meta/llama-3.1-8b-instruct"},{"id":""}]}"""
        val client = NvidiaAiClient(FakeTransport(NvidiaHttpResponse(200, body)))
        val result = client.listModelIds("nvapi-key")
        assertTrue(result.isSuccess)
        assertEquals(
            listOf("meta/llama-3.1-8b-instruct", "meta/llama-3.3-70b-instruct"),
            result.text.lines(),
        )
    }

    @Test
    fun modelDiscoveryClassifiesARejectedKey() = runBlocking {
        val client = NvidiaAiClient(FakeTransport(NvidiaHttpResponse(401, "{}")))
        assertEquals(NvidiaStatus.INVALID_KEY, client.listModelIds("nvapi-key").status)
    }

    @Test
    fun modelDiscoveryWithoutAKeyDoesNotCallTheTransport() = runBlocking {
        val transport = FakeTransport(NvidiaHttpResponse(200, "{}"))
        val result = NvidiaAiClient(transport).listModelIds("   ")
        assertEquals(NvidiaStatus.NOT_CONFIGURED, result.status)
        assertEquals(0, transport.calls)
    }

    @Test
    fun modelDiscoveryOnAnUnreadableBodyIsABadResponse() = runBlocking {
        val client = NvidiaAiClient(FakeTransport(NvidiaHttpResponse(200, "not json at all")))
        assertEquals(NvidiaStatus.BAD_RESPONSE, client.listModelIds("nvapi-key").status)
    }

    @Test
    fun theAssistantTurnsADiscoveredBodyIntoAListOfIds() = runBlocking {
        val body = """{"data":[{"id":"mistralai/mistral-nemo-12b-instruct"}]}"""
        val assistant = NvidiaJobAssistant(client = NvidiaAiClient(FakeTransport(NvidiaHttpResponse(200, body))))
        assertEquals(listOf("mistralai/mistral-nemo-12b-instruct"), assistant.discoverModels("nvapi-key"))
    }

    // ---- Model selection accepts what the account really serves ------------

    @Test
    fun anyNonBlankModelIdIsSelectableSoADiscoveredModelIsNotRejected() {
        assertTrue(NvidiaModels.isSelectable("meta/llama-3.3-70b-instruct"))
        assertTrue(NvidiaModels.isSelectable("some/vendor-brand-new-model"))
        assertFalse(NvidiaModels.isSelectable(""))
        assertFalse(NvidiaModels.isSelectable("   "))
        assertFalse(NvidiaModels.isKnown("some/vendor-brand-new-model"))
        assertEquals("some/vendor-brand-new-model", NvidiaModels.discovered("some/vendor-brand-new-model").label)
    }

    @Test
    fun theDefaultModelIsOneOfTheSuggestedModels() {
        assertTrue(NvidiaModels.isKnown(NvidiaModels.defaultModelId))
        assertEquals(NvidiaModels.known.first().id, NvidiaModels.defaultModelId)
    }

    // ---- The chosen model survives a restart -------------------------------

    @Test
    fun aCustomModelIdIsPersistedAndReadBack() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = java.io.File(context.cacheDir, "stage17_models.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        try {
            val prefs = UserPreferencesRepository(context, dataStore)
            prefs.setNvidiaModelId("some/vendor-brand-new-model")
            assertEquals("some/vendor-brand-new-model", prefs.nvidiaModelId.first())
            assertEquals("some/vendor-brand-new-model", prefs.nvidiaConfig.first().modelId)
        } finally {
            scope.cancel()
        }
    }
}
