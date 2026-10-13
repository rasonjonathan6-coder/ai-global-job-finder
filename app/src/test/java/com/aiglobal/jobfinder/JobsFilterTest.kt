package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.Job
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

/**
 * The Jobs screen derives everything the user sees from [JobsUiState]. These tests
 * exercise that derivation directly, so a filter or sort regression fails here rather
 * than only showing up as a wrong card in the UI.
 */
class JobsFilterTest {

    private fun job(
        id: String,
        title: String = "Maintenance Technician",
        company: String = "Acme",
        country: String = "Canada",
        providerId: String = "remotive",
        source: String = "Remotive",
        matchScore: Int = 70,
        eligibility: MadagascarEligibility = MadagascarEligibility.ELIGIBLE,
        visaSupport: VisaSupport = VisaSupport.NOT_MENTIONED,
        isSaved: Boolean = false,
        postedAt: Long = 1_000L,
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
        source = source,
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
        providerId = providerId,
        isSaved = isSaved,
    )

    private val jobs = listOf(
        job(id = "1", country = "Canada", matchScore = 90, eligibility = MadagascarEligibility.VISA_SPONSORED, visaSupport = VisaSupport.SPONSORSHIP_AVAILABLE, salaryMin = 80_000, postedAt = 500L),
        job(id = "2", company = "Siemens", country = "Germany", matchScore = 60, eligibility = MadagascarEligibility.ELIGIBLE, salaryMin = 60_000, currency = "EUR", postedAt = 900L, providerId = "arbeitnow", source = "Arbeitnow"),
        job(id = "3", country = "Australia", matchScore = 40, eligibility = MadagascarEligibility.RESTRICTED, visaSupport = VisaSupport.LOCAL_ONLY, salaryMin = 40_000, currency = "AUD", postedAt = 700L, isSaved = true),
        job(id = "4", country = "Worldwide", matchScore = 75, eligibility = MadagascarEligibility.UNCLEAR, postedAt = 300L, providerId = "remotive", source = "Remotive"),
    )

    @Test
    fun eligibleFilterKeepsOnlyApplyableJobs() {
        val visible = JobsUiState(jobs = jobs, eligibleOnly = true).visibleJobs
        assertEquals(listOf("1", "2"), visible.map { it.id })
    }

    @Test
    fun sponsoredFilterKeepsOnlySponsorshipPostings() {
        val visible = JobsUiState(jobs = jobs, sponsoredOnly = true).visibleJobs
        assertEquals(listOf("1"), visible.map { it.id })
    }

    @Test
    fun countryFilterMatchesCanonicalName() {
        val visible = JobsUiState(jobs = jobs, country = "Germany").visibleJobs
        assertEquals(listOf("2"), visible.map { it.id })
    }

    @Test
    fun sourceFilterMatchesProviderId() {
        val visible = JobsUiState(jobs = jobs, source = "arbeitnow").visibleJobs
        assertEquals(listOf("2"), visible.map { it.id })
    }

    @Test
    fun savedFilterKeepsBookmarkedJobs() {
        val visible = JobsUiState(jobs = jobs, savedOnly = true).visibleJobs
        assertEquals(listOf("3"), visible.map { it.id })
    }

    @Test
    fun queryMatchesTitleCompanyCountryAndSummary() {
        assertEquals(listOf("1"), JobsUiState(jobs = jobs, query = "canada").visibleJobs.map { it.id })
        // Every sample title is "Maintenance Technician"; assert as a set since the
        // default best-match sort reorders them.
        assertEquals(
            setOf("1", "2", "3", "4"),
            JobsUiState(jobs = jobs, query = "technician").visibleJobs.map { it.id }.toSet(),
        )
        assertEquals(listOf("2"), JobsUiState(jobs = jobs, query = "siemens").visibleJobs.map { it.id })
    }

    @Test
    fun sortBySalaryUsesNormalisedCurrencyNotRawAmount() {
        // Two postings with the identical raw number but different currencies: EUR is
        // worth more per unit than USD, so the EUR one must rank first even though the
        // raw amounts are equal. A zero-salary posting sorts last.
        val euro = job(id = "eur", country = "Germany", salaryMin = 60_000, currency = "EUR")
        val usd = job(id = "usd", country = "United States", salaryMin = 60_000, currency = "USD")
        val unknown = job(id = "none", country = "Worldwide", salaryMin = 0, currency = "")
        val state = JobsUiState(jobs = listOf(usd, euro, unknown), sort = JobSort.SALARY_HIGH)

        assertEquals(listOf("eur", "usd", "none"), state.visibleJobs.map { it.id })
    }

    @Test
    fun sortNewestUsesPostingTimestamp() {
        val visible = JobsUiState(jobs = jobs, sort = JobSort.NEWEST).visibleJobs
        assertEquals(listOf("2", "3", "1", "4"), visible.map { it.id })
    }

    @Test
    fun bestMatchIsTheDefaultOrder() {
        val visible = JobsUiState(jobs = jobs).visibleJobs
        assertEquals(listOf("1", "4", "2", "3"), visible.map { it.id })
    }

    @Test
    fun filtersComposeRatherThanReplaceEachOther() {
        val state = JobsUiState(jobs = jobs, eligibleOnly = true, country = "Canada", query = "technician")
        assertEquals(listOf("1"), state.visibleJobs.map { it.id })
    }

    @Test
    fun clearingFiltersRestoresEveryJob() {
        val filtered = JobsUiState(jobs = jobs, savedOnly = true, eligibleOnly = true, country = "Canada", query = "zzz")
        assertTrue(filtered.visibleJobs.isEmpty())
        val cleared = filtered.copy(
            savedOnly = false,
            eligibleOnly = false,
            sponsoredOnly = false,
            country = null,
            source = null,
            minMatch = 0,
            query = "",
        )
        assertEquals(4, cleared.visibleJobs.size)
        assertFalse(cleared.hasActiveFilters)
    }

    @Test
    fun filterFacetsAreDerivedFromLoadedJobs() {
        val state = JobsUiState(jobs = jobs)
        assertEquals(listOf("Australia", "Canada", "Germany", "Worldwide"), state.countries)
        assertEquals(listOf("arbeitnow" to "Arbeitnow", "remotive" to "Remotive"), state.sources)
        assertEquals(2, state.eligibleCount)
        assertTrue(state.hasActiveFilters.not())
    }
}
