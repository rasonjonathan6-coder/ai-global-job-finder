package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.JobCache
import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.availability.AvailabilityBudgetStore
import com.aiglobal.jobfinder.data.jobs.availability.AvailabilityCache
import com.aiglobal.jobfinder.data.jobs.availability.AvailabilityLinkProbe
import com.aiglobal.jobfinder.data.jobs.availability.AvailabilityRefresh
import com.aiglobal.jobfinder.data.jobs.availability.AvailabilityVerifier
import com.aiglobal.jobfinder.data.jobs.availability.LinkProbeOutcome
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.provider.JobProvider
import com.aiglobal.jobfinder.data.jobs.provider.ProviderCapabilities
import com.aiglobal.jobfinder.data.jobs.provider.ProviderKind
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.jobs.provider.runCatchingCancellable
import com.aiglobal.jobfinder.data.model.AvailabilityStatus
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import com.aiglobal.jobfinder.data.repository.LiveJobRepository
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 79 — the availability probe budget is a persistent, shared, 24 h window.
 *
 * The budget used to be reset by every refresh (a fresh [AvailabilityRefresh] per call), so a
 * background refresh every few hours could reach far more links per day than the cap intends.
 * These tests pin the fix, and the guarantees it must not break:
 *  1. the budget is shared across successive refreshes;
 *  2. it can never be exceeded by concurrent claims;
 *  3. it refills only after the 24 h window, including across a simulated restart;
 *  4. an exhausted budget or a failed probe never produces EXPIRED, and a failure is never cached;
 *  5. a fresh cached verdict avoids redundant requests and consumes no slot;
 *  6. classification and coroutine-cancellation propagation are intact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage79AvailabilityBudgetTest {

    private val now = 1_720_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    private fun job(
        id: String,
        openToApplicants: Boolean? = null,
        expiresAt: Long? = null,
    ) = Job(
        id = id,
        title = "Maintenance Technician",
        company = "Northline Industrial",
        companyAccent = 0xFF1E4FD8,
        country = "Canada",
        countryFlag = "\uD83C\uDDE8\uD83C\uDDE6",
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
        sourceUrl = "https://www.jobbank.gc.ca/job/$id",
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

    private fun jobs(count: Int, openToApplicants: Boolean? = null): List<Job> =
        (1..count).map { job("job-$it", openToApplicants = openToApplicants) }

    private class CountingProbe(private val outcome: () -> LinkProbeOutcome) {
        val calls = AtomicInteger(0)
        val probe = AvailabilityLinkProbe { calls.incrementAndGet(); outcome() }
    }

    private fun refresh(
        cache: AvailabilityCache,
        budget: Int,
        clock: () -> Long,
        probe: AvailabilityLinkProbe,
        store: AvailabilityBudgetStore? = null,
    ) = AvailabilityRefresh(
        verifier = AvailabilityVerifier(
            cache = cache,
            probe = probe,
            linkIsUsable = { true },
            clock = clock,
        ),
        probeBudget = budget,
        budgetWindowMillis = day,
        store = store,
        clock = clock,
    )

    // ---- 1. The budget is shared across successive refreshes ------------------------------

    @Test
    fun theBudgetIsSharedAcrossSuccessiveRefreshes() = runBlocking {
        val counting = CountingProbe { LinkProbeOutcome.Open("apply") }
        val cache = AvailabilityCache(ttlMillis = day, clock = { now })
        val r = refresh(cache, budget = 3, clock = { now }, probe = counting.probe)
        val feed = jobs(10)

        r.overlay(feed)
        assertEquals("the first refresh spends the budget", 3, counting.calls.get())

        r.overlay(feed)
        assertEquals("a second refresh must not reset and re-spend the budget", 3, counting.calls.get())

        r.overlay(feed)
        assertEquals("and a third neither", 3, counting.calls.get())
    }

    // ---- 2. Concurrency can never exceed the budget ----------------------------------------

    @Test
    fun theBudgetCanNeverBeExceededByConcurrentClaims() = runBlocking {
        val counting = CountingProbe { LinkProbeOutcome.Open("apply") }
        val cache = AvailabilityCache(ttlMillis = day, clock = { now })
        val r = refresh(cache, budget = 4, clock = { now }, probe = counting.probe)
        val feed = jobs(40)

        coroutineScope {
            (1..8).map { async(Dispatchers.Default) { r.overlay(feed) } }.awaitAll()
        }

        assertTrue(
            "concurrent claims must never exceed the budget (spent=${counting.calls.get()})",
            counting.calls.get() <= 4,
        )
    }

    // ---- 3. The window refills only after 24 h, including across a restart ------------------

    @Test
    fun theBudgetRefillsOnlyAfterTheWindowElapses() = runBlocking {
        var clockValue = now
        val counting = CountingProbe { LinkProbeOutcome.Open("apply") }
        val cache = AvailabilityCache(ttlMillis = day, clock = { clockValue })
        val r = refresh(cache, budget = 2, clock = { clockValue }, probe = counting.probe)
        val feed = jobs(6)

        r.overlay(feed)
        assertEquals(2, counting.calls.get())

        clockValue = now + day - 1
        r.overlay(feed)
        assertEquals("still inside the window, the budget stays spent", 2, counting.calls.get())

        clockValue = now + day + 1
        r.overlay(feed)
        assertEquals("after the window the budget refills", 4, counting.calls.get())
    }

    @Test
    fun aRestartWithinTheWindowDoesNotRefillTheBudget() = runBlocking {
        val dir = File.createTempFile("stage79", "").let { f -> f.delete(); f.mkdirs(); f }
        val counting = CountingProbe { LinkProbeOutcome.Open("apply") }
        // Fresh caches so the *budget* is the only thing carried across the "restart".
        val firstCache = AvailabilityCache(ttlMillis = day, clock = { now })
        val first = refresh(firstCache, budget = 2, clock = { now }, probe = counting.probe, store = AvailabilityBudgetStore(dir))
        first.overlay(jobs(5))
        assertEquals(2, counting.calls.get())

        // A new process: new cache (empty) and a new refresh that restores the persisted window.
        val secondCache = AvailabilityCache(ttlMillis = day, clock = { now })
        val second = refresh(secondCache, budget = 2, clock = { now }, probe = counting.probe, store = AvailabilityBudgetStore(dir))
        second.overlay(jobs(5))

        assertEquals("a restart inside the window must not reset the daily cap", 2, counting.calls.get())
    }

    @Test
    fun aRestartAfterTheWindowRefillsTheBudget() = runBlocking {
        val dir = File.createTempFile("stage79", "").let { f -> f.delete(); f.mkdirs(); f }
        val counting = CountingProbe { LinkProbeOutcome.Open("apply") }
        val firstCache = AvailabilityCache(ttlMillis = day, clock = { now })
        refresh(firstCache, budget = 2, clock = { now }, probe = counting.probe, store = AvailabilityBudgetStore(dir)).overlay(jobs(5))
        assertEquals(2, counting.calls.get())

        val later = now + day + 1
        val secondCache = AvailabilityCache(ttlMillis = day, clock = { later })
        refresh(secondCache, budget = 2, clock = { later }, probe = counting.probe, store = AvailabilityBudgetStore(dir)).overlay(jobs(5))

        assertEquals("after the window a restart starts a fresh budget", 4, counting.calls.get())
    }

    // ---- 4. Exhaustion and failure are never EXPIRED ---------------------------------------

    @Test
    fun anExhaustedBudgetNeverProducesExpired() = runBlocking {
        val counting = CountingProbe { LinkProbeOutcome.Open("apply") }
        val cache = AvailabilityCache(ttlMillis = day, clock = { now })
        val r = refresh(cache, budget = 1, clock = { now }, probe = counting.probe)

        val out = r.overlay(jobs(5))

        assertEquals(1, counting.calls.get())
        assertEquals(AvailabilityStatus.OPEN, out.getValue("job-1").status)
        (2..5).forEach { i ->
            assertEquals(
                "a posting the budget could not reach stays UNCONFIRMED, never EXPIRED",
                AvailabilityStatus.UNCONFIRMED,
                out.getValue("job-$i").status,
            )
        }
    }

    @Test
    fun aFailedProbeStaysUnconfirmedAndIsNeverCached() = runBlocking {
        val counting = CountingProbe { LinkProbeOutcome.Unknown("timeout") }
        val cache = AvailabilityCache(ttlMillis = day, clock = { now })
        val r = refresh(cache, budget = 5, clock = { now }, probe = counting.probe)

        val out = r.overlay(jobs(3))

        assertEquals(3, counting.calls.get())
        out.values.forEach { assertEquals(AvailabilityStatus.UNCONFIRMED, it.status) }
        assertEquals("an unconfirmed result must never be cached", 0, cache.size())
    }

    @Test
    fun aFlagDecidedPostingIsOpenWithoutSpendingBudget() = runBlocking {
        val counting = CountingProbe { LinkProbeOutcome.Unknown("should not run") }
        val cache = AvailabilityCache(ttlMillis = day, clock = { now })
        val r = refresh(cache, budget = 2, clock = { now }, probe = counting.probe)

        val out = r.overlay(listOf(job("flagged", openToApplicants = true)))

        assertEquals("a source flag needs no request", 0, counting.calls.get())
        assertEquals(AvailabilityStatus.OPEN, out.getValue("flagged").status)
    }

    // ---- 5. A fresh cache avoids redundant requests and frees the budget -------------------

    @Test
    fun aFreshCachedVerdictAvoidsARequestAndDoesNotConsumeASlot() = runBlocking {
        val counting = CountingProbe { LinkProbeOutcome.Open("apply") }
        val cache = AvailabilityCache(ttlMillis = day, clock = { now })
        val r = refresh(cache, budget = 3, clock = { now }, probe = counting.probe)
        val all = jobs(3)

        // First pass checks only the first two postings: two slots spent, one slot left.
        r.overlay(all.take(2))
        assertEquals(2, counting.calls.get())
        assertEquals("settled verdicts are cached", 2, cache.size())

        // Second pass sees the same feed. The two cached postings must cost nothing, so the
        // one remaining slot is free for job-3 — the cached pair did not consume it.
        r.overlay(all)
        assertEquals("cached postings must not consume a slot", 3, counting.calls.get())
        assertEquals(3, cache.size())
    }

    // ---- 6. Classification and cancellation guarantees remain intact ----------------------

    @Test
    fun classificationStillDistinguishesOpenClosedAndUnknown() = runBlocking {
        val openCache = AvailabilityCache(ttlMillis = day, clock = { now })
        val open = refresh(openCache, budget = 5, clock = { now }, probe = CountingProbe { LinkProbeOutcome.Open("apply") }.probe)
        assertEquals(AvailabilityStatus.OPEN, open.overlay(listOf(job("o"))).getValue("o").status)

        val closedCache = AvailabilityCache(ttlMillis = day, clock = { now })
        val closed = refresh(closedCache, budget = 5, clock = { now }, probe = CountingProbe { LinkProbeOutcome.Closed("filled") }.probe)
        assertEquals(AvailabilityStatus.EXPIRED, closed.overlay(listOf(job("c"))).getValue("c").status)

        val unknownCache = AvailabilityCache(ttlMillis = day, clock = { now })
        val unknown = refresh(unknownCache, budget = 5, clock = { now }, probe = CountingProbe { LinkProbeOutcome.Unknown("blocked") }.probe)
        assertEquals(AvailabilityStatus.UNCONFIRMED, unknown.overlay(listOf(job("u"))).getValue("u").status)
    }

    @Test
    fun theBudgetDefaultsArePreserved() {
        assertEquals(12, AvailabilityRefresh.DEFAULT_PROBE_BUDGET)
        assertEquals(day, AvailabilityRefresh.DEFAULT_BUDGET_WINDOW_MILLIS)
    }

    @Test
    fun cancellationStillPropagatesThroughTheBudgetHelper() {
        val caught = runCatching { runCatchingCancellable<Unit> { throw CancellationException("cancelled") } }.exceptionOrNull()
        assertTrue("Stage 77: CancellationException must propagate, not become a Result.failure", caught is CancellationException)
    }

    // ---- Repository-level wiring ----------------------------------------------------------

    private class StaticProvider(private val rows: List<RawJob>) : JobProvider {
        override val id: String = "static"
        override val name: String = "Static"
        override val homepage: String = "https://example.test"
        override val kind: ProviderKind = ProviderKind.EMPLOYER_BOARD
        override val capabilities: ProviderCapabilities = ProviderCapabilities(serverSideKeyword = false)
        override suspend fun fetch(keywords: List<String>, limit: Int): List<RawJob> = rows
    }

    private fun rawJob(i: Int) = RawJob(
        providerId = "static",
        providerName = "Static",
        externalId = "ext-$i",
        title = "Maintenance Technician $i",
        company = "Northline Industrial $i",
        location = "Calgary, Canada",
        descriptionHtml = "<p>Maintain pumps and gearboxes.</p>",
        url = "https://example.test/job/$i",
        postedAtEpochMillis = null,
    )

    @Test
    fun aRepositoryHoldsItsProbeBudgetAcrossTwoRefreshes() = runBlocking {
        val counting = CountingProbe { LinkProbeOutcome.Open("apply") }
        val verifier = AvailabilityVerifier(
            cache = AvailabilityCache(ttlMillis = day, clock = { now }),
            probe = counting.probe,
            linkIsUsable = { true },
            clock = { now },
        )
        val repo = LiveJobRepository(
            providers = listOf(StaticProvider((1..5).map { rawJob(it) })),
            mapper = JobMapper(matchingEngine = MatchingEngine(), eligibilityEngine = EligibilityEngine()),
            candidateProfile = MutableStateFlow(CandidateProfile()),
            cvRecord = MutableStateFlow(null),
            cache = JobCache(File.createTempFile("stage79", "").let { f -> f.delete(); f.mkdirs(); f }),
            isOnline = { true },
            availabilityVerifier = verifier,
            availabilityProbeBudget = 2,
            availabilityBudgetWindowMillis = day,
            clock = { now },
        )

        repo.awaitSettled()
        assertEquals("the first refresh spends exactly the budget", 2, counting.calls.get())

        repo.refresh()
        assertEquals("a second refresh must not repeat the checks", 2, counting.calls.get())
    }
}
