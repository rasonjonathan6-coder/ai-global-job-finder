package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.freshness.Freshness
import com.aiglobal.jobfinder.data.jobs.freshness.FreshnessWindow
import com.aiglobal.jobfinder.data.jobs.freshness.JobFreshnessEngine
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import com.aiglobal.jobfinder.viewmodel.JobsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "recent and still open" rule for the Jobs list.
 *
 * Two promises must hold together and must never be traded against each other:
 *
 * 1. The list opens on postings published inside a short, stated window (30 days by default).
 * 2. Nothing is invented to satisfy that window. A posting with no date, a zero date or a
 *    date the source placed in the future is *not* recent, is never counted as recent, and is
 *    only shown when the user explicitly asks for the undated/older group. A network failure
 *    upstream produces the same "unknown" date, so it can never masquerade as a fresh posting
 *    or, worse, be mislabelled "expired".
 */
class JobsFreshnessWindowTest {

    private companion object {
        const val DAY = 86_400_000L
        const val NOW = 1_760_000_000_000L
    }

    private fun job(id: String, postedAt: Long?, matchScore: Int = 70) = Job(
        id = id,
        title = "Maintenance Technician",
        company = "Acme",
        companyAccent = 0xFF1E4FD8,
        country = "Canada",
        countryFlag = "🇨🇦",
        city = "Toronto",
        salaryMin = 60_000,
        salaryMax = 60_000,
        currencyCode = "CAD",
        salaryPeriod = SalaryPeriod.YEAR,
        matchScore = matchScore,
        eligibility = MadagascarEligibility.ELIGIBLE,
        visaSupport = VisaSupport.NOT_MENTIONED,
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
        summary = "Summary for $id",
        responsibilities = emptyList(),
        benefits = emptyList(),
        aiInsight = "insight",
        providerId = "remotive",
    )

    // ------------------------------------------------------------- engine default

    @Test
    fun theEngineDefaultWindowIsThirtyDays() {
        assertEquals(30, JobFreshnessEngine.DEFAULT_STALE_AFTER_DAYS)
        val engine = JobFreshnessEngine()
        assertEquals(Freshness.AGING, engine.assess(job("29d", NOW - 29 * DAY), NOW).freshness)
        assertEquals(Freshness.STALE, engine.assess(job("31d", NOW - 31 * DAY), NOW).freshness)
    }

    // ------------------------------------------------------------- default view

    @Test
    fun theDefaultWindowIsThirtyDaysAndCountsAsNoActiveFilter() {
        val state = JobsUiState(nowMillis = NOW)
        assertEquals(FreshnessWindow.THIRTY_DAYS, state.freshnessWindow)
        assertFalse("the default window must not read as a user-applied filter", state.hasActiveFilters)
    }

    @Test
    fun onlyDatedPostingsInsideTheWindowAreShownByDefault() {
        val recent = job("recent", NOW - 5 * DAY)
        val boundary = job("boundary", NOW - 30 * DAY)
        val old = job("old", NOW - 31 * DAY)
        val state = JobsUiState(nowMillis = NOW, jobs = listOf(recent, boundary, old))

        assertEquals(listOf("recent", "boundary"), state.visibleJobs.map { it.id })
    }

    @Test
    fun anUndatedPostingIsHiddenByDefaultAndNeverCountedAsRecent() {
        val undated = job("undated", null)
        val state = JobsUiState(nowMillis = NOW, jobs = listOf(job("dated", NOW - 2 * DAY), undated))

        assertEquals(listOf("dated"), state.visibleJobs.map { it.id })
        assertEquals(1, state.withinWindowCount)
        assertEquals(1, state.undatedCount)
        assertEquals(0, state.staleCount)
    }

    @Test
    fun aZeroOrFutureDateIsNotRecent() {
        val zero = job("zero", 0L)
        val future = job("future", NOW + 3 * DAY)
        val state = JobsUiState(nowMillis = NOW, jobs = listOf(zero, future, job("ok", NOW - 1 * DAY)))

        assertEquals(listOf("ok"), state.visibleJobs.map { it.id })
        assertEquals("a zero or future date has no usable publication date", 2, state.undatedCount)
    }

    // ------------------------------------------------------------- selectable windows

    @Test
    fun eachWindowNarrowsToItsOwnHorizon() {
        val d3 = job("d3", NOW - 3 * DAY)
        val d10 = job("d10", NOW - 10 * DAY)
        val d25 = job("d25", NOW - 25 * DAY)
        val jobs = listOf(d3, d10, d25)

        assertEquals(listOf("d3"), JobsUiState(nowMillis = NOW, jobs = jobs, freshnessWindow = FreshnessWindow.SEVEN_DAYS).visibleJobs.map { it.id })
        assertEquals(listOf("d3", "d10"), JobsUiState(nowMillis = NOW, jobs = jobs, freshnessWindow = FreshnessWindow.FOURTEEN_DAYS).visibleJobs.map { it.id })
        assertEquals(listOf("d3", "d10", "d25"), JobsUiState(nowMillis = NOW, jobs = jobs, freshnessWindow = FreshnessWindow.THIRTY_DAYS).visibleJobs.map { it.id })
    }

    @Test
    fun choosingANonDefaultWindowCountsAsAnActiveFilter() {
        val state = JobsUiState(nowMillis = NOW, freshnessWindow = FreshnessWindow.SEVEN_DAYS)
        assertTrue(state.hasActiveFilters)
    }

    @Test
    fun theWindowNeverClaimsAnOpenVacancyItCannotSee() {
        // Narrowing the window is a *recency* view only: an undated posting is not thereby
        // declared closed, it is simply not shown. The undated count proves it was kept.
        val state = JobsUiState(
            nowMillis = NOW,
            jobs = listOf(job("undated", null)),
            freshnessWindow = FreshnessWindow.SEVEN_DAYS,
        )
        assertTrue(state.visibleJobs.isEmpty())
        assertEquals(1, state.undatedCount)
    }

    // ------------------------------------------------------------- opt-in reveal

    @Test
    fun revealingUndatedAndOlderRestoresThemWithoutInventingADate() {
        val undated = job("undated", null)
        val old = job("old", NOW - 90 * DAY)
        val recent = job("recent", NOW - 1 * DAY)
        val hidden = JobsUiState(nowMillis = NOW, jobs = listOf(undated, old, recent))
        assertEquals(listOf("recent"), hidden.visibleJobs.map { it.id })

        val revealed = hidden.copy(includeUndatedAndStale = true)
        assertEquals(setOf("undated", "old", "recent"), revealed.visibleJobs.map { it.id }.toSet())
        assertTrue("revealing the wider group is an explicit choice", revealed.hasActiveFilters)
    }

    @Test
    fun theHiddenCountsAddUpToThePostingsOutsideTheWindow() {
        val jobs = listOf(
            job("recent", NOW - 3 * DAY),
            job("old", NOW - 40 * DAY),
            job("undated", null),
            job("future", NOW + 2 * DAY),
        )
        val state = JobsUiState(nowMillis = NOW, jobs = jobs)
        assertEquals(1, state.withinWindowCount)
        assertEquals(1, state.staleCount)
        assertEquals("missing and future dates share the undated group", 2, state.undatedCount)
        assertEquals(4, state.withinWindowCount + state.staleCount + state.undatedCount)
    }
}
