package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.provider.ArbeitnowProvider
import com.aiglobal.jobfinder.data.jobs.provider.AshbyProvider
import com.aiglobal.jobfinder.data.jobs.provider.EuresProvider
import com.aiglobal.jobfinder.data.jobs.provider.GreenhouseProvider
import com.aiglobal.jobfinder.data.jobs.provider.HimalayasProvider
import com.aiglobal.jobfinder.data.jobs.provider.JobicyProvider
import com.aiglobal.jobfinder.data.jobs.provider.JobProvider
import com.aiglobal.jobfinder.data.jobs.provider.LeverProvider
import com.aiglobal.jobfinder.data.jobs.provider.RemotiveProvider
import com.aiglobal.jobfinder.data.jobs.provider.SmartRecruitersProvider
import com.aiglobal.jobfinder.data.jobs.provider.WorkdayProvider
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import com.aiglobal.jobfinder.data.submissions.SubmissionCapability
import com.aiglobal.jobfinder.data.submissions.SubmissionChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 48 — the real-submission capability audit.
 *
 * The app prepares applications but never sends them, because no source exposes a route
 * the app can drive end-to-end. This test pins that fact to a single source of truth so the
 * claim on screen cannot drift away from the gate the registry actually enforces:
 *
 *  - every registered provider reports `realSubmissionSupported = false`;
 *  - `SubmissionChannel.realSendSupported` is false for every channel, and the helper that
 *    describes the capability reads that same flag rather than a parallel table;
 *  - a source that says it has no submission endpoint can never be marked as sendable.
 *
 * These are plain JVM tests: no network, no credential, no send.
 */
class Stage48ProviderSubmissionCapabilityTest {

    /** The same provider set the app registers at startup (see `AiJobFinderApp`). */
    private fun registeredProviders(): List<JobProvider> = listOf(
        ArbeitnowProvider(),
        RemotiveProvider(),
        JobicyProvider(),
        HimalayasProvider(),
        EuresProvider(),
        WorkdayProvider(),
        GreenhouseProvider(),
        AshbyProvider(),
        LeverProvider(),
        SmartRecruitersProvider(),
    )

    @Test
    fun noRegisteredProviderClaimsItCanSubmit() {
        registeredProviders().forEach { provider ->
            assertFalse(
                "${provider.id} must not claim a submission route the app does not have",
                provider.capabilities.realSubmissionSupported,
            )
        }
    }

    @Test
    fun theCapabilityRecordCannotDisagreeWithTheRegistryGate() {
        // The registry refuses a real send when the channel is not drivable. The helper
        // that tells the user the same thing must read that flag, not a copy of it.
        assertTrue("no channel is drivable today", SubmissionCapability.sendsNothing)
        assertEquals(0, SubmissionCapability.supportedChannelCount)

        enumValues<SubmissionChannel>().forEach { channel ->
            assertFalse(channel.name, channel.realSendSupported)
            assertEquals(channel.compatibilityNote, SubmissionCapability.statement(channel))
        }
    }

    @Test
    fun aProviderThatReportsNoSubmissionEndpointIsNeverMarkedSendable() {
        registeredProviders().forEach { provider ->
            val caps = provider.capabilities
            if (!caps.submissionEndpoint) {
                assertFalse(
                    "${provider.id} reports no submission endpoint, so it cannot be sendable",
                    caps.realSubmissionSupported,
                )
            }
        }
    }

    @Test
    fun aPostingOnAnEmployerAtsBoardIsStillSimulated() {
        // The strongest-looking case: an employer's own applicant-tracking-system board,
        // where the posting applies directly. Even here the app must not send, because it
        // holds no signed-in session on the employer's portal.
        val job = Job(
            id = "gh-1",
            title = "Maintenance Technician",
            company = "Northline Industrial",
            companyAccent = 0xFF1E4FD8,
            country = "Canada",
            countryFlag = "🇨🇦",
            city = "Calgary",
            salaryMin = 58000,
            salaryMax = 74000,
            currencyCode = "CAD",
            salaryPeriod = SalaryPeriod.YEAR,
            matchScore = 92,
            eligibility = MadagascarEligibility.VISA_SPONSORED,
            visaSupport = VisaSupport.FULL_SPONSORSHIP,
            scamRisk = ScamRisk.LOW,
            postedAtEpochMillis = 1_699_999_000_000,
            source = "Industrial career sites",
            sourceUrl = "https://boards.greenhouse.io/redwoodmaterials/jobs/12345",
            workMode = WorkMode.ONSITE,
            employmentType = EmploymentType.FULL_TIME,
            experienceYears = 3,
            matchedSkills = listOf("Preventive maintenance"),
            missingSkills = emptyList(),
            languages = listOf("English (B2+)"),
            summary = "Maintain production line machinery.",
            responsibilities = listOf("Run preventive maintenance"),
            benefits = listOf("Relocation allowance"),
            aiInsight = "Strong technical fit.",
        )

        val channel = SubmissionChannel.infer(job)
        assertEquals(SubmissionChannel.ATS_API, channel)
        assertFalse("an ATS board is still not drivable by the app", channel.realSendSupported)
        assertTrue(SubmissionCapability.statement(channel).contains("will not send"))
    }
}
