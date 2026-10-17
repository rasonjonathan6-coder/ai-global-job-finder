package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.provider.AdzunaCredentials
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaProvider
import com.aiglobal.jobfinder.data.jobs.provider.ArbeitnowProvider
import com.aiglobal.jobfinder.data.jobs.provider.AshbyProvider
import com.aiglobal.jobfinder.data.jobs.provider.DataSourceKind
import com.aiglobal.jobfinder.data.jobs.provider.EuresProvider
import com.aiglobal.jobfinder.data.jobs.provider.GreenhouseProvider
import com.aiglobal.jobfinder.data.jobs.provider.HimalayasProvider
import com.aiglobal.jobfinder.data.jobs.provider.JoobleProvider
import com.aiglobal.jobfinder.data.jobs.provider.JobicyProvider
import com.aiglobal.jobfinder.data.jobs.provider.JobProvider
import com.aiglobal.jobfinder.data.jobs.provider.LeverProvider
import com.aiglobal.jobfinder.data.jobs.provider.ProviderAuditRegistry
import com.aiglobal.jobfinder.data.jobs.provider.ProviderCapabilities
import com.aiglobal.jobfinder.data.jobs.provider.ProviderKind
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.jobs.provider.RemotiveProvider
import com.aiglobal.jobfinder.data.jobs.provider.SmartRecruitersProvider
import com.aiglobal.jobfinder.data.jobs.provider.WorkdayProvider
import com.aiglobal.jobfinder.data.model.ProviderDiagnostic
import com.aiglobal.jobfinder.data.repository.SampleJobRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 50 — the source audit.
 *
 * The audit answers one question honestly: which of the registered sources are real, and
 * how is each one reached? It must never blur the two facts that are easy to conflate —
 * *where the postings come from* (live API vs bundled fixture) and *whether a source
 * answered just now* (that lives on the diagnostics, not here). These plain JVM tests pin
 * the audit to the providers themselves, so it cannot drift from what the app actually runs,
 * and they make no network call.
 */
class Stage50ProviderAuditTest {

    /** The same provider set the app registers at startup (see `AiJobFinderApp`). */
    private fun registeredProviders(): List<JobProvider> = listOf(
        ArbeitnowProvider(),
        RemotiveProvider(),
        JobicyProvider(),
        HimalayasProvider(),
        EuresProvider(),
        WorkdayProvider(),
        GreenhouseProvider(),
        AshbyProvider(),
        LeverProvider(),
        SmartRecruitersProvider(),
        AdzunaProvider(credentials = { AdzunaCredentials() }),
        JoobleProvider(apiKey = { "" }),
    )

    @Test
    fun theAuditCoversEveryRegisteredSourceExactlyOnce() {
        val providers = registeredProviders()
        val audit = ProviderAuditRegistry.audit(providers)

        assertEquals(providers.size, audit.size)
        assertEquals(providers.map { it.id }, audit.map { it.id })
        assertEquals(audit.size, audit.map { it.id }.distinct().size)
        audit.forEach { row ->
            assertTrue("${row.id} has a blank id", row.id.isNotBlank())
            assertTrue("${row.id} has a blank name", row.name.isNotBlank())
        }
    }

    @Test
    fun everyProductionSourceIsMarkedLive() {
        // All registered sources read a real API at fetch time. None is a fixture or a mock,
        // so the audit must say so — otherwise the UI could understate what is live.
        ProviderAuditRegistry.audit(registeredProviders()).forEach { row ->
            assertEquals("${row.id} should be a live source", DataSourceKind.LIVE, row.dataSource)
            assertTrue("${row.id} should read as live", row.isLive)
        }
    }

