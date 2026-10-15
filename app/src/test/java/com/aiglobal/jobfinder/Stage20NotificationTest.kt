package com.aiglobal.jobfinder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aiglobal.jobfinder.data.notifications.JobNotificationCenter
import com.aiglobal.jobfinder.data.notifications.JobNotificationsCoordinator
import com.aiglobal.jobfinder.data.notifications.NotificationHistoryStore
import com.aiglobal.jobfinder.data.repository.SampleJobRepository
import com.aiglobal.jobfinder.work.JobRefreshRunner
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
 * Stage 20 notification plumbing, exercised against real files and the real repository.
 *
 * The point of these tests is the *dedup guarantee*: a strong match is announced once and
 * never again, even after a process restart, and demo fixtures never raise a notification
 * because they are not real opportunities.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage20NotificationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun theHistorySurvivesANewStoreInstance() = runBlocking {
        val store = NotificationHistoryStore(context)
        store.record(listOf("job-a", "job-b"))

        // A fresh instance reads the same file, as it would after a restart.
        val reopened = NotificationHistoryStore(context)
        assertEquals(setOf("job-a", "job-b"), reopened.notifiedIds())
    }

    @Test
    fun aStrongMatchIsAnnouncedOnceAndNotAgain() = runBlocking {
        val coordinator = JobNotificationsCoordinator(
            center = JobNotificationCenter(context),
            history = NotificationHistoryStore(context),
        )
        val repository = SampleJobRepository(refreshDelayMillis = 0)
        val jobs = repository.jobs.first()

        val first = coordinator.notifyNewMatches(jobs, enabled = true)
        assertTrue(
            "expected a strong match among: " +
                jobs.joinToString { "${it.id}=${it.matchScore}" },
            first.isNotEmpty(),
        )

        // Repeated passes over the same feed must never repeat an id already announced. Each
        // pass may announce the next-strongest matches that did not fit the previous run's cap,
        // but across all passes every id appears at most once, and the feed eventually goes
        // quiet once every strong match has been announced.
        val seen = mutableSetOf<String>()
        var pass = 0
        var quiet = false
        while (pass < 20 && !quiet) {
            val batch = coordinator.notifyNewMatches(jobs, enabled = true)
            val repeated = batch.map { it.jobId }.filter { !seen.add(it) }
            assertTrue("no job may be announced twice, but saw $repeated", repeated.isEmpty())
            quiet = batch.isEmpty()
            pass++
        }
        assertTrue("the feed should go quiet once every strong match is announced", quiet)
    }

    @Test
    fun notificationsStaySilentWhenTheUserHasThemOff() = runBlocking {
        val coordinator = JobNotificationsCoordinator(
            center = JobNotificationCenter(context),
            history = NotificationHistoryStore(context),
        )
        val repository = SampleJobRepository(refreshDelayMillis = 0)
        val jobs = repository.jobs.first()
        assertTrue(coordinator.notifyNewMatches(jobs, enabled = false).isEmpty())
    }

    @Test
    fun theBackgroundRunnerNeverNotifiesForDemoFixtures() = runBlocking {
        val repository = SampleJobRepository(refreshDelayMillis = 0)
        val coordinator = JobNotificationsCoordinator(
            center = JobNotificationCenter(context),
            history = NotificationHistoryStore(context),
        )
        val runner = JobRefreshRunner(
            repository = repository,
            notifications = coordinator,
            notificationsEnabled = MutableStateFlow(true),
            demoMode = MutableStateFlow(true),
        )

        val posted = runner.run()
        assertTrue("demo fixtures are not real opportunities", posted.isEmpty())
    }
}
