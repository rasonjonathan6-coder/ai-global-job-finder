package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.provider.JobProviderException
import com.aiglobal.jobfinder.data.jobs.provider.JsonFeed
import com.aiglobal.jobfinder.data.jobs.provider.SmartRecruitersBoard
import com.aiglobal.jobfinder.data.jobs.provider.SmartRecruitersBoards
import com.aiglobal.jobfinder.data.jobs.provider.SmartRecruitersProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 43 behaviour: the keyless SmartRecruiters employer boards.
 *
 * The source is verified against its own public posting API shape: a search row carries the
 * title and location but not the description, so the provider makes a bounded per-posting
 * detail call. These tests feed canned JSON through the [JsonFeed] seam, so the mapping and
 * the trade-first detail budget are checked without touching the network.
 *
 * Robolectric supplies the real `org.json` implementation on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage43SmartRecruitersTest {

    /** Routes list vs detail calls by URL and records what was asked for. */
    private class RoutingFeed(
        private val onPostings: (String) -> JSONObject = { JSONObject("""{"totalFound":0,"content":[]}""") },
        private val onDetail: (String) -> JSONObject = { JSONObject() },
    ) : JsonFeed {
        val urls = mutableListOf<String>()

        override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject {
            urls += url
            // The list endpoint always carries a `q=` query; the detail endpoint never does.
            return if (url.contains("?")) onPostings(url) else onDetail(url)
        }

        override suspend fun objectPost(url: String, body: String, headers: Map<String, String>): JSONObject =
            throw UnsupportedOperationException("SmartRecruiters uses GET only")

        override suspend fun arrayAt(url: String, headers: Map<String, String>): JSONArray =
            throw UnsupportedOperationException("SmartRecruiters returns an object, not an array")

        /** The detail URLs, in the order they were requested. */
        fun detailUrls(): List<String> = urls.filter { !it.contains("?") }
    }

    private fun provider(feed: JsonFeed, boards: List<SmartRecruitersBoard> = listOf(SmartRecruitersBoard("SGS", "SGS"))) =
        SmartRecruitersProvider(feed = feed, boards = boards)

    private fun listRow(id: String, name: String) = JSONObject(
        """{"id":"$id","name":"$name","location":{"country":"za","fullLocation":"Durban, KwaZulu-Natal, South Africa"}}""",
    )

    private fun detail(
        id: String,
        name: String = "Maintenance Technician",
        active: Boolean = true,
    ) = JSONObject(
        """
        {"id":"$id","name":"$name","active":$active,
         "postingUrl":"https://jobs.smartrecruiters.com/SGS/$id",
         "releasedDate":"2026-10-10T10:00:00.000Z",
         "company":{"name":"SGS"},
         "location":{"country":"za","fullLocation":"Durban, KwaZulu-Natal, South Africa","remote":false},
         "typeOfEmployment":{"label":"Full-time"},
         "jobAd":{"sections":{
            "jobDescription":{"text":"<p>Maintain pumps and gearboxes.</p>"},
            "qualifications":{"text":"<p>5 years experience.</p>"}}}}
        """.trimIndent(),
    )

    private fun emptyPostings() = JSONObject("""{"totalFound":0,"content":[]}""")

    // ---- mapping ------------------------------------------------------------

    @Test
    fun aDetailRowIsMappedIntoAnHonestRawJob() = runBlocking {
        val feed = RoutingFeed(
            onPostings = { JSONObject("""{"totalFound":1,"content":[{"id":"1","name":"Maintenance Technician"}]}""") },
            onDetail = { detail("1") },
        )

        val jobs = provider(feed).fetch(keywords = listOf("maintenance"), limit = 10)

        assertEquals(1, jobs.size)
        val job = jobs.single()
        assertEquals("smartrecruiters", job.providerId)
        assertEquals("SGS-1", job.externalId)
        assertEquals("Maintenance Technician", job.title)
        assertEquals("SGS", job.company)
        assertEquals("Durban, KwaZulu-Natal, South Africa", job.location)
        assertEquals("https://jobs.smartrecruiters.com/SGS/1", job.url)
        assertEquals("Full-time", job.employmentType)
        // Both description sections are joined so the matching engine sees the full text.
        assertTrue(job.descriptionHtml.contains("Maintain pumps"))
        assertTrue(job.descriptionHtml.contains("5 years experience"))
        assertFalse(job.isRemote)
        assertEquals("2026-10-10T10:00:00.000Z".let { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }, job.postedAtEpochMillis)
    }

    @Test
    fun theEmployersOwnCompanyNameIsPreferredOverTheBoardLabel() = runBlocking {
        val feed = RoutingFeed(
            onPostings = { JSONObject("""{"content":[{"id":"1","name":"Mechanical Fitter"}]}""") },
            onDetail = { detail("1", name = "Mechanical Fitter") },
        )
        val job = provider(feed).fetch(listOf("fitter"), 10).single()
        assertEquals("SGS", job.company)
    }

    // ---- the keyword really narrows the feed --------------------------------

    @Test
    fun theSearchSendsTheCandidatesKeywordAndTheSourceCanNarrowByIt() = runBlocking {
        val feed = RoutingFeed(onPostings = { emptyPostings() })
        val provider = provider(feed)

        assertTrue(
            "the source must advertise server-side keyword search",
            provider.capabilities.serverSideKeyword,
        )
        provider.fetch(keywords = listOf("Mechanical Fitter"), limit = 10)

        val listUrl = feed.urls.first { it.contains("?") }
        assertTrue("the keyword must be sent as a query parameter", listUrl.contains("q="))
        assertTrue("the keyword must be URL-encoded", listUrl.contains("q=Mechanical+Fitter"))
        assertTrue("the board identifier must address the employer", listUrl.contains("/companies/SGS/postings"))
    }

    @Test
    fun anEmptyKeywordListFallsBackToTheTradesDefaultRatherThanFetchingEverything() = runBlocking {
        val feed = RoutingFeed(onPostings = { emptyPostings() })
        provider(feed).fetch(keywords = emptyList(), limit = 10)
        assertTrue(feed.urls.first { it.contains("?") }.contains("q=maintenance"))
    }

    // ---- the bounded detail budget reads trades first -----------------------

    @Test
    fun tradeTitlesAreReadBeforeTheRestOfTheBoard() = runBlocking {
        val rows = listOf(
            "Business Analyst", "Marketing Intern", "HR Officer",
            "Maintenance Technician", "Mechanical Fitter", "Millwright",
        )
        val feed = RoutingFeed(
            onPostings = {
                JSONObject(
                    """{"content":[${rows.withIndex().joinToString(",") { (i, t) -> """{"id":"${i + 1}","name":"$t"}""" }}]}""",
                )
            },
            onDetail = { url -> detail(url.substringAfterLast('/')) },
        )

        provider(feed).fetch(listOf("maintenance"), 10)

        val requestedIds = feed.detailUrls().map { it.substringAfterLast('/') }
        // Budget is 4: the three trade titles first, then the newest remaining row.
        assertEquals(4, requestedIds.size)
        assertEquals(listOf("4", "5", "6", "1"), requestedIds)
    }

    @Test
    fun whenNoTitleIsATradeTheSourcesOwnOrderIsPreserved() = runBlocking {
        val feed = RoutingFeed(
            onPostings = {
                JSONObject("""{"content":[{"id":"1","name":"Analyst"},{"id":"2","name":"Recruiter"},{"id":"3","name":"Designer"}]}""")
            },
            onDetail = { url -> detail(url.substringAfterLast('/'), name = "Analyst") },
        )
        provider(feed).fetch(listOf("maintenance"), 10)
        assertEquals(listOf("1", "2", "3"), feed.detailUrls().map { it.substringAfterLast('/') })
    }

    // ---- honesty about failure and emptiness --------------------------------

    @Test
    fun aClosedPostingIsNotShownAsIfItWereOpen() = runBlocking {
        val feed = RoutingFeed(
            onPostings = { JSONObject("""{"content":[{"id":"1","name":"Maintenance Technician"}]}""") },
            onDetail = { detail("1", active = false) },
        )
        assertTrue(provider(feed).fetch(listOf("maintenance"), 10).isEmpty())
    }

    @Test
    fun anEmployerWithNoPublicFeedIsTreatedAsEmptyNotAsABrokenSource() = runBlocking {
        // SGS answers with nothing, Wabtec has a real posting: the source must still succeed.
        val boards = listOf(SmartRecruitersBoard("SGS", "SGS"), SmartRecruitersBoard("Wabtec", "Wabtec"))
        val feed = RoutingFeed(
            onPostings = { url ->
                if (url.contains("/SGS/")) emptyPostings()
                else JSONObject("""{"content":[{"id":"9","name":"Mechanical Fitter"}]}""")
            },
            onDetail = { detail("9", name = "Mechanical Fitter") },
        )

        val jobs = provider(feed, boards).fetch(listOf("fitter"), 10)

        assertEquals(1, jobs.size)
        assertEquals("Wabtec-9", jobs.single().externalId)
    }

    @Test
    fun everyBoardFailingRaisesAUsableProviderErrorRatherThanAnEmptyResult() = runBlocking {
        val boards = listOf(SmartRecruitersBoard("SGS", "SGS"), SmartRecruitersBoard("Wabtec", "Wabtec"))
        val feed = object : JsonFeed {
            override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject =
                throw java.io.IOException("HTTP 503 for $url")
            override suspend fun objectPost(url: String, body: String, headers: Map<String, String>): JSONObject =
                throw UnsupportedOperationException()
            override suspend fun arrayAt(url: String, headers: Map<String, String>): JSONArray =
                throw UnsupportedOperationException()
        }

        try {
            provider(feed, boards).fetch(listOf("maintenance"), 10)
            fail("expected a JobProviderException when every board is unreachable")
        } catch (e: JobProviderException) {
            assertEquals("smartrecruiters", e.providerId)
        }
    }

    // ---- curation -----------------------------------------------------------

    @Test
    fun theCurationIsKeylessAndTradeLedWithDistinctBoards() {
        val boards = SmartRecruitersBoards.BOARDS
        assertTrue("at least the verified employers must be curated", boards.size >= 4)
        assertEquals(
            "board identifiers must be unique",
            boards.map { it.identifier.lowercase() }.distinct().size,
            boards.size,
        )
        assertTrue(
            "the curation must include a testing/inspection employer with African and Gulf sites",
            boards.any { it.identifier == "SGS" },
        )
        assertFalse(
            "the source needs no credentials",
            provider(RoutingFeed()).requiresCredentials,
        )
    }
}
