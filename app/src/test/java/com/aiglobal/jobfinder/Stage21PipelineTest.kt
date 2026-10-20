package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.assistant.ApplicationPlanEngine
import com.aiglobal.jobfinder.data.assistant.ExpatIntelligenceEngine
import com.aiglobal.jobfinder.data.expat.ShortlistEngine
import com.aiglobal.jobfinder.data.jobs.search.RoleClassifier
import com.aiglobal.jobfinder.data.model.BenefitEvidence
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.ExpatPreferences
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobBenefitTypes
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.PlanStepKind
import com.aiglobal.jobfinder.data.model.PlanStepStatus
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.ScamVerdict
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 21 behaviour: the per-posting application plan and the whole-feed expat
 * intelligence digest. Both are pure derivations of the posting, the CV and the user's
 * priorities, so they run on the JVM without Robolectric.
 *
 * The point of these tests is that the plan *tells the truth*: a posting with no route in
 * from Madagascar is blocked, a missing cover letter is to-do, and completing the pieces
 * actually moves the readiness number rather than just re-labelling a step.
 */
class Stage21PipelineTest {

    private val now = 1_700_000_000_000L
    private val shortlistEngine = ShortlistEngine(clock = { now })
    private val planEngine = ApplicationPlanEngine(clock = { now })
    private val intelligenceEngine = ExpatIntelligenceEngine(clock = { now })

    private val profile = CandidateProfile(
        fullName = "Jonathan Rakoto",
        currentTitle = "Maintenance Technician",
        previousTitles = listOf("Industrial Electrician"),
        yearsOfExperience = 6,
        skills = listOf("Preventive maintenance", "Hydraulics", "PLC"),
    )

    private fun job(
        id: String = "job-1",
        title: String = "Maintenance Technician",
        country: String = "Canada",
        matchScore: Int = 92,
        eligibility: MadagascarEligibility = MadagascarEligibility.VISA_SPONSORED,
        visaSupport: VisaSupport = VisaSupport.FULL_SPONSORSHIP,
        missingSkills: List<String> = listOf("Canadian Red Seal certification"),
    ) = Job(
        id = id,
        title = title,
        company = "Northline Industrial",
        companyAccent = 0xFF1E4FD8,
        country = country,
        countryFlag = "🇨🇦",
        city = "Calgary",
        salaryMin = 58000,
        salaryMax = 74000,
        currencyCode = "CAD",
        salaryPeriod = SalaryPeriod.YEAR,
        matchScore = matchScore,
        eligibility = eligibility,
        visaSupport = visaSupport,
        scamRisk = ScamRisk.LOW,
        postedAtEpochMillis = now - 86_400_000,
        source = "Canada Job Bank",
        sourceUrl = "https://www.jobbank.gc.ca",
        workMode = WorkMode.ONSITE,
        employmentType = EmploymentType.FULL_TIME,
        experienceYears = 3,
        matchedSkills = listOf("Preventive maintenance", "Hydraulics"),
        missingSkills = missingSkills,
        languages = listOf("English (B2+)"),
        summary = "Maintain production line machinery across two shifts.",
        responsibilities = listOf("Run preventive maintenance", "Log work orders"),
        benefits = listOf("Relocation allowance"),
        aiInsight = "Strong technical fit.",
        benefitEvidence = listOf(
            BenefitEvidence(JobBenefitTypes.VISA_SPONSORSHIP, BenefitStatus.CONFIRMED, "Visa sponsorship provided."),
            BenefitEvidence(JobBenefitTypes.RELOCATION_SUPPORT, BenefitStatus.CONFIRMED, "Relocation support is included."),
        ),
        // The real mapper fills the role verdict from the title; these fixtures build Job by
        // hand, so they classify it the same way the pipeline would rather than defaulting.
        roleFamily = RoleClassifier.classify(title).family,
    )

    private fun entry(job: Job, completeness: Int = 100) =
        shortlistEngine.assess(job, profile, ExpatPreferences.defaults(), completeness, 60_000)

    // --------------------------------------------------- application plan

    @Test
    fun aPostingWithNoRouteInIsBlockedAndTellsTheUserToMoveOn() {
        val plan = planEngine.build(
            job = job(eligibility = MadagascarEligibility.RESTRICTED),
            entry = entry(job(eligibility = MadagascarEligibility.RESTRICTED)),
            profileCompletenessPercent = 100,
            hasCoverLetter = true,
        )

        val visa = plan.steps.first { it.kind == PlanStepKind.VISA_ROUTE }
        assertEquals(PlanStepStatus.BLOCKED, visa.status)
        assertTrue(visa.official)
        assertFalse(plan.isReadyToApply)
        assertTrue(plan.blockers.isNotEmpty())
        assertTrue(plan.nextAction.contains("aside", ignoreCase = true))
    }

