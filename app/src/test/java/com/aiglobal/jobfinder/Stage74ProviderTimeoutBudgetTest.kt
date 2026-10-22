package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.JobCache
import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.provider.JobProvider
import com.aiglobal.jobfinder.data.jobs.provider.ProviderCapabilities
import com.aiglobal.jobfinder.data.jobs.provider.ProviderKind
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.jobs.provider.SmartRecruitersProvider
import com.aiglobal.jobfinder.data.jobs.provider.WorkdayProvider
import com.aiglobal.jobfinder.data.jobs.search.TargetSearch
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.ProviderStatus
import com.aiglobal.jobfinder.data.repository.LiveJobRepository
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 74 — the two fetch-orchestration bounds added after the provider audit.
 *
 * Priority 1: a provider may spend at most a fixed total across *all* its keyword fetches, not
 * that budget per keyword set, so one source cannot hold a whole refresh for minutes.
 * Priority 2: a provider whose feed reads only its first keyword (and re-walks every site on
 * each call) declares a keyword sweep budget, so the plan cannot multiply its request volume —
 * without touching a provider that reads every keyword (Eures).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage74ProviderTimeoutBudgetTest {

    /** A keyword-capable provider that can sleep per fetch, to exercise the time budgets. */
    private class SweepProvider(
        override val id: String,
        override val name: String,
        private val sweepBudget: Int = Int.MAX_VALUE,
        private val rowsPerKeyword: Int = 1,
        /** Sleeps this long on every fetch. */
        private val slowMillis: Long = 0,
        /** Sleeps this long only on the *second and later* fetches, so the first still lands. */
        private val slowAfterFirstMillis: Long = 0,
    ) : JobProvider {
        override val homepage: String = "https://example.test/$id"
        override val kind: ProviderKind = ProviderKind.EMPLOYER_BOARD
        override val capabilities: ProviderCapabilities =
            ProviderCapabilities(serverSideKeyword = true, keywordSweepBudget = sweepBudget)

        var fetches = 0
        val keywordsSeen = mutableListOf<List<String>>()

        override suspend fun fetch(keywords: List<String>, limit: Int): List<RawJob> {
            fetches++
            keywordsSeen += keywords
            if (slowMillis > 0) delay(slowMillis)
            if (slowAfterFirstMillis > 0 && fetches > 1) delay(slowAfterFirstMillis)
            return (0 until rowsPerKeyword).map { i ->
                RawJob(
                    providerId = id,
                    providerName = name,
                    externalId = "$id-$fetches-$i",
                    title = "Maintenance Technician $fetches",
                    company = "Northline $fetches",
                    location = "City $fetches",
                    descriptionHtml = "Maintain the production line.",
                    url = "https://example.test/$id-$fetches-$i",
                    isRemote = true,
                )
            }
        }
    }

    private fun repository(
        providers: List<JobProvider>,
        perKeywordTimeoutMillis: Long = 45_000,
        perProviderTotalTimeoutMillis: Long = 60_000,
    ): LiveJobRepository = LiveJobRepository(
        providers = providers,
        mapper = JobMapper(matchingEngine = MatchingEngine(), eligibilityEngine = EligibilityEngine()),
        candidateProfile = MutableStateFlow(CandidateProfile()),
        cvRecord = MutableStateFlow(null),
        cache = JobCache(File.createTempFile("stage74", "").let { f -> f.delete(); f.mkdirs(); f }),
        isOnline = { true },
        perKeywordTimeoutMillis = perKeywordTimeoutMillis,
        perProviderTotalTimeoutMillis = perProviderTotalTimeoutMillis,
    )

    private fun LiveJobRepository.statusOf(id: String): ProviderStatus =
        runBlocking { diagnostics.first().providers.first { it.providerId == id }.status }

    // ---- Priority 1: the total per-provider budget -------------------------------------

    @Test
    fun aProviderSlowerThanItsTotalBudgetIsCutOffOnceAndReportedAsTimeout() = runBlocking {
        // Six keyword sets, each would sleep 30 s, but the whole provider gets 3 s. awaitSettled
        // itself fails if the refresh runs past 5 s, so completing proves the total bound held.
        val provider = SweepProvider(
            id = "slow-sweep",
            name = "Slow sweep",
            slowMillis = 30_000,
        )
        val repo = repository(listOf(provider), perProviderTotalTimeoutMillis = 3_000)

        repo.awaitSettled()

        assertEquals(ProviderStatus.TIMEOUT, repo.statusOf("slow-sweep"))
        assertEquals("only the first set is attempted before the budget is spent", 1, provider.fetches)
    }

    @Test
    fun rowsFromACompletedKeywordSetSurviveALaterTimeout() = runBlocking {
        // The first keyword answers immediately; the second hangs. The first set's rows must
        // still be in the feed and the source must not be reported as a total failure.
        val provider = SweepProvider(
            id = "partial-sweep",
            name = "Partial sweep",
            slowAfterFirstMillis = 30_000,
        )
        val repo = repository(listOf(provider), perProviderTotalTimeoutMillis = 3_000)

        repo.awaitSettled()

        val diagnostic = repo.diagnostics.first().providers.first { it.providerId == "partial-sweep" }
        assertEquals(ProviderStatus.SUCCESS_WITH_RESULTS, diagnostic.status)
        assertEquals(1, diagnostic.validJobCount)
        assertEquals("the first set landed, the second was cut off", 2, provider.fetches)
    }

    @Test
    fun aFastProviderStillReceivesEveryKeywordSet() = runBlocking {
        val provider = SweepProvider(id = "fast-sweep", name = "Fast sweep", slowMillis = 20)
        val repo = repository(listOf(provider))

        repo.awaitSettled()

        assertEquals(6, provider.fetches)
        assertEquals(6, repo.diagnostics.first().providers.first { it.providerId == "fast-sweep" }.validJobCount)
    }

    @Test
    fun oneHangingProviderDoesNotBlockAFastOne() = runBlocking {
        val hanging = SweepProvider(id = "hang", name = "Hang", slowMillis = 30_000)
        val fast = SweepProvider(id = "fast", name = "Fast", slowMillis = 10)
        val repo = repository(listOf(hanging, fast), perProviderTotalTimeoutMillis = 3_000)

        repo.awaitSettled()

        val fastDiagnostic = repo.diagnostics.first().providers.first { it.providerId == "fast" }
        assertEquals(ProviderStatus.SUCCESS_WITH_RESULTS, fastDiagnostic.status)
        assertEquals(6, fastDiagnostic.validJobCount)
        assertEquals(ProviderStatus.TIMEOUT, repo.statusOf("hang"))
    }

    // ---- Priority 2: the per-provider keyword sweep budget ------------------------------

    @Test
    fun aProviderWithSweepBudgetOneIsFetchedOnce() = runBlocking {
        val provider = SweepProvider(id = "workday-like", name = "Workday-like", sweepBudget = 1)
        val repo = repository(listOf(provider))

        repo.awaitSettled()

        assertEquals("a full-sweep provider is not re-walked once per keyword", 1, provider.fetches)
    }

    @Test
    fun aProviderWithSweepBudgetTwoIsFetchedTwice() = runBlocking {
        val provider = SweepProvider(id = "sr-like", name = "SR-like", sweepBudget = 2)
        val repo = repository(listOf(provider))

        repo.awaitSettled()

        assertEquals(2, provider.fetches)
    }

    @Test
    fun theCoverageGuardHoldsAProviderThatReadsEveryKeywordReceivesAllSixSets() = runBlocking {
        // Eures reads the whole keyword list in one request, so it must keep the whole plan.
        val provider = SweepProvider(id = "eures-like", name = "Eures-like")
        val repo = repository(listOf(provider))

        repo.awaitSettled()

        assertEquals(6, provider.fetches)
        assertTrue("each set carries exactly one keyword", provider.keywordsSeen.all { it.size == 1 })
        assertEquals("the plan order is preserved", TargetSearch.keywordPlan(emptyList()).first(), provider.keywordsSeen.first().first())
    }

    @Test
    fun theHeavyProvidersDeclareTheirSweepBudgets() {
        assertEquals(1, WorkdayProvider().capabilities.keywordSweepBudget)
        // Stage 78: SmartRecruiters reads only the first keyword, so a second set re-runs the
        // identical country sweep. The budget is one, matching Workday's single-sweep bound.
        assertEquals(1, SmartRecruitersProvider().capabilities.keywordSweepBudget)
    }

    @Test
    fun anOrdinaryProviderKeepsTheUncappedDefault() {
        assertEquals(Int.MAX_VALUE, SweepProvider(id = "plain", name = "Plain").capabilities.keywordSweepBudget)
    }
}
