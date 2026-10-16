package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.ai.NvidiaAiClient
import com.aiglobal.jobfinder.data.ai.NvidiaHttpResponse
import com.aiglobal.jobfinder.data.ai.NvidiaJobAssistant
import com.aiglobal.jobfinder.data.ai.NvidiaTransport
import com.aiglobal.jobfinder.data.model.NvidiaModels
import com.aiglobal.jobfinder.data.model.NvidiaStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 32A — the NVIDIA "key saved but status Off" diagnostic.
 *
 * The field symptom was a valid key that still showed Off. Two defects produced it:
 *  1. The four suggested models were all retired on NVIDIA's side, so the default probe
 *     always failed ("Model unavailable") even though the key itself was fine.
 *  2. `testModel` reported success on any HTTP 200 with a non-blank body, so a reasoning
 *     model that returns `content: null` would have been marked "On" while drafting would
 *     still fail — an inconsistent status.
 *
 * These tests pin the fixes: the suggested list stays usable, and "On" now requires the same
 * non-empty content a real draft needs. Every network path goes through the [NvidiaTransport]
 * seam, so nothing here is a live call and no key ever leaves the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage32aNvidiaDiagnosticTest {

    /** Records the last request body and counts chat vs list calls. */
    private class FakeTransport(private val response: NvidiaHttpResponse) : NvidiaTransport {
        var lastBody: String? = null
        var chatCalls = 0
        var listCalls = 0
        override suspend fun chatCompletions(apiKey: String, body: String, timeoutMillis: Int): NvidiaHttpResponse {
            chatCalls++
            lastBody = body
            return response
        }
        override suspend fun listModels(apiKey: String, timeoutMillis: Int): NvidiaHttpResponse {
            listCalls++
            return response
        }
    }

    /** A reasoned reply whose visible answer is absent, the failure mode this stage guards. */
    private fun reasoningOnlyResponse() = NvidiaHttpResponse(
        statusCode = 200,
        body = """{"choices":[{"message":{"role":"assistant","content":null,"reasoning":"thinking..."},"finish_reason":"length"}]}""",
    )

    private fun contentResponse(text: String) = NvidiaHttpResponse(
        statusCode = 200,
        body = """{"choices":[{"message":{"role":"assistant","content":"$text"}}]}""",
    )

    // ---- The suggested list must stay usable -----------------------------

    @Test
    fun everySuggestedModelHasAValidIdAndALabel() {
        assertTrue("the app must suggest at least one model", NvidiaModels.known.isNotEmpty())
        NvidiaModels.known.forEach { model ->
            assertTrue("${model.id} must be vendor/name", NvidiaAiClient().looksLikeModelId(model.id))
            assertTrue("${model.label} must not be blank", model.label.isNotBlank())
        }
        // Duplicates would make a selection ambiguous.
        val ids = NvidiaModels.known.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun theDefaultModelIsTheFirstSuggestedOne() {
        assertEquals(NvidiaModels.known.first().id, NvidiaModels.defaultModelId)
        assertTrue(NvidiaModels.isKnown(NvidiaModels.defaultModelId))
    }

    // ---- "On" must mean the model actually produced text -----------------

    @Test
    fun aReasoningModelWithNoVisibleTextIsBadResponseNotSuccess() = runBlocking {
        val result = NvidiaAiClient(FakeTransport(reasoningOnlyResponse()))
            .testModel("nvapi-key", NvidiaModels.defaultModelId)
        assertEquals(NvidiaStatus.BAD_RESPONSE, result.status)
        assertFalse(result.isSuccess)
    }

    @Test
    fun whitespaceOnlyContentIsBadResponseNotSuccess() = runBlocking {
        val result = NvidiaAiClient(FakeTransport(contentResponse("   ")))
            .testModel("nvapi-key", NvidiaModels.defaultModelId)
        assertEquals(NvidiaStatus.BAD_RESPONSE, result.status)
    }

    @Test
    fun aModelThatProducesTextIsReportedUsable() = runBlocking {
        val result = NvidiaAiClient(FakeTransport(contentResponse("ok")))
            .testModel("nvapi-key", NvidiaModels.defaultModelId)
        assertTrue(result.isSuccess)
        assertEquals(NvidiaStatus.OK, result.status)
    }

    @Test
    fun theProbeAndTheRealDraftAgreeOnTheSameBody() = runBlocking {
        // A reply that the probe rejects must also be rejected by the draft path, so the
        // status shown in Profile can never claim readiness the drafting path lacks.
        val probe = NvidiaAiClient(FakeTransport(reasoningOnlyResponse()))
            .testModel("nvapi-key", NvidiaModels.defaultModelId)
        val draft = NvidiaAiClient(FakeTransport(reasoningOnlyResponse()))
            .complete("nvapi-key", NvidiaModels.defaultModelId, "system", "user")
        assertFalse(probe.isSuccess)
        assertFalse(draft.isSuccess)
        assertEquals(NvidiaStatus.BAD_RESPONSE, probe.status)
        assertEquals(NvidiaStatus.BAD_RESPONSE, draft.status)
    }

    @Test
    fun aRetiredOrAccountScopedModelIsUnavailableNotBroken() = runBlocking {
        // 410 is how NVIDIA reports a retired model; the app must say "pick another", not
        // "the app is broken", so the key is never blamed for a dead model id.
        val gone = NvidiaHttpResponse(410, """{"detail":"the model is no longer available"}""")
        val result = NvidiaAiClient(FakeTransport(gone)).testModel("nvapi-key", NvidiaModels.defaultModelId)
        assertEquals(NvidiaStatus.MODEL_UNAVAILABLE, result.status)
    }

    // ---- "Check key" is a list-only probe, never a chat call -------------

    @Test
    fun testingTheKeyOnlyListsModelsAndNeverSendsChat() = runBlocking {
        val transport = FakeTransport(NvidiaHttpResponse(200, """{"data":[{"id":"a/b"}]}"""))
        val assistant = NvidiaJobAssistant(NvidiaAiClient(transport))
        val result = assistant.testConnection("nvapi-key")
        assertEquals(NvidiaStatus.OK, result.status)
        assertEquals(1, transport.listCalls)
        assertEquals("checking a key must not send a chat request", 0, transport.chatCalls)
    }

    @Test
    fun theKeyCheckClassifiesEachStateWithoutLeakingTheKey() = runBlocking {
        val cases = mapOf(
            401 to NvidiaStatus.INVALID_KEY,
            403 to NvidiaStatus.INVALID_KEY,
            429 to NvidiaStatus.RATE_LIMITED,
            500 to NvidiaStatus.NETWORK,
        )
        val secret = "nvapi-should-never-appear"
        cases.forEach { (code, expected) ->
            val result = NvidiaAiClient(FakeTransport(NvidiaHttpResponse(code, "{}"))).validateKey(secret)
            assertEquals(expected, result.status)
            assertFalse("no message may echo the key", result.message.contains(secret))
            assertFalse("no message may carry the header name", result.message.contains("Bearer"))
        }
        val blank = NvidiaAiClient(FakeTransport(NvidiaHttpResponse(200, "{}"))).validateKey("")
        assertEquals(NvidiaStatus.NOT_CONFIGURED, blank.status)
    }

    @Test
    fun noOutcomeMessageEverCarriesTheApiKeyOrAnAuthHeader() = runBlocking {
        val secret = "nvapi-leak-canary"
        val bodies = listOf(reasoningOnlyResponse(), contentResponse("hi"), NvidiaHttpResponse(403, "{}"))
        bodies.forEach { response ->
            listOf(
                NvidiaAiClient(FakeTransport(response)).testModel(secret, NvidiaModels.defaultModelId),
                NvidiaAiClient(FakeTransport(response)).validateKey(secret),
            ).forEach { result ->
                assertFalse(result.message.contains(secret))
                assertFalse(result.message.contains("Bearer"))
            }
        }
    }
}
