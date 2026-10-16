package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.expat.ShortlistEngine
import com.aiglobal.jobfinder.data.model.BenefitEvidence
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.EligibilityReason
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.ExpatPreferences
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobBenefitTypes
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.NextStepAction
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.ShortlistQualityBand
import com.aiglobal.jobfinder.data.model.ShortlistState
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 26 behaviour: the shortlist-quality read and the actionable ranking.
 *
 * Stage 25 fixed what the feed contains; this stage answers a different question — is the
 * shortlist I am looking at actually strong, and what is the single next thing to do about
 * each posting? Two invariants are pinned here, mirroring the rest of the pipeline:
 *
 *  1. The dashboard only reports what the listings state. A list with no route in is "thin",
 *     never dressed up as opportunity, and the next step for a silent posting is "ask the
 *     employer", not a claim that the employer refused.
 *  2. A local-only posting is never "worth preparing for": there is no route in to prepare,
 *     so it belongs in neither the actionable nor the preparation list.
 *
 * Everything here is a pure function of the entry list, so it runs on the JVM.
 */
class Stage26PipelineTest {

    private val engine = ShortlistEngine(clock = { 1_700_000_000_000 })

    private val completeProfile = CandidateProfile(
        fullName = "Jonathan Rakoto",
        currentTitle = "Maintenance Technician",
        yearsOfExperience = 6,
        skills = listOf("Preventive maintenance", "Hydraulics", "PLC"),
    )

    private val allDocumentedBenefits: List<BenefitEvidence> = listOf(
        BenefitEvidence(JobBenefitTypes.VISA_SPONSORSHIP, BenefitStatus.CONFIRMED, "We provide visa sponsorship."),
        BenefitEvidence(JobBenefitTypes.RELOCATION_SUPPORT, BenefitStatus.CONFIRMED, "Relocation support is included."),
        BenefitEvidence(JobBenefitTypes.ACCOMMODATION, BenefitStatus.CONFIRMED, "Accommodation is provided."),
        BenefitEvidence(JobBenefitTypes.FLIGHTS_HOME, BenefitStatus.CONFIRMED, "One flight home each year."),
        BenefitEvidence(JobBenefitTypes.HEALTH_INSURANCE, BenefitStatus.CONFIRMED, "Private health insurance."),
        BenefitEvidence(JobBenefitTypes.FAMILY_SUPPORT, BenefitStatus.CONFIRMED, "Dependants are supported."),
    )

    private fun job(
        id: String = "job-1",
        title: String = "Maintenance Technician",
        country: String = "Canada",
        countryFlag: String = "🇨🇦",
        matchScore: Int = 90,
        eligibility: MadagascarEligibility = MadagascarEligibility.VISA_SPONSORED,
        visaSupport: VisaSupport = VisaSupport.FULL_SPONSORSHIP,
        scamRisk: ScamRisk = ScamRisk.LOW,
        missingSkills: List<String> = emptyList(),
        eligibilityReasons: List<EligibilityReason> = listOf(
            EligibilityReason("Visa sponsorship offered", "The employer states visa sponsorship.", official = true),
        ),
        benefitEvidence: List<BenefitEvidence> = allDocumentedBenefits,
    ) = Job(
        id = id,
        title = title,
        company = "Northline Industrial",
        companyAccent = 0xFF1E4FD8,
        country = country,
        countryFlag = countryFlag,
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
    )

    private fun assess(job: Job, completeness: Int = 100, salary: Int? = 60000) =
        engine.assess(job, completeProfile, ExpatPreferences.defaults(), completeness, salary)

    // ------------------------------------------------------------- next step

    @Test
    fun aReadyPostingTellsTheUserToApply() {
        val step = assess(job(matchScore = 100)).nextStep
        assertEquals(NextStepAction.APPLY, step.action)
        assertEquals("Ready to apply", step.label)
    }

    @Test
    fun aSilentPostingAsksTheUserToCheckEligibilityNotToGiveUp() {
        val step = assess(
            job(
                eligibility = MadagascarEligibility.UNCLEAR,
                visaSupport = VisaSupport.NOT_MENTIONED,
                eligibilityReasons = emptyList(),
            ),
        ).nextStep
        assertEquals(NextStepAction.REVIEW_ELIGIBILITY, step.action)
        // The advice must be "ask", never a claim the employer refuses.
        assertTrue(step.detail.contains("Ask before applying"))
    }

    @Test
    fun aHighScamRiskPostingAsksForTheScamCheckFirst() {
        // The scam blocker outranks the CV match gap, so it is the next step.
        val step = assess(job(matchScore = 60, scamRisk = ScamRisk.HIGH, missingSkills = listOf("PLC"))).nextStep
        assertEquals(NextStepAction.RUN_SCAM_CHECK, step.action)
    }

    @Test
    fun aCloseMatchGapBecomesATailorYourCvStep() {
        // Strong eligibility and full benefits, but a CV match below the strong threshold
        // and no other, harder-hitting blocker.
        val step = assess(job(matchScore = 60)).nextStep
        assertEquals(NextStepAction.TAILOR_CV, step.action)
    }

    @Test
    fun anUndocumentedPackageAsksAboutTheBenefits() {
        val step = assess(job(matchScore = 95, benefitEvidence = emptyList())).nextStep
        assertEquals(NextStepAction.CONFIRM_BENEFITS, step.action)
        assertTrue(step.detail.contains("not a refusal"))
    }

    @Test
    fun aThinProfileBecomesACompleteProfileStep() {
        val step = assess(job(matchScore = 100), completeness = 40).nextStep
        assertEquals(NextStepAction.COMPLETE_PROFILE, step.action)
    }

