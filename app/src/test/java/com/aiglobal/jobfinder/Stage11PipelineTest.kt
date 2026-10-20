package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.expat.ShortlistEngine
import com.aiglobal.jobfinder.data.jobs.search.RoleClassifier
import com.aiglobal.jobfinder.data.model.ApplicationReadiness
import com.aiglobal.jobfinder.data.model.BenefitEvidence
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.BlockerKind
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.EligibilityReason
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.ExpatPreferences
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobBenefitTypes
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.ShortlistState
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 11 behaviour: the application-eligibility shortlist. The readiness score answers
 * "can I actually apply for this from Madagascar, and what is stopping me?", which is a
 * different question from the CV match score.
 *
 * Two invariants are pinned here, mirroring the rest of the pipeline:
 *  1. Silence is never treated as a refusal. An unstated route scores low and produces a
 *     heuristic "verify" blocker, never an official claim that the employer refuses.
 *  2. Postings the employer restricts to local applicants are excluded outright, because
 *     there is no route in from Madagascar.
 */
class Stage11PipelineTest {

    private val engine = ShortlistEngine(clock = { 1_700_000_000_000 })

    private val completeProfile = CandidateProfile(
        fullName = "Jonathan Rakoto",
        currentTitle = "Maintenance Technician",
        previousTitles = listOf("Industrial Electrician"),
        yearsOfExperience = 6,
        skills = listOf("Preventive maintenance", "Hydraulics", "PLC"),
    )

    /** Documents every benefit the default preferences weight above zero. */
    private val allDocumentedBenefits: List<BenefitEvidence> = listOf(
        BenefitEvidence(JobBenefitTypes.VISA_SPONSORSHIP, BenefitStatus.CONFIRMED, "We provide visa sponsorship."),
        BenefitEvidence(JobBenefitTypes.RELOCATION_SUPPORT, BenefitStatus.CONFIRMED, "Relocation support is included."),
        BenefitEvidence(JobBenefitTypes.ACCOMMODATION, BenefitStatus.CONFIRMED, "Accommodation is provided."),
        BenefitEvidence(JobBenefitTypes.FLIGHTS_HOME, BenefitStatus.CONFIRMED, "One flight home each year."),
        BenefitEvidence(JobBenefitTypes.HEALTH_INSURANCE, BenefitStatus.CONFIRMED, "Private health insurance."),
        BenefitEvidence(JobBenefitTypes.WORK_PERMIT, BenefitStatus.CONFIRMED, "We obtain your work permit."),
        BenefitEvidence(JobBenefitTypes.PAID_LEAVE, BenefitStatus.CONFIRMED, "30 days paid annual leave."),
        BenefitEvidence(JobBenefitTypes.FAMILY_SUPPORT, BenefitStatus.CONFIRMED, "Dependants are supported."),
    )

    private fun job(
        id: String = "job-1",
        title: String = "Maintenance Technician",
        country: String = "Canada",
        matchScore: Int = 90,
        eligibility: MadagascarEligibility = MadagascarEligibility.VISA_SPONSORED,
        visaSupport: VisaSupport = VisaSupport.FULL_SPONSORSHIP,
        scamRisk: ScamRisk = ScamRisk.LOW,
        missingSkills: List<String> = emptyList(),
        eligibilityReasons: List<EligibilityReason> = listOf(
            EligibilityReason("Visa sponsorship offered", "The employer states visa sponsorship.", official = true),
        ),
        benefitEvidence: List<BenefitEvidence> = allDocumentedBenefits,
        isNew: Boolean = false,
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
        scamRisk = scamRisk,
        postedAtEpochMillis = 1_699_999_000_000,
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
        matchReasons = emptyList(),
        eligibilityReasons = eligibilityReasons,
        benefitEvidence = benefitEvidence,
        isNew = isNew,
        // The real mapper fills the role verdict from the title; these fixtures build Job by
        // hand, so they classify it the same way the pipeline would rather than defaulting.
        roleFamily = RoleClassifier.classify(title).family,
    )

    // ------------------------------------------------------------ scoring model

    @Test
    fun aFullyDocumentedSponsoredRoleWithACompleteProfileReachesTopPriority() {
        val entry = engine.assess(
            job = job(matchScore = 100, benefitEvidence = allDocumentedBenefits),
            profile = completeProfile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 100,
            salaryEurPerYear = 60000,
        )

        // 45 eligibility + 30 match + 15 benefits + 10 profile = 100.
        assertEquals(100, entry.readinessScore)
        assertEquals(ApplicationReadiness.PRIORITY, entry.readiness)
        assertTrue(entry.blockers.isEmpty())
        assertEquals("Nothing is holding this back — the route and your profile both line up.", entry.blockerSummary)
    }