    @Test
    fun credentialGatedSourcesAreReportedAsSuch() {
        val audit = ProviderAuditRegistry.audit(registeredProviders()).associateBy { it.id }

        listOf("adzuna", "jooble").forEach { id ->
            val row = audit.getValue(id)
            assertTrue("$id needs a key", row.requiresCredentials)
            assertFalse("$id is not reachable without setup", row.reachableWithoutSetup)
        }

        listOf(
            "arbeitnow", "remotive", "jobicy", "himalayas", "eures",
            "workday", "greenhouse", "ashby", "lever", "smartrecruiters",
        ).forEach { id ->
            val row = audit.getValue(id)
            assertFalse("$id must not need credentials", row.requiresCredentials)
            assertTrue("$id should be reachable without setup", row.reachableWithoutSetup)
        }
    }

    @Test
    fun joobleIsFlaggedAsBotGatedAndTheKeyFreeSourcesAreNot() {
        // Jooble answers an unauthenticated request from a datacenter with a Cloudflare
        // challenge (documented in AGENTS.md). The audit records that as a fact about the
        // host so the diagnostics screen can explain the failure rather than imply emptiness.
        val audit = ProviderAuditRegistry.audit(registeredProviders()).associateBy { it.id }

        assertTrue("jooble is known to be bot-gated", audit.getValue("jooble").botGated)
        assertFalse("adzuna is key-gated, not bot-gated", audit.getValue("adzuna").botGated)
        assertFalse("arbeitnow is key-free and not gated", audit.getValue("arbeitnow").botGated)
    }

    @Test
    fun noSourceClaimsARealSubmissionRoute() {
        // The audit restates the submission gate; it must never look like a send capability.
        ProviderAuditRegistry.audit(registeredProviders()).forEach { row ->
            assertFalse(
                "${row.id} must not claim an end-to-end submission route",
                row.capabilities.realSubmissionSupported,
            )
        }
    }

    @Test
    fun theAuditIsDerivedFromTheProvidersNotAHandWrittenTable() {
        // A provider that declares itself a mock must come through the audit as a mock. If the
        // audit were a fixed table this would still read "live", which is exactly the failure
        // the field exists to prevent.
        val mock = object : JobProvider {
            override val id = "mock-source"
            override val name = "Mock source"
            override val homepage = "https://example.invalid"
            override val kind = ProviderKind.REMOTE_BOARD
            override val dataSource = DataSourceKind.MOCK
            override val capabilities = ProviderCapabilities(serverSideKeyword = true)
            override suspend fun fetch(keywords: List<String>, limit: Int): List<RawJob> = emptyList()
        }

        val row = ProviderAuditRegistry.audit(listOf(mock)).single()
        assertEquals(DataSourceKind.MOCK, row.dataSource)
        assertFalse(row.isLive)
        assertTrue(row.capabilities.serverSideKeyword)
    }

    @Test
    fun theFixtureRepositoryNeverPresentsItsDataAsLive() {
        // The deterministic fixture repository serves bundled sample data. Its diagnostics
        // must say FIXTURES for every source, so nothing downstream can call it a live feed.
        val repository = SampleJobRepository(refreshDelayMillis = 0)
        val diagnostics = runBlocking { repository.diagnostics.first() }

        assertTrue("fixture repository has sources", diagnostics.providers.isNotEmpty())
        diagnostics.providers.forEach { provider ->
            assertEquals(
                "${provider.providerId} serves fixtures, not a live feed",
                DataSourceKind.FIXTURES,
                provider.dataSource,
            )
            assertFalse(provider.isLive)
        }

        // And it exposes no audit of its own: the audit describes the live source list, which
        // the fixture repository does not have, so it stays empty rather than guessing.
        assertTrue(repository.providerAudit.isEmpty())
    }

    @Test
    fun aDiagnosticWithoutAnExplicitSourceReadsAsLiveByDefault() {
        // Existing construction sites predate the field. The default is LIVE, which keeps a
        // record that was not explicitly re-typed from silently flipping to "fixture".
        val diagnostic = ProviderDiagnostic(
            providerId = "adzuna",
            name = "Adzuna",
            homepage = "https://www.adzuna.com",
            documentationUrl = "https://developer.adzuna.com/docs/search",
        )
        assertEquals(DataSourceKind.LIVE, diagnostic.dataSource)
        assertTrue(diagnostic.isLive)
    }
}
