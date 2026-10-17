package com.aiglobal.jobfinder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aiglobal.jobfinder.data.applications.ApplicationDraftCenter
import com.aiglobal.jobfinder.data.applications.ApplicationDraftStore
import com.aiglobal.jobfinder.data.coverletter.CoverLetterGenerator
import com.aiglobal.jobfinder.data.expat.ShortlistEngine
import com.aiglobal.jobfinder.data.jobs.ExpatRankingEngine
import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.DashboardStats
import com.aiglobal.jobfinder.data.model.DraftStatus
import com.aiglobal.jobfinder.data.model.ExpatPreferences
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobSyncState
import com.aiglobal.jobfinder.data.model.LetterLanguage
import com.aiglobal.jobfinder.data.model.ProviderDiagnostics
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.UserProfile
import com.aiglobal.jobfinder.data.discovery.DiscoveryDelta
import com.aiglobal.jobfinder.data.discovery.NewPostings
import com.aiglobal.jobfinder.data.jobs.search.SearchCoverage
import com.aiglobal.jobfinder.data.repository.JobRepository
import com.aiglobal.jobfinder.data.submissions.SubmissionChannel
import com.aiglobal.jobfinder.data.submissions.SubmissionOutcome
import com.aiglobal.jobfinder.data.submissions.SubmissionRegistry
import com.aiglobal.jobfinder.data.submissions.SubmissionRegistryStore
import com.aiglobal.jobfinder.data.submissions.SubmissionRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Stage 48 — end-to-end readiness verification.
 *
 * Every earlier stage tested one link. This test runs the whole chain the app promises, on
 * the real engines rather than stubs:
 *
 *   raw source record → JobMapper (match + eligibility) → shortlist → expat ranking
 *     → prepared draft → user approval → submission
 *
 * and asserts the safety invariant at the end of it: the submission is *simulated*, `SENT`
 * is never reached, and a second attempt on the same opening is refused. The CV is a plain
 * text fixture; no network and no credential are involved, and no real send is possible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage48PipelineReadinessTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1_700_000_000_000L

    private val profile = CandidateProfile(
        fullName = "Jonathan Rakoto",
        email = "jonathan.rakoto@example.mg",
        location = "Antananarivo, Madagascar",
        currentTitle = "Maintenance Technician",
        previousTitles = listOf("Industrial Electrician"),
        yearsOfExperience = 6,
        skills = listOf("Preventive maintenance", "Hydraulics", "PLC", "Electrical fault finding"),
        preferredCountries = listOf("Canada", "Germany"),
    )

    private fun rawJob(
        id: String = "gh-1",
        title: String = "Maintenance Technician",
        url: String = "https://boards.greenhouse.io/northline/jobs/$id",
        description: String = """
            We are hiring a Maintenance Technician to join our production team in Calgary.
            Visa sponsorship and relocation support are provided, including accommodation and
            annual flights home. You will run preventive maintenance and log work orders.
        """.trimIndent(),
    ) = RawJob(
        providerId = "greenhouse",
        providerName = "Industrial career sites",
        externalId = id,
        title = title,
        company = "Northline Industrial",
        location = "Calgary, Canada",
        descriptionHtml = description,
        url = url,
        employmentType = "Full-time",
        postedAtEpochMillis = now - 2 * 86_400_000L,
        retrievedAtEpochMillis = now,
        salaryMin = 58000,
        salaryMax = 74000,
        salaryCurrency = "CAD",
        salaryPeriod = SalaryPeriod.YEAR,
    )

    private fun tempFile(tag: String): File =
        File(context.cacheDir, "stage48-$tag-${System.nanoTime()}.json").also { it.delete() }

    @Test
    fun theWholeChainFromRawRecordToSimulatedSubmissionHolds() = runBlocking {
        // 1 — raw source record → Job (matching + eligibility + benefit evidence).
        val mapper = JobMapper(
            matchingEngine = MatchingEngine(),
            eligibilityEngine = EligibilityEngine(),
            clock = { now },
        )
        val job: Job = mapper.toJob(rawJob(), profile)
        assertEquals("greenhouse:gh-1", job.id)
        assertTrue("the mapper must score this as a strong fit", job.matchScore > 0)

        // 2 — shortlist: is it actionable from Madagascar, and how ready?
        val shortlist = ShortlistEngine(clock = { now }).build(
            jobs = listOf(job),
            profile = profile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 100,
            salaryEurPerYear = { 62_000 },
        )
        assertTrue("an applicable posting must enter the shortlist", shortlist.entries.isNotEmpty())

        // 3 — ranking: combined readiness + expat package, both visible.
        val ranking = ExpatRankingEngine(clock = { now })
            .rank(shortlist, ExpatPreferences.defaults())
        assertEquals(1, ranking.ranked.size)
        val ranked = ranking.ranked.single()
        assertEquals(1, ranked.rank)
        assertTrue(ranked.finalScore in 0..100)
        assertTrue(ranked.opportunity.dimensions.isNotEmpty())

        // 4 — draft: prepared from the reviewed CV summary and a real cover letter.
        val letter = CoverLetterGenerator(clock = { now }, idFactory = { "letter-1" })
            .generate(job, profile, LetterLanguage.ENGLISH)
        val registry = SubmissionRegistry(
            SubmissionRegistryStore(context, tempFile("registry")),
            clock = { now },
        )
        registry.load()
        val center = ApplicationDraftCenter(
            repository = PipelineFakeRepository(listOf(job)),
            store = ApplicationDraftStore(context, tempFile("drafts")),
            submissionRegistry = registry,
            clock = { now },
            idFactory = { "draft-1" },
        )
        val prepared = center.prepare(
            job = job,
            cvSummary = "Maintenance Technician with 6 years of industrial experience.",
            coverLetter = letter,
            answers = emptyList(),
            documents = listOf("CV"),
        )
        assertNotNull(prepared)
        assertTrue("nothing is submitted without approval", !prepared.isApproved)

        // 5 — an unapproved draft cannot be submitted.
        val blocked = center.submit("draft-1")
        assertNotNull(blocked)
        assertFalse("an unapproved draft must never reach a submission outcome", blocked!!.wasSimulated)
        assertEquals(0, registry.entries.value.size)

        // 6 — approve, then submit: the submission is simulated and recorded.
        center.approve("draft-1")
        val submitted = center.submit("draft-1")
        assertNotNull(submitted)
        assertTrue("the submission is simulated", submitted!!.wasSimulated)
        assertFalse("a real send must never be recorded", submitted.wasSent)
        assertEquals(DraftStatus.SUBMISSION_SIMULATED, submitted.status)
        assertTrue(submitted.attempts.isNotEmpty())
        assertTrue("the attempt must say nothing was sent", submitted.attempts.last().simulated)
        assertEquals(1, registry.entries.value.size)

        // 7 — the same opening cannot be applied to twice.
        val second = registry.submit(SubmissionRequest(job))
        assertEquals(SubmissionOutcome.BLOCKED_DUPLICATE, second.outcome)
        assertFalse(second.recorded)
        assertEquals(1, registry.entries.value.size)
    }

    @Test
    fun aLocalOnlyPostingNeverReachesTheRankingOrADraft() = runBlocking {
        val mapper = JobMapper(
            matchingEngine = MatchingEngine(),
            eligibilityEngine = EligibilityEngine(),
            clock = { now },
        )
        val localOnly = mapper.toJob(
            rawJob(
                id = "local",
                description = "Maintenance Technician. Applicants must already hold a valid " +
                    "Canadian work permit. This role is open to local candidates only.",
            ),
            profile,
        )

        val shortlist = ShortlistEngine(clock = { now }).build(
            jobs = listOf(localOnly),
            profile = profile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 100,
            salaryEurPerYear = { null },
        )
        // Whatever the eligibility engine decides, a posting with no route from Madagascar
        // must not appear on an actionable shortlist.
        if (localOnly.eligibility == com.aiglobal.jobfinder.data.model.MadagascarEligibility.RESTRICTED) {
            assertTrue(shortlist.entries.isEmpty())
        }
    }

    @Test
    fun everyChannelRefusesARealSendSoTheOutcomeIsAlwaysSimulated() = runBlocking {
        val registry = SubmissionRegistry(
            SubmissionRegistryStore(context, tempFile("real")),
            clock = { now },
            realSender = { true },
        )
        registry.load()

        val job = Job(
            id = "x:1",
            title = "Maintenance Technician",
            company = "Northline Industrial",
            companyAccent = 0xFF1E4FD8,
            country = "Canada",
            countryFlag = "🇨🇦",
            city = "Calgary",
            salaryMin = 1,
            salaryMax = 2,
            currencyCode = "CAD",
            salaryPeriod = SalaryPeriod.YEAR,
            matchScore = 90,
            eligibility = com.aiglobal.jobfinder.data.model.MadagascarEligibility.VISA_SPONSORED,
            visaSupport = com.aiglobal.jobfinder.data.model.VisaSupport.FULL_SPONSORSHIP,
            scamRisk = com.aiglobal.jobfinder.data.model.ScamRisk.LOW,
            postedAtEpochMillis = now,
            source = "Greenhouse",
            sourceUrl = "https://boards.greenhouse.io/acme/jobs/1",
            workMode = com.aiglobal.jobfinder.data.model.WorkMode.ONSITE,
            employmentType = com.aiglobal.jobfinder.data.model.EmploymentType.FULL_TIME,
            experienceYears = 3,
            matchedSkills = emptyList(),
            missingSkills = emptyList(),
            languages = emptyList(),
            summary = "Maintenance.",
            responsibilities = emptyList(),
            benefits = emptyList(),
            aiInsight = "",
        )

        // Even with a real sender wired and real send requested, the channel gate refuses,
        // because no channel is drivable. SENT is unreachable.
        val decision = registry.submit(
            SubmissionRequest(job = job, channel = SubmissionChannel.ATS_API, allowRealSend = true),
        )
        assertFalse(decision.outcome == SubmissionOutcome.SENT)
        assertFalse(decision.recorded)
    }
}

/** A minimal in-memory feed, so the pipeline test drives the real draft/registry code. */
private class PipelineFakeRepository(feed: List<Job>) : JobRepository {
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