    // ------------------------------------------------------- actionable ranking

    @Test
    fun actionableEntriesKeepTheirEngineOrderAndExcludeLocalOnlyPostings() {
        val top = assess(job(id = "top", matchScore = 100))
        val strong = assess(job(id = "strong", matchScore = 80))
        val localOnly = assess(
            job(
                id = "local",
                eligibility = MadagascarEligibility.RESTRICTED,
                visaSupport = VisaSupport.LOCAL_ONLY,
                eligibilityReasons = listOf(
                    EligibilityReason("Local applicants only", "Existing work authorisation required.", official = true),
                ),
            ),
        )

        val state = ShortlistState(isLoading = false, entries = listOf(top, strong, localOnly))

        assertEquals(listOf("top", "strong"), state.rankedActionable.map { it.job.id })
        // The local-only posting has no route in, so it is not "worth preparing for" either.
        assertFalse(state.needsPreparation.any { it.job.id == "local" })
    }

    @Test
    fun preparationListsOnlyNonActionablePostingsWithARouteIn() {
        val ready = assess(job(id = "ready", matchScore = 100))
        val prepare = assess(
            job(
                id = "prepare",
                matchScore = 60,
                eligibility = MadagascarEligibility.UNCLEAR,
                visaSupport = VisaSupport.NOT_MENTIONED,
                eligibilityReasons = emptyList(),
                benefitEvidence = emptyList(),
            ),
            completeness = 50,
        )

        val state = ShortlistState(isLoading = false, entries = listOf(ready, prepare))

        assertEquals(listOf("prepare"), state.needsPreparation.map { it.job.id })
        assertEquals(listOf("ready"), state.rankedActionable.map { it.job.id })
    }

    // ------------------------------------------------------------- quality read

    @Test
    fun anEmptyShortlistReportsNoDataAndNoOpportunity() {
        val quality = ShortlistState(isLoading = false, entries = emptyList()).quality
        assertEquals(ShortlistQualityBand.NONE, quality.band)
        assertEquals(0, quality.actionableCount)
        assertFalse(quality.hasActionable)
        assertNull(quality.topCountry)
    }

    @Test
    fun aListWithNoRouteInIsThinAndNeverClaimsOpportunity() {
        val unclear = assess(
            job(
                matchScore = 55,
                eligibility = MadagascarEligibility.UNCLEAR,
                visaSupport = VisaSupport.NOT_MENTIONED,
                eligibilityReasons = emptyList(),
                benefitEvidence = emptyList(),
            ),
            completeness = 40,
        )
        val state = ShortlistState(isLoading = false, entries = listOf(unclear, unclear.copy(job = job(id = "b", matchScore = 50))))

        val quality = state.quality
        assertEquals(ShortlistQualityBand.THIN, quality.band)
        assertEquals(0, quality.actionableCount)
        assertEquals("Routes in are thin right now", quality.headline)
        assertNull("No actionable posting means no leading country", quality.topCountry)
    }

    @Test
    fun oneActionablePostingIsBuildingAndNamesTheLeadingCountry() {
        val ready = assess(job(id = "ready", country = "Germany", countryFlag = "🇩🇪", matchScore = 100))
        val prepare = assess(
            job(
                id = "prepare",
                matchScore = 55,
                eligibility = MadagascarEligibility.UNCLEAR,
                visaSupport = VisaSupport.NOT_MENTIONED,
                eligibilityReasons = emptyList(),
                benefitEvidence = emptyList(),
            ),
            completeness = 40,
        )
        val state = ShortlistState(isLoading = false, entries = listOf(ready, prepare))

        val quality = state.quality
        assertEquals(ShortlistQualityBand.BUILDING, quality.band)
        assertEquals(1, quality.actionableCount)
        assertEquals(50, quality.actionableShare)
        assertEquals(100, quality.strongestReadiness)
        assertEquals("🇩🇪" to "Germany", quality.topCountry)
        assertEquals(2, quality.countriesRepresented)
    }

    @Test
    fun fiveActionablePostingsIncludingAPriorityAreAStrongShortlist() {
        val actionable = (1..5).map { assess(job(id = "job-$it", matchScore = 100)) }
        val weaker = listOf(
            assess(
                job(
                    id = "w1",
                    matchScore = 55,
                    eligibility = MadagascarEligibility.UNCLEAR,
                    visaSupport = VisaSupport.NOT_MENTIONED,
                    eligibilityReasons = emptyList(),
                    benefitEvidence = emptyList(),
                ),
                completeness = 40,
            ),
            assess(
                job(
                    id = "w2",
                    matchScore = 50,
                    eligibility = MadagascarEligibility.UNCLEAR,
                    visaSupport = VisaSupport.NOT_MENTIONED,
                    eligibilityReasons = emptyList(),
                    benefitEvidence = emptyList(),
                ),
                completeness = 40,
            ),
        )
        val state = ShortlistState(isLoading = false, entries = actionable + weaker)

        val quality = state.quality
        assertEquals(ShortlistQualityBand.STRONG, quality.band)
        assertEquals(5, quality.actionableCount)
        assertEquals(71, quality.actionableShare)
        assertEquals(100, quality.strongestReadiness)
        assertEquals("5 postings you can act on now", quality.headline)
    }

    @Test
    fun theQualityDetailStatesWhatWasScreenedAndNeverOverclaims() {
        val state = ShortlistState(isLoading = false, entries = listOf(assess(job(matchScore = 100))))
        val detail = state.quality.detail
        assertTrue(detail.contains("Screened 1 posting"))
        assertTrue(detail.contains("verify before you invest time"))
    }
}
