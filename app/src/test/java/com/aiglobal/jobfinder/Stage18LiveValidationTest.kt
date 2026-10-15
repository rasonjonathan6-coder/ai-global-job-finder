package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.ai.NvidiaAiClient
import com.aiglobal.jobfinder.data.ai.NvidiaHttpResponse
import com.aiglobal.jobfinder.data.ai.NvidiaJobAssistant
import com.aiglobal.jobfinder.data.ai.NvidiaTransport
import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityInput
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.NvidiaStatus
import com.aiglobal.jobfinder.data.model.VisaSupport
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
 * Stage 18 — live API validation and international job search.
 *
 * These tests pin the two things the stage adds on top of Stage 17's connection checks:
 *  1. A real inference probe that proves the selected NVIDIA model can be used (not merely
 *     listed), and an honest verdict for a model id that cannot resolve.
 *  2. Eligibility handling for remote roles scoped to a region that includes Madagascar
 *     (for example "Remote — EMEA"), which previously fell through to UNCLEAR.
 *
 * Every network path goes through the [NvidiaTransport] seam, so nothing here is a live call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage18LiveValidationTest {

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

    private fun okResponse() = NvidiaHttpResponse(
        statusCode = 200,
        body = """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""",
    )

    // ---- Model inference probe --------------------------------------------

    @Test
    fun aBlankKeyIsNotConfiguredAndNeverReachesTheNetwork() = runBlocking {
        val transport = FakeTransport(okResponse())
        val result = NvidiaAiClient(transport).testModel("", "meta/llama-3.1-8b-instruct")
        assertEquals(NvidiaStatus.NOT_CONFIGURED, result.status)
        assertEquals(0, transport.calls)
    }

    @Test
    fun aBlankModelIdIsRejectedBeforeTheNetwork() = runBlocking {
        val transport = FakeTransport(okResponse())
        val result = NvidiaAiClient(transport).testModel("nvapi-key", "")
        assertEquals(NvidiaStatus.MODEL_UNAVAILABLE, result.status)
        assertEquals(0, transport.calls)
    }

    @Test
    fun aModelIdWithoutAVendorIsReportedAsUnsupportedNotBroken() = runBlocking {
        val transport = FakeTransport(okResponse())
        val result = NvidiaAiClient(transport).testModel("nvapi-key", "llama-3.1-8b-instruct")
        assertEquals(NvidiaStatus.UNSUPPORTED_MODEL, result.status)
        assertEquals(0, transport.calls)
    }

    @Test
    fun aWorkingModelAnswersAndIsReportedUsable() = runBlocking {
        val transport = FakeTransport(okResponse())
        val result = NvidiaAiClient(transport).testModel("nvapi-key", "meta/llama-3.1-8b-instruct")
        assertTrue(result.isSuccess)
        assertEquals(1, transport.calls)
        // The probe must be the cheapest possible: the model is asked to echo one token.
        val body = JSONObject(transport.lastBody!!)
        assertEquals("meta/llama-3.1-8b-instruct", body.getString("model"))
    }

    @Test
    fun anEmptyBodyOnHttp200IsABadResponseNotASuccess() = runBlocking {
        val transport = FakeTransport(NvidiaHttpResponse(200, ""))
        val result = NvidiaAiClient(transport).testModel("nvapi-key", "meta/llama-3.1-8b-instruct")
        assertEquals(NvidiaStatus.BAD_RESPONSE, result.status)
    }

    @Test
    fun anUnknownModelIsReportedAsUnavailable() = runBlocking {
        val transport = FakeTransport(NvidiaHttpResponse(404, """{"error":{"message":"model not found"}}"""))
        val result = NvidiaAiClient(transport).testModel("nvapi-key", "meta/does-not-exist")
        assertEquals(NvidiaStatus.MODEL_UNAVAILABLE, result.status)
    }

    @Test
    fun aRejectedKeyIsClassifiedForTheModelProbeToo() = runBlocking {
        val transport = FakeTransport(NvidiaHttpResponse(401, """{"error":{"message":"unauthorized"}}"""))
        val result = NvidiaAiClient(transport).testModel("bad-key", "meta/llama-3.1-8b-instruct")
        assertEquals(NvidiaStatus.INVALID_KEY, result.status)
    }

    @Test
    fun theAssistantExposesTheModelProbeWithoutSendingProfileData() = runBlocking {
        val transport = FakeTransport(okResponse())
        val assistant = NvidiaJobAssistant(NvidiaAiClient(transport))
        val result = assistant.testModel("nvapi-key", "meta/llama-3.1-8b-instruct")
        assertTrue(result.isSuccess)
        // No CV facts belong in a connectivity probe.
        val body = transport.lastBody!!.lowercase()
        assertFalse(body.contains("candidate"))
        assertFalse(body.contains("cv"))
        assertFalse(body.contains("@"))
    }

    // ---- Region-scoped remote eligibility ---------------------------------

    private val engine = EligibilityEngine()

    @Test
    fun aRemoteRoleScopedToEMEAIsEligibleBecauseEMEAIncludesMadagascar() {
        val assessment = engine.assess(
            EligibilityInput(
                title = "Remote Customer Support Specialist",
                location = "EMEA",
                description = "Work remotely within the EMEA region.",
                isRemote = true,
                locationRestrictedRemote = true,
            ),
        )
        assertEquals(MadagascarEligibility.ELIGIBLE, assessment.eligibility)
        assertTrue(assessment.reasons.any { it.label.contains("region", ignoreCase = true) })
    }

    @Test
    fun aRemoteRoleScopedToAfricaIsEligible() {
        val assessment = engine.assess(
            EligibilityInput(
                title = "Remote Data Entry Clerk",
                location = "Africa",
                description = "Open to candidates across Africa.",
                isRemote = true,
                locationRestrictedRemote = true,
            ),
        )
        assertEquals(MadagascarEligibility.ELIGIBLE, assessment.eligibility)
    }

    @Test
    fun aRemoteRoleScopedToOneForeignCountryStaysUnclear() {
        val assessment = engine.assess(
            EligibilityInput(
                title = "Remote Support Agent",
                location = "United States",
                description = "Remote role, must be based in the US.",
                isRemote = true,
                locationRestrictedRemote = true,
            ),
        )
        assertEquals(MadagascarEligibility.UNCLEAR, assessment.eligibility)
    }

    @Test
    fun aRemoteRoleScopedToEuropeOnlyDoesNotIncludeMadagascar() {
        val assessment = engine.assess(
            EligibilityInput(
                title = "Remote Support Agent",
                location = "Europe",
                description = "Remote within the European Union.",
                isRemote = true,
                locationRestrictedRemote = true,
            ),
        )
        assertEquals(MadagascarEligibility.UNCLEAR, assessment.eligibility)
    }

    @Test
    fun aLocalOnlyRestrictionStillWinsOverARegionMatch() {
        val assessment = engine.assess(
            EligibilityInput(
                title = "Site Operator",
                location = "EMEA",
                description = "Local candidates only. Must already hold a work permit.",
                isRemote = true,
                locationRestrictedRemote = true,
            ),
        )
        assertEquals(MadagascarEligibility.RESTRICTED, assessment.eligibility)
        assertEquals(VisaSupport.LOCAL_ONLY, assessment.visaSupport)
    }

    @Test
    fun theMapperCarriesTheRegionVerdictThroughToTheJob() {
        val mapper = JobMapper(
            matchingEngine = MatchingEngine(),
            eligibilityEngine = EligibilityEngine(),
            clock = { 1_760_000_000_000 },
        )
        val job = mapper.toJob(
            RawJob(
                providerId = "remotive",
                providerName = "Remotive",
                externalId = "emea-1",
                title = "Remote Maintenance Planner",
                company = "Nordic Industrial Services",
                location = "EMEA",
                descriptionHtml = "<p>Remote role within the EMEA region.</p>",
                url = "https://remotive.com/jobs/emea-1",
                isRemote = true,
            ),
            CandidateProfile(fullName = "Jonathan Rakoto", currentTitle = "Maintenance Technician"),
        )
        assertEquals(MadagascarEligibility.ELIGIBLE, job.eligibility)
    }
}
