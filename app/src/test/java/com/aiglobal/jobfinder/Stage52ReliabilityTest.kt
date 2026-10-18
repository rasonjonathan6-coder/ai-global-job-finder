package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.freshness.Freshness
import com.aiglobal.jobfinder.data.jobs.freshness.JobFreshnessEngine
import com.aiglobal.jobfinder.data.jobs.provider.SalaryParser
import com.aiglobal.jobfinder.data.jobs.search.QueryExpansion
import com.aiglobal.jobfinder.data.jobs.search.RoleFamily
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import com.aiglobal.jobfinder.ui.components.SALARY_NOT_STATED
import com.aiglobal.jobfinder.ui.components.SALARY_TO_VERIFY
import com.aiglobal.jobfinder.ui.components.publishedLabel
import com.aiglobal.jobfinder.ui.components.salaryLabel
import com.aiglobal.jobfinder.viewmodel.JobsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 52 — reliability of the international feed.
 *
 * Each test pins one of the defects found on a real phone: a trade search that returned
 * nothing, a salary shown as "USD 106k–112k/hr", a publication date that could be confused
 * with the app's own fetch, and header counters that read as the same number when they are
 * not. The tests assert the honest behaviour, not just the fixed output.
 */
class Stage52ReliabilityTest {

    // ---- 1. Trade search by synonym ----------------------------------------

    @Test
    fun mechanicalSealQueryFindsRotatingEquipmentPosting() {
        val title = "Rotating Equipment Maintenance Technician"
        assertTrue(
            "a seal query must find the rotating-equipment posting a candidate would recognise",
            QueryExpansion.matches("Mechanical Seal Technician", title),
        )
    }

    @Test
    fun sealQueryDoesNotMatchUnrelatedTrade() {
        val title = "Senior Frontend Developer"
        assertFalse(QueryExpansion.matches("Mechanical Seal Technician", title))
    }

    @Test
    fun shortQueryDoesNotMatchInsideALongerWord() {
        // "pump" must not fire inside "pumpkin" — whole-word matching, not a loose substring.
        assertFalse(QueryExpansion.matches("pump", "Pumpkin farm operations manager"))
        assertTrue(QueryExpansion.matches("pump", "Centrifugal pump maintenance"))
    }

    @Test
    fun partialQueryStillNarrowsWhileTyping() {
        // The literal query is tried first so a half-typed word keeps narrowing the list.
        assertTrue(QueryExpansion.matches("rotat", "Rotating Equipment Mechanic"))
    }

    @Test
    fun expansionIncludesTheLiteralQueryFirst() {
        val terms = QueryExpansion.expand("Mechanical Seal Technician")
        assertEquals("mechanical seal technician", terms.first())
        assertTrue(terms.contains("rotating equipment"))
    }

    // ---- 2. The search field value is carried, cleared and passed through ----

    @Test
    fun queryIsAppliedToVisibleJobsAndClearedByClearFilters() {
        val jobs = listOf(
            job(id = "1", title = "Rotating Equipment Maintenance Technician"),
            job(id = "2", title = "Frontend Developer"),
        )
        val filtered = JobsUiState(jobs = jobs, query = "Mechanical Seal Technician").visibleJobs
        assertEquals(listOf("1"), filtered.map { it.id })

        val cleared = JobsUiState(jobs = jobs, query = "").visibleJobs
        assertEquals(listOf("1", "2"), cleared.map { it.id })
    }

    @Test
    fun queryIsTrimmedSoStrayWhitespaceDoesNotHideResults() {
        val jobs = listOf(job(id = "1", title = "Pump Technician"))
        val filtered = JobsUiState(jobs = jobs, query = "  pump  ").visibleJobs
        assertEquals(listOf("1"), filtered.map { it.id })
    }

    // ---- 3. Salary unit honesty --------------------------------------------

    @Test
    fun annualFigureMislabelledHourlyIsFlaggedNotShown() {
        val parsed = SalaryParser.parse("USD 106,000 - 112,000 per hour")
        assertTrue("an annual figure labelled hourly must be flagged", parsed.unitUncertain)
        assertNull("no trustworthy period should survive", parsed.period)
    }

    @Test
    fun ordinaryHourlyFigureStaysTrustworthy() {
        val parsed = SalaryParser.parse("$45 per hour")
        assertEquals(45, parsed.min)
        assertEquals(SalaryPeriod.HOUR, parsed.period)
        assertFalse(parsed.unitUncertain)
    }

    @Test
    fun inferredPeriodIsRecordedRatherThanAssumed() {
        // No unit stated: the period is inferred, and the flag says so.
        val parsed = SalaryParser.parse("$60,000")
        assertEquals(60_000, parsed.min)
        assertEquals(SalaryPeriod.YEAR, parsed.period)
        assertTrue(parsed.periodInferred)
        assertFalse(parsed.unitUncertain)
    }

    @Test
    fun salaryLabelShowsVerifyForAnUncertainUnit() {
        val uncertain = job(id = "1", salaryMin = 106_000, currency = "USD", period = SalaryPeriod.HOUR)
            .copy(salaryUnitUncertain = true)
        assertEquals(SALARY_TO_VERIFY, salaryLabel(uncertain))
    }

