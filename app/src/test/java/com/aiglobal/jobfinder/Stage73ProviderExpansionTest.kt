package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.provider.GreenhouseBoards
import com.aiglobal.jobfinder.data.jobs.provider.GreenhouseProvider
import com.aiglobal.jobfinder.data.jobs.provider.JsonFeed
import com.aiglobal.jobfinder.data.jobs.provider.LeverBoard
import com.aiglobal.jobfinder.data.jobs.provider.LeverBoards
import com.aiglobal.jobfinder.data.jobs.provider.LeverProvider
import com.aiglobal.jobfinder.data.jobs.provider.SmartRecruitersBoard
import com.aiglobal.jobfinder.data.jobs.provider.SmartRecruitersBoards
import com.aiglobal.jobfinder.data.jobs.provider.SmartRecruitersProvider
import com.aiglobal.jobfinder.data.jobs.provider.WorkdayBoards
import com.aiglobal.jobfinder.data.jobs.provider.WorkdayProvider
import com.aiglobal.jobfinder.data.jobs.provider.WorkdaySite
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
 * Stage 73 behaviour: the curated employer expansion and the retrieval improvements it needed.
 *
 * The candidates confirmed as employers — Greenhouse, Lever, Workday and SmartRecruiters boards —
 * were added behind the same keyless seams the rest of the provider tests use, so these checks run
 * without the network. What is pinned here is not just that the names are in the lists but that the
 * retrieval around them stays honest and bounded:
 *
 *  - Workday pages its search with `offset` and stops on a short or empty page, reads the trade
 *    titles before the rest of the budget, and visits every site even though it now runs them with
 *    bounded concurrency.
 *  - SmartRecruiters narrows by `country=` only for the markets each employer actually publishes
 *    in, merges and deduplicates the country rows, and never probes a country it was not given.
 *  - Lever stops paging a board once enough target titles have been seen, and still reads a sparse
 *    board to the page cap.
 *  - Every curated list is well formed and holds no duplicate identifier.
 *
 * Robolectric supplies the real `org.json` implementation on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage73ProviderExpansionTest {

    // ------------------------------------------------------------------ Workday

    private fun posting(id: String, title: String) = JSONObject()
        .put("title", title)
        .put("externalPath", "/job/site/$id")

    private fun postings(vararg rows: Pair<String, String>): JSONArray {
        val array = JSONArray()
        rows.forEach { (id, title) -> array.put(posting(id, title)) }
        return array
    }

    /** A Workday feed that answers each `offset` with the canned page and a detail per path. */
    private class WorkdayFeed(private val pages: Map<Int, JSONArray>) : JsonFeed {
        val offsets = mutableListOf<Int>()
        val detailUrls = mutableListOf<String>()

        override suspend fun objectPost(url: String, body: String, headers: Map<String, String>): JSONObject {
            val offset = JSONObject(body).optInt("offset")
            offsets += offset
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

    @Test
    fun workdayPagesTheSearchWithOffsetAndStopsAtAShortPage() = runBlocking {
        val full = postings(*(0 until 20).map { "p$it" to "Operator $it" }.toTypedArray())
        val feed = WorkdayFeed(mapOf(0 to full, 20 to postings("t1" to "Maintenance Technician")))

        WorkdayProvider(feed = feed, sites = listOf(site)).fetch(limit = 100)

        // Page 0 was full, so page 1 was read; page 1 was short, so the walk stopped there.
        assertEquals(listOf(0, 20), feed.offsets)
    }

    @Test
    fun workdayStopsAfterAnEmptyPageAndNeverAsksAgain() = runBlocking {
        val feed = WorkdayFeed(mapOf(0 to postings("a" to "Maintenance Technician")))

        WorkdayProvider(feed = feed, sites = listOf(site)).fetch(limit = 100)

        // A single short page ends the walk: no second request.
        assertEquals(listOf(0), feed.offsets)
    }

    @Test
    fun workdayReadsTheTradeTitlesBeforeTheRestOfTheBudget() = runBlocking {
        val page = postings(
            "1" to "Business Analyst",
            "2" to "HR Officer",
            "3" to "Maintenance Technician",
            "4" to "Mechanical Fitter",
        )
        val feed = WorkdayFeed(mapOf(0 to page))

        WorkdayProvider(feed = feed, sites = listOf(site)).fetch(limit = 100)

        // Only four rows exist, so all four details are read — the trades first.
        assertEquals(
            listOf("/job/site/3", "/job/site/4", "/job/site/1", "/job/site/2"),
            feed.detailUrls.map { it.substringAfter("/wday/cxs/acme/Careers") },
        )
    }

    @Test
    fun workdayStillVisitsEverySiteUnderBoundedConcurrency() = runBlocking {
        val second = WorkdaySite("beta", "beta.wd3.myworkdayjobs.com", "Careers", "Beta")
        val feed = object : JsonFeed {
            val hosts = mutableListOf<String>()
            override suspend fun objectPost(url: String, body: String, headers: Map<String, String>): JSONObject {
                val host = url.substringAfter("https://").substringBefore("/")
                return JSONObject().put("jobPostings", postings("t" to "Maintenance Technician").also { hosts += host })
            }
            override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject = JSONObject(
                """{"jobPostingInfo":{"title":"Maintenance Technician","jobDescription":"<p>x</p>","location":"Doha","country":{"descriptor":"Qatar"},"externalUrl":"$url"}}""",
            )
            override suspend fun arrayAt(url: String, headers: Map<String, String>): JSONArray = JSONArray()
        }

        val jobs = WorkdayProvider(feed = feed, sites = listOf(site, second)).fetch(limit = 100)

        // Both employers were asked, and both postings came back — concurrency must not drop sites.
        assertTrue(feed.hosts.any { it.startsWith("acme.") })
        assertTrue(feed.hosts.any { it.startsWith("beta.") })
        assertEquals(setOf("Acme", "Beta"), jobs.map { it.company }.toSet())
    }

    // ------------------------------------------------------------------ SmartRecruiters

    private class SmartRecruitersFeed(
        private val rowsFor: (url: String) -> JSONArray,
    ) : JsonFeed {
        val urls = mutableListOf<String>()

        override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject {
            urls += url
            // List calls carry `q=`; detail calls never do.
            return if (url.contains("?")) JSONObject().put("content", rowsFor(url))
            else JSONObject(
                """
                {"id":"${url.substringAfterLast('/')}","name":"Maintenance Technician","active":true,
                 "postingUrl":"$url","company":{"name":"SGS"},
                 "location":{"fullLocation":"Durban, South Africa","country":"za"},
                 "typeOfEmployment":{"label":"Full-time"},
                 "jobAd":{"sections":{"jobDescription":{"text":"<p>Maintain pumps.</p>"}}}}
                """.trimIndent(),
            )
        }

        override suspend fun objectPost(url: String, body: String, headers: Map<String, String>): JSONObject =
            throw UnsupportedOperationException("SmartRecruiters uses GET only")

        override suspend fun arrayAt(url: String, headers: Map<String, String>): JSONArray =
            throw UnsupportedOperationException("SmartRecruiters returns an object, not an array")

        fun listUrls(): List<String> = urls.filter { it.contains("?") }
        fun detailIds(): List<String> = urls.filter { !it.contains("?") }.map { it.substringAfterLast('/') }
    }

    private fun srRow(id: String) = JSONObject("""{"id":"$id","name":"Maintenance Technician"}""")

    @Test
    fun smartRecruitersNarrowsByCountryOnlyForTheGivenMarkets() = runBlocking {
        val board = SmartRecruitersBoard("SGS", "SGS", countries = listOf("ae", "za"))
        val feed = SmartRecruitersFeed { url ->
            when {
                url.contains("country=ae") -> JSONArray().put(srRow("1")).put(srRow("2"))
                url.contains("country=za") -> JSONArray().put(srRow("3"))
                else -> JSONArray().put(srRow("1"))
            }
        }

        val jobs = SmartRecruitersProvider(feed = feed, boards = listOf(board))
            .fetch(listOf("maintenance"), 100)

        // One unfiltered call plus one per country, and nothing else.
        assertEquals(3, feed.listUrls().size)
        assertTrue(feed.listUrls().any { it.contains("country=ae") })
        assertTrue(feed.listUrls().any { it.contains("country=za") })
        // Row 1 came back twice but is read once; the other two are distinct.
        assertEquals(setOf("1", "2", "3"), feed.detailIds().toSet())
        assertEquals(3, jobs.size)
    }

    @Test
    fun smartRecruitersNeverProbesACountryTheEmployerWasNotGiven() = runBlocking {
        val board = SmartRecruitersBoard("BoschGroup", "Bosch")
        val feed = SmartRecruitersFeed { JSONArray().put(srRow("1")) }

        SmartRecruitersProvider(feed = feed, boards = listOf(board)).fetch(listOf("maintenance"), 100)

        assertEquals(1, feed.listUrls().size)
        assertFalse(feed.listUrls().any { it.contains("country=") })
    }

    @Test
    fun smartRecruitersAdvertisesTheCountryFilterItNowUses() {
        assertTrue(SmartRecruitersProvider().capabilities.countryFilter)
    }

    @Test
    fun smartRecruitersCountryCurationIsOnlyIsoAlpha2Markets() {
        val known = setOf("ae", "sa", "qa", "om", "kw", "za", "mz", "na", "zm", "ke")
        SmartRecruitersBoards.BOARDS.forEach { board ->
            board.countries.forEach { country ->
                assertTrue(
                    "country '$country' must be a lowercase ISO-3166 alpha-2 code",
                    country == country.lowercase() && country.length == 2,
                )
                assertTrue("unexpected country '$country' for ${board.identifier}", country in known)
            }
        }
        // At least one curated board must actually sweep a country, or the filter does nothing.
        assertTrue(SmartRecruitersBoards.BOARDS.any { it.countries.isNotEmpty() })
    }

    // ------------------------------------------------------------------ Lever

    private class LeverFeed(private val pages: Map<Int, JSONArray>) : JsonFeed {
        val skips = mutableListOf<Int>()

        override suspend fun arrayAt(url: String, headers: Map<String, String>): JSONArray {
            val skip = url.substringAfter("skip=").substringBefore("&").toInt()
            skips += skip
            return pages[skip] ?: JSONArray()
        }

        override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject =
            throw UnsupportedOperationException("Lever uses array GETs")
        override suspend fun objectPost(url: String, body: String, headers: Map<String, String>): JSONObject =
            throw UnsupportedOperationException("Lever uses array GETs")
    }

    private fun leverRow(id: String, title: String) = JSONObject(
        """{"id":"$id","text":"$title","categories":{"location":"Perth"},"descriptionPlain":"Run the plant."}""",
    )

    @Test
    fun leverStopsPagingOnceEnoughTargetTitlesAreSeen() = runBlocking {
        val page = JSONArray()
        (0 until 12).forEach { page.put(leverRow("t$it", "Maintenance Technician $it")) }
        (0 until 88).forEach { page.put(leverRow("e$it", "Software Engineer $it")) }
        val feed = LeverFeed(mapOf(0 to page))

        LeverProvider(feed = feed, boards = listOf(LeverBoard("acme", "Acme"))).fetch(limit = 500)

        // Twelve trades were on the first page, so the board was not paged further.
        assertEquals(listOf(0), feed.skips)
    }

    @Test
    fun leverStopsASparseBoardAtTheSparsePageCap() = runBlocking {
        val full = JSONArray()
        (0 until 100).forEach { full.put(leverRow("e$it", "Software Engineer $it")) }
        val feed = LeverFeed(mapOf(0 to full, 100 to full, 200 to JSONArray().put(leverRow("last", "Designer"))))

        LeverProvider(feed = feed, boards = listOf(LeverBoard("acme", "Acme"))).fetch(limit = 500)

        // No target title on page 0, so the board is treated as off-domain and read only to the
        // sparse cap (two pages), not the full three-page cap.
        assertEquals(listOf(0, 100), feed.skips)
    }

    // ------------------------------------------------------------------ the curated lists

    @Test
    fun theConfirmedEmployersAreAllCurated() {
        val greenhouse = GreenhouseBoards.BOARDS.map { it.token }
        listOf(
            "oklo", "spacex", "triumvirateenvironmental", "geostabilizationinternational", "forgen",
        ).forEach { assertTrue("missing Greenhouse employer $it", it in greenhouse) }

        val lever = LeverBoards.BOARDS.map { it.token }
        listOf("hermeus", "zoox", "hive").forEach {
            assertTrue("missing Lever employer $it", it in lever)
        }

        val workday = WorkdayBoards.SITES.map { it.tenant }
        listOf(
            "bakerhughes", "abb", "championx", "airproducts", "marmon", "shell", "tcenergy",
            "williams", "ameresco", "dukeenergy", "micron", "onehealthineers", "jll",
        ).forEach { assertTrue("missing Workday employer $it", it in workday) }

        val smartRecruiters = SmartRecruitersBoards.BOARDS.map { it.identifier }
        listOf(
            "EgisGroup", "ValterraPlatinum1", "prosidianconsulting", "IOTAGROUP",
            "ContinentalGroupSectorContiTech", "PlantsAndGoodwinInc",
        ).forEach { assertTrue("missing SmartRecruiters employer $it", it in smartRecruiters) }
    }

    @Test
    fun everyCuratedListHasNoDuplicateIdentifiers() {
        assertEquals(
            GreenhouseBoards.BOARDS.map { it.token.lowercase() }.distinct().size,
            GreenhouseBoards.BOARDS.size,
        )
        assertEquals(
            LeverBoards.BOARDS.map { it.token.lowercase() }.distinct().size,
            LeverBoards.BOARDS.size,
        )
        assertEquals(
            WorkdayBoards.SITES.map { "${it.tenant}/${it.site}".lowercase() }.distinct().size,
            WorkdayBoards.SITES.size,
        )
        assertEquals(
            SmartRecruitersBoards.BOARDS.map { it.identifier.lowercase() }.distinct().size,
            SmartRecruitersBoards.BOARDS.size,
        )
    }

    @Test
    fun theDefaultProvidersAreStillConstructible() {
        // A guard that the widened lists still build with the production defaults.
        GreenhouseProvider()
        LeverProvider()
        WorkdayProvider()
        SmartRecruitersProvider()
    }
}
