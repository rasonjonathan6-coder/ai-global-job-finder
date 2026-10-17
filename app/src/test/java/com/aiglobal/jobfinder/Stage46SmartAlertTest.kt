package com.aiglobal.jobfinder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aiglobal.jobfinder.data.discovery.DiscoveryDelta
import com.aiglobal.jobfinder.data.discovery.NewPostings
import com.aiglobal.jobfinder.data.jobs.search.RoleFamily
import com.aiglobal.jobfinder.data.jobs.search.SearchCoverage
import com.aiglobal.jobfinder.data.model.DashboardStats
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobSyncState
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.OfferVerification
import com.aiglobal.jobfinder.data.model.ProviderDiagnostics
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.UserProfile
import com.aiglobal.jobfinder.data.model.VerificationState
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import com.aiglobal.jobfinder.data.notifications.JobNotificationCenter
import com.aiglobal.jobfinder.data.notifications.JobNotificationsCoordinator
import com.aiglobal.jobfinder.data.notifications.NotificationHistoryStore
import com.aiglobal.jobfinder.data.notifications.SmartAlertPolicy
import com.aiglobal.jobfinder.data.repository.JobRepository
import com.aiglobal.jobfinder.work.JobRefreshRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Stage 46 smart alerts.
 *
 * The point of these tests is that a "smart alert" is a *claim about a genuinely new,
 * high-quality international opportunity* — not merely a strong score. So the gate is pinned
 * on both axes: newness (from the incremental ledger) and quality (no scam, no local-only
 * role, no unrelated trade, and still actionable). A posting that is new but poor, or good
 * but already seen, must stay silent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage46SmartAlertTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun job(
        id: String = "arbeitnow:1",
        matchScore: Int = 92,
        eligibility: MadagascarEligibility = MadagascarEligibility.VISA_SPONSORED,
        visaSupport: VisaSupport = VisaSupport.FULL_SPONSORSHIP,
        scamRisk: ScamRisk = ScamRisk.LOW,
        roleFamily: RoleFamily = RoleFamily.TARGET,
        verification: OfferVerification = OfferVerification.UNKNOWN,
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
        matchScore = matchScore,
        eligibility = eligibility,
        visaSupport = visaSupport,
        scamRisk = scamRisk,
        postedAtEpochMillis = 1_700_000_000_000,
        retrievedAtEpochMillis = 1_700_000_000_000,
        source = "Arbeitnow",
        sourceUrl = "https://example.com/job/1",
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
        roleFamily = roleFamily,
        verification = verification,
    )

    private fun brokenLink(): OfferVerification = OfferVerification.UNKNOWN.copy(
        link = VerificationState.INACCESSIBLE,
    )

    private fun expired(): OfferVerification = OfferVerification.UNKNOWN.copy(
        activity = VerificationState.EXPIRED,
    )

    private fun coordinator(file: File): JobNotificationsCoordinator = JobNotificationsCoordinator(
        center = JobNotificationCenter(context),
        history = NotificationHistoryStore(context, file),
    )

    private fun tempFile(tag: String): File =
        File.createTempFile(tag, ".txt").also { it.delete() }

    // ------------------------------------------------------ quality gate

    @Test
    fun aStrongNewInternationalMatchQualifies() {
        assertTrue(SmartAlertPolicy.NEW_ONLY.qualifies(job(id = "a"), newJobIds = setOf("a")))
    }

    @Test
    fun aScamIsNeverWorthAnInterruption() {
        val scam = job(id = "s", scamRisk = ScamRisk.HIGH)
        assertFalse(SmartAlertPolicy.QUALITY_ONLY.qualifies(scam))
        assertTrue(SmartAlertPolicy.QUALITY_ONLY.reasonsAgainst(scam).contains("Flagged as a likely scam"))
    }

    @Test
    fun aLocalOnlyRoleIsRejected() {
        val local = job(id = "l", eligibility = MadagascarEligibility.RESTRICTED, visaSupport = VisaSupport.LOCAL_ONLY)
        assertFalse(SmartAlertPolicy.QUALITY_ONLY.qualifies(local))
    }

    @Test
    fun anUnrelatedTradeIsRejected() {
        val unrelated = job(id = "u", roleFamily = RoleFamily.UNRELATED)
        assertFalse(SmartAlertPolicy.QUALITY_ONLY.qualifies(unrelated))
    }

    @Test
    fun aBrokenApplicationLinkIsRejected() {
        val broken = job(id = "b", verification = brokenLink())
        assertFalse(SmartAlertPolicy.QUALITY_ONLY.qualifies(broken))
    }

    @Test
    fun anExpiredVacancyIsRejected() {
        val dead = job(id = "d", verification = expired())
        assertFalse(SmartAlertPolicy.QUALITY_ONLY.qualifies(dead))
    }

    @Test
    fun theNewOnlyGateDropsAPostingAlreadySeen() {
        val policy = SmartAlertPolicy.NEW_ONLY
        assertFalse(policy.qualifies(job(id = "old"), newJobIds = setOf("other")))
        assertTrue(policy.qualifies(job(id = "fresh"), newJobIds = setOf("fresh")))
    }

    @Test
    fun theQualityOnlyGateJudgesQualityAlone() {
        assertTrue(SmartAlertPolicy.QUALITY_ONLY.qualifies(job(id = "old"), newJobIds = emptySet()))
    }

    // ------------------------------------------------------ coordinator

    @Test
    fun theSmartPathAnnouncesOnlyGenuinelyNewQualityMatches() = runBlocking {
        val coordinator = coordinator(tempFile("smart-coord"))
        val fresh = job(id = "fresh")
        val old = job(id = "old")
        val scam = job(id = "scam", scamRisk = ScamRisk.HIGH)

        val posted = coordinator.notifyNewMatches(
            jobs = listOf(fresh, old, scam),
            enabled = true,
            newJobIds = setOf("fresh", "scam"),
        )

        assertEquals(listOf("fresh"), posted.map { it.jobId })
    }

    @Test
    fun anOldStrongMatchStaysSilentOnTheSmartPath() = runBlocking {
        val coordinator = coordinator(tempFile("smart-old"))
        val posted = coordinator.notifyNewMatches(
            jobs = listOf(job(id = "old")),
            enabled = true,
            newJobIds = emptySet(),
        )
        assertTrue(posted.isEmpty())
    }

    @Test
    fun theManualPathStillAnnouncesAStrongMatchRegardlessOfNewness() = runBlocking {
        val coordinator = coordinator(tempFile("manual"))
        val posted = coordinator.notifyNewMatches(
            jobs = listOf(job(id = "old")),
            enabled = true,
            // newJobIds omitted: the Stage 20 behaviour, where any strong match may be announced.
        )
        assertEquals(listOf("old"), posted.map { it.jobId })
    }

    @Test
    fun theSmartGateNeverRepeatsAPostingAcrossRestarts() = runBlocking {
        val file = tempFile("smart-dedup")
        val fresh = job(id = "fresh")

        val first = coordinator(file).notifyNewMatches(
            jobs = listOf(fresh),
            enabled = true,
            newJobIds = setOf("fresh"),
        )
        // A brand-new coordinator over the same history file, as after a process restart.
        val second = coordinator(file).notifyNewMatches(
            jobs = listOf(fresh),
            enabled = true,
            newJobIds = setOf("fresh"),
        )

        assertEquals(1, first.size)
        assertTrue("a job already announced must never repeat", second.isEmpty())
    }

    // ------------------------------------------------------ background runner

    @Test
    fun theRunnerAnnouncesOnlyWhatTheLedgerCallsNew() = runBlocking {
        val repository = FakeJobRepository(
            jobs = listOf(job(id = "fresh"), job(id = "old")),
            newPostings = NewPostings(jobIds = listOf("fresh"), baselineEstablished = true),
        )
        val runner = JobRefreshRunner(
            repository = repository,
            notifications = coordinator(tempFile("runner")),
            notificationsEnabled = MutableStateFlow(true),
            demoMode = MutableStateFlow(false),
        )

        assertEquals(listOf("fresh"), runner.run())
    }

    @Test
    fun theRunnerStaysSilentWhenNothingIsGenuinelyNew() = runBlocking {
        val repository = FakeJobRepository(
            jobs = listOf(job(id = "old")),
            newPostings = NewPostings(baselineEstablished = true),
        )
        val runner = JobRefreshRunner(
            repository = repository,
            notifications = coordinator(tempFile("runner-quiet")),
            notificationsEnabled = MutableStateFlow(true),
            demoMode = MutableStateFlow(false),
        )

        assertTrue(runner.run().isEmpty())
    }
}

