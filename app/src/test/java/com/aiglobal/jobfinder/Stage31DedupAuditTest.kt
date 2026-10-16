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
import com.aiglobal.jobfinder.data.submissions.CanonicalUrl
import com.aiglobal.jobfinder.data.submissions.IdentityRelation
import com.aiglobal.jobfinder.data.submissions.SubmissionChannel
import com.aiglobal.jobfinder.data.submissions.SubmissionIdentity
import com.aiglobal.jobfinder.data.submissions.SubmissionOutcome
import com.aiglobal.jobfinder.data.submissions.SubmissionRegistry
import com.aiglobal.jobfinder.data.submissions.SubmissionRegistryStore
import com.aiglobal.jobfinder.data.submissions.SubmissionRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Stage 31: the deduplication audit.
 *
 * These tests pin down where the registry must recognise the *same* opening and where it must
 * keep two genuinely different postings apart. The identity is layered — source record id,
 * canonical URL, then a normalised employer+title+country fingerprint — so the cases below
 * cover each layer, plus the "close but not provable" middle ground, which must never be
 * merged silently: it is parked for the user to confirm.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage31DedupAuditTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun tempFile(tag: String): File =
        File(context.cacheDir, "submissions31-$tag-${System.nanoTime()}.json").also { it.delete() }

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

    private suspend fun registry(file: File): SubmissionRegistry {
        val registry = SubmissionRegistry(SubmissionRegistryStore(context, file))
        registry.load()
        return registry
    }

    // ---- URL canonicalisation ---------------------------------------------

    @Test
    fun trackingNoiseAndFragmentsDoNotChangeTheCanonicalUrl() {
        val clean = CanonicalUrl.of("https://www.adzuna.ca/land/ad/100")
        val noisy = CanonicalUrl.of("https://adzuna.ca/land/ad/100?utm_source=email&utm_campaign=x#apply")
        assertEquals(clean, noisy)
        assertEquals("adzuna.ca/land/ad/100", clean)
    }

    @Test
    fun differentHostsStayDifferent() {
        val adzuna = CanonicalUrl.of("https://www.adzuna.ca/land/ad/100")
        val employer = CanonicalUrl.of("https://careers.northline.example/jobs/100")
        assertNotEquals(adzuna, employer)
    }

    // ---- Same opening, different URLs -------------------------------------

    @Test
    fun theSameOpeningWithDifferentUrlsIsRefused() = runBlocking {
        val registry = registry(tempFile("same-urls"))
        // Same employer, title and country, but a different board id and URL: the fingerprint
        // is what links them, because nothing else can.
        val fromBoard = job(id = "adzuna:100", sourceUrl = "https://www.adzuna.ca/land/ad/100")
        val fromEmployer = job(
            id = "eures:9001",
            source = "EURES",
            sourceUrl = "https://careers.northline.example/jobs/maintenance-technician",
        )

        assertEquals(SubmissionOutcome.DRY_RUN, registry.submit(SubmissionRequest(fromBoard)).outcome)
        val second = registry.submit(SubmissionRequest(fromEmployer))

        assertEquals(SubmissionOutcome.BLOCKED_DUPLICATE, second.outcome)
        assertEquals("Still a single entry", 1, registry.entries.value.size)
    }

    @Test
    fun aSharedCanonicalUrlIsEnoughToCatchADuplicate() = runBlocking {
        val registry = registry(tempFile("url-wins"))
        val first = job(id = "adzuna:100", sourceUrl = "https://www.adzuna.ca/land/ad/100")
        // Different board id and slightly different metadata, but the exact same posting URL.
        val second = job(
            id = "jooble:555",
            title = "Maintenance Technician (Nights)",
            source = "Jooble",
            sourceUrl = "https://www.adzuna.ca/land/ad/100?utm_source=jooble",
        )

        assertEquals(SubmissionOutcome.DRY_RUN, registry.submit(SubmissionRequest(first)).outcome)
        assertEquals(SubmissionOutcome.BLOCKED_DUPLICATE, registry.submit(SubmissionRequest(second)).outcome)
    }

    @Test
    fun aSharedRecordIdIsEnoughToCatchADuplicate() = runBlocking {
        val registry = registry(tempFile("id-wins"))
        val first = job(id = "eures:42", sourceUrl = "https://eures.example/jobs/42")
        val second = job(
            id = "eures:42",
            title = "Maintenance Technician — relocated",
            sourceUrl = "https://eures.example/jobs/42-v2",
        )

        assertEquals(SubmissionOutcome.DRY_RUN, registry.submit(SubmissionRequest(first)).outcome)
        assertEquals(SubmissionOutcome.BLOCKED_DUPLICATE, registry.submit(SubmissionRequest(second)).outcome)
    }

    // ---- Distinct postings -------------------------------------------------

    @Test
    fun twoDifferentRolesAtTheSameEmployerStayDistinct() = runBlocking {
        val registry = registry(tempFile("distinct-roles"))
        val technician = job(id = "adzuna:100", title = "Maintenance Technician")
        val engineer = job(id = "adzuna:101", title = "Electrical Engineer", sourceUrl = "https://www.adzuna.ca/land/ad/101")

        assertEquals(SubmissionOutcome.DRY_RUN, registry.submit(SubmissionRequest(technician)).outcome)
        assertEquals(SubmissionOutcome.DRY_RUN, registry.submit(SubmissionRequest(engineer)).outcome)
        assertEquals("Both roles are recorded separately", 2, registry.entries.value.size)
    }

    @Test
    fun theSameRoleInTwoCountriesStaysDistinct() = runBlocking {
        val registry = registry(tempFile("distinct-countries"))
        val canada = job(id = "adzuna:100", country = "Canada")
        val germany = job(id = "adzuna:200", country = "Germany", sourceUrl = "https://www.adzuna.de/land/ad/200")

        assertEquals(SubmissionOutcome.DRY_RUN, registry.submit(SubmissionRequest(canada)).outcome)
        assertEquals(SubmissionOutcome.DRY_RUN, registry.submit(SubmissionRequest(germany)).outcome)
        assertEquals(2, registry.entries.value.size)
    }

    // ---- Ambiguous identities ---------------------------------------------

    @Test
    fun aNearlyIdenticalTitleIsParkedForVerification() = runBlocking {
        val registry = registry(tempFile("close-title"))
        val existing = job(id = "adzuna:100", title = "Maintenance Technician")
        val nearDuplicate = job(id = "jooble:7", title = "Maintenance Technicians", source = "Jooble", sourceUrl = "https://jooble.org/desc/7")

        assertEquals(SubmissionOutcome.DRY_RUN, registry.submit(SubmissionRequest(existing)).outcome)
        val decision = registry.submit(SubmissionRequest(nearDuplicate))

        assertEquals(SubmissionOutcome.NEEDS_VERIFICATION, decision.outcome)
        assertTrue("The ambiguous attempt is parked, not sent", decision.recorded)
        val parked = registry.entries.value.first { it.awaitsVerification }
        assertTrue("It points at the entry to compare against", parked.pendingReview.isNotBlank())
        assertEquals("Nothing was sent", false, parked.sent)
    }

    @Test
    fun aSlightlyDifferentEmployerNameIsParkedForVerification() = runBlocking {
        val registry = registry(tempFile("close-employer"))
        val existing = job(id = "adzuna:100", company = "Northline Industrial")
        val nearDuplicate = job(id = "jooble:8", company = "Northline Industries", source = "Jooble", sourceUrl = "https://jooble.org/desc/8")

        assertEquals(SubmissionOutcome.DRY_RUN, registry.submit(SubmissionRequest(existing)).outcome)
        assertEquals(SubmissionOutcome.NEEDS_VERIFICATION, registry.submit(SubmissionRequest(nearDuplicate)).outcome)
    }

    @Test
    fun confirmingTheSameFoldsTheEntryAndRefusesASecondSubmission() = runBlocking {
        val registry = registry(tempFile("confirm-same"))
        val existing = job(id = "adzuna:100", title = "Maintenance Technician")
        val nearDuplicate = job(id = "jooble:7", title = "Maintenance Technicians", source = "Jooble", sourceUrl = "https://jooble.org/desc/7")

        registry.submit(SubmissionRequest(existing))
        registry.submit(SubmissionRequest(nearDuplicate))
        val parked = registry.entries.value.first { it.awaitsVerification }

        registry.confirmSame(parked.key, parked.pendingReview)

        assertEquals("The parked entry is folded into the original", 1, registry.entries.value.size)
        assertEquals("No entry is left awaiting verification", 0, registry.pendingVerifications().size)
        // The very same posting must still be refused afterwards.
        assertEquals(SubmissionOutcome.BLOCKED_DUPLICATE, registry.submit(SubmissionRequest(nearDuplicate)).outcome)
    }

    @Test
    fun confirmingDistinctKeepsBothAndPreventsASecondRecordOfTheNewOne() = runBlocking {
        val registry = registry(tempFile("confirm-distinct"))
        val existing = job(id = "adzuna:100", title = "Maintenance Technician")
        val nearDuplicate = job(id = "jooble:7", title = "Maintenance Technicians", source = "Jooble", sourceUrl = "https://jooble.org/desc/7")

        registry.submit(SubmissionRequest(existing))
        registry.submit(SubmissionRequest(nearDuplicate))
        val parked = registry.entries.value.first { it.awaitsVerification }

        registry.confirmDistinct(parked.key)

        assertEquals("Both postings now stand on their own", 2, registry.entries.value.size)
        assertEquals(0, registry.pendingVerifications().size)
        assertEquals(
            "The newly confirmed posting cannot be recorded twice",
            SubmissionOutcome.BLOCKED_DUPLICATE,
            registry.submit(SubmissionRequest(nearDuplicate)).outcome,
        )
    }

    @Test
    fun reSubmittingAParkedPostingStaysPendingRatherThanBecomingADuplicate() = runBlocking {
        val registry = registry(tempFile("re-park"))
        val existing = job(id = "adzuna:100", title = "Maintenance Technician")
        val nearDuplicate = job(id = "jooble:7", title = "Maintenance Technicians", source = "Jooble", sourceUrl = "https://jooble.org/desc/7")

        registry.submit(SubmissionRequest(existing))
        registry.submit(SubmissionRequest(nearDuplicate))
        val again = registry.submit(SubmissionRequest(nearDuplicate))

        assertEquals(SubmissionOutcome.NEEDS_VERIFICATION, again.outcome)
        assertEquals("No second parked entry", 1, registry.pendingVerifications().size)
    }

    // ---- Identity relation, unit level ------------------------------------

    @Test
    fun identityRelationSeparatesSameAmbiguousAndDistinct() {
        val base = SubmissionIdentity(
            providerRecordId = "adzuna:100",
            canonicalUrl = "adzuna.ca/land/ad/100",
            company = "northline industrial",
            title = "maintenance technician",
            country = "canada",
        )
        val same = base.copy(providerRecordId = "jooble:1", canonicalUrl = "jooble.org/desc/1")
        val ambiguous = base.copy(
            providerRecordId = "jooble:2",
            canonicalUrl = "jooble.org/desc/2",
            title = "maintenance technicians",
        )
        val distinct = base.copy(
            providerRecordId = "jooble:3",
            canonicalUrl = "jooble.org/desc/3",
            title = "electrical engineer",
        )

        assertEquals(IdentityRelation.SAME, base.relationTo(same))
        assertEquals(IdentityRelation.AMBIGUOUS, base.relationTo(ambiguous))
        assertEquals(IdentityRelation.DISTINCT, base.relationTo(distinct))
    }

    // ---- Persistence and concurrency preserved ----------------------------

    @Test
    fun anAmbiguousDecisionSurvivesARestart() = runBlocking {
        val file = tempFile("restart")
        val first = registry(file)
        val existing = job(id = "adzuna:100", title = "Maintenance Technician")
        val nearDuplicate = job(id = "jooble:7", title = "Maintenance Technicians", source = "Jooble", sourceUrl = "https://jooble.org/desc/7")
        first.submit(SubmissionRequest(existing))
        first.submit(SubmissionRequest(nearDuplicate))

        val reopened = registry(file)
        assertEquals("The parked entry is still there", 1, reopened.pendingVerifications().size)
        assertEquals(SubmissionOutcome.NEEDS_VERIFICATION, reopened.submit(SubmissionRequest(nearDuplicate)).outcome)
    }

    @Test
    fun concurrentSubmissionsOfOneOpeningRecordExactlyOne() = runBlocking {
        val registry = registry(tempFile("concurrent"))
        val posting = job(id = "adzuna:100")

        val outcomes = (1..12).map {
            async { registry.submit(SubmissionRequest(posting)).outcome }
        }.awaitAll()

        assertEquals("Exactly one attempt is recorded", 1, outcomes.count { it == SubmissionOutcome.DRY_RUN })
        assertEquals("The rest are refused as duplicates", 11, outcomes.count { it == SubmissionOutcome.BLOCKED_DUPLICATE })
        assertEquals(1, registry.entries.value.size)
    }

    // ---- Safety posture unchanged -----------------------------------------

    @Test
    fun sendingStaysOffAndNoChannelCanReallySend() = runBlocking {
        val registry = registry(tempFile("safety"))
        assertFalse("Real sending is off on a fresh registry", registry.sendingEnabled.value)
        assertTrue("No channel claims real-send support", SubmissionChannel.entries.none { it.realSendSupported })

        val posting = job(id = "adzuna:100")
        val requested = registry.submit(SubmissionRequest(posting, allowRealSend = true))
        assertEquals(SubmissionOutcome.BLOCKED_NOT_ENABLED, requested.outcome)
        assertTrue("Nothing is sent even when a real send is requested", registry.entries.value.none { it.sent })
    }
}
