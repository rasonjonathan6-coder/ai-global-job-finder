package com.aiglobal.jobfinder

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import com.aiglobal.jobfinder.ui.screens.submissions.SubmissionHistoryContent
import com.aiglobal.jobfinder.ui.theme.AiJobFinderTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Stage 33 — automated replay of the CV → offers → deduplication → history journey.
 *
 * This is a *validation* stage. No emulator or physical device is available in this
 * environment (there is no `/dev/kvm`, no emulator binary and no system image), so the
 * on-device scenarios are not claimed as run. What *is* run here are the scenarios that can
 * be exercised deterministically off-device, driven through the same code the screens use:
 * the real [SubmissionRegistry] over a real on-disk document (reopened to prove persistence)
 * and the real [SubmissionHistoryContent] composable, rendered under Robolectric.
 *
 * Coverage map (device scenarios from the stage brief):
 *  - 6  a posting seen from two sources does not become two applications
 *  - 7  two genuinely different postings stay distinct
 *  - 8  confirming a duplicate survives a restart
 *  - 9  confirming two distinct postings survives a restart
 *  - 10 the "N to verify" figure equals what is really pending
 *  - 11 the history never shows a "Sent" entry, and a real send cannot produce one
 *
 * The CV import/parse scenarios (2–4) are covered by Stage 28/29, the offer search, filters
 * and match scores (5) by Stage 10/21/24/25, and the offline/API-error behaviour (12) by
 * Stage 22. Nothing here ever sends: `SENT` is unreachable and no credential is touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp-hdpi")
