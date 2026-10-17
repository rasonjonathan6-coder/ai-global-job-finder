package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.freshness.Freshness
import com.aiglobal.jobfinder.data.jobs.freshness.JobFreshnessEngine
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import com.aiglobal.jobfinder.data.notifications.JobNotificationPolicy
import com.aiglobal.jobfinder.data.refresh.AlertThreshold
import com.aiglobal.jobfinder.data.refresh.RefreshInterval
import com.aiglobal.jobfinder.data.refresh.RefreshPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 20 freshness and notification rules.
 *
 * Both are honesty rules rather than features, so they are pinned precisely:
 *  - freshness is derived only from dates the source actually gave, and never invents one;
 *  - a notification fires only for a strong, genuinely new match, and its wording claims
 *    sponsorship or international eligibility only when the listing itself supports it.
 */
class Stage20DiscoveryTest {

    private val now = 1_700_000_000_000L
    private val dayMs = 86_400_000L

    private fun job(
        id: String = "j1",
        matchScore: Int = 90,
        postedDaysAgo: Long? = 1,
        eligibility: MadagascarEligibility = MadagascarEligibility.ELIGIBLE,
        visaSupport: VisaSupport = VisaSupport.NOT_MENTIONED,
        country: String = "Canada",
    ) = Job(
        id = id,
        title = "Maintenance Technician",
        company = "Northline Industrial",
        companyAccent = 0xFF1E4FD8,
        country = country,
        countryFlag = "🇨🇦",
        city = "Toronto",
        salaryMin = 4000,
        salaryMax = 5000,
        currencyCode = "CAD",
        salaryPeriod = SalaryPeriod.MONTH,
        matchScore = matchScore,
        eligibility = eligibility,
        visaSupport = visaSupport,
        scamRisk = ScamRisk.LOW,
        postedAtEpochMillis = postedDaysAgo?.let { now - it * dayMs },
        retrievedAtEpochMillis = now,
        source = "Arbeitnow",
        sourceUrl = "https://example.com/job/1",
        workMode = WorkMode.ONSITE,
        employmentType = EmploymentType.FULL_TIME,
        experienceYears = 3,
        matchedSkills = emptyList(),
        missingSkills = emptyList(),
        languages = listOf("English"),
        summary = "Industrial maintenance role.",
        responsibilities = emptyList(),
        benefits = emptyList(),
        aiInsight = "Strong technical fit.",
    )

    // ------------------------------------------------------- freshness engine

    @Test
    fun aPostingWithNoPublicationDateIsUnknownAndNeverCalledFresh() {
        val assessment = JobFreshnessEngine().assess(job(postedDaysAgo = null), now)
        assertEquals(Freshness.UNKNOWN, assessment.freshness)
        assertEquals("Date not stated", assessment.label)
    }

    @Test
    fun aFuturePublicationDateIsTreatedAsUnknownNotFresh() {
        val future = job().copy(postedAtEpochMillis = now + dayMs)
        assertEquals(Freshness.UNKNOWN, JobFreshnessEngine().assess(future, now).freshness)
    }

    @Test
    fun ageBucketsFollowTheStatedPublicationDate() {
        val engine = JobFreshnessEngine(staleAfterDays = 120)
        assertEquals(Freshness.FRESH, engine.assess(job(postedDaysAgo = 1), now).freshness)
        assertEquals(Freshness.RECENT, engine.assess(job(postedDaysAgo = 5), now).freshness)
        assertEquals(Freshness.AGING, engine.assess(job(postedDaysAgo = 30), now).freshness)
        assertEquals(Freshness.STALE, engine.assess(job(postedDaysAgo = 200), now).freshness)
    }

    // ---------------------------------------------------- notification policy

    @Test
    fun onlyStrongMatchesAreSelected() {
        val policy = JobNotificationPolicy(minMatchScore = 65)
        val selected = policy.select(
            listOf(job(id = "strong", matchScore = 80), job(id = "weak", matchScore = 40)),
            alreadyNotified = emptySet(),
        )
        assertEquals(listOf("strong"), selected.map { it.jobId })
    }

    @Test
    fun anAlreadyNotifiedJobIsNeverRepeated() {
        val policy = JobNotificationPolicy()
        val selected = policy.select(listOf(job(id = "seen")), alreadyNotified = setOf("seen"))
        assertTrue(selected.isEmpty())
    }

    @Test
    fun aRunIsCappedSoABigFeedCannotFireADozenNotifications() {
        val policy = JobNotificationPolicy(maxPerRun = 3)
        val jobs = (1..10).map { job(id = "j$it", matchScore = 70 + it) }
        assertEquals(3, policy.select(jobs, emptySet()).size)
    }

    @Test
    fun theStrongestMatchesWinWhenCapped() {
        val policy = JobNotificationPolicy(maxPerRun = 1)
        val jobs = listOf(job(id = "low", matchScore = 70), job(id = "high", matchScore = 99))
        assertEquals("high", policy.select(jobs, emptySet()).single().jobId)
    }

    @Test
    fun sponsorshipIsClaimedOnlyWhenTheListingStatesFullSponsorship() {
        val policy = JobNotificationPolicy()
        val sponsored = policy.select(
            listOf(
                job(
                    id = "s",
                    eligibility = MadagascarEligibility.VISA_SPONSORED,
                    visaSupport = VisaSupport.FULL_SPONSORSHIP,
                ),
            ),
            emptySet(),
        ).single()
        assertTrue(sponsored.body.contains("visa sponsorship"))

        val partial = policy.select(
            listOf(
                job(
                    id = "p",
                    eligibility = MadagascarEligibility.VISA_SPONSORED,
                    visaSupport = VisaSupport.SPONSORSHIP_AVAILABLE,
                ),
            ),
            emptySet(),
        ).single()
        assertFalse(partial.body.contains("visa sponsorship"))
    }

    @Test
    fun anUnclearPostingDoesNotClaimItAcceptsInternationalApplicants() {
        val policy = JobNotificationPolicy()
        val unclear = policy.select(
            listOf(job(eligibility = MadagascarEligibility.UNCLEAR)),
            emptySet(),
        ).single()
        assertFalse(unclear.body.contains("accepts international applicants"))
        assertTrue(unclear.body.contains("Check whether"))
    }

    // ------------------------------------------------------- refresh prefs

    @Test
    fun anUnknownStoredIntervalFallsBackToTwiceDaily() {
        assertEquals(RefreshInterval.TWICE_DAILY, RefreshInterval.fromHours(999))
        assertEquals(RefreshInterval.DAILY, RefreshInterval.fromHours(24))
    }

    @Test
    fun refreshIsOnByDefaultAtATwiceDailyCadence() {
        val defaults = RefreshPreferences()
        assertTrue(defaults.enabled)
        assertEquals(RefreshInterval.TWICE_DAILY, defaults.interval)
    }

    @Test
    fun theDefaultAlertBarMatchesTheStrongTier() {
        assertEquals(AlertThreshold.STRONG, RefreshPreferences().alertThreshold)
        assertEquals(JobNotificationPolicy.DEFAULT_MIN_MATCH, RefreshPreferences().alertMinMatchScore)
    }

    @Test
    fun anUnknownStoredThresholdFallsBackToStrong() {
        assertEquals(AlertThreshold.STRONG, AlertThreshold.fromScore(73))
        assertEquals(AlertThreshold.EXCELLENT, AlertThreshold.fromScore(90))
    }
}
