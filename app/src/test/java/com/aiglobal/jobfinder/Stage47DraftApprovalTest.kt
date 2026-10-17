package com.aiglobal.jobfinder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aiglobal.jobfinder.data.applications.ApplicationDraftCenter
import com.aiglobal.jobfinder.data.applications.ApplicationDraftStore
import com.aiglobal.jobfinder.data.model.ApplicationDraft
import com.aiglobal.jobfinder.data.model.CoverLetter
import com.aiglobal.jobfinder.data.model.DraftAnswer
import com.aiglobal.jobfinder.data.model.DraftStatus
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.LetterLanguage
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import com.aiglobal.jobfinder.data.notifications.DraftReviewAlertPolicy
import com.aiglobal.jobfinder.data.notifications.JobNotificationCenter
import com.aiglobal.jobfinder.data.notifications.JobNotificationsCoordinator
import com.aiglobal.jobfinder.data.notifications.NotificationHistoryStore
import com.aiglobal.jobfinder.data.submissions.SubmissionRegistry
import com.aiglobal.jobfinder.data.submissions.SubmissionRegistryStore
import com.aiglobal.jobfinder.data.repository.JobRepository
import com.aiglobal.jobfinder.data.model.DashboardStats
import com.aiglobal.jobfinder.data.model.JobSyncState
import com.aiglobal.jobfinder.data.model.ProviderDiagnostics
import com.aiglobal.jobfinder.data.model.UserProfile
import com.aiglobal.jobfinder.data.discovery.DiscoveryDelta
import com.aiglobal.jobfinder.data.discovery.NewPostings
import com.aiglobal.jobfinder.data.jobs.search.SearchCoverage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Stage 47: the application draft approval queue.
 *
 * The point of these tests is the safety contract, not the plumbing. An approval is only
 * meaningful if it is bound to the exact content the user reviewed, so the tests pin that:
 * an unapproved draft cannot be submitted, an approval does not survive a content edit, and
 * the submit path records a *simulated* attempt — never a real send — while still preventing
 * the same opening from being applied to twice. The notification wording is pinned too, so
 * a preparation nudge can never be read as "we sent your application".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage47DraftApprovalTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var now = 1_700_000_000_000L

    private fun job(
        id: String = "arbeitnow:1",
        sourceUrl: String = "https://boards.greenhouse.io/acme/jobs/1",
    ) = Job(
        id = id,
        title = "Maintenance Technician",
        company = "Northline Industrial",
        companyAccent = 0xFF1E4FD8,
        country = "Canada",
        countryFlag = "🇨🇦",
        city = "Toronto",
        salaryMin = 5000,
        salaryMax = 6000,
        currencyCode = "CAD",
        salaryPeriod = SalaryPeriod.MONTH,
        matchScore = 92,
        eligibility = MadagascarEligibility.VISA_SPONSORED,
        visaSupport = VisaSupport.FULL_SPONSORSHIP,
        scamRisk = ScamRisk.LOW,
        postedAtEpochMillis = now - 86_400_000,
        retrievedAtEpochMillis = now,
        source = "Greenhouse",
        sourceUrl = sourceUrl,
        workMode = WorkMode.ONSITE,
        employmentType = EmploymentType.FULL_TIME,
        experienceYears = 3,
        matchedSkills = listOf("Preventive maintenance"),
        missingSkills = emptyList(),
        languages = listOf("English"),
        summary = "Industrial maintenance role.",
        responsibilities = emptyList(),
        benefits = emptyList(),
        aiInsight = "Strong technical fit.",
    )

    private fun coverLetter(jobId: String, body: String = "Dear hiring team, I am a strong fit.") =
        CoverLetter(
            id = "letter-1",
            jobId = jobId,
            language = LetterLanguage.ENGLISH,
            subject = "Application for Maintenance Technician",
            salutation = "Dear hiring team,",
            paragraphs = listOf(body),
            closing = "Sincerely,",
            signature = "Jonathan Rakoto",
            usedFacts = listOf("6 years experience"),
            missingFacts = emptyList(),
            generatedAtEpochMillis = now,
        )

    private fun newCenter(
        jobs: List<Job> = listOf(job()),
        draftsFile: File = File.createTempFile("drafts", ".json").also { it.delete() },
        registryFile: File = File.createTempFile("registry", ".json").also { it.delete() },
    ): Triple<ApplicationDraftCenter, SubmissionRegistry, ApplicationDraftStore> {
        val repository = DraftFakeRepository(jobs)
        val registry = SubmissionRegistry(SubmissionRegistryStore(context, registryFile), clock = { now })
        val store = ApplicationDraftStore(context, draftsFile)
        val center = ApplicationDraftCenter(
            repository = repository,
            store = store,
            submissionRegistry = registry,
            clock = { now },
            idFactory = { "draft-1" },
        )
        runBlocking { registry.load() }
        return Triple(center, registry, store)
    }

    // -------------------------------------------------- hash binds approval to content

    @Test
    fun theContentHashChangesWhenAnyReviewedFieldChanges() {
        val base = ApplicationDraft.hashOf("summary", "letter", emptyList(), emptyList())
        assertEquals(base, ApplicationDraft.hashOf("summary", "letter", emptyList(), emptyList()))
        assertNotEquals(base, ApplicationDraft.hashOf("summary CHANGED", "letter", emptyList(), emptyList()))
        assertNotEquals(base, ApplicationDraft.hashOf("summary", "letter CHANGED", emptyList(), emptyList()))
        assertNotEquals(
            base,
            ApplicationDraft.hashOf("summary", "letter", listOf(DraftAnswer("Q", "A")), emptyList()),
        )
    }

    // -------------------------------------------------- prepare -> approve -> submit

    @Test
    fun anUnapprovedDraftCannotBeSubmitted() = runBlocking {
        val (center, _, _) = newCenter()
        val draft = center.prepare(job(), "Maintenance technician", coverLetter("arbeitnow:1"))
        assertFalse(draft.isApproved)

        val attempted = center.submit(draft.id)
        assertNotNull(attempted)
        assertFalse(attempted!!.wasSent)
        assertFalse(attempted.wasSimulated)
        assertTrue(attempted.attempts.isEmpty())
    }

    @Test
    fun approvingThenSubmittingRecordsASimulationAndNeverSends() = runBlocking {
        val (center, _, _) = newCenter()
        val draft = center.prepare(job(), "Maintenance technician", coverLetter("arbeitnow:1"))
        val approved = center.approve(draft.id)!!

        assertTrue(approved.isApproved)
        val submitted = center.submit(draft.id)!!

        assertTrue(submitted.wasSimulated)
        assertFalse(submitted.wasSent)
        val attempt = submitted.lastAttempt
        assertNotNull(attempt)
        assertTrue(attempt!!.simulated)
        assertTrue(submitted.reason.contains("nothing was sent", ignoreCase = true) ||
            submitted.reason.contains("simulated", ignoreCase = true))
    }

    @Test
    fun editingAfterApprovalRequiresAFreshApproval() = runBlocking {
        val (center, _, _) = newCenter()
        val draft = center.prepare(job(), "Maintenance technician", coverLetter("arbeitnow:1"))
        center.approve(draft.id)

        val edited = center.edit(draft.id) { it.copy(coverLetterText = "A completely different letter.") }!!
        assertTrue(edited.needsResubmission)
        assertFalse(edited.isApproved)

        val attempted = center.submit(draft.id)!!
        assertTrue(attempted.attempts.isEmpty())
        assertEquals(DraftStatus.NEEDS_RESUBMISSION, attempted.status)
    }

    @Test
    fun rePreparingWithUnchangedContentKeepsTheApproval() = runBlocking {
        val (center, _, _) = newCenter()
        val draft = center.prepare(job(), "Maintenance technician", coverLetter("arbeitnow:1"))
        center.approve(draft.id)

        // Same content: a refresh must not revoke a valid approval.
        val refreshed = center.prepare(job(), "Maintenance technician", coverLetter("arbeitnow:1"))
        assertTrue(refreshed.isApproved)

        // Changed content: the approval is revoked, as it must be.
        val changed = center.prepare(job(), "A different summary", coverLetter("arbeitnow:1"))
        assertTrue(changed.needsResubmission)
    }

    @Test
    fun theSameOpeningCannotBeSubmittedTwice() = runBlocking {
        val (center, _, _) = newCenter()
        val draft = center.prepare(job(), "Maintenance technician", coverLetter("arbeitnow:1"))
        center.approve(draft.id)
        val first = center.submit(draft.id)!!

        // Approve again (content is unchanged, so this is a no-op approval) and resubmit.
        center.approve(draft.id)
        val second = center.submit(draft.id)!!

        assertEquals(DraftStatus.SUBMISSION_SIMULATED, first.status)
        assertEquals(DraftStatus.BLOCKED, second.status)
        assertEquals(2, second.attempts.size)
        assertTrue(second.lastAttempt!!.outcome.contains("Already applied", ignoreCase = true))
    }

    @Test
    fun aSubmissionInterruptedBeforeRecordingIsResumedNotLost() = runBlocking {
        val draftsFile = File.createTempFile("drafts-resume", ".json").also { it.delete() }
        val (center, _, store) = newCenter(draftsFile = draftsFile)
        val draft = center.prepare(job(), "Maintenance technician", coverLetter("arbeitnow:1"))
        center.approve(draft.id)

        // Simulate a crash between "approved" and "recorded" by writing the pending marker.
        store.updateDraft(draft.id) { it.copy(status = DraftStatus.SUBMISSION_PENDING) }

        val resumed = ApplicationDraftCenter(
            repository = DraftFakeRepository(listOf(job())),
            store = ApplicationDraftStore(context, draftsFile),
            submissionRegistry = SubmissionRegistry(
                SubmissionRegistryStore(context, File.createTempFile("reg2", ".json").also { it.delete() }),
                clock = { now },
            ),
            clock = { now },
        )
        // init{} loads and resumes asynchronously; give it a beat, then read the result.
        val settled = awaitResumed(resumed, draft.id)
        assertTrue(settled.wasSimulated)
        assertTrue(settled.attempts.isNotEmpty())
    }

    // -------------------------------------------------- notification wording

    @Test
    fun aPreparedDraftRaisesOneReviewAlertThatNeverClaimsASend() = runBlocking {
        val draft = ApplicationDraft(
            id = "draft-x",
            jobId = "arbeitnow:1",
            jobTitle = "Maintenance Technician",
            company = "Northline Industrial",
            country = "Canada",
            countryFlag = "🇨🇦",
            sourceUrl = "https://example.com",
            channelKey = "ATS_API",
            cvSummary = "Maintenance technician",
            coverLetterText = "Dear hiring team,",
            status = DraftStatus.READY_FOR_REVIEW,
            contentHash = "hash",
            preparedAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        val alerts = DraftReviewAlertPolicy.select(listOf(draft), alreadyNotified = emptySet())
        assertEquals(1, alerts.size)
        assertTrue(alerts.first().body.contains("Nothing has been sent", ignoreCase = true))
        assertTrue(alerts.first().body.contains("approve", ignoreCase = true))

        // Already told: never repeats.
        assertTrue(DraftReviewAlertPolicy.select(listOf(draft), setOf("draft-x")).isEmpty())
    }

    @Test
    fun theCoordinatorPostsADraftReviewOnlyOnce() = runBlocking {
        val coordinator = JobNotificationsCoordinator(
            center = JobNotificationCenter(context),
            history = NotificationHistoryStore(context, File.createTempFile("hist", ".txt").also { it.delete() }),
            draftReviewHistory = NotificationHistoryStore(
                context,
                File.createTempFile("dhist", ".txt").also { it.delete() },
            ),
        )
        val draft = ApplicationDraft(
            id = "draft-y",
            jobId = "arbeitnow:1",
            jobTitle = "Maintenance Technician",
            company = "Northline Industrial",
            country = "Canada",
            countryFlag = "🇨🇦",
            sourceUrl = "https://example.com",
            channelKey = "ATS_API",
            cvSummary = "Maintenance technician",
            coverLetterText = "Dear hiring team,",
            status = DraftStatus.READY_FOR_REVIEW,
            contentHash = "hash",
            preparedAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        val first = coordinator.notifyDraftReviews(listOf(draft), enabled = true)
        val second = coordinator.notifyDraftReviews(listOf(draft), enabled = true)
        assertEquals(1, first.size)
        assertTrue(second.isEmpty())

        // A disabled switch is silent.
        val other = ApplicationDraft(
            id = "draft-z", jobId = "b", jobTitle = "T", company = "C", country = "D",
            countryFlag = "🏳️", sourceUrl = "u", channelKey = "ATS_API", cvSummary = "s",
            coverLetterText = "l", status = DraftStatus.READY_FOR_REVIEW, contentHash = "h",
            preparedAtEpochMillis = now, updatedAtEpochMillis = now,
        )
        assertTrue(coordinator.notifyDraftReviews(listOf(other), enabled = false).isEmpty())
    }

    // -------------------------------------------------- helpers

    private suspend fun awaitResumed(center: ApplicationDraftCenter, id: String): ApplicationDraft {
        // The center resumes in init{}; poll briefly for the recorded attempt.
        repeat(50) {
            val draft = center.draft(id)
            if (draft != null && draft.attempts.isNotEmpty()) return draft
            kotlinx.coroutines.delay(20)
        }
        return center.draft(id) ?: error("draft $id was not loaded")
    }
}

/**
 * The smallest repository that satisfies the draft center: a fixed feed and neutral defaults
 * for everything it does not read.
 */
private class DraftFakeRepository(feed: List<Job>) : JobRepository {
    private val _jobs = MutableStateFlow(feed)
    override val jobs: Flow<List<Job>> = _jobs
    override val profile: Flow<UserProfile> = MutableStateFlow(
        UserProfile(
            fullName = "", headline = "", location = "", email = "", phone = "", summary = "",
            yearsOfExperience = 0, skills = emptyList(), experience = emptyList(),
            education = emptyList(), languages = emptyList(), targetCountries = emptyList(),
            targetRoles = emptyList(), cvImported = false, cvFileName = null,
            cvUpdatedAtEpochMillis = null, autoApplyEnabled = false, profileCompletion = 0,
        ),
    )
    override val stats: Flow<DashboardStats> = MutableStateFlow(DashboardStats(0, 0, 0, 0))
    override val syncState: Flow<JobSyncState> = MutableStateFlow(JobSyncState())
    override val diagnostics: Flow<ProviderDiagnostics> = MutableStateFlow(ProviderDiagnostics())
    override val coverage: Flow<SearchCoverage> = MutableStateFlow(SearchCoverage())
    override val discovery: Flow<DiscoveryDelta> = MutableStateFlow(DiscoveryDelta())
    override val newPostings: Flow<NewPostings> = MutableStateFlow(NewPostings())

    override suspend fun runLiveValidation() = Unit
    override suspend fun markNewPostingsReviewed() = Unit
    override suspend fun refresh() = Unit
    override suspend fun toggleSaved(jobId: String) = Unit
    override fun jobById(id: String): Job? = _jobs.value.firstOrNull { it.id == id }
    override suspend fun setAutoApply(enabled: Boolean) = Unit
    override suspend fun setDemoMode(enabled: Boolean) = Unit
}
