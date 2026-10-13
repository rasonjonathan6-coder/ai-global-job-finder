package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.geo.CurrencyConverter
import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityInput
import com.aiglobal.jobfinder.data.jobs.engine.MatchInput
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.provider.HtmlText
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.jobs.provider.SalaryParser
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.LanguageLevel
import com.aiglobal.jobfinder.data.model.LanguageSkill
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.MatchWeights
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.VisaSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 3 pipeline behaviour. These run on the JVM without Robolectric because the
 * engines, the parsers and the mapper are pure functions of their inputs — which is
 * exactly what makes them worth testing.
 */
class Stage3PipelineTest {

    private val eligibilityEngine = EligibilityEngine()
    private val matchingEngine = MatchingEngine()

    // ---- Eligibility -------------------------------------------------------

    @Test
    fun localOnlyPostingIsRestrictedAndCitesThePosting() {
        val assessment = eligibilityEngine.assess(
            EligibilityInput(
                title = "Maintenance Technician",
                location = "Toronto, Canada",
                description = "Local candidates only. Must already have the right to work in Canada.",
            ),
        )
        assertEquals(MadagascarEligibility.RESTRICTED, assessment.eligibility)
        assertEquals(VisaSupport.LOCAL_ONLY, assessment.visaSupport)
        assertTrue(assessment.reasons.any { it.official })
    }

    @Test
    fun sponsorshipStatementIsTreatedAsAnOfficialSignal() {
        val assessment = eligibilityEngine.assess(
            EligibilityInput(
                title = "Mechanical Fitter",
                location = "Munich, Germany",
                description = "We offer visa sponsorship and a relocation package for the right candidate.",
            ),
        )
        assertEquals(MadagascarEligibility.VISA_SPONSORED, assessment.eligibility)
        assertTrue(assessment.hasOfficialSignal)
    }

    @Test
    fun silenceIsNeverReadAsSponsorship() {
        val assessment = eligibilityEngine.assess(
            EligibilityInput(
                title = "Electrician",
                location = "Dubai, United Arab Emirates",
                description = "Install and maintain electrical systems on industrial sites.",
            ),
        )
        assertEquals(MadagascarEligibility.UNCLEAR, assessment.eligibility)
        assertEquals(VisaSupport.NOT_MENTIONED, assessment.visaSupport)
        assertTrue(assessment.reasons.any { !it.official })
    }

    @Test
    fun remoteWorldwideRoleIsEligible() {
        val assessment = eligibilityEngine.assess(
            EligibilityInput(
                title = "Customer Support Specialist",
                location = "Worldwide",
                description = "Work from anywhere in the world.",
                isRemote = true,
            ),
        )
        assertEquals(MadagascarEligibility.ELIGIBLE, assessment.eligibility)
    }

    @Test
    fun restrictionWinsOverSponsorshipMention() {
        val assessment = eligibilityEngine.assess(
            EligibilityInput(
                title = "Warehouse Operative",
                location = "Sydney, Australia",
                description = "No visa sponsorship. Applicants must be citizens and permanent residents.",
            ),
        )
        assertEquals(MadagascarEligibility.RESTRICTED, assessment.eligibility)
    }

    // ---- Matching ----------------------------------------------------------

    private val technicianProfile = CandidateProfile(
        fullName = "Jonathan Rakoto",
        currentTitle = "Maintenance Technician",
        previousTitles = listOf("Industrial Electrician"),
        yearsOfExperience = 6,
        skills = listOf("Preventive maintenance", "Hydraulics", "PLC", "Electrical diagnostics"),
        languages = listOf(LanguageSkill("French", LanguageLevel.NATIVE), LanguageSkill("English", LanguageLevel.FLUENT)),
        preferredCountries = listOf("Canada", "Germany"),
    )

    @Test
    fun strongProfileScoresHigherThanEmptyProfile() {
        val job = MatchInput(
            title = "Maintenance Technician",
            country = "Canada",
            description = "Preventive maintenance, hydraulics and PLC work. 5 years experience. English required.",
            tags = listOf("maintenance"),
            postedDaysAgo = 2,
        )
        val strong = matchingEngine.assess(job, technicianProfile).score
        val empty = matchingEngine.assess(job, CandidateProfile()).score
        assertTrue("expected strong ($strong) > empty ($empty)", strong > empty)
        assertTrue(strong >= 60)
    }

    @Test
    fun everyPointOfTheScoreIsExplained() {
        val assessment = matchingEngine.assess(
            MatchInput(
                title = "Maintenance Technician",
                country = "Canada",
                description = "Preventive maintenance and hydraulics. 5 years experience.",
                postedDaysAgo = 3,
            ),
            technicianProfile,
        )
        assertEquals(6, assessment.reasons.size)
        assertTrue(assessment.reasons.all { it.weight >= 0 })
        assertTrue(assessment.matchedSkills.contains("Preventive maintenance"))
    }

