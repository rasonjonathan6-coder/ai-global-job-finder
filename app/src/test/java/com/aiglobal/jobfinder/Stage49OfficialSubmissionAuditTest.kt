package com.aiglobal.jobfinder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aiglobal.jobfinder.data.model.ApplicationDraft
import com.aiglobal.jobfinder.data.model.CoverLetter
import com.aiglobal.jobfinder.data.model.DraftStatus
import com.aiglobal.jobfinder.data.model.DashboardStats
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobSyncState
import com.aiglobal.jobfinder.data.model.LetterLanguage
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.ProviderDiagnostics
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.UserProfile
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import com.aiglobal.jobfinder.data.discovery.DiscoveryDelta
import com.aiglobal.jobfinder.data.discovery.NewPostings
import com.aiglobal.jobfinder.data.jobs.search.SearchCoverage
import com.aiglobal.jobfinder.data.repository.JobRepository
import com.aiglobal.jobfinder.data.submissions.OfficialSubmissionAudit
import com.aiglobal.jobfinder.data.submissions.OfficialSubmissionClassifier
import com.aiglobal.jobfinder.data.submissions.OfficialSubmissionOutcome
import com.aiglobal.jobfinder.data.submissions.SubmissionBlocker
import com.aiglobal.jobfinder.data.submissions.SubmissionCapability
import com.aiglobal.jobfinder.data.submissions.SubmissionChannel
import com.aiglobal.jobfinder.data.submissions.SubmissionLifecycle
import com.aiglobal.jobfinder.data.submissions.SubmissionPreflightCheck
import com.aiglobal.jobfinder.data.submissions.lifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Stage 49: the official application-submission audit and the safe boundaries around it.
 *
 * The brief's hard rule is that the app must never claim an application was sent without the
 * provider's own confirmation. These tests pin that rule at every layer:
 *
 *  - the audit is derived from the provider's documented endpoints and refuses to claim a
 *    route exists while any prerequisite is unmet;
 *  - a lifecycle state exists specifically so a simulation is never shown as "sent";
 *  - the preflight gate blocks a real send on every unmet rule, including consent and
 *    authorization, not just approval;
 *  - the response classifier never turns an ambiguous or timed-out response into a success,
 *    and treats a conflict as "verify", never "retry" (a blind retry is how duplicates are born).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage49OfficialSubmissionAuditTest {

    // -------------------------------------------------- the audit is honest

    @Test
    fun noRouteIsAvailableAndTheAuditSaysWhy() {
        assertFalse(OfficialSubmissionAudit.realSubmissionAvailable)
        assertEquals(0, SubmissionCapability.supportedChannelCount)
        assertTrue(SubmissionCapability.sendsNothing)

        // Every prerequisite must be listed with a reason and a remedy, and none may be met
        // while the route is unavailable — the flag is derived, so it cannot contradict this.
        val prerequisites = OfficialSubmissionAudit.SMART_RECRUITERS_PREREQUISITES
        assertTrue(prerequisites.isNotEmpty())
        assertTrue(prerequisites.none { it.met })
        assertTrue(prerequisites.all { it.detail.isNotBlank() && it.howToSatisfy.isNotBlank() })
        assertEquals(prerequisites.size, OfficialSubmissionAudit.unmetPrerequisites.size)

        // The reason it cannot send must name the concrete blocker (the client secret on Android).
        assertTrue(
            OfficialSubmissionAudit.unmetPrerequisites.any { it.id == "client-secret-custody" },
        )
    }

    @Test
    fun theAuditOnlyNamesDocumentedEndpoints() {
        // A test that fails if someone adds an endpoint that the documentation does not list,
        // so the audit cannot quietly drift into invented API surface.
        assertEquals(
            listOf(
                "GET /postings/:uuid/configuration",
                "POST /postings/:uuid/candidates",
                "GET /postings/:uuid/candidates/:candidateId/status",
            ),
            OfficialSubmissionAudit.SMART_RECRUITERS_ENDPOINTS,
        )
        assertTrue(OfficialSubmissionAudit.SMART_RECRUITERS_DOCS.startsWith("https://developers.smartrecruiters.com"))
    }

    @Test
    fun theOfficialApplyLinkIsOnlyEverTheEmployersOwnPostingUrl() {
        val job = job(sourceUrl = "https://jobs.smartrecruiters.com/acme/744000012345678")
        assertEquals(job.sourceUrl, OfficialSubmissionAudit.officialApplyUrl(job))
        // A blank posting URL yields nothing rather than a fabricated endpoint.
        assertEquals(null, OfficialSubmissionAudit.officialApplyUrl(job(sourceUrl = "")))
    }

    // -------------------------------------------------- the lifecycle cannot lie

    @Test
    fun aSimulationIsNeverReportedAsSent() {
        assertEquals(
            SubmissionLifecycle.SIMULATED_NOT_SENT,
            SubmissionLifecycle.of(DraftStatus.SUBMISSION_SIMULATED),
        )
        // Only a provider-confirmed state counts as sent.
        assertTrue(SubmissionLifecycle.SUBMITTED_CONFIRMED.isConfirmedSent)
        assertTrue(SubmissionLifecycle.SIMULATED_NOT_SENT.isNotSent)
        assertFalse(SubmissionLifecycle.SIMULATED_NOT_SENT.isConfirmedSent)
        // The other states must not be mistaken for a send either.
        assertFalse(SubmissionLifecycle.APPROVED.isConfirmedSent)
        assertFalse(SubmissionLifecycle.SUBMITTING.isConfirmedSent)
        assertFalse(SubmissionLifecycle.SUBMISSION_UNKNOWN.isConfirmedSent)
    }

    @Test
    fun everyDraftStatusMapsToALifecycleWithoutAnException() {
        DraftStatus.entries.forEach { status ->
            val lifecycle = SubmissionLifecycle.of(status)
            // Round-trip sanity: the mapping is total, and simulated content stays unsent.
            if (status == DraftStatus.SUBMISSION_SIMULATED) {
                assertEquals(SubmissionLifecycle.SIMULATED_NOT_SENT, lifecycle)
            }
        }
        assertEquals(SubmissionLifecycle.PENDING_APPROVAL, SubmissionLifecycle.of(DraftStatus.READY_FOR_REVIEW))
        assertEquals(SubmissionLifecycle.PENDING_APPROVAL, SubmissionLifecycle.of(DraftStatus.NEEDS_RESUBMISSION))
    }

    // -------------------------------------------------- the preflight gate

    @Test
    fun anUnapprovedDraftIsBlockedBeforeAnythingElse() {
        val draft = draft(status = DraftStatus.READY_FOR_REVIEW)
        val preflight = SubmissionPreflightCheck.evaluate(
            draft = draft,
            realSubmissionAvailable = true,
            consentGiven = true,
            alreadyApplied = false,
        )
        assertFalse(preflight.allowed)
        assertEquals(SubmissionBlocker.NOT_APPROVED, preflight.primaryBlocker)
    }

    @Test
    fun evenAFullyApprovedDraftIsBlockedWhileNoRouteExists() {
        val draft = draft(status = DraftStatus.APPROVED)
        val preflight = SubmissionPreflightCheck.evaluate(
            draft = draft,
            realSubmissionAvailable = OfficialSubmissionAudit.realSubmissionAvailable,
            consentGiven = true,
            alreadyApplied = false,
        )
        assertFalse(preflight.allowed)
        assertEquals(SubmissionBlocker.NO_OFFICIAL_CHANNEL, preflight.primaryBlocker)
    }

    @Test
    fun missingContentConsentAndDuplicatesEachBlockOnTheirOwn() {
        val approved = draft(status = DraftStatus.APPROVED)

        val noContent = approved.copy(cvSummary = "", coverLetterText = "")
        assertTrue(
            SubmissionPreflightCheck.evaluate(noContent, true, true, false)
                .blockers.contains(SubmissionBlocker.MISSING_REQUIRED_FIELDS),
        )

        assertTrue(
            SubmissionPreflightCheck.evaluate(approved, true, consentGiven = false, alreadyApplied = false)
                .blockers.contains(SubmissionBlocker.CONSENT_NOT_GIVEN),
        )

        assertTrue(
            SubmissionPreflightCheck.evaluate(approved, true, consentGiven = true, alreadyApplied = true)
                .blockers.contains(SubmissionBlocker.ALREADY_APPLIED),
        )
    }

    @Test
    fun aFullySatisfiedApplicationIsTheOnlyOneThatPasses() {
        val preflight = SubmissionPreflightCheck.evaluate(
            draft = draft(status = DraftStatus.APPROVED),
            realSubmissionAvailable = true,
            consentGiven = true,
            alreadyApplied = false,
        )
        assertTrue(preflight.allowed)
        assertEquals(null, preflight.primaryBlocker)
        assertTrue(preflight.explanation.contains("Ready", ignoreCase = true))
    }

    // -------------------------------------------------- the response classifier

    @Test
    fun onlyAnExplicitIdCountsAsConfirmed() {
        val confirmed = OfficialSubmissionClassifier.classify(201, "cand-123")
        assertTrue(confirmed is OfficialSubmissionOutcome.Confirmed)
        assertEquals("cand-123", (confirmed as OfficialSubmissionOutcome.Confirmed).remoteApplicationId)
        assertEquals(SubmissionLifecycle.SUBMITTED_CONFIRMED, OfficialSubmissionClassifier.lifecycleOf(confirmed))

        // A 2xx with no id is not a confirmation: it is unknown, and must be verified.
        val noId = OfficialSubmissionClassifier.classify(200, "")
        assertTrue(noId is OfficialSubmissionOutcome.Unknown)
        assertTrue(OfficialSubmissionClassifier.classify(200, null) is OfficialSubmissionOutcome.Unknown)
    }

    @Test
    fun aTimeoutIsUnknownAndNeverASuccess() {
        val timeout = OfficialSubmissionClassifier.classify(null, null)
        assertTrue(timeout is OfficialSubmissionOutcome.Unknown)
        assertEquals(SubmissionLifecycle.SUBMISSION_UNKNOWN, OfficialSubmissionClassifier.lifecycleOf(timeout))
    }

    @Test
    fun aConflictIsVerifyNotRetry() {
        // 409 means an application may already exist; retrying could duplicate it.
        val conflict = OfficialSubmissionClassifier.classify(409, null)
        assertTrue(conflict is OfficialSubmissionOutcome.Unknown)
        assertFalse(conflict is OfficialSubmissionOutcome.Failed)
    }

    @Test
    fun aServerErrorIsUnknownWhileARejectionIsAFailure() {
        assertTrue(OfficialSubmissionClassifier.classify(503, null) is OfficialSubmissionOutcome.Unknown)
        assertTrue(OfficialSubmissionClassifier.classify(422, null) is OfficialSubmissionOutcome.Failed)
        assertEquals(
            SubmissionLifecycle.SUBMISSION_FAILED,
            OfficialSubmissionClassifier.lifecycleOf(OfficialSubmissionClassifier.classify(400, null)),
        )
        // Rate limited: not processed, so not a failure of the application itself.
        assertTrue(OfficialSubmissionClassifier.classify(429, null) is OfficialSubmissionOutcome.Failed)
    }

    // -------------------------------------------------- consent is part of the reviewed content

    @Test
    fun theConsentDecisionIsPartOfTheApprovedContent() {
        val withoutConsent = ApplicationDraft.hashOf("summary", "letter", emptyList(), emptyList(), consentGiven = false)
        val withConsent = ApplicationDraft.hashOf("summary", "letter", emptyList(), emptyList(), consentGiven = true)
        assertNotEquals(withoutConsent, withConsent)

        // Defaulting to no consent keeps the previous hashes stable for callers that omit it.
        assertEquals(
            ApplicationDraft.hashOf("summary", "letter", emptyList(), emptyList()),
            ApplicationDraft.hashOf("summary", "letter", emptyList(), emptyList(), consentGiven = false),
        )
    }

    @Test
    fun aDraftWithARecordedReceiptReportsItsConfirmedState() {
        val draft = draft(status = DraftStatus.SUBMISSION_SENT).copy(
            remoteApplicationId = "cand-999",
            confirmedAtEpochMillis = 1_700_000_100_000L,
        )
        assertEquals(SubmissionLifecycle.SUBMITTED_CONFIRMED, draft.lifecycle)
        assertEquals("cand-999", draft.remoteApplicationId)
    }

    // -------------------------------------------------- end-to-end data layer

    @Test
    fun theSimulatedPathNeverFabricatesAReceiptAndConsentBindsApproval() = runBlocking {
        val repository = Stage49FakeRepository(listOf(job("https://jobs.smartrecruiters.com/acme/1")))
        val store = com.aiglobal.jobfinder.data.applications.ApplicationDraftStore(
            ApplicationProvider.getApplicationContext(),
            File.createTempFile("stage49-drafts", ".json").also { it.delete() },
        )
        val registry = com.aiglobal.jobfinder.data.submissions.SubmissionRegistry(
            com.aiglobal.jobfinder.data.submissions.SubmissionRegistryStore(
                ApplicationProvider.getApplicationContext(),
                File.createTempFile("stage49-registry", ".json").also { it.delete() },
            ),
            clock = { 1_700_000_000_000L },
        )
        runBlocking { registry.load() }
        val center = com.aiglobal.jobfinder.data.applications.ApplicationDraftCenter(
            repository = repository,
            store = store,
            submissionRegistry = registry,
            clock = { 1_700_000_000_000L },
            idFactory = { "stage49-draft" },
        )

        val prepared = center.prepare(
            job = job("https://jobs.smartrecruiters.com/acme/1"),
            cvSummary = "Maintenance technician, 6 years.",
            coverLetter = null,
        )
        assertFalse(prepared.consentGiven)

        // Giving consent after approval invalidates the approval, because consent is content.
        center.approve(prepared.id)
        val consented = center.setConsent(prepared.id, true)!!
        assertTrue(consented.consentGiven)
        assertTrue(consented.needsResubmission)
        assertFalse(consented.isApproved)

        // Re-approve, then submit: the pipeline records a simulation and no remote id.
        center.approve(prepared.id)
        val submitted = center.submit(prepared.id)!!
        assertTrue(submitted.wasSimulated)
        assertFalse(submitted.wasSent)
        assertEquals(null, submitted.remoteApplicationId)
        assertEquals(null, submitted.confirmedAtEpochMillis)
        assertEquals(SubmissionLifecycle.SIMULATED_NOT_SENT, submitted.lifecycle)
        assertFalse(submitted.lifecycle.isConfirmedSent)
    }

    // -------------------------------------------------- helpers

    private fun draft(status: DraftStatus) = ApplicationDraft(
        id = "draft-49",
        jobId = "smartrecruiters:1",
        jobTitle = "Maintenance Technician",
        company = "Northline Industrial",
        country = "Canada",
        countryFlag = "🇨🇦",
        sourceUrl = "https://jobs.smartrecruiters.com/acme/744000012345678",
        channelKey = SubmissionChannel.ATS_API.name,
        cvSummary = "Maintenance technician with 6 years of experience.",
        coverLetterText = "Dear hiring team, I am a strong fit.",
        status = status,
        contentHash = "hash",
        approvedHash = if (status == DraftStatus.APPROVED) "hash" else null,
        preparedAtEpochMillis = 1_700_000_000_000L,
        updatedAtEpochMillis = 1_700_000_000_000L,
    )

    private fun job(sourceUrl: String) = Job(
        id = "smartrecruiters:1",
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
        postedAtEpochMillis = 1_699_000_000_000L,
        retrievedAtEpochMillis = 1_700_000_000_000L,
        source = "SmartRecruiters",
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
}

/**
 * The smallest repository the draft center needs: a fixed feed and neutral defaults for
 * everything else it does not read. Mirrors the fake used by the Stage 47 suite.
 */
private class Stage49FakeRepository(feed: List<Job>) : JobRepository {
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
