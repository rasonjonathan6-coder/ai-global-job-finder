package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.expat.ShortlistEngine
import com.aiglobal.jobfinder.data.jobs.ExpatRankingEngine
import com.aiglobal.jobfinder.data.jobs.search.RoleFamily
import com.aiglobal.jobfinder.data.model.ApplicationReadiness
import com.aiglobal.jobfinder.data.model.BenefitEvidence
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.ExpatPreferences
import com.aiglobal.jobfinder.data.model.ExpatRankingState
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobBenefitTypes
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.OfferVerification
import com.aiglobal.jobfinder.data.model.RankingLabel
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.VerificationState
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 48 behaviour: the expatriate ranking that the opportunity/ranking foundation was
 * built for, now surfaced.
 *
 * The point of these tests is that the ranking *cannot disagree with the screens that feed
 * it*: it reads readiness straight from the shortlist, it never invents a label whose
 * evidence is absent, and a posting with a hard stop can never sit at the top however
 * attractive its package looks.
 */
class Stage48ExpatRankingTest {

    private val now = 1_700_000_000_000L
    private val shortlistEngine = ShortlistEngine(clock = { now })
    private val rankingEngine = ExpatRankingEngine(clock = { now })

    private val profile = CandidateProfile(
        fullName = "Jonathan Rakoto",
        currentTitle = "Maintenance Technician",
        previousTitles = listOf("Industrial Electrician"),
        yearsOfExperience = 6,
        skills = listOf("Preventive maintenance", "Hydraulics", "PLC"),
    )

    private val strongBenefits = listOf(
        BenefitEvidence(JobBenefitTypes.VISA_SPONSORSHIP, BenefitStatus.CONFIRMED, "Visa sponsorship provided."),
        BenefitEvidence(JobBenefitTypes.RELOCATION_SUPPORT, BenefitStatus.CONFIRMED, "Relocation support is included."),
        BenefitEvidence(JobBenefitTypes.ACCOMMODATION, BenefitStatus.CONFIRMED, "Accommodation is provided."),
        BenefitEvidence(JobBenefitTypes.FLIGHTS_HOME, BenefitStatus.CONFIRMED, "Annual flights home are covered."),
        BenefitEvidence(JobBenefitTypes.HEALTH_INSURANCE, BenefitStatus.CONFIRMED, "Private health insurance."),
    )

    private val confirmedVerification = OfferVerification(
        activity = VerificationState.CONFIRMED,
        deadline = VerificationState.CONFIRMED,
        link = VerificationState.CONFIRMED,
        visa = VerificationState.CONFIRMED,
        relocation = VerificationState.CONFIRMED,
        lastRetrievedAtEpochMillis = now,
        freshness = VerificationState.CONFIRMED,
        facts = emptyList(),
        caveats = emptyList(),
        summary = "Checked.",
    )

    private fun job(
        id: String = "job-1",
        matchScore: Int = 92,
        eligibility: MadagascarEligibility = MadagascarEligibility.VISA_SPONSORED,
        visaSupport: VisaSupport = VisaSupport.FULL_SPONSORSHIP,
        scamRisk: ScamRisk = ScamRisk.LOW,
        benefitEvidence: List<BenefitEvidence> = strongBenefits,
    ) = Job(
        id = id,
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
        matchScore = matchScore,
        eligibility = eligibility,
        visaSupport = visaSupport,
        scamRisk = scamRisk,
        postedAtEpochMillis = now - 86_400_000,
        source = "Canada Job Bank",
        sourceUrl = "https://www.jobbank.gc.ca",
        workMode = WorkMode.ONSITE,
        employmentType = EmploymentType.FULL_TIME,
        experienceYears = 3,
        roleFamily = RoleFamily.TARGET,
        openToApplicants = true,
        matchedSkills = listOf("Preventive maintenance", "Hydraulics"),
        missingSkills = emptyList(),
        languages = listOf("English (B2+)"),
        summary = "Maintain production line machinery across two shifts.",
        responsibilities = listOf("Run preventive maintenance", "Log work orders"),
        benefits = listOf("Relocation allowance"),
        aiInsight = "Strong technical fit.",
        verification = confirmedVerification,
        benefitEvidence = benefitEvidence,
    )

    private fun rank(jobs: List<Job>): ExpatRankingState {
        val shortlist = shortlistEngine.build(
            jobs = jobs,
            profile = profile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 100,
            salaryEurPerYear = { 60_000 },
        )
        return rankingEngine.rank(shortlist, ExpatPreferences.defaults())
    }

    // ------------------------------------------------------------ ranking

    @Test
    fun ranksStrongestFirstAndNumbersEveryPositionFromOne() {
        val state = rank(
            listOf(
                job(id = "weak", matchScore = 40, eligibility = MadagascarEligibility.UNCLEAR, benefitEvidence = emptyList()),
                job(id = "strong", matchScore = 96),
            ),
        )

        assertEquals(2, state.ranked.size)
        assertEquals(listOf(1, 2), state.ranked.map { it.rank })
        assertEquals("strong", state.ranked.first().job.id)
        assertTrue(state.ranked.first().finalScore >= state.ranked.last().finalScore)
    }

    @Test
    fun readinessComesStraightFromTheShortlistSoTheTwoScreensCannotDisagree() {
        val jobs = listOf(job(id = "a"), job(id = "b", matchScore = 60, benefitEvidence = emptyList()))
        val shortlist = shortlistEngine.build(
            jobs = jobs,
            profile = profile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 100,
            salaryEurPerYear = { 60_000 },
        )
        val state = rankingEngine.rank(shortlist, ExpatPreferences.defaults())

        val readinessByJob = shortlist.entries.associate { it.job.id to it.readinessScore }
        state.ranked.forEach { item ->
            assertEquals(readinessByJob[item.job.id], item.readinessScore)
        }
    }