    @Test
    fun scoreStaysWithinDisplayBounds() {
        val assessment = matchingEngine.assess(
            MatchInput(
                title = "Maintenance Technician",
                country = "Canada",
                description = "Preventive maintenance hydraulics PLC electrical diagnostics. 1 year experience.",
            ),
            technicianProfile,
        )
        assertTrue(assessment.score in 5..99)
    }

    @Test
    fun missingSkillIsReportedNotHidden() {
        val assessment = matchingEngine.assess(
            MatchInput(
                title = "Maintenance Technician",
                country = "Canada",
                description = "Requires welding and preventive maintenance. 3 years experience.",
            ),
            technicianProfile,
        )
        assertTrue(assessment.missingSkills.contains("Welding"))
        // A partial skill match must score below a full one rather than being rounded up.
        val skillsReason = assessment.reasons.first { it.label == "Skills" }
        assertTrue(skillsReason.weight < MatchWeights().skills)
    }

    // ---- Salary parsing ----------------------------------------------------

    @Test
    fun parsesSymbolRangeWithThousandsSeparators() {
        val parsed = SalaryParser.parse("$58,000 - $72,000 per year")
        assertEquals(58_000, parsed.min)
        assertEquals(72_000, parsed.max)
        assertEquals("USD", parsed.currency)
        assertEquals(SalaryPeriod.YEAR, parsed.period)
    }

    @Test
    fun parsesEuropeanDecimalAmounts() {
        val parsed = SalaryParser.parse("€35,5k - €52k a year")
        assertEquals(35_500, parsed.min)
        assertEquals(52_000, parsed.max)
        assertEquals("EUR", parsed.currency)
    }

    @Test
    fun unparseableSalaryYieldsNothingRatherThanAGuess() {
        val parsed = SalaryParser.parse("Competitive")
        assertNull(parsed.min)
        assertNull(parsed.max)
        assertNull(parsed.currency)
    }

    // ---- Currency ----------------------------------------------------------

    @Test
    fun convertsKnownCurrenciesToEurPerYear() {
        assertEquals(9200, CurrencyConverter.toEurPerYear(10_000, "USD", SalaryPeriod.YEAR))
        assertEquals(1104, CurrencyConverter.toEurPerYear(100, "USD", SalaryPeriod.MONTH))
        assertTrue(CurrencyConverter.canConvert("CAD"))
        assertFalse(CurrencyConverter.canConvert("XYZ"))
    }

    @Test
    fun unknownCurrencyDoesNotInventANumber() {
        assertNull(CurrencyConverter.toEurPerYear(1000, "XYZ", SalaryPeriod.YEAR))
    }

    // ---- HTML --------------------------------------------------------------

    @Test
    fun htmlIsFlattenedIntoReadableText() {
        val text = HtmlText.toPlainText("<p>Line one</p><ul><li>Item &amp; more</li></ul>")
        assertEquals("Line one\nItem & more", text)
    }

    // ---- End-to-end mapping ------------------------------------------------

    @Test
    fun mapperProducesAJobWithExplainedScoreAndVerdict() {
        val mapper = JobMapper(matchingEngine, eligibilityEngine, clock = { 1_760_000_000_000 })
        val job = mapper.toJob(
            RawJob(
                providerId = "remotive",
                providerName = "Remotive",
                externalId = "42",
                title = "Maintenance Technician",
                company = "Northwind Industrial",
                location = "Toronto, Canada",
                descriptionHtml = "<p>Visa sponsorship available. Preventive maintenance and hydraulics.</p>",
                url = "https://example.com/jobs/42",
                tags = listOf("maintenance"),
                salaryMin = 60_000,
                salaryMax = 72_000,
                salaryCurrency = "CAD",
                salaryPeriod = SalaryPeriod.YEAR,
                postedAtEpochMillis = 1_759_000_000_000,
                isRemote = false,
                employmentType = "full_time",
            ),
            technicianProfile,
        )

        assertEquals("remotive:42", job.id)
        assertEquals("Canada", job.country)
        assertEquals("Toronto", job.city)
        assertTrue(job.matchReasons.isNotEmpty())
        assertTrue(job.eligibilityReasons.isNotEmpty())
        assertEquals(MadagascarEligibility.VISA_SPONSORED, job.eligibility)
        assertTrue(job.applyableFromMadagascar)
        assertFalse(job.salaryIsEstimated)
        assertNotNull(mapper.eurPerYear(job))
    }

    @Test
    fun unknownSalaryIsMarkedEstimatedAndCurrencyBlank() {
        val mapper = JobMapper(matchingEngine, eligibilityEngine, clock = { 1_760_000_000_000 })
        val job = mapper.toJob(
            RawJob(
                providerId = "arbeitnow",
                providerName = "Arbeitnow",
                externalId = "7",
                title = "Technician",
                company = "Acme",
                location = "Worldwide",
                descriptionHtml = "",
                url = "https://example.com/jobs/7",
            ),
            CandidateProfile(),
        )
        assertTrue(job.salaryIsEstimated)
        assertEquals("", job.currencyCode)
        assertNull(mapper.eurPerYear(job))
    }
}
