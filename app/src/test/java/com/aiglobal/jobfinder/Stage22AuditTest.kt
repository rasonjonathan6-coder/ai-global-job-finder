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
import com.aiglobal.jobfinder.data.model.ProviderStatus
import com.aiglobal.jobfinder.data.model.SourceState
import com.aiglobal.jobfinder.data.repository.LiveJobRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * Waits for a refresh to settle. The repository kicks off its own first refresh in the
     * constructor, so an explicit call can be skipped by the single-flight mutex; polling
     * until no source is in progress removes that race from the assertions.
     */
    private suspend fun LiveJobRepository.awaitSettled() {
        withTimeout(5_000) {
            diagnostics.first { state ->
                !state.isRefreshing && state.providers.none { it.status == ProviderStatus.IN_PROGRESS }
            }
        }
    }

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
}
