package com.aiglobal.jobfinder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import com.aiglobal.jobfinder.data.submissions.SubmissionChannel
import com.aiglobal.jobfinder.data.submissions.SubmissionOutcome
import com.aiglobal.jobfinder.data.submissions.SubmissionRegistry
import com.aiglobal.jobfinder.data.submissions.SubmissionRegistryStore
import com.aiglobal.jobfinder.data.submissions.SubmissionRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Stage 30: the persistent application registry.
 *
 * The behaviour that matters most is that the *same opening* cannot be applied to twice,
 * whatever route it arrives by and even across a restart. These tests drive the real
 * registry over a real on-disk document (a temp file under the test app's cache), so the
 * dedup and the persistence are exercised together rather than against a fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage30SubmissionRegistryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun tempFile(tag: String): File =
        File(context.cacheDir, "submissions-$tag-${System.nanoTime()}.json").also { it.delete() }

    /** A believable maintenance posting; the identity fields drive the dedup key. */
    private fun job(
        id: String = "job-1",
        title: String = "Maintenance Technician",
        company: String = "Northline Industrial",
        country: String = "Canada",
        source: String = "Adzuna",
        sourceUrl: String = "https://www.adzuna.ca/land/ad/12345",
    ) = Job(
        id = id,
        title = title,
        company = company,
        companyAccent = 0xFF1E4FD8,
        country = country,
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
        source = source,
        sourceUrl = sourceUrl,
        workMode = WorkMode.ONSITE,
        employmentType = EmploymentType.FULL_TIME,
        experienceYears = 3,
        matchedSkills = listOf("Preventive maintenance", "Hydraulics"),
        missingSkills = emptyList(),
        languages = listOf("English (B2+)"),
        summary = "Maintain production line machinery across two shifts.",
        responsibilities = listOf("Run preventive maintenance", "Log work orders"),
        benefits = listOf("Relocation allowance"),
        aiInsight = "Strong technical fit.",
    )

    private suspend fun registry(file: File, sender: (suspend (SubmissionRequest) -> Boolean)? = null): SubmissionRegistry {
        val registry = SubmissionRegistry(SubmissionRegistryStore(context, file), realSender = sender)
        registry.load()
        return registry
    }

    // ---- The core guarantee ------------------------------------------------

    @Test
    fun aSecondSubmissionOfTheSameOpeningIsRefused() = runBlocking {
        val registry = registry(tempFile("dup"))
        val posting = job()

        val first = registry.submit(SubmissionRequest(posting))
        assertEquals(SubmissionOutcome.DRY_RUN, first.outcome)
        assertTrue("The first attempt is recorded", first.recorded)

        val second = registry.submit(SubmissionRequest(posting))
        assertEquals(SubmissionOutcome.BLOCKED_DUPLICATE, second.outcome)
        assertFalse("A duplicate must not be recorded", second.recorded)
        assertTrue(second.isDuplicate)

        assertEquals("Exactly one entry must exist", 1, registry.entries.value.size)
    }

    @Test
    fun theSameOpeningFromADifferentSourceIsStillRefused() = runBlocking {
        val registry = registry(tempFile("cross"))
        val fromAdzuna = job(id = "adzuna-9", source = "Adzuna", sourceUrl = "https://www.adzuna.ca/land/ad/1")
        val fromJooble = job(id = "jooble-77", source = "Jooble", sourceUrl = "https://jooble.org/desc/77")

        assertEquals(SubmissionOutcome.DRY_RUN, registry.submit(SubmissionRequest(fromAdzuna)).outcome)
        val second = registry.submit(SubmissionRequest(fromJooble))

        assertEquals(SubmissionOutcome.BLOCKED_DUPLICATE, second.outcome)
        assertEquals("Still one entry for the opening", 1, registry.entries.value.size)
        assertTrue("The refusal must name the other source", second.reason.contains("Adzuna"))
    }

    @Test
    fun theSameOpeningSurvivesARestartAndIsStillRefused() = runBlocking {
        val file = tempFile("restart")

        val firstSession = registry(file)
        assertEquals(SubmissionOutcome.DRY_RUN, firstSession.submit(SubmissionRequest(job())).outcome)

        // A fresh registry over the same file models the app being relaunched.
        val secondSession = registry(file)
        assertNotNull("The entry must have been persisted", secondSession.entryFor(job()))

        val afterRestart = secondSession.submit(SubmissionRequest(job(id = "re-fetched", source = "EURES")))
        assertEquals(SubmissionOutcome.BLOCKED_DUPLICATE, afterRestart.outcome)
        assertEquals(1, secondSession.entries.value.size)
    }

    // ---- Concurrency -------------------------------------------------------

    @Test
    fun concurrentSubmissionsOfTheSameOpeningRecordExactlyOne() = runBlocking {
        val registry = registry(tempFile("concurrent"))
        val posting = job()

        val outcomes = (1..12).map {
            async { registry.submit(SubmissionRequest(posting)).outcome }
        }.awaitAll()

        assertEquals("Only one attempt may be recorded", 1, outcomes.count { it == SubmissionOutcome.DRY_RUN })
        assertEquals("Every other attempt must be refused", 11, outcomes.count { it.isBlocked() })
        assertEquals(1, registry.entries.value.size)
    }

    // ---- Simulation mode and the real-send gate ----------------------------

    @Test
    fun sendingIsOffByDefaultAndNothingIsEverSent() = runBlocking {
        val sender: suspend (SubmissionRequest) -> Boolean = { true }
        val registry = registry(tempFile("sim"), sender)

        assertFalse("Real sending must be off by default", registry.sendingEnabled.value)

        val decision = registry.submit(SubmissionRequest(job()))
        assertEquals(SubmissionOutcome.DRY_RUN, decision.outcome)
        assertTrue(decision.outcome.simulated)
        assertFalse("Nothing was sent", registry.entryFor(job())!!.sent)
    }

    @Test
    fun aRealSendIsRefusedWhileSendingIsNotActivated() = runBlocking {
        val sender: suspend (SubmissionRequest) -> Boolean = { true }
        val registry = registry(tempFile("gate-off"), sender)

        val decision = registry.submit(SubmissionRequest(job(), allowRealSend = true))
        assertEquals(SubmissionOutcome.BLOCKED_NOT_ENABLED, decision.outcome)
        assertFalse(decision.recorded)
        assertEquals(0, registry.entries.value.size)
    }

    @Test
    fun aRealSendIsRefusedForAnIncompatibleChannelEvenWhenActivated() = runBlocking {
        val sender: suspend (SubmissionRequest) -> Boolean = { true }
        val registry = registry(tempFile("gate-channel"), sender)
        registry.setSendingEnabled(true)

        // An email application would need the mailbox; the app must refuse regardless.
        val decision = registry.submit(
            SubmissionRequest(job(), channel = SubmissionChannel.EMAIL, allowRealSend = true),
        )
        assertEquals(SubmissionOutcome.BLOCKED_INCOMPATIBLE, decision.outcome)
        assertFalse(decision.recorded)
        assertEquals(0, registry.entries.value.size)
    }

    @Test
    fun aRealSendIsRefusedWhenNoVerifiedSenderIsWiredIn() = runBlocking {
        val registry = registry(tempFile("gate-nosender")) // no realSender
        registry.setSendingEnabled(true)

        val decision = registry.submit(
            SubmissionRequest(job(), channel = SubmissionChannel.ATS_API, allowRealSend = true),
        )
        assertEquals(SubmissionOutcome.BLOCKED_INCOMPATIBLE, decision.outcome)
        assertFalse(decision.recorded)
    }

    @Test
    fun everyChannelIsCurrentlyIncompatibleWithRealSending() {
        assertTrue(
            "No channel may claim it can send until a verified integration exists",
            SubmissionChannel.values().none { it.realSendSupported },
        )
    }

    // ---- Channel inference and identity ------------------------------------

    @Test
    fun theChannelIsInferredFromThePostingUrl() {
        assertEquals(SubmissionChannel.ATS_API, SubmissionChannel.infer(job(sourceUrl = "https://boards.greenhouse.io/acme/jobs/1")))
        assertEquals(SubmissionChannel.ATS_API, SubmissionChannel.infer(job(sourceUrl = "https://acme.myworkdayjobs.com/careers/job/1")))
        assertEquals(SubmissionChannel.EMAIL, SubmissionChannel.infer(job(sourceUrl = "mailto:hr@acme.example")))
        assertEquals(SubmissionChannel.EMPLOYER_PORTAL, SubmissionChannel.infer(job(sourceUrl = "https://acme.example/careers/apply")))
        assertEquals(SubmissionChannel.UNKNOWN, SubmissionChannel.infer(job(sourceUrl = "")))
    }

    @Test
    fun identityIgnoresCaseAndPunctuationButKeepsDistinctRolesApart() = runBlocking {
        val registry = registry(tempFile("identity"))

        val messy = job(id = "a", company = "Northline  Industrial,", title = "Maintenance  Technician")
        assertEquals(SubmissionOutcome.DRY_RUN, registry.submit(SubmissionRequest(messy)).outcome)

        val tidy = job(id = "b", company = "northline industrial", title = "maintenance technician")
        assertEquals(
            "Case and punctuation must not create a second application",
            SubmissionOutcome.BLOCKED_DUPLICATE,
            registry.submit(SubmissionRequest(tidy)).outcome,
        )

        val differentRole = job(id = "c", title = "Welder")
        assertEquals(
            "A different role at the same employer is a different application",
            SubmissionOutcome.DRY_RUN,
            registry.submit(SubmissionRequest(differentRole)).outcome,
        )
        assertEquals(2, registry.entries.value.size)
    }

    private fun SubmissionOutcome.isBlocked(): Boolean =
        this != SubmissionOutcome.DRY_RUN && this != SubmissionOutcome.SENT
}
