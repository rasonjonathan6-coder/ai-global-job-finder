package com.aiglobal.jobfinder

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.aiglobal.jobfinder.data.alerts.JobAlertStore
import com.aiglobal.jobfinder.data.alerts.JobAlertsCenter
import com.aiglobal.jobfinder.data.jobs.JobCache
import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.provider.JobProvider
import com.aiglobal.jobfinder.data.jobs.provider.ProviderKind
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.CvRecord
import com.aiglobal.jobfinder.data.prefs.UserPreferencesRepository
import com.aiglobal.jobfinder.data.repository.LiveJobRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Stage 13: the preferences a user sets in Profile must survive a restart, and a fresh
 * install must not show content the user never created.
 *
 * These run against the real [UserPreferencesRepository] backed by a temporary DataStore
 * file, so the assertions exercise the actual persistence path rather than a fake: the
 * first repository "writes", the second one "relaunches" and reads the same file back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage13PersistenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A single worldwide posting, so a "restart" has a real feed to score against. */
    private class SingleJobProvider : JobProvider {
        override val id = "test-feed"
        override val name = "Test feed"
        override val homepage = "https://example.test"
        override val kind = ProviderKind.REMOTE_BOARD
        override suspend fun fetch(keywords: List<String>, limit: Int): List<RawJob> = listOf(
            RawJob(
                providerId = id,
                providerName = name,
                externalId = "job-1",
                title = "Maintenance Technician",
                company = "Northline",
                location = "Worldwide",
                descriptionHtml = "Maintain the production line. Work from anywhere.",
                url = "https://example.test/job-1",
                isRemote = true,
            ),
        )
    }

    /** A repository over its own DataStore scope; [shutDown] releases the file for a restart. */
    private class Session(val repository: LiveJobRepository, val shutDown: suspend () -> Unit)

    /**
     * Builds a repository wired to a fresh preferences file under [tag]. Calling it twice
     * models a restart: the second instance starts with no in-memory state and must read
     * the file the first one wrote. The first session must be shut down before the second
     * opens the same file, since DataStore refuses two live instances on one file.
     */
    private fun sessionAt(tag: String): Session {
        val prefsFile = File(context.cacheDir, "stage13_$tag.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { prefsFile })
        val prefs = UserPreferencesRepository(context, dataStore)
        val cvRecord = MutableStateFlow<CvRecord?>(null)
        val repository = LiveJobRepository(
            providers = listOf(SingleJobProvider()),
            mapper = JobMapper(matchingEngine = MatchingEngine(), eligibilityEngine = EligibilityEngine()),
            candidateProfile = MutableStateFlow(CandidateProfile()),
            cvRecord = cvRecord,
            cache = JobCache(File(context.cacheDir, "stage13_${tag}_feed").also { it.mkdirs() }),
            // The test provider serves its posting without a network, so "online" lets the
            // repository run its real fetch-and-score path.
            isOnline = { true },
            demoMode = prefs.demoMode,
            demoModeSetter = { prefs.setDemoMode(it) },
            autoApplyFlow = prefs.autoApplyEnabled,
            autoApplySetter = { prefs.setAutoApplyEnabled(it) },
            savedIdsFlow = prefs.savedJobIds,
            savedIdsSetter = { prefs.setSavedJobIds(it) },
        )
        return Session(repository) {
            scope.cancel()
            delay(50)
        }
    }

    @Test
    fun autoApplyChoiceSurvivesARestart() = runBlocking {
        val tag = "autoapply"
        val first = sessionAt(tag)
        first.repository.setAutoApply(true)
        assertTrue(first.repository.profile.first().autoApplyEnabled)
        first.shutDown()

        // Relaunch: a brand-new repository over the same preferences file.
        val second = sessionAt(tag)
        assertTrue(
            "auto-apply should still be on after a restart",
            second.repository.profile.first().autoApplyEnabled,
        )
        second.shutDown()
    }

    @Test
    fun savedPostingsSurviveARestart() = runBlocking {
        val tag = "saved"
        val first = sessionAt(tag)
        val jobId = first.repository.awaitFirstJob().id
        first.repository.toggleSaved(jobId)
        assertTrue(first.repository.jobs.first().first { it.id == jobId }.isSaved)
        first.shutDown()

        val second = sessionAt(tag)
        assertTrue(
            "the bookmarked posting should still be saved after a restart",
            second.repository.awaitFirstJob().isSaved,
        )
        second.shutDown()
    }

    @Test
    fun unbookmarkingSurvivesARestart() = runBlocking {
        val tag = "unsaved"
        val first = sessionAt(tag)
        val jobId = first.repository.awaitFirstJob().id
        first.repository.toggleSaved(jobId)
        first.repository.toggleSaved(jobId)
        assertFalse(first.repository.jobs.first().first { it.id == jobId }.isSaved)
        first.shutDown()

        val second = sessionAt(tag)
        assertFalse(second.repository.awaitFirstJob().isSaved)
        second.shutDown()
    }

    /**
     * The repository loads its feed in an init coroutine, so wait for the first scored
     * posting rather than assuming it is ready the instant the object is built.
     */
    private suspend fun LiveJobRepository.awaitFirstJob() =
        withTimeout(10_000) { jobs.first { it.isNotEmpty() }.first() }

    /** The auto-apply preference defaults to off, so a fresh install starts disabled. */
    @Test
    fun autoApplyDefaultsToOffOnAFreshInstall() = runBlocking {
        val session = sessionAt("fresh")
        assertFalse(session.repository.profile.first().autoApplyEnabled)
        session.shutDown()
    }

    /** With no CV imported, the profile stays empty rather than inventing a candidate. */
    @Test
    fun noCvYieldsAnEmptyProfile() = runBlocking {
        val session = sessionAt("emptycv")
        val profile = session.repository.profile.first()
        assertTrue(profile.fullName.isBlank())
        assertTrue(profile.skills.isEmpty())
        assertFalse(profile.cvImported)
        session.shutDown()
    }

    /** A fresh install must not show saved searches the user never created. */
    @Test
    fun alertsStartEmptyOutsideDemoMode() = runBlocking {
        val session = sessionAt("alerts_live")
        val store = JobAlertStore(context, File(context.cacheDir, "stage13_alerts_live.json").also { it.delete() })
        val center = JobAlertsCenter(repository = session.repository, store = store, demoMode = flowOf(false))

        // Let the center's init coroutine run; it must decide not to seed anything.
        delay(500)
        assertTrue("no saved searches should be seeded outside demo mode", center.state.value.rules.isEmpty())
        session.shutDown()
    }

    /** In demo mode the two curated example searches are seeded so the demo has content. */
    @Test
    fun demoModeSeedsExampleAlerts() = runBlocking {
        val session = sessionAt("alerts_demo")
        val store = JobAlertStore(context, File(context.cacheDir, "stage13_alerts_demo.json").also { it.delete() })
        val center = JobAlertsCenter(repository = session.repository, store = store, demoMode = flowOf(true))

        val seeded = withTimeout(5_000) { center.state.first { it.rules.isNotEmpty() } }
        assertTrue("demo mode should seed the example searches", seeded.rules.isNotEmpty())
        session.shutDown()
    }
}
