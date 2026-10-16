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
import com.aiglobal.jobfinder.data.notifications.JobNotificationCenter
import com.aiglobal.jobfinder.data.notifications.JobNotificationPolicy
import com.aiglobal.jobfinder.data.notifications.JobNotificationsCoordinator
import com.aiglobal.jobfinder.data.notifications.NotificationHistoryStore
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Stage 32: the deduplication and verification journey, end to end.
 *
 * This is a *validation* stage, so these tests are deliberately behavioural rather than
 * structural: each one drives a user-visible outcome through the real registry over a real
 * on-disk document (a temp file under the test app's files dir), and the store is reopened
 * to prove persistence rather than asserting on an in-memory shortcut. Together they pin
 * the ten guarantees the stage is meant to protect, plus the notification rule that a
 * notification is announced once and never causes a submission.
 *
 * Real sending stays impossible throughout: `SENT` is unreachable, and the last test proves
 * even requesting a real send cannot mark a pending posting as sent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage32DedupJourneyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun tempFile(tag: String): File =
        File(context.filesDir, "submissions32-$tag-${System.nanoTime()}.json").also { it.delete() }

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

    // ---- 1. Same providerRecordId -> identical ------------------------------

    @Test
    fun one_sameRecordIdIsRecognisedAsIdenticalEvenWithDifferentMetadata() = runBlocking {
        val registry = registry(tempFile("record-id"))
        val first = job(id = "eures:42", sourceUrl = "https://eures.example/jobs/42")
        // Same board record, but the title and employer text drifted and the URL changed.
        val again = job(
            id = "eures:42",
            title = "Maintenance Technician (Site B)",
            company = "Northline Industrial Group",
            sourceUrl = "https://eures.example/jobs/42-reposted",
        )

        assertEquals(SubmissionOutcome.DRY_RUN, registry.submit(SubmissionRequest(first)).outcome)
        assertEquals(SubmissionOutcome.BLOCKED_DUPLICATE, registry.submit(SubmissionRequest(again)).outcome)
        assertEquals(1, registry.entries.value.size)
        assertFalse("Nothing is ever sent", registry.entries.value.any { it.sent })
    }

    // ---- 2. Same canonical URL, different tracking -> identical -------------

    @Test
    fun two_sameCanonicalUrlWithTrackingNoiseIsIdentical() = runBlocking {
        val registry = registry(tempFile("url-noise"))
        val clean = job(id = "adzuna:100", sourceUrl = "https://www.adzuna.ca/land/ad/100")
        val tracked = job(
            id = "jooble:9",
            title = "Maintenance Technician — urgent",
            source = "Jooble",
            sourceUrl = "https://adzuna.ca/land/ad/100?utm_source=jooble&utm_campaign=digest&fbclid=abc#apply",
        )

        assertEquals(SubmissionOutcome.DRY_RUN, registry.submit(SubmissionRequest(clean)).outcome)
        assertEquals(SubmissionOutcome.BLOCKED_DUPLICATE, registry.submit(SubmissionRequest(tracked)).outcome)
        assertEquals(1, registry.entries.value.size)
    }

    @Test
    fun three_canonicalUrlDropsTrackingButKeepsRealParameters() {
        assertEquals("adzuna.ca/land/ad/100", CanonicalUrl.of("https://www.adzuna.ca/land/ad/100?utm_source=x#y"))
        assertEquals(
            "employer.example/apply?job=7",
            CanonicalUrl.of("https://employer.example/apply?job=7&utm_medium=email"),
        )
        assertEquals(
            "employer.example/apply?a=1&b=2",
            CanonicalUrl.of("https://employer.example/apply?b=2&a=1"),
        )
    }

    // ---- 3. Two distinct postings of one employer stay distinct ------------

    @Test
    fun four_distinctPostingsOfOneEmployerStayDistinct() = runBlocking {
        val registry = registry(tempFile("distinct"))
        val roles = listOf(
            job(id = "adzuna:100", title = "Maintenance Technician", sourceUrl = "https://www.adzuna.ca/land/ad/100"),
            job(id = "adzuna:101", title = "Electrical Engineer", sourceUrl = "https://www.adzuna.ca/land/ad/101"),
            job(id = "adzuna:102", title = "Welder", sourceUrl = "https://www.adzuna.ca/land/ad/102"),
            job(id = "adzuna:103", title = "Maintenance planner", sourceUrl = "https://www.adzuna.ca/land/ad/103"),
        )

        roles.forEach { role ->
            assertEquals(
                "Each distinct role is recorded on its own",
                SubmissionOutcome.DRY_RUN,
                registry.submit(SubmissionRequest(role)).outcome,
            )
        }
        assertEquals(4, registry.entries.value.size)
        assertEquals(4, registry.entries.value.map { it.key }.distinct().size)
    }

    // ---- 4. Ambiguous -> NEEDS_VERIFICATION, never a confirmed duplicate ---

    @Test
    fun five_anAmbiguousPostingIsParkedAndNotPresentedAsAConfirmedDuplicate() = runBlocking {
        val registry = registry(tempFile("ambiguous"))
        val existing = job(id = "adzuna:100", title = "Maintenance Technician")
        val close = job(
            id = "jooble:7",
            title = "Maintenance Technicians",
            source = "Jooble",
            sourceUrl = "https://jooble.org/desc/7",
        )

        registry.submit(SubmissionRequest(existing))
        val decision = registry.submit(SubmissionRequest(close))

        assertEquals(SubmissionOutcome.NEEDS_VERIFICATION, decision.outcome)
        assertFalse("Not refused as a duplicate", decision.isDuplicate)
        assertTrue("The ambiguous attempt is recorded for review", decision.recorded)
        val parked = registry.entries.value.first { it.key == decision.key.value }
        assertTrue(parked.awaitsVerification)
        assertEquals("It points at the entry to compare", "northline industrial|maintenance technician|canada", parked.pendingReview)
        assertEquals("Nothing was sent", false, parked.sent)
        assertEquals("The original is untouched", 2, registry.entries.value.size)
    }

    // ---- 5. confirmSame merges signals, keeps aliases ----------------------

    @Test
    fun six_confirmSameMergesSignalsAndKeepsAliasesForALaterImport() = runBlocking {
        val registry = registry(tempFile("confirm-same"))
        val existing = job(id = "adzuna:100", title = "Maintenance Technician")
        val close = job(id = "jooble:7", title = "Maintenance Technicians", source = "Jooble", sourceUrl = "https://jooble.org/desc/7")

        registry.submit(SubmissionRequest(existing))
        registry.submit(SubmissionRequest(close))
        val parked = registry.entries.value.first { it.awaitsVerification }

        registry.confirmSame(parked.key, parked.pendingReview)

        val survivor = registry.entries.value.single()
        assertTrue("The parked posting's record id is kept as an alias", survivor.aliases.contains("jooble:7"))
        assertTrue("Its canonical URL is kept too", survivor.aliases.contains("jooble.org/desc/7"))
        assertTrue("Both sources are recorded", survivor.seenSources.containsAll(listOf("Adzuna", "Jooble")))
        assertFalse("Still nothing sent", survivor.sent)

        // A future import of the same opening under the jooble alias is refused.
        assertEquals(SubmissionOutcome.BLOCKED_DUPLICATE, registry.submit(SubmissionRequest(close)).outcome)
        assertEquals(1, registry.entries.value.size)
    }

    @Test
    fun seven_aliasIsHonouredAfterARealRestart() = runBlocking {
        val file = tempFile("alias-restart")
        val first = registry(file)
        val existing = job(id = "adzuna:100", title = "Maintenance Technician")
        val close = job(id = "jooble:7", title = "Maintenance Technicians", source = "Jooble", sourceUrl = "https://jooble.org/desc/7")
        first.submit(SubmissionRequest(existing))
        first.submit(SubmissionRequest(close))
        val parked = first.entries.value.first { it.awaitsVerification }
        first.confirmSame(parked.key, parked.pendingReview)

        // Brand-new registry over the same document: the alias must have been persisted.
        val reopened = registry(file)
        assertEquals(SubmissionOutcome.BLOCKED_DUPLICATE, reopened.submit(SubmissionRequest(close)).outcome)
        assertEquals(1, reopened.entries.value.size)
    }

    // ---- 6. confirmDistinct survives close and restart ---------------------

    @Test
    fun eight_confirmDistinctKeepsBothPostingsAcrossARestart() = runBlocking {
        val file = tempFile("distinct-restart")
        val first = registry(file)
        val existing = job(id = "adzuna:100", title = "Maintenance Technician")
        val close = job(id = "jooble:7", title = "Maintenance Technicians", source = "Jooble", sourceUrl = "https://jooble.org/desc/7")
        first.submit(SubmissionRequest(existing))
        first.submit(SubmissionRequest(close))
        val parked = first.entries.value.first { it.awaitsVerification }
        first.confirmDistinct(parked.key)

        assertEquals("Both postings stand", 2, first.entries.value.size)
        assertEquals("Nothing left pending", 0, first.pendingVerifications().size)

        val reopened = registry(file)
        assertEquals("Both still stand after restart", 2, reopened.entries.value.size)
        assertEquals("No entry is pending after restart", 0, reopened.pendingVerifications().size)
        assertTrue("No entry is awaiting verification", reopened.entries.value.none { it.awaitsVerification })
        // And the promoted posting cannot be recorded a second time.
        assertEquals(SubmissionOutcome.BLOCKED_DUPLICATE, reopened.submit(SubmissionRequest(close)).outcome)
    }

    // ---- 7. A pending posting cannot be sent or marked sent ----------------

    @Test
    fun nine_aPendingPostingCanNeverBeSentEvenWithRealSendRequested() = runBlocking {
        val file = tempFile("pending-not-sendable")
        val registry = registry(file)
        val existing = job(id = "adzuna:100", title = "Maintenance Technician")
        val close = job(id = "jooble:7", title = "Maintenance Technicians", source = "Jooble", sourceUrl = "https://jooble.org/desc/7")
        registry.submit(SubmissionRequest(existing))
        registry.submit(SubmissionRequest(close))

        // Ask to really send the pending posting, with sending "activated".
        registry.setSendingEnabled(true)
        val requested = registry.submit(SubmissionRequest(close, allowRealSend = true))

        assertEquals("Ambiguity is resolved before anything else", SubmissionOutcome.NEEDS_VERIFICATION, requested.outcome)
        assertFalse(requested.recorded)
        assertEquals("Still exactly one parked entry", 1, registry.pendingVerifications().size)
        assertTrue("Nothing anywhere is marked sent", registry.entries.value.none { it.sent })
    }

    // ---- 8. Notifications fire once and never submit -----------------------

    @Test
    fun ten_notificationsFireOncePerPostingAndNeverCreateASubmission() = runBlocking {
        val registryFile = tempFile("notify-registry")
        val registry = registry(registryFile)
        val posting = job(id = "adzuna:100")

        val history = NotificationHistoryStore(context, tempFile("notify-history") )
        val coordinator = JobNotificationsCoordinator(
            center = JobNotificationCenter(context),
            history = history,
            policy = JobNotificationPolicy(minMatchScore = 65, maxPerRun = 3),
        )

        val first = coordinator.notifyNewMatches(listOf(posting), enabled = true)
        val second = coordinator.notifyNewMatches(listOf(posting), enabled = true)
        val third = coordinator.notifyNewMatches(listOf(posting), enabled = true)

        assertEquals("Announced once", 1, first.size)
        assertTrue("Never repeated", second.isEmpty() && third.isEmpty())
        assertTrue("The notification decision never touches the registry", registry.entries.value.isEmpty())
        assertFalse("And therefore never sends", registry.entries.value.any { it.sent })
    }

    // ---- 10. Concurrent mutations lose no data and double no entry ---------

    @Test
    fun eleven_concurrentMutationsNeverLoseDataOrDuplicateEntries() = runBlocking {
        val file = tempFile("concurrent-mutations")
        val registry = registry(file)
        val distinctRoles = (1..8).map { n ->
            job(id = "adzuna:$n", title = "Role $n", sourceUrl = "https://www.adzuna.ca/land/ad/$n")
        }
        val sameOpening = job(id = "adzuna:100")

        // Many distinct postings submitted at once, plus a storm on one opening.
        val distinctDeferred = distinctRoles.map { role -> async { registry.submit(SubmissionRequest(role)).outcome } }
        val stormDeferred = (1..12).map { async { registry.submit(SubmissionRequest(sameOpening)).outcome } }
        val distinctOutcomes = distinctDeferred.awaitAll()
        val stormOutcomes = stormDeferred.awaitAll()

        assertEquals("Every distinct role is recorded once", 8, distinctOutcomes.count { it == SubmissionOutcome.DRY_RUN })
        assertEquals("Only one of the storm is recorded", 1, stormOutcomes.count { it == SubmissionOutcome.DRY_RUN })
        assertEquals("The rest of the storm is refused", 11, stormOutcomes.count { it == SubmissionOutcome.BLOCKED_DUPLICATE })
        assertEquals("All keys are unique", registry.entries.value.size, registry.entries.value.map { it.key }.distinct().size)
        assertEquals("Nothing was lost", 9, registry.entries.value.size)

        // Reopen: the on-disk document must match, with nothing lost.
        val reopened = registry(file)
        assertEquals("Persistence keeps every entry", registry.entries.value.size, reopened.entries.value.size)
        assertTrue(reopened.entries.value.none { it.sent })
    }

    // ---- Identity relation, at the unit level ------------------------------

    @Test
    fun twelve_identityRelationNeverCallsTwoDistinctRolesTheSame() {
        val technician = SubmissionIdentity.of(
            job(id = "adzuna:100", title = "Maintenance Technician", sourceUrl = "https://www.adzuna.ca/land/ad/100"),
        )
        val engineer = SubmissionIdentity.of(
            job(id = "adzuna:101", title = "Electrical Engineer", sourceUrl = "https://www.adzuna.ca/land/ad/101"),
        )
        assertEquals("Two different roles are distinct", IdentityRelation.DISTINCT, technician.relationTo(engineer))
        assertFalse("Not identical", technician.matchesExactly(engineer))
        assertFalse("Not ambiguous", technician.matchesAmbiguously(engineer))
    }

    @Test
    fun thirteen_aBlankPostingDoesNotCollideWithEverythingElse() = runBlocking {
        val registry = registry(tempFile("blank"))
        // A posting with no employer falls back to the title; two unrelated blanks must not merge
        // with a real posting just because a field is empty.
        val real = job(id = "adzuna:100", title = "Maintenance Technician", company = "Northline Industrial")
        val blankCompany = job(id = "jooble:zz", title = "Data Entry Clerk", company = "", source = "Jooble", sourceUrl = "https://jooble.org/desc/zz")

        assertEquals(SubmissionOutcome.DRY_RUN, registry.submit(SubmissionRequest(real)).outcome)
        assertEquals(SubmissionOutcome.DRY_RUN, registry.submit(SubmissionRequest(blankCompany)).outcome)
        assertEquals(2, registry.entries.value.size)
    }

    // ---- Safety invariant --------------------------------------------------

    @Test
    fun fourteen_theSafetyPostureIsUnchanged() = runBlocking {
        val registry = registry(tempFile("safety"))
        assertFalse("Sending is off on a fresh registry", registry.sendingEnabled.value)
        assertTrue("No channel can really send", SubmissionChannel.entries.none { it.realSendSupported })

        val posting = job(id = "adzuna:100")
        val blocked = registry.submit(SubmissionRequest(posting, allowRealSend = true))
        assertEquals(SubmissionOutcome.BLOCKED_NOT_ENABLED, blocked.outcome)
        assertFalse("A refused real send records nothing", blocked.recorded)
        assertTrue("Nothing was recorded at all", registry.entries.value.isEmpty())

        // The simulated path is the only one reachable.
        val simulated = registry.submit(SubmissionRequest(posting))
        assertEquals(SubmissionOutcome.DRY_RUN, simulated.outcome)
        val entry = registry.entries.value.single()
        assertFalse("Even recorded, it is not sent", entry.sent)
        assertEquals(SubmissionOutcome.DRY_RUN, entry.outcome)
    }
}
