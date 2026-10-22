package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.availability.AvailabilityCache
import com.aiglobal.jobfinder.data.jobs.availability.AvailabilityLinkProbe
import com.aiglobal.jobfinder.data.jobs.availability.AvailabilityVerifier
import com.aiglobal.jobfinder.data.jobs.availability.LinkProbeClassifier
import com.aiglobal.jobfinder.data.jobs.availability.LinkProbeOutcome
import com.aiglobal.jobfinder.data.jobs.availability.OfferAvailabilityEngine
import com.aiglobal.jobfinder.data.model.AvailabilitySignalType
import com.aiglobal.jobfinder.data.model.AvailabilityStatus
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Étape B — cautious availability verification.
 *
 * The tests are grouped by the promise each one protects:
 *  1. the source's own flag and a passed deadline settle the verdict without any request;
 *  2. silence is never read as a verdict, and a network error is never read as "expired";
 *  3. a plain HTTP 200 is not proof of openness — only a positive marker is;
 *  4. the 24 h cache limits requests, survives a clock advance, and never freezes a failure.
 */
class OfferAvailabilityVerificationTest {

    private val now = 1_720_000_000_000L

    // ---------------------------------------------------------------------
    // 1. The source's own indicators (no network needed)
    // ---------------------------------------------------------------------

    @Test
    fun `source flag true is open and needs no probe`() {
        val result = OfferAvailabilityEngine().assess(job(openToApplicants = true), now)
        assertEquals(AvailabilityStatus.OPEN, result.status)
        assertEquals(AvailabilitySignalType.SOURCE_FLAG, result.decisiveSignal)
        assertEquals(now, result.lastCheckedAtEpochMillis)
    }

    @Test
    fun `source flag false is expired`() {
        val result = OfferAvailabilityEngine().assess(job(openToApplicants = false), now)
        assertEquals(AvailabilityStatus.EXPIRED, result.status)
        assertTrue(result.isExpired)
    }

    @Test
    fun `a passed employer deadline is expired even without a flag`() {
        val result = OfferAvailabilityEngine().assess(
            job(openToApplicants = null, expiresAt = now - 60_000),
            now,
        )
        assertEquals(AvailabilityStatus.EXPIRED, result.status)
        assertEquals(AvailabilitySignalType.EMPLOYER_DEADLINE, result.decisiveSignal)
    }

    @Test
    fun `a future deadline alone does not confirm the posting is open`() {
        // A stated closing date says nothing about whether it is accepting applicants today,
        // so the honest verdict stays unconfirmed — it is recorded as a fact, not a verdict.
        val result = OfferAvailabilityEngine().assess(
            job(openToApplicants = null, expiresAt = now + 86_400_000),
            now,
        )
        assertEquals(AvailabilityStatus.UNCONFIRMED, result.status)
        assertTrue(result.evidence.any { it.type == AvailabilitySignalType.EMPLOYER_DEADLINE })
    }

    @Test
    fun `silence is unconfirmed, not open and not expired`() {
        val result = OfferAvailabilityEngine().assess(job(openToApplicants = null), now)
        assertEquals(AvailabilityStatus.UNCONFIRMED, result.status)
        assertTrue(result.isUnconfirmed)
        assertFalse(result.isOpen)
        assertFalse(result.isExpired)
        assertTrue(result.evidence.isEmpty())
    }

    // ---------------------------------------------------------------------
    // 2. Failures and unknowns are never a verdict
    // ---------------------------------------------------------------------

    @Test
    fun `a network error leaves the posting unconfirmed, never expired`() = runBlocking {
        val verifier = AvailabilityVerifier(
            probe = AvailabilityLinkProbe {
                LinkProbeOutcome.Unknown("could not reach")
            },
        )
        val result = verifier.verify(job(openToApplicants = null))
        assertEquals(AvailabilityStatus.UNCONFIRMED, result.status)
        assertFalse(result.isExpired)
    }

    @Test
    fun `an unreachable link is never cached, so the next attempt retries`() = runBlocking {
        val cache = AvailabilityCache(ttlMillis = 24 * 60 * 60 * 1000L, clock = { now })
        var calls = 0
        val verifier = AvailabilityVerifier(
            cache = cache,
            probe = AvailabilityLinkProbe { calls++; LinkProbeOutcome.Unknown("timeout") },
        )
        verifier.verify(job(openToApplicants = null))
        verifier.verify(job(openToApplicants = null))
        assertEquals("a failure must not be cached", 2, calls)
        assertEquals(0, cache.size())
    }

    // ---------------------------------------------------------------------
    // 3. A 200 is not proof — the classifier
    // ---------------------------------------------------------------------

    @Test
    fun `a plain 200 with no marker is not open`() {
        val outcome = LinkProbeClassifier.classify(200, "<html><body>Welcome to our careers site</body></html>")
        assertTrue("a bare 200 proves only that a page exists", outcome is LinkProbeOutcome.Unknown)
    }

    @Test
    fun `a 200 with an apply action is open`() {
        val outcome = LinkProbeClassifier.classify(200, "<button>Apply for this job</button>")
        assertTrue(outcome is LinkProbeOutcome.Open)
    }

