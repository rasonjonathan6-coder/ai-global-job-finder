package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.expat.ShortlistEngine
import com.aiglobal.jobfinder.data.geo.CountryCatalog
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityInput
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.ExpatPreferences
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.WorkMode
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.VisaSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 19 hardening.
 *
 * Pins the two behaviours this pass tightened:
 *  - a region is not a country, so the region rules must not be short-circuited by a
 *    location that merely contains a region word (e.g. "South Africa" contains "africa");
 *  - the readiness score is fully explainable: its four components sum to the number the
 *    user sees, so the rank can be traced rather than trusted.
 */
class Stage19HardeningTest {

    private val engine = EligibilityEngine()

    private val profile = CandidateProfile(
        fullName = "Jonathan Rakoto",
        currentTitle = "Maintenance Technician",
        previousTitles = listOf("Industrial Electrician"),
        yearsOfExperience = 6,
        skills = listOf("Preventive maintenance", "Hydraulics", "PLC"),
    )

    // --------------------------------------------------- region vs country

    @Test
    fun aRemoteRoleScopedToACountryThatEmbedsARegionNameStaysUnclear() {
        // "South Africa" contains the region word "africa", but it is a country. A remote
        // role bounded to it must not be rated eligible via the Africa region rule.
        val assessment = engine.assess(
            EligibilityInput(
                title = "Remote Maintenance Planner",
                location = "South Africa",
                description = "Remote role, must be based in South Africa.",
                isRemote = true,
                isRecognizedCountry = true,
            ),
        )
        assertEquals(MadagascarEligibility.UNCLEAR, assessment.eligibility)
    }

    @Test
    fun aRemoteRoleScopedToTheAfricaRegionIsEligible() {
        val assessment = engine.assess(
            EligibilityInput(
                title = "Remote Support Agent",
                location = "Africa",
                description = "Open to candidates across Africa.",
                isRemote = true,
                isRecognizedCountry = false,
            ),
        )
        assertEquals(MadagascarEligibility.ELIGIBLE, assessment.eligibility)
    }

    @Test
    fun theCatalogTreatsRegionsAsUnrecognisedAndRealCountriesAsRecognised() {
        val catalog = CountryCatalog
        assertFalse(catalog.isKnownCountry("EMEA"))
        assertFalse(catalog.isKnownCountry("Europe"))
        assertFalse(catalog.isKnownCountry("Africa"))
        assertFalse(catalog.isKnownCountry(CountryCatalog.WORLDWIDE))
        assertTrue(catalog.isKnownCountry("Madagascar"))
        assertTrue(catalog.isKnownCountry("Canada"))
        assertTrue(catalog.isKnownCountry("South Africa"))
    }

    // --------------------------------------------------- explainable ranking

    @Test
    fun theRankComponentsSumToTheReadinessScore() {
        val job = job(matchScore = 80)
        val entry = ShortlistEngine(clock = { 0L }).assess(
            job = job,
            profile = profile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 50,
            salaryEurPerYear = null,
        )

        assertEquals(4, entry.rankComponents.size)
        val sum = entry.rankComponents.sumOf { it.points }
        assertEquals(entry.readinessScore, sum)

        // Each component reports the ceiling it was scored against: 45 route + 30 CV +
        // 15 benefits + 10 profile.
        val maxima = entry.rankComponents.map { it.maxPoints }
        assertEquals(listOf(45, 30, 15, 10), maxima)
    }

    @Test
    fun everyComponentReportsWithinItsCeiling() {
        val entry = ShortlistEngine(clock = { 0L }).assess(
            job = job(matchScore = 100),
            profile = profile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 100,
            salaryEurPerYear = 60000,
        )
        entry.rankComponents.forEach { component ->
            assertTrue(
                "${component.label} earned ${component.points} of ${component.maxPoints}",
                component.points in 0..component.maxPoints,
            )
        }
    }

    private fun job(matchScore: Int = 80): Job = Job(
        id = "job-1",
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
        eligibility = MadagascarEligibility.VISA_SPONSORED,
        visaSupport = VisaSupport.FULL_SPONSORSHIP,
        scamRisk = ScamRisk.LOW,
        postedAtEpochMillis = 1_699_999_000_000,
        source = "Canada Job Bank",
        sourceUrl = "https://www.jobbank.gc.ca",
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
        matchReasons = emptyList(),
        eligibilityReasons = emptyList(),
        benefitEvidence = emptyList(),
    )
}