class Stage33JourneyValidationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun tempFile(tag: String): File =
        File(context.filesDir, "submissions33-$tag-${System.nanoTime()}.json").also { it.delete() }

    private suspend fun registry(file: File): SubmissionRegistry {
        val registry = SubmissionRegistry(SubmissionRegistryStore(context, file))
        registry.load()
        return registry
    }

    private fun job(
        id: String = "adzuna:100",
        title: String = "Maintenance Technician",
        company: String = "Northline Industrial",
        country: String = "Canada",
        source: String = "Adzuna",
        sourceUrl: String = "https://www.adzuna.ca/land/ad/100",
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

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent {
            AiJobFinderTheme {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    // ---- 6. one posting from two sources -> one application -----------------

    @Test
    fun scenario6_aPostingReachedFromTwoSourcesDoesNotBecomeTwoApplications() = runBlocking {
        val registry = registry(tempFile("two-sources"))
        val fromAdzuna = job(id = "adzuna:100", source = "Adzuna", sourceUrl = "https://www.adzuna.ca/land/ad/100")
        // The same opening surfaced by another board: different id, tracking-laden URL.
        val fromJooble = job(
            id = "jooble:9",
            source = "Jooble",
            sourceUrl = "https://adzuna.ca/land/ad/100?utm_source=jooble&fbclid=abc#apply",
        )

        assertEquals(SubmissionOutcome.DRY_RUN, registry.submit(SubmissionRequest(fromAdzuna)).outcome)
        assertEquals(SubmissionOutcome.BLOCKED_DUPLICATE, registry.submit(SubmissionRequest(fromJooble)).outcome)

        assertEquals("Only one application is recorded", 1, registry.entries.value.size)
        assertFalse("Nothing is ever sent", registry.entries.value.any { it.sent })
    }

    // ---- 7. two different postings stay distinct ----------------------------

    @Test
    fun scenario7_twoGenuinelyDifferentPostingsStayDistinct() = runBlocking {
        val registry = registry(tempFile("distinct"))
        val technician = job(id = "adzuna:100", title = "Maintenance Technician")
        val supervisor = job(
            id = "adzuna:101",
            title = "Warehouse Supervisor",
            sourceUrl = "https://www.adzuna.ca/land/ad/101",
        )

        assertEquals(SubmissionOutcome.DRY_RUN, registry.submit(SubmissionRequest(technician)).outcome)
        assertEquals(SubmissionOutcome.DRY_RUN, registry.submit(SubmissionRequest(supervisor)).outcome)

        assertEquals("Both postings are kept", 2, registry.entries.value.size)
        assertNotEquals(
            "They are recorded under different keys",
            registry.entries.value[0].key,
            registry.entries.value[1].key,
        )
    }

    // ---- 8. confirming a duplicate persists across a restart ----------------

    @Test
    fun scenario8_confirmingADuplicatePersistsAcrossARestart() = runBlocking {
        val file = tempFile("confirm-duplicate")
        val first = registry(file)
        val existing = job(id = "adzuna:100", title = "Maintenance Technician")
        val close = job(
            id = "jooble:7",
            title = "Maintenance Technicians",
            source = "Jooble",
            sourceUrl = "https://jooble.org/desc/7",
        )

        first.submit(SubmissionRequest(existing))
        assertEquals(SubmissionOutcome.NEEDS_VERIFICATION, first.submit(SubmissionRequest(close)).outcome)
        val parked = first.entries.value.first { it.awaitsVerification }

        first.confirmSame(parked.key, parked.pendingReview)
        assertEquals("The two collapse into one", 1, first.entries.value.size)

        val reopened = registry(file)
        assertEquals("The duplicate decision survives the restart", 1, reopened.entries.value.size)
        assertEquals("Nothing is still pending", 0, reopened.pendingVerifications().size)
        assertEquals(
            "The same opening is refused again after restart",
            SubmissionOutcome.BLOCKED_DUPLICATE,
            reopened.submit(SubmissionRequest(close)).outcome,
        )
    }

    // ---- 9. confirming distinct postings persists across a restart ----------

    @Test
    fun scenario9_confirmingDistinctPostingsPersistsAcrossARestart() = runBlocking {
        val file = tempFile("confirm-distinct")
        val first = registry(file)
        val existing = job(id = "adzuna:100", title = "Maintenance Technician")
        val close = job(
            id = "jooble:7",
            title = "Maintenance Technicians",
            source = "Jooble",
            sourceUrl = "https://jooble.org/desc/7",
        )

        first.submit(SubmissionRequest(existing))
        assertEquals(SubmissionOutcome.NEEDS_VERIFICATION, first.submit(SubmissionRequest(close)).outcome)
        val parked = first.entries.value.first { it.awaitsVerification }

        first.confirmDistinct(parked.key)
        assertEquals("Both postings remain", 2, first.entries.value.size)

        val reopened = registry(file)
        assertEquals("Both postings survive the restart", 2, reopened.entries.value.size)
        assertEquals("Nothing is pending after the verdict", 0, reopened.pendingVerifications().size)
        assertTrue("No entry is awaiting verification", reopened.entries.value.none { it.awaitsVerification })
        assertEquals(
            "A second record for the same opening is still refused",
            SubmissionOutcome.BLOCKED_DUPLICATE,
            reopened.submit(SubmissionRequest(close)).outcome,
        )
    }

    // ---- 10. the "N to verify" figure matches what is really pending --------

    @Test
    fun scenario10_theToVerifyCounterMatchesWhatIsActuallyPending() = runBlocking {
        val registry = registry(tempFile("counter"))
        val existing = job(id = "adzuna:100", title = "Maintenance Technician")
        val close = job(
            id = "jooble:7",
            title = "Maintenance Technicians",
            source = "Jooble",
            sourceUrl = "https://jooble.org/desc/7",
        )

        registry.submit(SubmissionRequest(existing))
        registry.submit(SubmissionRequest(close))

        // The figure the Profile pill and the history screen both derive: one real pending
        // posting, counted the same way the app counts it.
        val shownCount = registry.entries.value.count { it.awaitsVerification }
        assertEquals("Exactly one posting is pending", 1, shownCount)
        assertEquals("The counter equals the pending list", registry.pendingVerifications().size, shownCount)

        // The history screen surfaces the same pending concept.
        render {
            SubmissionHistoryContent(
                entries = registry.entries.value,
                sendingEnabled = false,
                onBack = {},
            )
        }
        composeRule.onNodeWithText("Need verification").assertIsDisplayed()
        composeRule.onNodeWithText("Verify").assertIsDisplayed()

        // Resolving the posting drops the counter to zero.
        val parked = registry.entries.value.first { it.awaitsVerification }
        registry.confirmDistinct(parked.key)
        assertEquals("Nothing pending once resolved", 0, registry.entries.value.count { it.awaitsVerification })
    }

    // ---- 11. the history never shows a "Sent" entry -------------------------

    @Test
    fun scenario11_theHistoryNeverShowsASentEntry() = runBlocking {
        val registry = registry(tempFile("no-sent"))
        val a = job(id = "adzuna:100", title = "Maintenance Technician")
        val b = job(id = "adzuna:101", title = "Warehouse Supervisor", sourceUrl = "https://www.adzuna.ca/land/ad/101")
        registry.submit(SubmissionRequest(a))
        registry.submit(SubmissionRequest(b))

        assertTrue("No recorded attempt is sent", registry.entries.value.none { it.sent })
        assertTrue(
            "No outcome is SENT",
            registry.entries.value.none { it.outcome == SubmissionOutcome.SENT },
        )

        // Even explicitly requesting a real send, with sending switched on, cannot reach
        // SENT here: every channel the app can infer is incompatible, so nothing is sent.
        registry.setSendingEnabled(true)
        val forced = registry.submit(
            SubmissionRequest(
                job(id = "adzuna:102", title = "Millwright", sourceUrl = "https://www.adzuna.ca/land/ad/102"),
                channel = SubmissionChannel.EMPLOYER_PORTAL,
                allowRealSend = true,
            ),
        )
        assertEquals(SubmissionOutcome.BLOCKED_INCOMPATIBLE, forced.outcome)
        assertTrue("Still nothing sent", registry.entries.value.none { it.sent })

        render {
            SubmissionHistoryContent(
                entries = registry.entries.value,
                sendingEnabled = true,
                onBack = {},
            )
        }
        composeRule.onNodeWithText("Every attempt, simulated and blocked — nothing is ever sent")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Sent").assertDoesNotExist()
    }
}
