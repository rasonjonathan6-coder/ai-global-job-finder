package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.ai.AssistTask
import com.aiglobal.jobfinder.data.ai.NvidiaAiClient
import com.aiglobal.jobfinder.data.ai.NvidiaHttpResponse
import com.aiglobal.jobfinder.data.ai.NvidiaJobAssistant
import com.aiglobal.jobfinder.data.ai.NvidiaTransport
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaCredentials
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaProvider
import com.aiglobal.jobfinder.data.jobs.provider.JsonFeed
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.EducationEntry
import com.aiglobal.jobfinder.data.model.NvidiaConfig
import com.aiglobal.jobfinder.data.model.NvidiaModels
import com.aiglobal.jobfinder.data.model.NvidiaStatus
import com.aiglobal.jobfinder.data.repository.SampleData
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 16 — the optional NVIDIA assistant and the user-configurable Adzuna markets.
 *
 * Every network path is exercised through the [NvidiaTransport] seam with canned responses,
 * so the tests assert what the app would send and how it classifies each reply without a
 * live call. The two properties that matter most — no request without consent, and no
 * personal identifiers in the prompt — are checked directly against the captured body.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage16NvidiaTest {

    /** Records the last request body and replays one canned reply. */
    private class FakeTransport(private val response: NvidiaHttpResponse) : NvidiaTransport {
        var lastBody: String? = null
        var calls = 0
        override suspend fun chatCompletions(apiKey: String, body: String, timeoutMillis: Int): NvidiaHttpResponse {
            calls++
            lastBody = body
            return response
        }
        override suspend fun listModels(apiKey: String, timeoutMillis: Int): NvidiaHttpResponse {
            calls++
            return response
        }
    }

    private val profile = CandidateProfile(
        fullName = "Jonathan Rakoto",
        email = "jonathan.private@example.com",
        phone = "+261 34 000 0000",
        currentTitle = "Maintenance Technician",
        yearsOfExperience = 6,
        skills = listOf("Hydraulics", "Electrical diagnostics", "CMMS"),
        education = listOf(EducationEntry(degree = "BTS Maintenance Industrielle", institution = "Lycée Technique", country = "Madagascar", year = 2016)),
        summary = "Hands-on industrial maintenance technician.",
    )

    private val job = SampleData.jobs(0L).first()

    private fun okResponse() = NvidiaHttpResponse(
        statusCode = 200,
        body = """{"choices":[{"message":{"role":"assistant","content":"A grounded draft."}}]}""",
    )

    // ---- Consent and configuration gates -----------------------------------

    @Test
    fun aBlankKeyNeverReachesTheNetwork() = runBlocking {
        val transport = FakeTransport(okResponse())
        val result = NvidiaAiClient(transport).complete("", "openai/gpt-oss-20b", "s", "u")
        assertEquals(NvidiaStatus.NOT_CONFIGURED, result.status)
        assertEquals(0, transport.calls)
    }

    @Test
    fun withoutConsentNothingIsSent() = runBlocking {
        val transport = FakeTransport(okResponse())
        val assistant = NvidiaJobAssistant(NvidiaAiClient(transport))
        val result = assistant.run(
            config = NvidiaConfig(apiKey = "nvapi-key", consentGranted = false),
            task = AssistTask.EXPLAIN_MATCH,
            profile = profile,
            job = job,
        )
        assertEquals(NvidiaStatus.CONSENT_REQUIRED, result.status)
        assertEquals(0, transport.calls)
    }

    // ---- Success and failure classification --------------------------------

    @Test
    fun aGroundedRequestReturnsTheModelsText() = runBlocking {
        val transport = FakeTransport(okResponse())
        val assistant = NvidiaJobAssistant(NvidiaAiClient(transport))
        val result = assistant.run(
            config = NvidiaConfig(apiKey = "nvapi-key", consentGranted = true),
            task = AssistTask.COVER_LETTER,
            profile = profile,
            job = job,
        )
        assertTrue(result.isSuccess)
        assertEquals("A grounded draft.", result.text)
        assertEquals(1, transport.calls)
    }

    @Test
    fun anInvalidKeyIsClassifiedAsRejected() = runBlocking {
        val transport = FakeTransport(NvidiaHttpResponse(401, """{"detail":"unauthorized"}"""))
        val result = NvidiaAiClient(transport).complete("nvapi-key", "openai/gpt-oss-20b", "s", "u")
        assertEquals(NvidiaStatus.INVALID_KEY, result.status)
    }

    @Test
    fun aRetiredModelIsReportedAsUnavailableNotBroken() = runBlocking {
        val transport = FakeTransport(NvidiaHttpResponse(410, """{"title":"Gone"}"""))
        val result = NvidiaAiClient(transport).complete("nvapi-key", "old/model", "s", "u")
        assertEquals(NvidiaStatus.MODEL_UNAVAILABLE, result.status)
    }

    @Test
    fun rateLimitingIsClassified() = runBlocking {
        val transport = FakeTransport(NvidiaHttpResponse(429, "{}"))
        val result = NvidiaAiClient(transport).complete("nvapi-key", "openai/gpt-oss-20b", "s", "u")
        assertEquals(NvidiaStatus.RATE_LIMITED, result.status)
    }

    @Test
    fun validateKeyAcceptsAValidKeyAndRejectsABadOne() = runBlocking {
        assertEquals(
            NvidiaStatus.OK,
            NvidiaAiClient(FakeTransport(NvidiaHttpResponse(200, "{}"))).validateKey("nvapi-key").status,
        )
        assertEquals(
            NvidiaStatus.INVALID_KEY,
            NvidiaAiClient(FakeTransport(NvidiaHttpResponse(403, "{}"))).validateKey("nvapi-key").status,
        )
    }

    // ---- What actually leaves the device -----------------------------------

    @Test
    fun theRequestIsGroundedBoundedAndFreeOfPersonalIdentifiers() = runBlocking {
        val transport = FakeTransport(okResponse())
        val assistant = NvidiaJobAssistant(NvidiaAiClient(transport))
        assistant.run(
            config = NvidiaConfig(apiKey = "nvapi-key", modelId = "openai/gpt-oss-20b", consentGranted = true),
            task = AssistTask.EXPLAIN_MATCH,
            profile = profile,
            job = job,
        )
        val body = JSONObject(transport.lastBody!!)
        assertEquals("openai/gpt-oss-20b", body.getString("model"))
        assertTrue(body.getInt("max_tokens") <= NvidiaAiClient.MAX_TOKENS_CEILING)

        val messages = body.getJSONArray("messages")
        val system = messages.getJSONObject(0).getString("content")
        val user = messages.getJSONObject(1).getString("content")
        assertTrue("system prompt should forbid invention", system.contains("Never invent"))
        assertTrue("prompt should carry the candidate's skills", user.contains("Hydraulics"))
        assertTrue("prompt should name the posting", user.contains(job.title))

        // The narrow grounding context must exclude the identifiers a drafting model never needs.
        val whole = system + user
        assertFalse(whole.contains(profile.email))
        assertFalse(whole.contains(profile.phone))
    }

    // ---- Graceful degradation ----------------------------------------------

    @Test
    fun aFailedRemoteCallStillLeavesAUsefulOnDeviceExplanation() {
        val assistant = NvidiaJobAssistant()
        val text = assistant.localExplanation(job, profile)
        assertTrue(text.isNotBlank())
        assertTrue("the fallback must say nothing was sent", text.contains("no data was sent"))
    }

    @Test
    fun everyAssistTaskHasAPlainLanguageLabel() {
        AssistTask.entries.forEach { assertTrue(it.label.isNotBlank()) }
        assertEquals(NvidiaModels.defaultModelId, NvidiaModels.known.first().id)
    }

    // ---- Adzuna market selection -------------------------------------------

    private class FakeFeed(private val response: JSONObject) : JsonFeed {
        val urls = mutableListOf<String>()
        override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject {
            urls += url
            return response
        }
        override suspend fun objectPost(url: String, body: String, headers: Map<String, String>): JSONObject =
            response
    }

    @Test
    fun onlyTheChosenMarketsAreQueried() = runBlocking {
        val feed = FakeFeed(JSONObject("""{"results":[]}"""))
        val provider = AdzunaProvider(
            credentials = { AdzunaCredentials("id", "key") },
            feed = feed,
            countries = listOf("ca", "de"),
        )
        provider.fetch()
        assertEquals(2, feed.urls.size)
        assertTrue(feed.urls.any { it.contains("/ca/search/") })
        assertTrue(feed.urls.any { it.contains("/de/search/") })
        assertFalse("a market the user did not pick must not be queried", feed.urls.any { it.contains("/gb/") })
    }
}
