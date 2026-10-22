package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.provider.GreenhouseBoard
import com.aiglobal.jobfinder.data.jobs.provider.GreenhouseBoards
import com.aiglobal.jobfinder.data.jobs.provider.GreenhouseProvider
import com.aiglobal.jobfinder.data.jobs.provider.JsonFeed
import com.aiglobal.jobfinder.data.jobs.search.RoleClassifier
import com.aiglobal.jobfinder.data.jobs.search.RoleFamily
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
 * Stage 35 — targeted international discovery from the employer boards.
 *
 * Stage 34 established, with live evidence, that the curated employer boards are where the
 * candidate's trade roles actually live, but that Greenhouse contributed none of them. The
 * cause is structural rather than a data gap: Greenhouse's list endpoint has no keyword
 * search, so the provider spends a bounded per-posting detail budget, and it was spending
 * it on the first rows in list order. An industrial employer's maintenance titles sit deep
 * in its board, so the trades were never read and the board looked empty of them.
 *
 * These tests pin the fix — read the trade titles first, with the same [RoleClassifier] the
 * rest of the pipeline uses to define relevance — and pin that the fix changes only the
 * order, never the number of requests and never the data:
 *
 *  1. A board whose trades are buried is read at its trades, so real vacancies surface.
 *  2. A board with no trade title is unchanged: the plain newest-first rows come back.
 *  3. The provider still asks for the same, bounded number of details, so no run is made
 *     heavier and no posting is fabricated.
 *
 * Robolectric is required: the provider parses with `org.json`, which is a stub on a plain
 * JVM and would return null for every field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage35TargetedDiscoveryTest {

    /**
     * A feed that serves a list for the board and a generated detail for every id it is
     * asked for, recording which detail URLs were requested so the read order is observable.
     */
    private class RecordingFeed(
        private val list: JSONObject,
        private val board: GreenhouseBoard,
    ) : JsonFeed {
        val detailIds = mutableListOf<String>()

        override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject {
            if (url == board.listUrl) return list
            val id = url.substringAfterLast('/')
            detailIds += id
            return JSONObject(
                """
                {"id":$id,
                 "title":"Detail $id",
                 "company_name":"${board.label}",
                 "location":{"name":"McCarran, NV"},
                 "absolute_url":"https://boards.greenhouse.io/${board.token}/jobs/$id",
                 "content":"<p>Maintain plant machinery.</p>"}
                """.trimIndent(),
            )
        }

        override suspend fun arrayAt(url: String, headers: Map<String, String>): JSONArray =
            throw IllegalStateException("Greenhouse never uses array GETs")

        override suspend fun objectPost(url: String, body: String, headers: Map<String, String>): JSONObject =
            throw IllegalStateException("Greenhouse never uses POSTs")
    }

    private val board = GreenhouseBoard("redwoodmaterials", "Redwood Materials")

    /** A realistic board: unrelated roles first, the trades buried well past the read budget. */
    private fun buriedTradesBoard(): JSONObject {
        val rows = mutableListOf<Pair<String, String>>()
        // Ten unrelated, newest-first roles that a top-N read would see instead of the trades.
        (0 until 10).forEach { rows += it.toString() to "Business Development Analyst $it" }
        // The candidate's real roles, deeper in the board.
        rows += "54" to "Lead Maintenance Technician"
        rows += "59" to "Maintenance Technician"
        rows += "66" to "Automation Maintenance Technician"
        val jobs = JSONArray()
        rows.forEach { (id, title) ->
            jobs.put(JSONObject().put("id", id).put("title", title))
        }
        return JSONObject().put("jobs", jobs)
    }

    // ------------------------------------------------- relevance is the shared authority

    @Test
    fun theBuriedTitlesAreExactlyWhatTheCandidateWants() {
        // Guard the fixture: the buried titles must be relevance-positive, and the crowded
        // top rows must not be, or the test below would prove nothing.
        assertTrue(RoleClassifier.classify("Lead Maintenance Technician").isMaintenanceFamily)
        assertTrue(RoleClassifier.classify("Maintenance Technician").isMaintenanceFamily)
        assertTrue(RoleClassifier.classify("Automation Maintenance Technician").isMaintenanceFamily)
        assertFalse(RoleClassifier.classify("Business Development Analyst 3").isMaintenanceFamily)
        assertEquals(RoleFamily.UNRELATED, RoleClassifier.classify("Business Development Analyst 3").family)
    }

    // ------------------------------------------------- the buried trades are read

    @Test
    fun greenhouseReadsTheBuriedTradesRatherThanOnlyTheNewestRows() = runBlocking {
        val feed = RecordingFeed(buriedTradesBoard(), board)
        val jobs = GreenhouseProvider(feed = feed, boards = listOf(board)).fetch(limit = 10)

        // The trades really were read, even though they are past the top of the board.
        val readIds = feed.detailIds.toSet()
        assertTrue("expected the buried maintenance title to be read, read=$readIds", "59" in readIds)
        assertTrue("expected the traded electrician title to be read, read=$readIds", "66" in readIds)
        assertTrue("expected the lead maintenance title to be read, read=$readIds", "54" in readIds)
    }

    @Test
    fun theReadBudgetStaysBounded() = runBlocking {
        val feed = RecordingFeed(buriedTradesBoard(), board)
        GreenhouseProvider(feed = feed, boards = listOf(board)).fetch(limit = 10)

        // The budget is fixed and small: the fix reorders the read, it does not widen it, so a
        // mobile refresh is not made heavier. (8 = the trade-first detail budget.)
        assertEquals(8, feed.detailIds.size)
        assertEquals(8, feed.detailIds.toSet().size)
    }

    // ------------------------------------------------- behaviour is unchanged without trades

    @Test
    fun aBoardWithNoTradeTitlesFallsBackToTheNewestRows() = runBlocking {
        val rows = JSONArray()
        (0 until 12).forEach { rows.put(JSONObject().put("id", it.toString()).put("title", "Software Engineer $it")) }
        val feed = RecordingFeed(JSONObject().put("jobs", rows), board)

        GreenhouseProvider(feed = feed, boards = listOf(board)).fetch(limit = 10)

        // No trade title exists, so the plain newest-first rows are read — unchanged behaviour.
        assertEquals(listOf("0", "1", "2", "3", "4", "5", "6", "7"), feed.detailIds)
    }

    // ------------------------------------------------- the curated boards are well formed

    @Test
    fun greenhouseCurationIsAllWellFormedBoards() {
        assertTrue(GreenhouseBoards.BOARDS.isNotEmpty())
        GreenhouseBoards.BOARDS.forEach { board ->
            assertTrue(board.token.isNotBlank())
            assertTrue(board.label.isNotBlank())
            assertTrue(board.listUrl.startsWith("https://boards-api.greenhouse.io/"))
            assertTrue(board.detailUrl("1").startsWith(board.listUrl))
        }
    }
}
