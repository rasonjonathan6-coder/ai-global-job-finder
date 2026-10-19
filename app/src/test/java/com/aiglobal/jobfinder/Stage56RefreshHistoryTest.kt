package com.aiglobal.jobfinder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aiglobal.jobfinder.data.discovery.DiscoveryDelta
import com.aiglobal.jobfinder.data.discovery.RefreshHistory
import com.aiglobal.jobfinder.data.discovery.RefreshHistoryStore
import com.aiglobal.jobfinder.data.discovery.RefreshOutcome
import com.aiglobal.jobfinder.data.discovery.RefreshOutcomeClassifier
import com.aiglobal.jobfinder.data.discovery.RefreshRunRecord
import com.aiglobal.jobfinder.data.discovery.RefreshTrigger
import com.aiglobal.jobfinder.data.jobs.provider.ProviderErrorCategory
import com.aiglobal.jobfinder.data.model.DashboardStats
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobSyncState
import com.aiglobal.jobfinder.data.model.ProviderDiagnostic
import com.aiglobal.jobfinder.data.model.ProviderDiagnostics
import com.aiglobal.jobfinder.data.model.ProviderStatus
import com.aiglobal.jobfinder.data.model.UserProfile
import com.aiglobal.jobfinder.data.repository.JobRepository
import com.aiglobal.jobfinder.work.RefreshRequestResult
import com.aiglobal.jobfinder.work.RefreshRunCoordinator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Stage 56 — the refresh history.
 *
 * Stage 55 made a background run stop and recorded *that* it stopped. What it could not answer
 * was "what did it do?". The history is that answer: one record per finished run, manual and
 * scheduled alike, persisted so it survives a restart, and honest about the three cases the
 * earlier work left implicit —
 *  - a run refused because another was already in flight was never attempted, so it is
 *    recorded as such rather than as a failure;
 *  - a run that reached a source but read nothing has no honest "new postings" figure, so it
 *    stores none rather than a misleading zero;
 *  - the same run classified from the repository's diagnostics and from a thrown error must
 *    agree, because the manual and scheduled paths share one classifier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage56RefreshHistoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun historyFile(tag: String): File =
        File(context.cacheDir, "refresh-history-$tag-${System.nanoTime()}.json").also { it.delete() }

    private fun diag(
        id: String,
        status: ProviderStatus,
        category: ProviderErrorCategory? = null,
        duplicateRemoved: Int = 0,
    ) = ProviderDiagnostic(
        providerId = id,
        name = id.replaceFirstChar { it.uppercase() },
        homepage = "https://example.com",
        documentationUrl = "https://example.com/docs",
        status = status,
        errorCategory = category,
        duplicateRemovedCount = duplicateRemoved,
    )

    // ------------------------------------------------------------- classification

    @Test
    fun aRefusedRequestIsNotAttemptedAndIsNotAFailure() {
        val classification = RefreshOutcomeClassifier.classify(
            attempted = false,
            diagnostics = ProviderDiagnostics(),
            delta = DiscoveryDelta(),
        )
        assertEquals(RefreshOutcome.NOT_ATTEMPTED, classification.outcome)
        assertNull(classification.newCount)
        assertNull(classification.updatedCount)
    }

    @Test
    fun aSuccessfulReadCarriesTheMeasuredCounts() {
        val classification = RefreshOutcomeClassifier.classify(
            attempted = true,
            diagnostics = ProviderDiagnostics(
                providers = listOf(diag("remotive", ProviderStatus.SUCCESS_WITH_RESULTS)),
                networkAvailable = true,
                lastAttemptAtEpochMillis = 1,
            ),
            delta = DiscoveryDelta(newJobIds = listOf("a", "b"), updatedJobIds = listOf("c")),
        )
        assertEquals(RefreshOutcome.SUCCEEDED, classification.outcome)
        assertEquals(2, classification.newCount)
        assertEquals(1, classification.updatedCount)
    }

    @Test
    fun aFailedReadDoesNotClaimZeroNewPostings() {
        // The delta is untouched by a failed read, so reporting its (empty) counts as "0 new"
        // would read as "the search ran and found nothing", which is a different, false claim.
        val classification = RefreshOutcomeClassifier.classify(
            attempted = true,
            diagnostics = ProviderDiagnostics(
                providers = listOf(diag("remotive", ProviderStatus.NETWORK_ERROR, ProviderErrorCategory.NETWORK)),
                networkAvailable = true,
            ),
            delta = DiscoveryDelta(newJobIds = listOf("stale")),
        )
        assertEquals(RefreshOutcome.FAILED, classification.outcome)
        assertNull(classification.newCount)
        assertNull(classification.updatedCount)
    }

    @Test
    fun aPartialReadIsPartialNotFailed() {
        val classification = RefreshOutcomeClassifier.classify(
            attempted = true,
            diagnostics = ProviderDiagnostics(
                providers = listOf(
                    diag("remotive", ProviderStatus.SUCCESS_WITH_RESULTS),
                    diag("jobicy", ProviderStatus.TIMEOUT, ProviderErrorCategory.TIMEOUT),
                ),
                networkAvailable = true,
            ),
            delta = DiscoveryDelta(newJobIds = listOf("a")),
        )
        assertEquals(RefreshOutcome.PARTIAL, classification.outcome)
        assertEquals(1, classification.newCount)
        assertNotNull(classification.reason)
    }

    @Test
    fun noNetworkIsOfflineNotFailed() {
        val classification = RefreshOutcomeClassifier.classify(
            attempted = true,
            diagnostics = ProviderDiagnostics(networkAvailable = false),
            delta = DiscoveryDelta(),
        )
        assertEquals(RefreshOutcome.OFFLINE, classification.outcome)
    }

    @Test
    fun onlyRateLimitingIsNamedAsRateLimiting() {
        val classification = RefreshOutcomeClassifier.classify(
            attempted = true,
            diagnostics = ProviderDiagnostics(
                providers = listOf(diag("jooble", ProviderStatus.RATE_LIMITED, ProviderErrorCategory.RATE_LIMITED)),
                networkAvailable = true,
            ),
            delta = DiscoveryDelta(),
        )
        assertEquals(RefreshOutcome.RATE_LIMITED, classification.outcome)
    }

    // ------------------------------------------------------------- persistence

    @Test
    fun aRecordSurvivesAReload() = runBlocking {
        val file = historyFile("roundtrip")
        val store = RefreshHistoryStore(context, file)
        val record = RefreshRunRecord(
            id = 1_000,
            trigger = RefreshTrigger.MANUAL,
            startedAtEpochMillis = 1_000,
            endedAtEpochMillis = 4_000,
            outcome = RefreshOutcome.PARTIAL,
            reason = "1 of 2 sources could not be reached.",
            providers = listOf(
                com.aiglobal.jobfinder.data.discovery.RefreshProviderResult(
                    "remotive", "Remotive", ProviderStatus.SUCCESS_WITH_RESULTS,
                ),
                com.aiglobal.jobfinder.data.discovery.RefreshProviderResult(
                    "jobicy", "Jobicy", ProviderStatus.TIMEOUT,
                ),
            ),
            newCount = 3,
            updatedCount = 1,
            duplicateCount = 2,
        )
        store.record(record)

        // A second store over the same file models the next app launch: the record must come
        // back whole, not from an in-memory cache.
        val reloaded = RefreshHistoryStore(context, file).read().runs.single()
        assertEquals(record.id, reloaded.id)
        assertEquals(RefreshTrigger.MANUAL, reloaded.trigger)
        assertEquals(RefreshOutcome.PARTIAL, reloaded.outcome)
        assertEquals(3, reloaded.newCount)
        assertEquals(1, reloaded.successCount)
        assertEquals(1, reloaded.failureCount)
        assertEquals(3_000L, reloaded.durationMillis)
    }

    @Test
    fun historyIsNewestFirstAndCapped() = runBlocking {
        val store = RefreshHistoryStore(context, historyFile("cap"))
        repeat(RefreshHistory.MAX_RUNS + 5) { index ->
            store.record(
                RefreshRunRecord(
                    id = index.toLong(),
                    trigger = RefreshTrigger.SCHEDULED,
                    startedAtEpochMillis = index.toLong(),
                    endedAtEpochMillis = index.toLong() + 1,
                    outcome = RefreshOutcome.SUCCEEDED,
                ),
            )
        }
        val history = store.read()
        assertEquals(RefreshHistory.MAX_RUNS, history.runs.size)
        assertEquals((RefreshHistory.MAX_RUNS + 4).toLong(), history.runs.first().id)
        assertTrue(history.runs.first().id > history.runs.last().id)
    }

    @Test
    fun aCorruptFileIsDiscardedRatherThanFatal() = runBlocking {
        val file = historyFile("corrupt")
        file.parentFile?.mkdirs()
        file.writeText("{ this is not json")
        val history = RefreshHistoryStore(context, file).read()
        assertTrue(history.runs.isEmpty())
    }

    @Test
    fun anUnmeasuredCountStaysNullThroughAReLoad() = runBlocking {
        val file = historyFile("nulls")
        val store = RefreshHistoryStore(context, file)
        store.record(
            RefreshRunRecord(
                id = 1,
                trigger = RefreshTrigger.SCHEDULED,
                startedAtEpochMillis = 1,
                endedAtEpochMillis = 2,
                outcome = RefreshOutcome.FAILED,
                reason = "The job sources could not be reached.",
            ),
        )
        val reloaded = RefreshHistoryStore(context, file).read().runs.single()
        assertNull(reloaded.newCount)
        assertNull(reloaded.updatedCount)
        assertFalse(reloaded.succeeded)
    }

    @Test
    fun lastSuccessIsNotOverwrittenByALaterFailure() {
        val history = RefreshHistory(
            listOf(
                RefreshRunRecord(2, RefreshTrigger.SCHEDULED, 20, 21, RefreshOutcome.FAILED),
                RefreshRunRecord(1, RefreshTrigger.SCHEDULED, 10, 11, RefreshOutcome.SUCCEEDED),
            ),
        )
        assertEquals(2L, history.lastAttempt?.id)
        assertEquals(1L, history.lastSuccess?.id)
    }

    // ------------------------------------------------------------- coordinator

    @Test
    fun aManualRunIsRecordedInTheHistory() = runBlocking {
        val store = RefreshHistoryStore(context, historyFile("coordinator"))
        val repository = RecordingRepository()
        val coordinator = RefreshRunCoordinator(
            repository = repository,
            historyStore = store,
            clock = System::currentTimeMillis,
            retryDelayMillis = { 0L },
        )

        val result = coordinator.refresh(RefreshTrigger.MANUAL)

        assertTrue(result is RefreshRequestResult.Ran)
        val record = (result as RefreshRequestResult.Ran).record
        assertEquals(RefreshTrigger.MANUAL, record.trigger)
        assertTrue(record.succeeded)
        assertEquals(record.id, store.read().lastAttempt?.id)
    }

    @Test
    fun aRefusedRunIsRecordedHonestlyNotAsAFailure() = runBlocking {
        val store = RefreshHistoryStore(context, historyFile("refused"))
        val repository = RecordingRepository()
        val coordinator = RefreshRunCoordinator(
            repository = repository,
            historyStore = store,
            retryDelayMillis = { 0L },
        )
        // The repository refuses the re-entrant read silently: the attempt stamp never moves.
        repository.refuseNextRun = true

        val record = (coordinator.refresh(RefreshTrigger.SCHEDULED) as RefreshRequestResult.Ran).record

        assertEquals(RefreshOutcome.NOT_ATTEMPTED, record.outcome)
        assertFalse(record.succeeded)
        assertNull(record.newCount)
    }

    /** A repository whose reads actually move the attempt stamp, so a run is observable. */
    private class RecordingRepository : JobRepository {
        private val _diagnostics = MutableStateFlow(ProviderDiagnostics())
        private val _jobs = MutableStateFlow<List<Job>>(emptyList())

        var refuseNextRun = false

        override val jobs: Flow<List<Job>> = _jobs
        override val profile: Flow<UserProfile> = MutableStateFlow(emptyProfile())
        override val stats: Flow<DashboardStats> = MutableStateFlow(DashboardStats(0, 0, 0, 0))
        override val syncState: Flow<JobSyncState> = MutableStateFlow(JobSyncState())
        override val diagnostics: Flow<ProviderDiagnostics> = _diagnostics
        override val coverage: Flow<com.aiglobal.jobfinder.data.jobs.search.SearchCoverage> =
            MutableStateFlow(com.aiglobal.jobfinder.data.jobs.search.SearchCoverage())
        override val discovery: Flow<DiscoveryDelta> = MutableStateFlow(DiscoveryDelta())
        override val newPostings: Flow<com.aiglobal.jobfinder.data.discovery.NewPostings> =
            MutableStateFlow(com.aiglobal.jobfinder.data.discovery.NewPostings())

        override suspend fun runLiveValidation() {
            if (refuseNextRun) return
            _diagnostics.value = ProviderDiagnostics(
                providers = listOf(
                    diag("remotive", ProviderStatus.SUCCESS_WITH_RESULTS),
                ),
                networkAvailable = true,
                lastAttemptAtEpochMillis = System.currentTimeMillis(),
            )
        }

        override suspend fun markNewPostingsReviewed() = Unit
        override suspend fun refresh() = Unit
        override suspend fun toggleSaved(jobId: String) = Unit
        override fun jobById(id: String): Job? = _jobs.value.firstOrNull { it.id == id }
        override suspend fun setAutoApply(enabled: Boolean) = Unit
        override suspend fun setDemoMode(enabled: Boolean) = Unit

        private fun diag(id: String, status: ProviderStatus) = ProviderDiagnostic(
            providerId = id,
            name = id.replaceFirstChar { it.uppercase() },
            homepage = "https://example.com",
            documentationUrl = "https://example.com/docs",
            status = status,
        )

        private fun emptyProfile() = UserProfile(
            fullName = "",
            headline = "",
            location = "",
            email = "",
            phone = "",
            summary = "",
            yearsOfExperience = 0,
            skills = emptyList(),
            experience = emptyList(),
            education = emptyList(),
            languages = emptyList(),
            targetCountries = emptyList(),
            targetRoles = emptyList(),
            cvImported = false,
            cvFileName = null,
            cvUpdatedAtEpochMillis = null,
            autoApplyEnabled = false,
            profileCompletion = 0,
        )
    }
}
