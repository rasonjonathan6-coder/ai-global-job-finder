package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.JobCache
import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.provider.JobProvider
import com.aiglobal.jobfinder.data.jobs.provider.ProviderCapabilities
import com.aiglobal.jobfinder.data.jobs.provider.ProviderKind
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.ProviderDiagnostic
import com.aiglobal.jobfinder.data.model.ProviderDiagnostics
import com.aiglobal.jobfinder.data.model.ProviderStatus
import com.aiglobal.jobfinder.data.model.SourceState
import com.aiglobal.jobfinder.data.repository.LiveJobRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Stage 22 — audit of the live data path.
 *
 * These tests pin behaviour that the audit found could silently regress: what the feed
 * reports while the device is offline, and that a credential-gated source stays visibly
 * "not configured" rather than disappearing or being reported as broken.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage22AuditTest {

    private class StaticProvider(
        override val id: String,
        override val name: String,
        private val jobs: List<RawJob> = emptyList(),
        override val requiresCredentials: Boolean = false,
    ) : JobProvider {
        override val homepage: String = "https://example.test/$id"
        override val documentationUrl: String = "https://example.test/$id/docs"
        override val kind: ProviderKind = ProviderKind.REMOTE_BOARD
        override val capabilities: ProviderCapabilities = ProviderCapabilities(serverSideKeyword = false)
        var fetches = 0

        override suspend fun fetch(keywords: List<String>, limit: Int): List<RawJob> {
            fetches++
            return jobs
        }
    }

    private fun raw(id: String, providerId: String) = RawJob(
        providerId = providerId,
        providerName = providerId,
        externalId = id,
        title = "Maintenance Technician",
        company = "Northline",
        location = "Worldwide",
        descriptionHtml = "Maintain the production line. Work from anywhere.",
        url = "https://example.test/$id",
        isRemote = true,
    )

    private fun repository(
        providers: List<JobProvider>,
        online: Boolean,
    ): LiveJobRepository = LiveJobRepository(
        providers = providers,
        mapper = JobMapper(matchingEngine = MatchingEngine(), eligibilityEngine = EligibilityEngine()),
        candidateProfile = MutableStateFlow(CandidateProfile()),
        cvRecord = MutableStateFlow(null),
        cache = JobCache(File.createTempFile("stage22", "").let { f -> f.delete(); f.mkdirs(); f }),
        isOnline = { online },
    )

    @Test
    fun offlineAttemptStillNamesEverySourceAndNeverReportsFailure() = runBlocking {
        val provider = StaticProvider(id = "arbeitnow", name = "Arbeitnow")
        val repo = repository(listOf(provider), online = false)

        repo.awaitSettled()

        val diagnostics = repo.diagnostics.first()
        assertFalse("network is not available", diagnostics.networkAvailable)
        assertEquals(
            "the offline attempt must still list every source, not an empty table",
            1,
            diagnostics.providers.size,
        )
        assertEquals(
            "offline is not a failure; it is waiting for a network",
            ProviderStatus.WAITING_FOR_NETWORK,
            diagnostics.providers.single().status,
        )
        assertEquals(0, provider.fetches)

        val sync = repo.syncState.first()
        assertEquals(1, sync.sources.size)
        assertEquals(SourceState.IDLE, sync.sources.single().state)
    }

    @Test
    fun aCredentialGatedSourceWithoutAKeyIsReportedNotConfiguredNotBroken() = runBlocking {
        val provider = StaticProvider(id = "jooble", name = "Jooble", requiresCredentials = true)
        val repo = repository(listOf(provider), online = true)

        repo.awaitSettled()

        val diagnostic = repo.diagnostics.first().providers.single()
        assertEquals(ProviderStatus.NOT_CONFIGURED, diagnostic.status)
        assertTrue(diagnostic.needsSetup)
        assertFalse("not configured is dormant, not a red failure", diagnostic.status.isFailure)
        assertTrue("a dormant source is not shown as an error row", diagnostic.status.isDormant)
        assertEquals("a dormant source is not queried at all", 0, provider.fetches)
    }

    @Test
    fun anOnlineAttemptListsEachSourceExactlyOnce() = runBlocking {
        // A configured key-free source, a switched-off source and an unconfigured
        // credential-gated source: the online path must produce exactly one row per
        // source. Two rows for one id crash the diagnostics list, which keys on it.
        val free = StaticProvider(id = "arbeitnow", name = "Arbeitnow", jobs = listOf(raw("a1", "arbeitnow")))
        val gated = StaticProvider(id = "jooble", name = "Jooble", requiresCredentials = true)
        val repo = repository(listOf(free, gated), online = true)

        repo.awaitSettled()

        val ids = repo.diagnostics.first().providers.map { it.providerId }
        assertEquals("one row per source", ids.size, ids.distinct().size)
        assertEquals(setOf("arbeitnow", "jooble"), ids.toSet())
        assertEquals(ProviderStatus.SUCCESS_WITH_RESULTS, repo.diagnostics.first().providers.first { it.providerId == "arbeitnow" }.status)
        assertEquals(ProviderStatus.NOT_CONFIGURED, repo.diagnostics.first().providers.first { it.providerId == "jooble" }.status)
    }

    /**
     * Regression for the Stage 22 flake. The repository seeds diagnostics with
     * `isRefreshing = false` and no provider in progress *before* its init coroutine has run,
     * so the old "is it idle?" predicate matched that seed and returned the blank state. The
     * completion signal must require a recorded attempt.
     */
    @Test
    fun theUntouchedSeedStateIsNotTreatedAsSettled() {
        // Exactly the seed: no attempt recorded, nothing refreshing, no provider in progress.
        assertFalse(
            "the pre-attempt seed must never read as settled",
            isSettledDiagnostics(lastAttemptAtEpochMillis = null, isRefreshing = false, anyProviderInProgress = false),
        )
    }

    @Test
    fun aFinishedAttemptIsTreatedAsSettled() {
        assertTrue(
            isSettledDiagnostics(lastAttemptAtEpochMillis = 1_700_000_000_000, isRefreshing = false, anyProviderInProgress = false),
        )
    }

    /** A refresh that is still running — or has a source in flight — is never settled. */
    @Test
    fun anInFlightAttemptIsNotSettled() {
        assertFalse(isSettledDiagnostics(lastAttemptAtEpochMillis = 1_700_000_000_000, isRefreshing = true, anyProviderInProgress = false))
        assertFalse(isSettledDiagnostics(lastAttemptAtEpochMillis = 1_700_000_000_000, isRefreshing = false, anyProviderInProgress = true))
    }

    /**
     * The interleaving that produced the failure, pinned without racing the init coroutine:
     * the repository's untouched seed — the exact values it is constructed with — is what the
     * old predicate matched, and it must not count as settled.
     */
    @Test
    fun theRepositorySeedMatchesTheOldPredicateButNotTheRecordedAttemptContract() = runBlocking {
        val repo = repository(listOf(StaticProvider(id = "arbeitnow", name = "Arbeitnow")), online = false)

        // What the object is constructed with, before any attempt. This is the exact shape
        // the old "!refreshing && no IN_PROGRESS" check accepted.
        val seed = ProviderDiagnostics(
            providers = listOf(
                ProviderDiagnostic(
                    providerId = "arbeitnow",
                    name = "Arbeitnow",
                    homepage = "https://example.test/arbeitnow",
                    documentationUrl = "https://example.test/arbeitnow/docs",
                ),
            ),
        )
        assertFalse("the seed must not satisfy the recorded-attempt contract", isSettledDiagnostics(
            lastAttemptAtEpochMillis = seed.lastAttemptAtEpochMillis,
            isRefreshing = seed.isRefreshing,
            anyProviderInProgress = seed.providers.any { it.status == ProviderStatus.IN_PROGRESS },
        ))

        // And once the real init refresh settles, it does.
        repo.awaitSettled()
        val settled = repo.diagnostics.first()
        assertTrue(isSettledDiagnostics(
            lastAttemptAtEpochMillis = settled.lastAttemptAtEpochMillis,
            isRefreshing = settled.isRefreshing,
            anyProviderInProgress = settled.providers.any { it.status == ProviderStatus.IN_PROGRESS },
        ))
    }

    /**
     * Regression for the second half of the Stage 22 flake. The repository used to fire two
     * refreshes on startup — the explicit init refresh plus one from the demo-mode collector
     * reacting to its own seed value — so a source could be queried twice and the sync state
     * could be caught mid-second-attempt (LOADING). A launch must read each source once.
     */
    @Test
    fun oneLaunchReadsEachSourceOnce() = runBlocking {
        val provider = StaticProvider(id = "arbeitnow", name = "Arbeitnow", jobs = listOf(raw("a1", "arbeitnow")))
        val repo = repository(listOf(provider), online = true)

        repo.awaitSettled()

        assertEquals("a single launch must not schedule a second, redundant refresh", 1, provider.fetches)
        assertEquals(SourceState.SUCCESS, repo.syncState.first().sources.single().state)
    }

    /**
     * After the initial refresh settles, both flows must describe the same finished attempt:
     * diagnostics report the observed network and a recorded time, and sync reports idle
     * sources consistent with that attempt.
     */
    @Test
    fun afterSettlingBothFlowsDescribeTheSameFinishedAttempt() = runBlocking {
        val repo = repository(listOf(StaticProvider(id = "arbeitnow", name = "Arbeitnow")), online = false)

        repo.awaitSettled()

        val diagnostics = repo.diagnostics.first()
        val sync = repo.syncState.first()
        assertNotNull("a settled state has a recorded attempt", diagnostics.lastAttemptAtEpochMillis)
        assertFalse(diagnostics.isRefreshing)
        assertFalse("sync must agree the attempt finished", sync.isRefreshing)
        assertTrue("offline is reported as such, not left at the optimistic default", !diagnostics.networkAvailable)
        assertEquals(1, sync.sources.size)
        assertTrue("a finished attempt leaves no source loading", sync.sources.none { it.state == SourceState.LOADING })
    }
}
