package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.engine.InternationalFitEngine
import com.aiglobal.jobfinder.data.jobs.search.RoleFamily
import com.aiglobal.jobfinder.data.model.BenefitEvidence
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.EvidenceStrength
import com.aiglobal.jobfinder.data.model.ExpatBenefitsAssessment
import com.aiglobal.jobfinder.data.model.InternationalFitAssessment
import com.aiglobal.jobfinder.data.model.InternationalFitBand
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobBenefitTypes
import com.aiglobal.jobfinder.data.model.JobRequirements
import com.aiglobal.jobfinder.data.model.LanguageLevel
import com.aiglobal.jobfinder.data.model.LanguageSkill
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import com.aiglobal.jobfinder.viewmodel.JobSort
import com.aiglobal.jobfinder.viewmodel.JobsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 41 — an explainable international fit per posting.
 *
 * The app already keeps three numbers apart: the CV match, the Madagascar eligibility verdict
 * and the documented Expat Benefits Score. This stage adds a fourth read that folds the
 * destination, the route in from Madagascar, the language and the credential facts into one
 * number, and proves it is honest: silence is neutral rather than negative, the number is the
 * sum of its stated components, and a thin profile produces "not enough profile" rather than a
 * confident low score.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage41InternationalFitTest {

    private val engine = InternationalFitEngine()

    private fun profile(
        title: String = "Maintenance Technician",
        skills: List<String> = listOf("Preventive maintenance", "Hydraulics"),
        years: Int? = 5,
        languages: List<LanguageSkill> = listOf(LanguageSkill("English", LanguageLevel.FLUENT)),
        preferredCountries: List<String> = listOf("Saudi Arabia"),
    ) = CandidateProfile(
        currentTitle = title,
        yearsOfExperience = years,
        skills = skills,
        languages = languages,
        preferredCountries = preferredCountries,
    )

    private fun job(
        eligibility: MadagascarEligibility = MadagascarEligibility.UNCLEAR,
        visaSupport: VisaSupport = VisaSupport.NOT_MENTIONED,
        roleFamily: RoleFamily = RoleFamily.TARGET,
        country: String = "Saudi Arabia",
        matchedSkills: List<String> = listOf("Preventive maintenance", "Hydraulics"),
        missingSkills: List<String> = listOf("PLC"),
        languages: List<String> = listOf("English"),
        minYears: Int? = 3,
        scamRisk: ScamRisk = ScamRisk.LOW,
    ) = Job(
        id = "j1",
        title = "Maintenance Technician",
        company = "Acme",
        companyAccent = 0xFF1E4FD8,
        country = country,
        countryFlag = "🇸🇦",
        city = "Dammam",
        salaryMin = 60_000,
        salaryMax = 60_000,
        currencyCode = "SAR",
        salaryPeriod = SalaryPeriod.YEAR,
        matchScore = 70,
        eligibility = eligibility,
        visaSupport = visaSupport,
        scamRisk = scamRisk,
        postedAtEpochMillis = 1_000L,
        source = "Workday",
        sourceUrl = "https://example.com/j1",
        workMode = WorkMode.ONSITE,
        employmentType = EmploymentType.FULL_TIME,
        experienceYears = minYears ?: 0,
        matchedSkills = matchedSkills,
        missingSkills = missingSkills,
        languages = languages,
        summary = "Summary",
        responsibilities = emptyList(),
        benefits = emptyList(),
        aiInsight = "insight",
        providerId = "workday",
        roleFamily = roleFamily,
        requirements = JobRequirements(minYears = minYears),
    )

    // ---- Band mapping ------------------------------------------------------

    @Test
    fun bandMapsScoreThresholdsAndNeverConfusesLowWithInsufficient() {
        assertEquals(InternationalFitBand.EXCELLENT, InternationalFitBand.fromScore(80))
        assertEquals(InternationalFitBand.STRONG, InternationalFitBand.fromScore(65))
        assertEquals(InternationalFitBand.POSSIBLE, InternationalFitBand.fromScore(45))
        assertEquals(InternationalFitBand.WEAK, InternationalFitBand.fromScore(44))
        assertEquals(InternationalFitBand.WEAK, InternationalFitBand.fromScore(0))
    }

    // ---- Route from Madagascar --------------------------------------------

    @Test
    fun eligibilityRouteIsScoredWithOfficialStrength() {
        val eligible = engine.assess(job(eligibility = MadagascarEligibility.ELIGIBLE), profile())
        val sponsored = engine.assess(job(eligibility = MadagascarEligibility.VISA_SPONSORED), profile())
        val unclear = engine.assess(job(eligibility = MadagascarEligibility.UNCLEAR), profile())
        val restricted = engine.assess(job(eligibility = MadagascarEligibility.RESTRICTED), profile())

        val eligibleRoute = eligible.components.first { it.label == "Route from Madagascar" }
        val sponsoredRoute = sponsored.components.first { it.label == "Route from Madagascar" }
        val unclearRoute = unclear.components.first { it.label == "Route from Madagascar" }
        val restrictedRoute = restricted.components.first { it.label == "Route from Madagascar" }

        assertEquals(35, eligibleRoute.points)
        assertEquals(EvidenceStrength.DOCUMENTED, eligibleRoute.strength)
        assertEquals(30, sponsoredRoute.points)
        assertEquals(10, unclearRoute.points)
        // Silence is "not stated", never a refusal.
        assertEquals(EvidenceStrength.UNKNOWN, unclearRoute.strength)
        assertEquals(0, restrictedRoute.points)
        assertEquals(EvidenceStrength.REFUSED, restrictedRoute.strength)
    }

    // ---- Honesty of the total ---------------------------------------------

    @Test
    fun scoreIsTheSumOfItsComponentsAndNeverExceedsOneHundred() {
        val result = engine.assess(job(eligibility = MadagascarEligibility.ELIGIBLE), profile())
        assertEquals(result.components.sumOf { it.points }, result.score)
        assertTrue(result.score <= 100)
    }

    @Test
    fun eachComponentIsCappedAtItsOwnCeiling() {
        val result = engine.assess(job(eligibility = MadagascarEligibility.ELIGIBLE), profile())
        result.components.forEach { component ->
            assertTrue("${component.label} earned ${component.points} of ${component.maxPoints}",
                component.points in 0..component.maxPoints)
        }
    }

    // ---- Role, skills, experience, language, destination -------------------

    @Test
    fun roleAlignmentRewardsTargetTitlesAndPenalisesUnrelatedOnes() {
        val target = engine.assess(job(roleFamily = RoleFamily.TARGET), profile())
        val maintenance = engine.assess(job(roleFamily = RoleFamily.MAINTENANCE_FAMILY), profile())
        val unrelated = engine.assess(job(roleFamily = RoleFamily.UNRELATED), profile())

        val targetRole = target.components.first { it.label == "Role alignment" }
        val unrelatedRole = unrelated.components.first { it.label == "Role alignment" }
        assertEquals(20, targetRole.points)
        assertEquals(EvidenceStrength.DOCUMENTED, targetRole.strength)
        assertTrue(maintenance.components.first { it.label == "Role alignment" }.points in 1..19)
        assertTrue(unrelatedRole.points < 5)
    }

    @Test
    fun skillsComponentUsesTheCanonicalSkillMatchAndGatesUnrelatedRoles() {
        val aligned = engine.assess(job(roleFamily = RoleFamily.TARGET), profile())
        val unrelated = engine.assess(job(roleFamily = RoleFamily.UNRELATED), profile())

        val alignedSkills = aligned.components.first { it.label == "Skills" }
        val unrelatedSkills = unrelated.components.first { it.label == "Skills" }

        // Two of three named skills match: 2/3 of 20, gated at 1.0 for a target role.
        assertEquals(13, alignedSkills.points)
        // The same overlap counts for little when it is not a role the candidate does.
        assertTrue(unrelatedSkills.points < alignedSkills.points)
    }

    @Test
    fun silenceAboutSkillsIsNeutralNotNegative() {
        val silent = engine.assess(job(matchedSkills = emptyList(), missingSkills = emptyList()), profile())
        val skills = silent.components.first { it.label == "Skills" }
        assertEquals(EvidenceStrength.UNKNOWN, skills.strength)
        assertEquals(10, skills.points)
    }

    @Test
    fun experienceMeetsRequestedYearsOrReportsTheGap() {
        val meets = engine.assess(job(minYears = 3), profile(years = 5))
        val gap = engine.assess(job(minYears = 10), profile(years = 2))

        assertEquals(10, meets.components.first { it.label == "Experience" }.points)
        assertTrue(gap.components.first { it.label == "Experience" }.points in 1..6)
    }

    @Test
    fun languageIsDocumentedOnlyWhenThePostingNamesALanguageTheCvSpeaksFluently() {
        val fluent = engine.assess(job(languages = listOf("English")), profile())
        val missing = engine.assess(
            job(languages = listOf("Arabic")),
            profile(languages = listOf(LanguageSkill("English", LanguageLevel.FLUENT))),
        )

        assertEquals(10, fluent.components.first { it.label == "Language" }.points)
        assertEquals(EvidenceStrength.DOCUMENTED, fluent.components.first { it.label == "Language" }.strength)
        assertEquals(0, missing.components.first { it.label == "Language" }.points)
    }

    @Test
    fun destinationRewardsPreferredCountries() {
        val preferred = engine.assess(job(country = "Saudi Arabia"), profile(preferredCountries = listOf("Saudi Arabia")))
        val other = engine.assess(job(country = "Canada"), profile(preferredCountries = listOf("Saudi Arabia")))

        assertEquals(5, preferred.components.first { it.label == "Destination" }.points)
        assertTrue(other.components.first { it.label == "Destination" }.points < 5)
    }

    // ---- Insufficient profile ---------------------------------------------

    @Test
    fun thinProfileProducesInsufficientRatherThanAConfidentNumber() {
        val result = engine.assess(job(), CandidateProfile())
        assertEquals(InternationalFitBand.INSUFFICIENT, result.band)
        assertEquals(0, result.score)
        assertTrue(result.isInsufficient)
        assertTrue(result.components.isEmpty())
        assertTrue(result.explanation.contains("not enough", ignoreCase = true))
    }

    // ---- Blockers ----------------------------------------------------------

    @Test
    fun restrictedRolesAndFlaggedPostingsSurfaceAsBlockers() {
        val restricted = engine.assess(job(eligibility = MadagascarEligibility.RESTRICTED), profile())
        val flagged = engine.assess(job(scamRisk = ScamRisk.HIGH), profile())

        assertTrue(restricted.blockers.any { it.contains("local applicants", ignoreCase = true) })
        assertTrue(flagged.blockers.any { it.contains("scam", ignoreCase = true) })
    }

    // ---- Expat benefits stay a separate number -----------------------------

    @Test
    fun documentedExpatScoreIsShownBesideTheFitAndNeverBlendedIn() {
        val expat = ExpatBenefitsAssessment(
            score = 82,
            evidenceCoverage = 70,
            components = listOf(
                com.aiglobal.jobfinder.data.model.BenefitScoreComponent(
                    type = JobBenefitTypes.VISA_SPONSORSHIP,
                    priority = com.aiglobal.jobfinder.data.model.BenefitPriority.ESSENTIAL,
                    status = BenefitStatus.CONFIRMED,
                    evidence = "Visa sponsorship provided.",
                    credit = 1f,
                    potentialPoints = 3,
                    earnedPoints = 3,
                ),
            ),
            explanation = "score 82",
            unmetEssentials = emptyList(),
            lowEvidence = false,
        )
        val withEvidence = engine.assess(job(), profile(), expat)
        val withoutEvidence = engine.assess(job(), profile())

        assertEquals(82, withEvidence.expatBenefitsScore)
        assertEquals(70, withEvidence.expatEvidenceCoverage)
        // The benefit score never leaks into the fit score: both postings score the same.
        assertEquals(withEvidence.score, withoutEvidence.score)
        assertEquals(null, withoutEvidence.expatBenefitsScore)
    }

    // ---- UI state: filter and sort ----------------------------------------

    private fun fit(score: Int): InternationalFitAssessment = InternationalFitAssessment(
        score = score,
        band = InternationalFitBand.fromScore(score),
        components = emptyList(),
        blockers = emptyList(),
        routeSummary = "route",
        expatBenefitsScore = null,
        expatEvidenceCoverage = 0,
        expatLowEvidence = false,
        explanation = "explanation",
    )

    @Test
    fun strongFitFilterKeepsOnlyActionableBands() {
        val strong = job(eligibility = MadagascarEligibility.ELIGIBLE).copy(id = "strong")
        val weak = job(eligibility = MadagascarEligibility.RESTRICTED).copy(id = "weak")
        val state = JobsUiState(
            jobs = listOf(strong, weak),
            strongInternationalFitOnly = true,
            internationalFit = mapOf("strong" to fit(78), "weak" to fit(30)),
        )

        assertEquals(listOf("strong"), state.visibleJobs.map { it.id })
        assertTrue(state.hasActiveFilters)
    }

    @Test
    fun internationalFitSortOrdersByScoreThenEligibility() {
        val high = job(eligibility = MadagascarEligibility.RESTRICTED).copy(id = "high")
        val low = job(eligibility = MadagascarEligibility.ELIGIBLE).copy(id = "low")
        val state = JobsUiState(
            jobs = listOf(low, high),
            sort = JobSort.INTERNATIONAL_FIT,
            internationalFit = mapOf("high" to fit(90), "low" to fit(50)),
        )

        assertEquals(listOf("high", "low"), state.visibleJobs.map { it.id })
    }

    @Test
    fun savingIsUnaffectedByTheNewFitState() {
        val saved = job().copy(id = "saved")
        val state = JobsUiState(
            jobs = listOf(saved),
            internationalFit = mapOf("saved" to fit(60)),
            savedJobIds = setOf("saved"),
        )
        assertTrue(state.isSaved(saved))
        assertFalse(state.hasActiveFilters)
    }
}