    @Test
    fun aMissingCoverLetterIsAToDoStepAndBlocksReadiness() {
        val job = job()
        val without = planEngine.build(
            job = job,
            entry = entry(job),
            profileCompletenessPercent = 100,
            hasCoverLetter = false,
        )
        val with = planEngine.build(
            job = job,
            entry = entry(job),
            profileCompletenessPercent = 100,
            hasCoverLetter = true,
        )

        assertEquals(
            PlanStepStatus.TO_DO,
            without.steps.first { it.kind == PlanStepKind.COVER_LETTER }.status,
        )
        assertEquals(
            PlanStepStatus.DONE,
            with.steps.first { it.kind == PlanStepKind.COVER_LETTER }.status,
        )
        assertFalse(without.isReadyToApply)
        assertTrue(with.isReadyToApply)
        assertTrue(with.doneCount > without.doneCount)
    }

    @Test
    fun anIncompleteProfileIsFlaggedAndDisappearsOnceComplete() {
        val job = job()
        val thin = planEngine.build(
            job = job,
            entry = entry(job, completeness = 20),
            profileCompletenessPercent = 20,
            hasCoverLetter = true,
        )
        val full = planEngine.build(
            job = job,
            entry = entry(job, completeness = 100),
            profileCompletenessPercent = 100,
            hasCoverLetter = true,
        )

        assertEquals(PlanStepStatus.TO_DO, thin.steps.first { it.kind == PlanStepKind.PROFILE }.status)
        assertEquals(PlanStepStatus.DONE, full.steps.first { it.kind == PlanStepKind.PROFILE }.status)
    }

    @Test
    fun progressCountsOnlyTheStepsThatStillMatter() {
        val job = job()
        val plan = planEngine.build(
            job = job,
            entry = entry(job),
            profileCompletenessPercent = 100,
            hasCoverLetter = false,
        )

        assertEquals(plan.countableSteps.size, plan.totalCount)
        assertEquals(plan.countableSteps.count { it.isDone }, plan.doneCount)
        assertTrue(plan.progressPercent in 0..100)
    }

    @Test
    fun thePlanCarriesThePostingsOwnFactsAndTailoring() {
        val job = job()
        val plan = planEngine.build(
            job = job,
            entry = entry(job),
            profileCompletenessPercent = 100,
            hasCoverLetter = true,
            scamVerdict = ScamVerdict.LIKELY_SAFE,
        )

        assertEquals(job.id, plan.jobId)
        assertEquals(job.title, plan.jobTitle)
        assertEquals(job.country, plan.country)
        assertEquals(job.matchScore, plan.matchScore)
        assertEquals(ScamVerdict.LIKELY_SAFE, plan.scamVerdict)
        assertTrue(plan.tailoring.note.isNotBlank())
        assertTrue(plan.documents.isNotEmpty())
        assertTrue(plan.routeSummary.isNotBlank())
    }

    // --------------------------------------------------- expat intelligence

    @Test
    fun theDigestCountsOnlyThePostingsOpenToMadagascar() {
        val open = job(id = "open", country = "Canada")
        val closed = job(
            id = "closed",
            country = "Germany",
            eligibility = MadagascarEligibility.RESTRICTED,
        )

        val shortlist = shortlistEngine.build(
            jobs = listOf(open, closed),
            profile = profile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 100,
            salaryEurPerYear = { 60_000 },
        )
        val digest = intelligenceEngine.build(shortlist)

        assertEquals(2, digest.totalJobs)
        assertEquals(1, digest.actionableCount)
        assertEquals(1, digest.countries.size)
        assertEquals("Canada", digest.countries.first().country)
        assertTrue(digest.hasRoutes)
    }

    @Test
    fun anEmptyFeedSaysSoRatherThanImplyingOpportunity() {
        val shortlist = shortlistEngine.build(
            jobs = emptyList(),
            profile = profile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 100,
            salaryEurPerYear = { null },
        )
        val digest = intelligenceEngine.build(shortlist)

        assertTrue(digest.isEmpty)
        assertFalse(digest.hasRoutes)
        assertTrue(digest.countries.isEmpty())
        assertTrue(digest.summary.contains("No postings", ignoreCase = true))
    }

    @Test
    fun theDigestSurfacesTheStrongestOpportunitiesFirst() {
        val jobs = listOf(
            job(id = "strong", matchScore = 95),
            job(id = "weaker", matchScore = 61, missingSkills = listOf("Red Seal", "French C1", "PLC")),
        )
        val shortlist = shortlistEngine.build(
            jobs = jobs,
            profile = profile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 100,
            salaryEurPerYear = { 60_000 },
        )
        val digest = intelligenceEngine.build(shortlist)

        assertNotNull(digest.topOpportunities.firstOrNull())
        val scores = digest.topOpportunities.map { it.readinessScore }
        assertEquals(scores.sortedDescending(), scores)
    }
}
