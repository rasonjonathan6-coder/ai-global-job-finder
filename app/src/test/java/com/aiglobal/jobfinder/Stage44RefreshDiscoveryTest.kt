package com.aiglobal.jobfinder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aiglobal.jobfinder.data.discovery.DiscoverySummary
import com.aiglobal.jobfinder.data.discovery.JobSeenLedger
import com.aiglobal.jobfinder.data.jobs.provider.ProviderErrorCategory
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.model.ProviderDiagnostic
import com.aiglobal.jobfinder.data.model.ProviderDiagnostics
import com.aiglobal.jobfinder.data.model.ProviderStatus
import com.aiglobal.jobfinder.work.RefreshRetryPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Stage 44: incremental discovery and the background retry policy.
 *
 * Two honesty rules are pinned here:
 *  - the seen ledger must report a genuinely new posting as new, a re-written one as an
 *    update, and an unchanged one as neither — and it must not announce the whole market on
 *    a fresh install;
 *  - a retry must be asked for only when a later attempt could plausibly help.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage44RefreshDiscoveryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun tempFile(tag: String): File =
        File(context.cacheDir, "seen-$tag-${System.nanoTime()}.json").also { it.delete() }

    private fun ledger(file: File): JobSeenLedger = JobSeenLedger(context, file = file)

    private fun raw(
        externalId: String = "1",
        title: String = "Maintenance Technician",
        company: String = "Northline Industrial",
        location: String = "Calgary",
        url: String = "https://example.com/jobs/$externalId",
        description: String = "Maintain production line machinery.",
    ) = RawJob(
        providerId = "arbeitnow",
        providerName = "Arbeitnow",
        externalId = externalId,
        title = title,
        company = company,
        location = location,
        descriptionHtml = description,
        url = url,
    )

    // ------------------------------------------------------ seen ledger

    @Test
    fun theFirstRunRecordsABaselineWithoutAnnouncingTheWholeMarket() = runBlocking {
        val file = tempFile("baseline")
        val delta = ledger(file).observe(listOf(raw("1"), raw("2")))
        assertTrue(delta.firstRun)
        assertTrue("a fresh install must not announce the market", delta.isEmpty)
        assertEquals(2, delta.knownCount)
    }

    @Test
    fun aGenuinelyNewPostingIsReportedAsNew() = runBlocking {
        val file = tempFile("new")
        val ledger = ledger(file)
        ledger.observe(listOf(raw("1")))
        val delta = ledger.observe(listOf(raw("1"), raw("2", title = "Welder")))
        assertFalse(delta.firstRun)
        assertEquals(listOf("arbeitnow:2"), delta.newJobIds)
        assertTrue(delta.updatedJobIds.isEmpty())
    }

    @Test
    fun anUnchangedPostingIsNotNews() = runBlocking {
        val file = tempFile("unchanged")
        val ledger = ledger(file)
        val feed = listOf(raw("1"), raw("2", title = "Welder"))
        ledger.observe(feed)
        assertTrue(ledger.observe(feed).isEmpty)
    }

    @Test
    fun aRewrittenPostingIsReportedAsAnUpdateNotANewJob() = runBlocking {
        val file = tempFile("update")
        val ledger = ledger(file)
        ledger.observe(listOf(raw("1")))
        val delta = ledger.observe(listOf(raw("1", title = "Senior Maintenance Technician")))
        assertTrue(delta.newJobIds.isEmpty())
        assertEquals(listOf("arbeitnow:1"), delta.updatedJobIds)
    }

    @Test
    fun theSameOpeningFromAnotherSourceIsMatchedByItsUrl() = runBlocking {
        val file = tempFile("url")
        val ledger = ledger(file)
        ledger.observe(listOf(raw("1", url = "https://example.com/jobs/1?utm_source=x")))
        // A different provider id, but the same canonical URL: not a new opening.
        val delta = ledger.observe(
            listOf(raw("1", url = "https://example.com/jobs/1").copy(providerId = "adzuna")),
        )
        assertTrue(delta.newJobIds.isEmpty())
    }

    @Test
    fun theBaselineCanBeAnnouncedWhenTheCallerAsks() = runBlocking {
        val file = tempFile("announce")
        val delta = ledger(file).observe(listOf(raw("1")), notifyBaseline = true)
        assertEquals(listOf("arbeitnow:1"), delta.newJobIds)
    }

    @Test
    fun theLedgerReportsOnlyPostingsFirstSeenAfterTheLastReview() = runBlocking {
        val file = tempFile("newpostings")
        var now = 1_000L
        val ledger = JobSeenLedger(context, file = file, clock = { now })

        // The first read records the baseline; the market it saw is not announced as new.
        ledger.observe(listOf(raw("1")))
        assertTrue(ledger.newPostings().isEmpty)
        assertTrue(ledger.newPostings().baselineEstablished)

        now = 2_000L
        ledger.observe(listOf(raw("1"), raw("2", title = "Welder")))
        val afterSecond = ledger.newPostings()
        assertEquals(listOf("arbeitnow:2"), afterSecond.jobIds)
        assertEquals(2_000L, afterSecond.discoveredAtByJobId.getValue("arbeitnow:2"))

        // Reviewing clears the list and moves the watermark forward.
        ledger.markReviewed(at = 3_000L)
        assertTrue(ledger.newPostings().isEmpty)

        // A later discovery is measured from the review, not from the original baseline.
        now = 4_000L
        ledger.observe(listOf(raw("1"), raw("2", title = "Welder"), raw("3", title = "Fitter")))
        assertEquals(listOf("arbeitnow:3"), ledger.newPostings().jobIds)
    }

    // ------------------------------------------------------ discovery summary

    @Test
    fun theSummaryDescribesWhatActuallyHappened() {
        val diagnostics = ProviderDiagnostics(
            providers = listOf(
                diag("adzuna", ProviderStatus.RATE_LIMITED, ProviderErrorCategory.RATE_LIMITED),
                diag("jooble", ProviderStatus.SUCCESS_WITH_RESULTS, null),
            ),
            lastAttemptAtEpochMillis = 5_000,
            lastSuccessAtEpochMillis = 5_000,
        )
        val summary = DiscoverySummary.from(diagnostics, com.aiglobal.jobfinder.data.discovery.DiscoveryDelta())
        assertTrue(summary.hasFailures)
        assertEquals(1, summary.rateLimitedCount)
        assertEquals(listOf("Adzuna"), summary.failures.map { it.name })
        assertEquals(5_000L, summary.lastSuccessAtEpochMillis)
    }

    @Test
    fun theDefaultSummaryClaimsNothing() {
        val summary = DiscoverySummary()
        assertFalse(summary.hasFailures)
        assertEquals(0, summary.discoveredCount)
        assertEquals(null, summary.lastSuccessAtEpochMillis)
    }

    // ------------------------------------------------------ retry policy

    @Test
    fun aRunWithNoConnectionIsRetried() {
        assertTrue(RefreshRetryPolicy.shouldRetry(ProviderDiagnostics(networkAvailable = false)))
    }

    @Test
    fun aRunThatReturnedAnythingUsableIsNotRetried() {
        val diagnostics = ProviderDiagnostics(
            providers = listOf(
                diag("adzuna", ProviderStatus.SUCCESS_WITH_RESULTS, null),
                diag("jooble", ProviderStatus.NETWORK_ERROR, ProviderErrorCategory.NETWORK),
            ),
        )
        assertFalse(RefreshRetryPolicy.shouldRetry(diagnostics))
    }

    @Test
    fun anAllTransientFailureIsRetried() {
        val diagnostics = ProviderDiagnostics(
            providers = listOf(diag("adzuna", ProviderStatus.TIMEOUT, ProviderErrorCategory.TIMEOUT)),
        )
        assertTrue(RefreshRetryPolicy.shouldRetry(diagnostics))
    }

    @Test
    fun aMissingKeyOrRejectedCredentialsIsNotRetried() {
        assertFalse(
            RefreshRetryPolicy.shouldRetry(
                ProviderDiagnostics(
                    providers = listOf(diag("adzuna", ProviderStatus.AUTHENTICATION_FAILED, ProviderErrorCategory.AUTHENTICATION)),
                ),
            ),
        )
    }

    @Test
    fun aRateLimitedSourceIsNeverHammeredAgain() {
        val diagnostics = ProviderDiagnostics(
            providers = listOf(
                diag("adzuna", ProviderStatus.RATE_LIMITED, ProviderErrorCategory.RATE_LIMITED),
                diag("jooble", ProviderStatus.TIMEOUT, ProviderErrorCategory.TIMEOUT),
            ),
        )
        assertFalse(RefreshRetryPolicy.shouldRetry(diagnostics))
    }

    private fun diag(
        id: String,
        status: ProviderStatus,
        category: ProviderErrorCategory?,
    ) = ProviderDiagnostic(
        providerId = id,
        name = id.replaceFirstChar { it.uppercase() },
        homepage = "https://example.com",
        documentationUrl = "https://example.com/docs",
        status = status,
        errorCategory = category,
    )
}
