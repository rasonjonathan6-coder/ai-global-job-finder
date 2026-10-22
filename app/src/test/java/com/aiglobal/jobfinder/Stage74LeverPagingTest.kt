package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.provider.JsonFeed
import com.aiglobal.jobfinder.data.jobs.provider.LeverBoard
import com.aiglobal.jobfinder.data.jobs.provider.LeverProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds

/**
 * Stage 74 — Lever is the only source that returns every description inline, so its page walk
 * is the one with a real payload cost. These tests pin the three guards added after the audit:
 * a sparse board is read fewer pages, a productive board keeps its depth, and a slow board is
 * short-circuited by the per-board clock without losing the rows it already returned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage74LeverPagingTest {

    /** A Lever board feed whose every page is full (100 rows), so only a guard can stop paging. */
    private class FullPagesFeed(
        private val rowTitle: (page: Int, index: Int) -> String,
        private val pageDelayMillis: Long = 0,
    ) : JsonFeed {
        val pagesRequested = mutableListOf<Int>()

        override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject = JSONObject()

        override suspend fun arrayAt(url: String, headers: Map<String, String>): JSONArray {
            val skip = Regex("skip=(\\d+)").find(url)?.groupValues?.get(1)?.toInt() ?: 0
            val page = skip / 100
            pagesRequested += page
            if (pageDelayMillis > 0) delay(pageDelayMillis)
            val array = JSONArray()
            for (i in 0 until 100) {
                val title = rowTitle(page, i)
                val row = JSONObject()
                    .put("id", "p$page-$i")
                    .put("text", title)
                    .put("descriptionPlain", "Repair and maintain plant equipment.")
                    .put("hostedUrl", "https://jobs.lever.co/board/p$page-$i")
                    .put("categories", JSONObject().put("location", "Winnipeg, Canada"))
                array.put(row)
            }
            return array
        }

        override suspend fun objectPost(
            url: String,
            body: String,
            headers: Map<String, String>,
        ): JSONObject = JSONObject()
    }

    private val board = listOf(LeverBoard("probe", "Probe"))

    @Test
    fun aSparseBoardIsReadTwoPages() = runBlocking {
        // No row carries a target title, so the board never matches and the sparse cap applies.
        val feed = FullPagesFeed(rowTitle = { _, i -> "Software Engineer $i" })
        val provider = LeverProvider(feed = feed, boards = board)

        provider.fetch(keywords = listOf("maintenance"), limit = 60)

        assertEquals("a board with no trade title on page 0 stops after two pages", listOf(0, 1), feed.pagesRequested)
    }

    @Test
    fun aProductiveBoardKeepsItsFullDepth() = runBlocking {
        // Every page carries target titles; with fewer than 12 in total the board walks to the
        // page cap, proving the sparse cap does not apply once a board has matched.
        val feed = FullPagesFeed(rowTitle = { _, i -> if (i < 3) "Maintenance Technician $i" else "Helper $i" })
        val provider = LeverProvider(feed = feed, boards = board)

        val jobs = provider.fetch(keywords = listOf("maintenance"), limit = 5_000)

        assertEquals("a productive board is read to the page cap", listOf(0, 1, 2), feed.pagesRequested)
        assertEquals(9, jobs.size)
    }

    @Test
    fun aSlowBoardIsShortCircuitedByThePerBoardClock() = runBlocking {
        // Page 0 is productive but takes a second; the injected 300 ms board clock expires
        // before page 1, so paging stops early without dropping what page 0 returned.
        val feed = FullPagesFeed(
            rowTitle = { _, i -> if (i < 2) "Mechanic $i" else "Helper $i" },
            pageDelayMillis = 1_000,
        )
        val provider = LeverProvider(feed = feed, boards = board, boardTimeout = 300.milliseconds)

        val jobs = provider.fetch(keywords = listOf("mechanic"), limit = 5_000)

        assertEquals("the slow board is not paged again", listOf(0), feed.pagesRequested)
        assertEquals("page 0's matches still land", 2, jobs.size)
    }
}