    @Test
    fun anUnclearPostingScoresLowAndNeverClaimsARefusal() {
        val entry = engine.assess(
            job = job(
                matchScore = 60,
                eligibility = MadagascarEligibility.UNCLEAR,
                visaSupport = VisaSupport.NOT_MENTIONED,
                eligibilityReasons = emptyList(),
                benefitEvidence = emptyList(),
            ),
            profile = completeProfile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 50,
            salaryEurPerYear = 60000,
        )

        // 18 eligibility + 18 match + 0 benefits + 5 profile = 41.
        assertEquals(41, entry.readinessScore)
        assertEquals(ApplicationReadiness.NEEDS_REVIEW, entry.readiness)

        val eligibilityBlocker = entry.blockers.first { it.label == "Eligibility not stated" }
        assertEquals(BlockerKind.HEURISTIC, eligibilityBlocker.kind)
        assertFalse(
            "An unstated route must never be presented as an official refusal",
            eligibilityBlocker.isOfficial,
        )
        assertFalse(entry.blockers.any { it.isOfficial })
    }

    @Test
    fun silenceAboutBenefitsIsMissingInformationNotAShortcoming() {
        val entry = engine.assess(
            job = job(matchScore = 95, benefitEvidence = emptyList()),
            profile = completeProfile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 100,
            salaryEurPerYear = null,
        )

        assertEquals(0, entry.expatScore)
        val blocker = entry.blockers.first { it.label == "Benefits not stated" }
        assertEquals(BlockerKind.HEURISTIC, blocker.kind)
        assertTrue(
            "The blocker must say the information is missing, not that the employer refused",
            blocker.detail.contains("not a refusal"),
        )
    }

    @Test
    fun aSponsoredRoleWithoutPinnedVisaTermsAsksTheUserToConfirm() {
        val entry = engine.assess(
            job = job(
                eligibility = MadagascarEligibility.VISA_SPONSORED,
                // Sponsored verdict, but no benefit sentence that confirms the visa terms.
                benefitEvidence = listOf(
                    BenefitEvidence(JobBenefitTypes.RELOCATION_SUPPORT, BenefitStatus.CONFIRMED, "Relocation support."),
                ),
            ),
            profile = completeProfile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 100,
            salaryEurPerYear = 60000,
        )

        val confirm = entry.blockers.first { it.label == "Confirm the visa terms" }
        assertEquals(BlockerKind.HEURISTIC, confirm.kind)
        assertEquals(8, confirm.points)
    }

    @Test
    fun blockersAreOrderedByImpactAndCarryTheirSource() {
        val entry = engine.assess(
            job = job(
                matchScore = 60,
                eligibility = MadagascarEligibility.UNCLEAR,
                visaSupport = VisaSupport.NOT_MENTIONED,
                eligibilityReasons = emptyList(),
                benefitEvidence = emptyList(),
                missingSkills = listOf("Canadian Red Seal certification", "French (B2)"),
            ),
            profile = completeProfile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 50,
            salaryEurPerYear = 60000,
        )

        val ordered = entry.orderedBlockers
        assertEquals("Eligibility not stated", ordered.first().label)
        // Strictly non-increasing by withheld points.
        ordered.zipWithNext().forEach { (a, b) ->
            assertTrue("Blockers must be ordered hardest-hitting first", a.points >= b.points)
        }
        assertTrue(ordered.any { it.kind == BlockerKind.PROFILE })
    }

    @Test
    fun aThinProfileProducesAProfileGapBlocker() {
        val entry = engine.assess(
            job = job(),
            profile = completeProfile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 40,
            salaryEurPerYear = 60000,
        )

        val gap = entry.blockers.first { it.label == "Profile 40% complete" }
        assertEquals(BlockerKind.PROFILE, gap.kind)
        assertTrue(gap.isProfileGap)
        // (80 - 40) * 10 / 100 = 4.
        assertEquals(4, gap.points)
    }

    @Test
    fun aHighScamRiskIsSurfacedBeforeTheUserApplies() {
        val entry = engine.assess(
            job = job(scamRisk = ScamRisk.HIGH),
            profile = completeProfile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 100,
            salaryEurPerYear = 60000,
        )

        val scam = entry.blockers.first { it.label == "Scam shield flagged this posting" }
        assertEquals(20, scam.points)
    }

    // ------------------------------------------------------------ list assembly

