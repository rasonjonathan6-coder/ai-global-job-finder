package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.expat.ShortlistEngine
import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.provider.ArbeitnowProvider
import com.aiglobal.jobfinder.data.jobs.provider.JsonFeed
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.jobs.search.RelevanceFeed
import com.aiglobal.jobfinder.data.jobs.search.RoleClassifier
import com.aiglobal.jobfinder.data.jobs.search.RoleFamily
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.ExpatPreferences
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 70 — live job relevance and shortlist correction.
 *
 * The live audit found the key-free boards returning a broad, newest-first global sample
 * with near-zero relevant matches, and the shortlist carrying postings that were not even
 * the candidate's trade. These tests pin the three fixes on the real engines: the Arbeitnow
 * pagination that widens the sample, the [RelevanceFeed] filter that narrows the ranked feed
 * to the candidate's trade, and the [ShortlistEngine] correction that only lists actionable
 * roles. Robolectric supplies the real `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage70RelevanceTest {

    private var now = 1_700_000_000_000L

    private val profile = CandidateProfile(
        fullName = "Jonathan Rakoto",
        email = "jonathan.rakoto@example.mg",
        location = "Antananarivo, Madagascar",
        currentTitle = "Maintenance Technician",
        previousTitles = listOf("Industrial Electrician"),
        yearsOfExperience = 6,
        skills = listOf("Preventive maintenance", "Hydraulics", "PLC", "Electrical fault finding"),
        preferredCountries = listOf("Canada", "Germany"),
    )

    /** Replays a canned body per page/URL and records what was asked for. */
    private class PagedFeed(
        private val onObject: (String) -> JSONObject,
    ) : JsonFeed {
        val urls = mutableListOf<String>()

        override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject {
            urls += url
            return onObject(url)
        }

        override suspend fun objectPost(url: String, body: String, headers: Map<String, String>): JSONObject =
            throw UnsupportedOperationException()

        override suspend fun arrayAt(url: String, headers: Map<String, String>): JSONArray =
            throw UnsupportedOperationException()
    }

    private fun rawJob(
        id: String,
        title: String,
        description: String = "Maintenance role. Visa sponsorship available.",
    ) = RawJob(
        providerId = "arbeitnow",
        providerName = "Arbeitnow",
        externalId = id,
        title = title,
        company = "Northline Industrial",
        location = "Calgary, Canada",
        descriptionHtml = description,
        url = "https://example.test/$id",
        employmentType = "Full-time",
        postedAtEpochMillis = now - 86_400_000L,
        retrievedAtEpochMillis = now,
        salaryMin = 58_000,
        salaryMax = 74_000,
        salaryCurrency = "CAD",
        salaryPeriod = SalaryPeriod.YEAR,
    )

    private fun job(
        id: String,
        title: String,
        description: String = "Maintenance role. Visa sponsorship available.",
    ): Job = JobMapper(
        matchingEngine = MatchingEngine(),
        eligibilityEngine = EligibilityEngine(),
        clock = { now },
    ).toJob(rawJob(id, title, description), profile)

    // ---- Arbeitnow pagination ----------------------------------------------

    @Test
    fun arbeitnowPoolsSeveralPagesSoTradeRolesPastTheFirstPageAreFound() = runBlocking {
        val feed = PagedFeed { url ->
            when (url.substringAfter("page=").toInt()) {
                1 -> JSONObject("""{"data":[{"slug":"p1","title":"Software Engineer","url":"u1"}]}""")
                2 -> JSONObject("""{"data":[{"slug":"p2","title":"Maintenance Technician","url":"u2"}]}""")
                else -> JSONObject("""{"data":[]}""")
            }
        }
        val jobs = ArbeitnowProvider(feed).fetch(keywords = emptyList(), limit = 60)
        assertEquals(listOf("Software Engineer", "Maintenance Technician"), jobs.map { it.title })
        assertEquals(3, feed.urls.size)
    }

    @Test
    fun arbeitnowKeepsThePagesItAlreadyReadWhenALaterPageFails() = runBlocking {
        val feed = PagedFeed { url ->
            if (url.endsWith("page=1")) JSONObject("""{"data":[{"slug":"p1","title":"Fitter","url":"u1"}]}""")
            else throw java.io.IOException("rate limited")
        }
        val jobs = ArbeitnowProvider(feed).fetch(keywords = emptyList(), limit = 60)
        assertEquals(listOf("Fitter"), jobs.map { it.title })
    }

    // ---- the relevance filter ----------------------------------------------

    @Test
    fun theFilterKeepsTradesAndDropsUnrelatedRolesWithoutReordering() {
        val feed = listOf(
            job("a", "Marketing Manager", "Lead campaigns for a global brand."),
            job("b", "Maintenance Technician"),
            job("c", "Software Engineer", "Build the maintenance scheduling tool."),
            job("d", "Millwright", "Install and align rotating equipment."),
            job("e", "Lab Technician", "Quality control at an industrial manufacturer."),
        )
        val kept = RelevanceFeed.relevant(feed)
        val dropped = RelevanceFeed.unrelated(feed)
        assertEquals(listOf("arbeitnow:b", "arbeitnow:d"), kept.map { it.id })
        assertEquals(listOf("arbeitnow:a", "arbeitnow:c", "arbeitnow:e"), dropped.map { it.id })
        // A pure subset: order preserved, nothing invented.
        assertEquals(feed.filter { it.id in kept.map { k -> k.id } }, kept)
    }

    @Test
    fun aTitleThatNamesAnotherProfessionIsNotRescuedByAMaintenanceWordInTheDescription() {
        val relevance = RoleClassifier.classify(
            "Software Engineer, Maintenance Tooling",
            "Build tooling for the maintenance team at a plant.",
        )
        assertEquals(RoleFamily.UNRELATED, relevance.family)
        assertFalse(relevance.isMaintenanceFamily)
    }

    @Test
    fun theSummaryStatesHowManyUnrelatedPostingsWereSetAside() {
        assertEquals(
            "7 of 9 retrieved postings matched your trade; 2 unrelated postings were set aside.",
            RelevanceFeed.summary(totalRetrieved = 9, relevant = 7),
        )
        assertEquals("No postings were retrieved.", RelevanceFeed.summary(0, 0))
        assertEquals(
            "None of the 3 retrieved postings matched your trade.",
            RelevanceFeed.summary(3, 0),
        )
        assertEquals(
            "All 2 retrieved postings matched your trade.",
            RelevanceFeed.summary(2, 2),
        )
    }

    // ---- shortlist correction ----------------------------------------------

    @Test
    fun theShortlistOnlyListsPostingsTheCandidateCanActOn() = runBlocking {
        val jobs = listOf(
            job("trade", "Maintenance Technician"),
            job("unrelated", "Marketing Manager", "Lead campaigns for a global brand."),
        )
        val shortlist = ShortlistEngine(clock = { now }).build(
            jobs = jobs,
            profile = profile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 100,
            salaryEurPerYear = { null },
        )
        assertTrue(
            "an unrelated profession must never appear on an actionable shortlist",
            shortlist.entries.none { it.job.id == "arbeitnow:unrelated" },
        )
    }

    @Test
    fun aBroadFeedYieldsAShortlistOfOnlyTradeRoles() = runBlocking {
        val jobs = listOf(
            job("a", "Marketing Manager", "Lead campaigns for a global brand."),
            job("b", "Maintenance Technician"),
            job("c", "Software Engineer", "Build the maintenance scheduling tool."),
            job("d", "Millwright", "Install and align rotating equipment."),
        )
        val relevant = RelevanceFeed.relevant(jobs)
        val shortlist = ShortlistEngine(clock = { now }).build(
            jobs = relevant,
            profile = profile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 100,
            salaryEurPerYear = { null },
        )
        assertTrue(shortlist.entries.all { it.job.isMaintenanceRelevant })
        assertEquals(
            setOf("arbeitnow:b", "arbeitnow:d"),
            shortlist.entries.map { it.job.id }.toSet(),
        )
    }
}
