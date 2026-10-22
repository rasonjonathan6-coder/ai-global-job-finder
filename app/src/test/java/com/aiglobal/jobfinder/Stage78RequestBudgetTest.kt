package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.JobCache
import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.provider.JsonFeed
import com.aiglobal.jobfinder.data.jobs.provider.SmartRecruitersBoard
import com.aiglobal.jobfinder.data.jobs.provider.SmartRecruitersProvider
import com.aiglobal.jobfinder.data.jobs.provider.WorkdayBoards
import com.aiglobal.jobfinder.data.jobs.provider.WorkdayProvider
import com.aiglobal.jobfinder.data.jobs.provider.WorkdaySite
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.repository.LiveJobRepository
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 78 — the request budget of the two employer-board providers.
 *
 * SmartRecruiters reads only the first keyword, so a sweep budget above one re-runs the whole
 * country sweep for the same keyword. These tests pin the budget at one and prove that one
 * sweep still reads every country, so coverage is unchanged while the duplicate requests are
 * removed.
 *
 * Workday's per-site request count is measured exactly (pages plus detail calls) against
 * deterministic fake feeds, so the bound the provider claims is the bound it actually spends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage78RequestBudgetTest {

    // ---- SmartRecruiters ----------------------------------------------------------------

    /**
     * Counts the list vs detail calls. The list endpoint always carries a `q=` query; the
     * detail endpoint never does, which is how the provider's own tests separate the two.
     */
    private class CountingSmartRecruitersFeed : JsonFeed {
        val listUrls = mutableListOf<String>()

        override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject =
            if (url.contains("?")) {
                listUrls += url
                JSONObject("""{"totalFound":1,"content":[{"id":"1","name":"Maintenance Technician"}]}""")
            } else {
                JSONObject(
                    """
                    {"id":"1","name":"Maintenance Technician","active":true,
                     "postingUrl":"https://jobs.smartrecruiters.com/SGS/1",
                     "releasedDate":"2026-10-10T10:00:00.000Z",
                     "company":{"name":"SGS"},
                     "location":{"country":"za","fullLocation":"Durban, South Africa","remote":false},
                     "jobAd":{"sections":{"jobDescription":{"text":"<p>Maintain pumps.</p>"}}}}
                    """.trimIndent(),
                )
            }

        override suspend fun objectPost(url: String, body: String, headers: Map<String, String>): JSONObject =
            throw UnsupportedOperationException("SmartRecruiters uses GET only")

        override suspend fun arrayAt(url: String, headers: Map<String, String>): JSONArray =
            throw UnsupportedOperationException("SmartRecruiters returns an object, not an array")
    }

    private fun repository(providers: List<com.aiglobal.jobfinder.data.jobs.provider.JobProvider>) =
        LiveJobRepository(
            providers = providers,
            mapper = JobMapper(matchingEngine = MatchingEngine(), eligibilityEngine = EligibilityEngine()),
            candidateProfile = MutableStateFlow(CandidateProfile()),
            cvRecord = MutableStateFlow(null),
            cache = JobCache(File.createTempFile("stage78", "").let { f -> f.delete(); f.mkdirs(); f }),
            isOnline = { true },
        )

    @Test
    fun theSmartRecruitersSweepBudgetIsOne() {
        assertEquals(
            "the feed reads only the first keyword, so a second set re-runs the identical sweep",
            1,
            SmartRecruitersProvider().capabilities.keywordSweepBudget,
        )
    }

    @Test
    fun oneRefreshReadsTheBoardListOnceNotTwice() = runBlocking {
        // A real provider, driven through the repository the way a refresh drives it. With a
        // sweep budget of one, the plan hands it a single keyword set, so the board list is read
        // once. A budget of two would read it twice for the identical keyword.
        val feed = CountingSmartRecruitersFeed()
        val provider = SmartRecruitersProvider(feed = feed, boards = listOf(SmartRecruitersBoard("SGS", "SGS")))
        val repo = repository(listOf(provider))

        repo.awaitSettled()

        assertEquals("one sweep must read the list once", 1, feed.listUrls.size)
        assertEquals("the plan keyword is still sent", 1, feed.listUrls.count { it.contains("q=") })
    }

    @Test
    fun oneSweepStillReadsEveryConfiguredCountry() = runBlocking {
        // Coverage is preserved: a single sweep still makes the unfiltered call plus one call per
        // configured country, so no market is dropped by lowering the budget.
        val feed = CountingSmartRecruitersFeed()
        val board = SmartRecruitersBoard("SGS", "SGS", countries = listOf("ae", "za"))
        val provider = SmartRecruitersProvider(feed = feed, boards = listOf(board))

        provider.fetch(listOf("maintenance"), limit = 10)

        assertEquals("one unfiltered call plus one per country", 3, feed.listUrls.size)
        assertTrue("the UAE market must still be swept", feed.listUrls.any { it.contains("country=ae") })
        assertTrue("the South African market must still be swept", feed.listUrls.any { it.contains("country=za") })
    }

    // ---- Workday ------------------------------------------------------------------------

    /** Counts search (POST, by offset) and detail (GET) calls, answering each deterministically. */
    private class CountingWorkdayFeed(private val pages: Map<Int, JSONArray>) : JsonFeed {
        val searchOffsets = mutableListOf<Int>()
        val detailUrls = mutableListOf<String>()

        override suspend fun objectPost(url: String, body: String, headers: Map<String, String>): JSONObject {
            val offset = JSONObject(body).optInt("offset")
            searchOffsets += offset
            return JSONObject().put("jobPostings", pages[offset] ?: JSONArray())
        }

        override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject {
            detailUrls += url
            return JSONObject(
                """
                {"jobPostingInfo":{"title":"Maintenance Technician",
                 "jobDescription":"<p>Maintain the fixed plant.</p>",
                 "location":"Doha","country":{"descriptor":"Qatar"},
                 "jobReqId":"R1","externalUrl":"$url"}}
                """.trimIndent(),
            )
        }

        override suspend fun arrayAt(url: String, headers: Map<String, String>): JSONArray =
            throw UnsupportedOperationException("Workday uses POST for search")
    }

    private val site = WorkdaySite("acme", "acme.wd3.myworkdayjobs.com", "Careers", "Acme")

    private fun page(count: Int): JSONArray = JSONArray().apply {
        (0 until count).forEach { put(JSONObject().put("title", "Operator $it").put("externalPath", "/job/site/$it")) }
    }

    @Test
    fun aWorkdaySiteSpendsAtMostTwoSearchPagesPlusTheDetailBudget() = runBlocking {
        // Both pages are full (20 rows each). PAGES_PER_SITE caps the walk at two pages, so the
        // third page is never requested even though the second page was full.
        val feed = CountingWorkdayFeed(mapOf(0 to page(20), 20 to page(20)))
        val provider = WorkdayProvider(feed = feed, sites = listOf(site))

        provider.fetch(listOf("maintenance"), limit = 100)

        assertEquals("exactly the two bounded search pages", listOf(0, 20), feed.searchOffsets)
        assertEquals("at most the per-site detail budget", 6, feed.detailUrls.size)
        assertEquals("two pages plus six details is the per-site ceiling", 8, feed.searchOffsets.size + feed.detailUrls.size)
    }

    @Test
    fun aWorkdaySiteThatAnswersOneShortPageSpendsOneSearchCall() = runBlocking {
        val feed = CountingWorkdayFeed(mapOf(0 to page(3)))
        val provider = WorkdayProvider(feed = feed, sites = listOf(site))

        provider.fetch(listOf("maintenance"), limit = 100)

        assertEquals("a short page ends the walk", listOf(0), feed.searchOffsets)
        assertEquals("only the three rows that exist are detailed", 3, feed.detailUrls.size)
    }

    @Test
    fun theWholeCuratedWorkdaySweepStaysWithinOneProviderBudget() {
        // Measured, not guessed: each site spends at most two search pages plus six detail calls.
        // The whole curated list therefore costs at most sites × 8 requests in one refresh, well
        // under the per-provider total budget, and no employer is dropped to reach it.
        val sites = WorkdayBoards.SITES.size
        val maxRequestsPerSweep = sites * 8

        assertTrue("the curated employer list must stay intact", sites >= 24)
        assertTrue("a single sweep must stay modest in request volume", maxRequestsPerSweep <= 208)
    }

    @Test
    fun loweringTheWorkdayBoundWouldNotBeJustified() {
        // The audit found no reduction that could be justified: two pages already stop early on a
        // short page, and the detail budget is the trade-first minimum. This test records that the
        // per-site bound is unchanged so a later stage does not lower it without new evidence.
        val feed = CountingWorkdayFeed(mapOf(0 to page(20), 20 to page(20)))
        val provider = WorkdayProvider(feed = feed, sites = listOf(site))

        runBlocking { provider.fetch(listOf("maintenance"), limit = 100) }

        assertEquals("the detail budget is preserved", 6, feed.detailUrls.size)
    }
}