    @Test
    fun postingsRestrictedToLocalApplicantsAreExcludedFromTheShortlist() {
        val restricted = job(
            id = "restricted",
            eligibility = MadagascarEligibility.RESTRICTED,
            visaSupport = VisaSupport.LOCAL_ONLY,
            eligibilityReasons = listOf(
                EligibilityReason("Local applicants only", "The posting requires existing work authorisation.", official = true),
            ),
        )
        val open = job(id = "open")

        val state = engine.build(
            jobs = listOf(restricted, open),
            profile = completeProfile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 100,
            salaryEurPerYear = { 60000 },
        )

        assertEquals(1, state.entries.size)
        assertEquals("open", state.entries.single().job.id)
        // The total still counts everything that was screened.
        assertEquals(2, state.totalJobs)
    }

    @Test
    fun entriesAreRankedByReadinessThenMatchScore() {
        val top = job(id = "top", matchScore = 100)
        val strong = job(id = "strong", matchScore = 80)
        val weaker = job(id = "weaker", matchScore = 55, missingSkills = listOf("PLC"))

        val state = engine.build(
            jobs = listOf(weaker, top, strong),
            profile = completeProfile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 100,
            salaryEurPerYear = { 60000 },
        )

        assertEquals(listOf("top", "strong", "weaker"), state.entries.map { it.job.id })
        state.entries.zipWithNext().forEach { (a, b) ->
            assertTrue(a.readinessScore >= b.readinessScore)
        }
    }

    @Test
    fun theLimitCapsHowManyEntriesAreReturned() {
        val jobs = (1..12).map { job(id = "job-$it", matchScore = 60 + it) }

        val state = engine.build(
            jobs = jobs,
            profile = completeProfile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 100,
            salaryEurPerYear = { 60000 },
            limit = 5,
        )

        assertEquals(5, state.entries.size)
        assertEquals(12, state.totalJobs)
    }

    @Test
    fun recurringProfileGapsCollapseToOneEntryPerLabel() {
        val jobs = (1..3).map { job(id = "job-$it") }

        val state = engine.build(
            jobs = jobs,
            profile = completeProfile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 40,
            salaryEurPerYear = { 60000 },
        )

        val gapLabels = state.recurringProfileGaps.map { it.label }
        assertEquals(listOf("Profile 40% complete"), gapLabels)
    }

    // ------------------------------------------------------------ state aggregates

    @Test
    fun theStateCountsEveryReadinessBandAndTheOfficialRoute() {
        val state = ShortlistState(
            isLoading = false,
            entries = listOf(
                engine.assess(job(id = "p", matchScore = 100), completeProfile, ExpatPreferences.defaults(), 100, 60000),
                engine.assess(
                    job(
                        id = "v",
                        matchScore = 60,
                        eligibility = MadagascarEligibility.UNCLEAR,
                        visaSupport = VisaSupport.NOT_MENTIONED,
                        eligibilityReasons = emptyList(),
                        benefitEvidence = emptyList(),
                    ),
                    completeProfile, ExpatPreferences.defaults(), 50, 60000,
                ),
            ),
        )

        assertEquals(1, state.priorityCount)
        assertEquals(0, state.readyCount)
        assertEquals(1, state.verifyCount)
        assertEquals(0, state.blockedCount)
        assertEquals(1, state.actionableCount)
        // Only the sponsored posting states an official route in.
        assertEquals(1, state.officialRouteCount)
        assertFalse(state.isEmpty)
    }

    @Test
    fun readinessBandsAreOrderedWeakestToStrongestAndMarkActionableOnes() {
        assertEquals(ApplicationReadiness.BLOCKED, ApplicationReadiness.fromScore(10))
        assertEquals(ApplicationReadiness.NEEDS_REVIEW, ApplicationReadiness.fromScore(30))
        assertEquals(ApplicationReadiness.PREPARE_FIRST, ApplicationReadiness.fromScore(50))
        assertEquals(ApplicationReadiness.READY, ApplicationReadiness.fromScore(70))
        assertEquals(ApplicationReadiness.PRIORITY, ApplicationReadiness.fromScore(85))

        assertTrue(ApplicationReadiness.PRIORITY.isActionable)
        assertTrue(ApplicationReadiness.READY.isActionable)
        assertFalse(ApplicationReadiness.NEEDS_REVIEW.isActionable)
        assertFalse(ApplicationReadiness.BLOCKED.isActionable)

        assertTrue(ApplicationReadiness.BLOCKED.ordinal < ApplicationReadiness.PRIORITY.ordinal)
    }
}