/**
 * The smallest repository that satisfies the runner's needs: a fixed feed and a fixed
 * incremental list. Everything the runner does not read is a neutral default, so the test
 * exercises the real runner wiring rather than a mock of it.
 */
private class FakeJobRepository(
    jobs: List<Job>,
    newPostings: NewPostings,
) : JobRepository {
    private val _jobs = MutableStateFlow(jobs)
    override val jobs: Flow<List<Job>> = _jobs
    override val profile: Flow<UserProfile> = MutableStateFlow(
        UserProfile(
            fullName = "",
            headline = "",
            location = "",
            email = "",
            phone = "",
            summary = "",
            yearsOfExperience = 0,
            skills = emptyList(),
            experience = emptyList(),
            education = emptyList(),
            languages = emptyList(),
            targetCountries = emptyList(),
            targetRoles = emptyList(),
            cvImported = false,
            cvFileName = null,
            cvUpdatedAtEpochMillis = null,
            autoApplyEnabled = false,
            profileCompletion = 0,
        ),
    )
    override val stats: Flow<DashboardStats> = MutableStateFlow(DashboardStats(0, 0, 0, 0))
    override val syncState: Flow<JobSyncState> = MutableStateFlow(JobSyncState())
    override val diagnostics: Flow<ProviderDiagnostics> = MutableStateFlow(ProviderDiagnostics())
    override val coverage: Flow<SearchCoverage> = MutableStateFlow(SearchCoverage())
    override val discovery: Flow<DiscoveryDelta> = MutableStateFlow(DiscoveryDelta())
    override val newPostings: Flow<NewPostings> = MutableStateFlow(newPostings)

    override suspend fun runLiveValidation() = Unit
    override suspend fun markNewPostingsReviewed() = Unit
    override suspend fun refresh() = Unit
    override suspend fun toggleSaved(jobId: String) = Unit
    override fun jobById(id: String): Job? = _jobs.value.firstOrNull { it.id == id }
    override suspend fun setAutoApply(enabled: Boolean) = Unit
    override suspend fun setDemoMode(enabled: Boolean) = Unit
}