    @Test
    fun aPostingRestrictedToLocalApplicantsNeverEntersTheRanking() {
        // There is no route in from Madagascar, so the shortlist drops it before the ranking
        // ever sees it. The total still counts it, but it cannot occupy a place.
        val state = rank(
            listOf(
                job(id = "local-only", eligibility = MadagascarEligibility.RESTRICTED, matchScore = 99),
                job(id = "open", matchScore = 70, eligibility = MadagascarEligibility.ELIGIBLE),
            ),
        )

        assertEquals(1, state.ranked.size)
        assertEquals("open", state.ranked.single().job.id)
        assertEquals(2, state.totalJobs)
    }

    @Test
    fun aPostingWithNoReachableRouteIsCappedAndPlacedBelowEveryApplicableOne() {
        // An unstated route scores so low that the posting is not actionable. It stays in the
        // ranking (unlike a hard restriction) but is capped, so it can never sit at the top.
        val state = rank(
            listOf(
                job(id = "unreachable", eligibility = MadagascarEligibility.UNCLEAR, matchScore = 0, benefitEvidence = emptyList()),
                job(id = "open", matchScore = 70, eligibility = MadagascarEligibility.ELIGIBLE),
            ),
        )

        assertEquals("open", state.ranked.first().job.id)
        val capped = state.ranked.last()
        assertEquals("unreachable", capped.job.id)
        assertEquals(ApplicationReadiness.BLOCKED, capped.readiness)
        assertTrue(capped.finalScore <= 35)
        assertTrue(capped.labels.contains(RankingLabel.NOT_OPEN_TO_YOU))
    }

    @Test
    fun aScamFlaggedPostingCannotSitAtTheTopHoweverGoodItsPackageLooks() {
        val state = rank(
            listOf(
                job(id = "scam", scamRisk = ScamRisk.HIGH, matchScore = 99),
                job(id = "clean", matchScore = 72),
            ),
        )

        assertEquals("clean", state.ranked.first().job.id)
        val scam = state.ranked.first { it.job.id == "scam" }
        assertTrue(scam.opportunity.disqualified)
        assertTrue(scam.finalScore <= 35)
    }

    // ------------------------------------------------------------ honesty

    @Test
    fun labelsAreOnlyAttachedWhenTheirEvidenceExists() {
        val state = rank(
            listOf(
                job(
                    id = "documented",
                    benefitEvidence = listOf(
                        BenefitEvidence(JobBenefitTypes.RELOCATION_SUPPORT, BenefitStatus.CONFIRMED, "Relocation support is included."),
                    ),
                ),
                job(
                    id = "silent",
                    eligibility = MadagascarEligibility.ELIGIBLE,
                    visaSupport = VisaSupport.NOT_MENTIONED,
                    benefitEvidence = emptyList(),
                ),
            ),
        )

        val documented = state.ranked.first { it.job.id == "documented" }
        val silent = state.ranked.first { it.job.id == "silent" }

        assertTrue(documented.labels.contains(RankingLabel.RELOCATION_CONFIRMED))
        assertTrue(documented.labels.contains(RankingLabel.VISA_SPONSORSHIP))

        // Silence must never produce a positive label — only the honest caution.
        assertFalse(silent.labels.contains(RankingLabel.RELOCATION_CONFIRMED))
        assertFalse(silent.labels.contains(RankingLabel.VISA_SPONSORSHIP))
        assertFalse(silent.labels.contains(RankingLabel.ACCOMMODATION_CONFIRMED))
        assertTrue(silent.labels.contains(RankingLabel.BENEFITS_NOT_DISCLOSED))
    }

    @Test
    fun aCautionIsSurfacedEvenWhenThePostingScoresWell() {
        val state = rank(
            listOf(job(id = "unclear-eligibility", eligibility = MadagascarEligibility.UNCLEAR, matchScore = 95)),
        )
        val item = state.ranked.single()

        assertTrue(item.labels.contains(RankingLabel.VERIFY_ELIGIBILITY))
    }

    @Test
    fun rankingIsDeterministicSoTheSameFeedAlwaysYieldsTheSameOrder() {
        val jobs = listOf(job(id = "a"), job(id = "b"), job(id = "c", matchScore = 55, benefitEvidence = emptyList()))

        val first = rank(jobs).ranked.map { it.job.id }
        val second = rank(jobs).ranked.map { it.job.id }

        assertEquals(first, second)
    }

    @Test
    fun anEmptyFeedRanksNothingRatherThanGuessing() {
        val state = rank(emptyList())

        assertTrue(state.ranked.isEmpty())
        assertEquals(0, state.totalJobs)
        assertEquals(0, state.actionableCount)
        assertEquals(0, state.disqualifiedCount)
    }

    @Test
    fun theTwoIngredientsStayVisibleBesideTheTotal() {
        val item = rank(listOf(job(id = "a"))).ranked.single()

        // Readiness and the expatriate package are both on the record, so the rank can be
        // traced rather than taken on faith.
        assertTrue(item.readinessScore in 0..100)
        assertTrue(item.opportunity.score in 0..100)
        assertTrue(item.finalScore in 0..100)
        assertTrue(item.opportunity.dimensions.isNotEmpty())
    }
}