    @Test
    fun `a 200 that states the position is filled is expired`() {
        val outcome = LinkProbeClassifier.classify(200, "<h1>This position has been filled</h1>")
        assertTrue(outcome is LinkProbeOutcome.Closed)
    }

    @Test
    fun `a closed marker wins over a stale apply button`() {
        val outcome = LinkProbeClassifier.classify(
            200,
            "<button>Apply now</button><p>This job has been filled</p>",
        )
        assertTrue("when the two conflict, the safe reading is closed", outcome is LinkProbeOutcome.Closed)
    }

    @Test
    fun `404 and 410 are reliable closed signals`() {
        assertTrue(LinkProbeClassifier.classify(404, "") is LinkProbeOutcome.Closed)
        assertTrue(LinkProbeClassifier.classify(410, "") is LinkProbeOutcome.Closed)
    }

    @Test
    fun `403 and 429 are unknown, never closed`() {
        assertTrue(LinkProbeClassifier.classify(403, "") is LinkProbeOutcome.Unknown)
        assertTrue(LinkProbeClassifier.classify(429, "") is LinkProbeOutcome.Unknown)
    }

    @Test
    fun `a positive marker promotes an unflagged posting to open`() = runBlocking {
        val verifier = AvailabilityVerifier(
            probe = AvailabilityLinkProbe { LinkProbeOutcome.Open("apply button present") },
        )
        val result = verifier.verify(job(openToApplicants = null))
        assertEquals(AvailabilityStatus.OPEN, result.status)
        assertEquals(AvailabilitySignalType.LINK_PROBE, result.decisiveSignal)
    }

    // ---------------------------------------------------------------------
    // 4. The 24 h cache
    // ---------------------------------------------------------------------

    @Test
    fun `a settled verdict is served from cache and the link is not reached twice`() = runBlocking {
        var calls = 0
        val cache = AvailabilityCache(ttlMillis = 24 * 60 * 60 * 1000L, clock = { now })
        val verifier = AvailabilityVerifier(
            cache = cache,
            probe = AvailabilityLinkProbe { calls++; LinkProbeOutcome.Open("apply") },
        )
        val first = verifier.verify(job(openToApplicants = null))
        val second = verifier.verify(job(openToApplicants = null))
        assertEquals(1, calls)
        assertEquals(first, second)
    }

    @Test
    fun `the cache expires after 24 hours and re-checks`() = runBlocking {
        var clockValue = now
        var calls = 0
        val cache = AvailabilityCache(ttlMillis = 24 * 60 * 60 * 1000L, clock = { clockValue })
        val verifier = AvailabilityVerifier(
            cache = cache,
            probe = AvailabilityLinkProbe { calls++; LinkProbeOutcome.Open("apply") },
            clock = { clockValue },
        )
        verifier.verify(job(openToApplicants = null))
        // 23 h later: still inside the window, no new request.
        clockValue = now + 23 * 60 * 60 * 1000L
        verifier.verify(job(openToApplicants = null))
        assertEquals(1, calls)
        // 25 h later: outside the window, the link is reached again.
        clockValue = now + 25 * 60 * 60 * 1000L
        verifier.verify(job(openToApplicants = null))
        assertEquals(2, calls)
    }

    @Test
    fun `a flag decided posting is cached without any request`() = runBlocking {
        var calls = 0
        val cache = AvailabilityCache(ttlMillis = 24 * 60 * 60 * 1000L, clock = { now })
        val verifier = AvailabilityVerifier(
            cache = cache,
            probe = AvailabilityLinkProbe { calls++; LinkProbeOutcome.Unknown("should not run") },
        )
        val result = verifier.verify(job(openToApplicants = true))
        assertEquals(AvailabilityStatus.OPEN, result.status)
        assertEquals("a source flag needs no request", 0, calls)
        assertEquals(1, cache.size())
    }

    @Test
    fun `an unknown result is not stored in the cache`() {
        val cache = AvailabilityCache(ttlMillis = 1000L, clock = { now })
        cache.put("job-1", com.aiglobal.jobfinder.data.model.AvailabilityResult.UNKNOWN)
        assertEquals(0, cache.size())
        assertNull(cache.get("job-1"))
    }

    // ---------------------------------------------------------------------

    private fun job(
        id: String = "job-1",
        openToApplicants: Boolean? = null,
        expiresAt: Long? = null,
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
        matchScore = 92,
        eligibility = MadagascarEligibility.VISA_SPONSORED,
        visaSupport = VisaSupport.FULL_SPONSORSHIP,
        scamRisk = ScamRisk.LOW,
        postedAtEpochMillis = now - 86_400_000,
        expiresAtEpochMillis = expiresAt,
        source = "Canada Job Bank",
        sourceUrl = "https://www.jobbank.gc.ca/job/1",
        workMode = WorkMode.ONSITE,
        employmentType = EmploymentType.FULL_TIME,
        experienceYears = 3,
        openToApplicants = openToApplicants,
        matchedSkills = listOf("Preventive maintenance"),
        missingSkills = emptyList(),
        languages = listOf("English (B2+)"),
        summary = "Maintain production line machinery.",
        responsibilities = listOf("Run preventive maintenance"),
        benefits = listOf("Relocation allowance"),
        aiInsight = "Strong technical fit.",
    )
}