    @Test
    fun salaryLabelShowsNotStatedWhenTheSourceGaveNone() {
        val none = job(id = "1", salaryMin = 0, currency = "")
        assertEquals(SALARY_NOT_STATED, salaryLabel(none))
    }

    @Test
    fun salaryLabelShowsTheRealFigureWhenItIsTrustworthy() {
        val real = job(id = "1", salaryMin = 58_000, currency = "USD", period = SalaryPeriod.YEAR)
        assertTrue(salaryLabel(real).contains("58"))
        assertTrue(salaryLabel(real).contains("USD"))
    }

    // ---- 4. Eligibility counters read as verified --------------------------

    @Test
    fun eligibleCountCountsOnlyApplyablePostings() {
        val jobs = listOf(
            job(id = "1", eligibility = MadagascarEligibility.ELIGIBLE),
            job(id = "2", eligibility = MadagascarEligibility.VISA_SPONSORED),
            job(id = "3", eligibility = MadagascarEligibility.UNCLEAR),
            job(id = "4", eligibility = MadagascarEligibility.RESTRICTED),
        )
        assertEquals(2, JobsUiState(jobs = jobs).eligibleCount)
    }

    @Test
    fun unclearEligibilityIsNotCountedAsOpenToMadagascar() {
        val unclear = job(id = "1", eligibility = MadagascarEligibility.UNCLEAR)
        assertFalse(unclear.applyableFromMadagascar)
    }

    // ---- 5. Freshness: publication vs retrieval vs discovery ---------------

    @Test
    fun missingPublicationDateIsUnknownNeverFresh() {
        val assessment = JobFreshnessEngine().assess(job(id = "1", postedAt = null))
        assertEquals(Freshness.UNKNOWN, assessment.freshness)
        assertEquals("Date not stated", assessment.label)
    }

    @Test
    fun oldPostingIsNotPresentedAsNew() {
        val now = 1_000_000_000_000L
        // Older than the engine's stale window (120 days by default).
        val old = job(id = "1", postedAt = now - 200L * 86_400_000L)
        val assessment = JobFreshnessEngine().assess(old, now = now)
        assertEquals(Freshness.STALE, assessment.freshness)
        assertTrue(assessment.detail.contains("older than"))
    }

    @Test
    fun publishedLabelNamesTheEmployerDateNotTheFetch() {
        val now = 1_000_000_000_000L
        val label = publishedLabel(now - 3L * 86_400_000L, now = now)
        assertTrue("the publication label must say it is the employer's date", label.startsWith("Published"))
    }

    @Test
    fun publishedLabelNeverInventsADate() {
        assertEquals("Date not stated", publishedLabel(null))
    }

    // ---- 6. Counter honesty -------------------------------------------------

    @Test
    fun cvMatchCounterIsSeparateFromMadagascarEligibility() {
        // A posting can score highly for the CV yet be unclear for Madagascar. The two counts
        // must never be conflated, so the eligibility count ignores the match score entirely.
        val jobs = listOf(
            job(id = "1", matchScore = 95, eligibility = MadagascarEligibility.UNCLEAR),
            job(id = "2", matchScore = 20, eligibility = MadagascarEligibility.ELIGIBLE),
        )
        assertEquals(1, JobsUiState(jobs = jobs).eligibleCount)
        assertEquals(2, JobsUiState(jobs = jobs).jobs.size)
    }

    // ---- fixtures -----------------------------------------------------------

    private fun job(
        id: String,
        title: String = "Maintenance Technician",
        company: String = "Acme",
        country: String = "Canada",
        matchScore: Int = 70,
        eligibility: MadagascarEligibility = MadagascarEligibility.ELIGIBLE,
        visaSupport: VisaSupport = VisaSupport.NOT_MENTIONED,
        postedAt: Long? = 1_000L,
        salaryMin: Int = 50_000,
        currency: String = "CAD",
        period: SalaryPeriod = SalaryPeriod.YEAR,
    ) = Job(
        id = id,
        title = title,
        company = company,
        companyAccent = 0xFF1E4FD8,
        country = country,
        countryFlag = "🇨🇦",
        city = "Toronto",
        salaryMin = salaryMin,
        salaryMax = salaryMin,
        currencyCode = currency,
        salaryPeriod = period,
        matchScore = matchScore,
        eligibility = eligibility,
        visaSupport = visaSupport,
        scamRisk = ScamRisk.LOW,
        postedAtEpochMillis = postedAt,
        source = "Remotive",
        sourceUrl = "https://example.com/$id",
        workMode = WorkMode.ONSITE,
        employmentType = EmploymentType.FULL_TIME,
        experienceYears = 3,
        matchedSkills = listOf("Preventive maintenance"),
        missingSkills = emptyList(),
        languages = listOf("English"),
        summary = "Summary for $title",
        responsibilities = emptyList(),
        benefits = emptyList(),
        aiInsight = "insight",
        providerId = "remotive",
        roleFamily = RoleFamily.TARGET,
    )
}
