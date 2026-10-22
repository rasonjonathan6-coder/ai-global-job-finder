package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.provider.AshbyBoard
import com.aiglobal.jobfinder.data.jobs.provider.AshbyBoards
import com.aiglobal.jobfinder.data.jobs.provider.AshbyProvider
import com.aiglobal.jobfinder.data.jobs.provider.GreenhouseBoard
import com.aiglobal.jobfinder.data.jobs.provider.GreenhouseBoards
import com.aiglobal.jobfinder.data.jobs.provider.GreenhouseProvider
import com.aiglobal.jobfinder.data.jobs.provider.JsonFeed
import com.aiglobal.jobfinder.data.jobs.provider.LeverBoard
import com.aiglobal.jobfinder.data.jobs.provider.LeverBoards
import com.aiglobal.jobfinder.data.jobs.provider.LeverProvider
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
 * Stage 75 — the seven employer portals added in the P1 expansion.
 *
 * The previous provider audit confirmed seven additional employer boards whose live public
 * endpoints answered with the candidate's trades: one Greenhouse board (`atlassand`), one Lever
 * board (`evr`), one Ashby board (`marianaminerals`) and four Workday sites (KSB, FLSmidth,
 * Alfa Laval, ONEOK). Each is added as a curated entry in the list its provider already walks, so
 * no provider class, capability or pipeline path changes — only the curated employer set grows.
 *
 * These checks pin the three things that a list entry can silently get wrong, without touching the
 * network: the exact identifier (a mistyped token would 404 at runtime), the paging contract the
 * provider applies to it, and the raw-job normalisation (id namespacing, country resolution,
 * deduplication) the rest of the pipeline depends on. Robolectric supplies the real `org.json`
 * implementation on the JVM, matching the other provider tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage75ProviderPortalExpansionTest {

    // ------------------------------------------------------------------ identifiers

    @Test
    fun theSevenConfirmedPortalsAreCuratedWithExactIdentifiers() {
        assertTrue(
            "missing Greenhouse board atlassand",
            GreenhouseBoards.BOARDS.any { it.token == "atlassand" },
        )
        assertTrue(
            "missing Lever board evr",
            LeverBoards.BOARDS.any { it.token == "evr" },
        )
        assertTrue(
            "missing Ashby board marianaminerals",
            AshbyBoards.BOARDS.any { it.board == "marianaminerals" },
        )

        val workday = WorkdayBoards.SITES.associateBy { it.tenant }
        // The tenant, host and site string must all be exactly the audited values: Workday's
        // search path is tenant/site-qualified and a wrong site name answers with an error.
        val expected = mapOf(
            "ksb" to Triple("ksb.wd3.myworkdayjobs.com", "KSB_ExternalCareerSite", "KSB"),
            "flsmidth" to Triple("flsmidth.wd3.myworkdayjobs.com", "fls_global", "FLSmidth"),
            "alfalaval" to Triple("alfalaval.wd3.myworkdayjobs.com", "alfa_laval_jobs", "Alfa Laval"),
            "oneok" to Triple("oneok.wd1.myworkdayjobs.com", "ONEOK", "ONEOK"),
        )
        expected.forEach { (tenant, spec) ->
            val (host, site, label) = spec
            val curated = workday[tenant] ?: error("missing Workday site $tenant")
            assertEquals("host for $tenant", host, curated.host)
            assertEquals("site for $tenant", site, curated.site)
            assertEquals("label for $tenant", label, curated.label)
            assertEquals(
                "search URL for $tenant",
                "https://$host/wday/cxs/$tenant/$site/jobs",
                curated.searchUrl,
            )
        }
    }

    @Test
    fun theWidenedListsStillHoldNoDuplicateIdentifier() {
        assertEquals(
            GreenhouseBoards.BOARDS.map { it.token.lowercase() }.distinct().size,
            GreenhouseBoards.BOARDS.size,
        )
        assertEquals(
            LeverBoards.BOARDS.map { it.token.lowercase() }.distinct().size,
            LeverBoards.BOARDS.size,
        )
        assertEquals(
            AshbyBoards.BOARDS.map { it.board.lowercase() }.distinct().size,
            AshbyBoards.BOARDS.size,
        )
        assertEquals(
            WorkdayBoards.SITES.map { "${it.tenant}/${it.site}".lowercase() }.distinct().size,
            WorkdayBoards.SITES.size,
        )
    }

    @Test
    fun theExistingEmployersAreStillPresentAfterTheExpansion() {
        // The expansion adds portals; it must never replace the curated set already shipped.
        val greenhouse = GreenhouseBoards.BOARDS.map { it.token }
        listOf("redwoodmaterials", "lucidmotors", "koboldmetals", "oklo", "spacex").forEach {
            assertTrue("existing Greenhouse board $it was removed", it in greenhouse)
        }
        val lever = LeverBoards.BOARDS.map { it.token }
        listOf("shieldai", "sila", "hermeus", "zoox", "hive").forEach {
            assertTrue("existing Lever board $it was removed", it in lever)
        }
        val ashby = AshbyBoards.BOARDS.map { it.board }
        listOf("formenergy", "twelve").forEach {
            assertTrue("existing Ashby board $it was removed", it in ashby)
        }
        val workday = WorkdayBoards.SITES.map { it.tenant }
        listOf("riotinto", "alcoa", "weir", "sulzer", "firstquantum").forEach {
            assertTrue("existing Workday site $it was removed", it in workday)
        }
    }

    // ------------------------------------------------------------------ Greenhouse (atlassand)

    private class GreenhouseFeed(
        private val listRows: JSONArray,
        private val detail: (String) -> JSONObject?,
    ) : JsonFeed {
        val detailIds = mutableListOf<String>()

        override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject {
            if (url.endsWith("/jobs")) return JSONObject().put("jobs", listRows)
            val id = url.substringAfterLast('/')
            detailIds += id
            return detail(id) ?: JSONObject()
        }

        override suspend fun objectPost(url: String, body: String, headers: Map<String, String>): JSONObject =
            throw UnsupportedOperationException("Greenhouse uses GET only")

        override suspend fun arrayAt(url: String, headers: Map<String, String>): JSONArray =
            throw UnsupportedOperationException("Greenhouse returns objects, not arrays")
    }

    @Test
    fun greenhouseAtlasSandNormalisesAndNamespacesItsPostings() = runBlocking {
        val list = JSONArray().put(
            JSONObject("""{"id":9001,"title":"Industrial Maintenance Mechanic","location":{"name":"Kermit, TX"}}"""),
        )
        val feed = GreenhouseFeed(list) { id ->
            JSONObject(
                """
                {"id":$id,"title":"Industrial Maintenance Mechanic","content":"<p>Maintain conveyors.</p>",
                 "location":{"name":"Kermit, TX"},"absolute_url":"https://boards.greenhouse.io/atlassand/jobs/$id",
                 "first_published":"2026-09-03T14:06:35-04:00"}
                """.trimIndent(),
            )
        }

        val jobs = GreenhouseProvider(feed = feed, boards = listOf(GreenhouseBoard("atlassand", "Atlas Energy Solutions")))
            .fetch(listOf("maintenance"), 100)

        assertEquals(1, jobs.size)
        val job = jobs.single()
        assertEquals("greenhouse", job.providerId)
        // Namespaced by board token so two employers can never collide on a raw id.
        assertEquals("atlassand-9001", job.externalId)
        // The board label is the employer when the detail carries no company_name.
        assertEquals("Atlas Energy Solutions", job.company)
        assertEquals("Kermit, TX", job.location)
        assertEquals(listOf("9001"), feed.detailIds)
    }

    // ------------------------------------------------------------------ Lever (evr)

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

    private fun leverRow(id: String, title: String, listed: Boolean = true) = JSONObject(
        """
        {"id":"$id","text":"$title","categories":{"location":"Sparwood, BC"},
         "descriptionPlain":"Repair and maintain mine equipment.","isListed":$listed}
        """.trimIndent(),
    )

    @Test
    fun leverEvrPagesWithSkipAndFiltersLocallyByTitle() = runBlocking {
        val page = JSONArray()
        (0 until 100).forEach { page.put(leverRow("e$it", "Software Engineer $it")) }
        val tradePage = JSONArray()
        tradePage.put(leverRow("m1", "Heavy Duty Mechanic"))
        tradePage.put(leverRow("m2", "Millwright"))
        val feed = LeverFeed(mapOf(0 to page, 100 to tradePage))

        val jobs = LeverProvider(feed = feed, boards = listOf(LeverBoard("evr", "Elk Valley Resources")))
            .fetch(keywords = listOf("mechanic", "millwright"), limit = 500)

        // The first page carried no target title but was full, so the board was paged once more;
        // the second page matched and paging stopped. Only the matching titles survive the local
        // filter, proving the trade rows are not drowned by the off-domain ones.
        assertEquals(listOf(0, 100), feed.skips)
        assertEquals(setOf("Heavy Duty Mechanic", "Millwright"), jobs.map { it.title }.toSet())
        assertEquals(setOf("evr-m1", "evr-m2"), jobs.map { it.externalId }.toSet())
        assertTrue(jobs.all { it.company == "Elk Valley Resources" })
    }

    @Test
    fun leverEvrDropsUnlistedPostingsAndDeduplicatesAcrossBoards() = runBlocking {
        val page = JSONArray()
        page.put(leverRow("dup", "Mechanic"))
        page.put(leverRow("hidden", "Mechanic", listed = false))
        val feed = LeverFeed(mapOf(0 to page))

        val jobs = LeverProvider(
            feed = feed,
            boards = listOf(LeverBoard("evr", "Elk Valley Resources"), LeverBoard("evr", "Elk Valley Resources")),
        ).fetch(keywords = listOf("mechanic"), limit = 500)

        // The unlisted posting never surfaces and the same id read from the repeated board is
        // collapsed to one row.
        assertEquals(listOf("evr-dup"), jobs.map { it.externalId })
    }

    // ------------------------------------------------------------------ Ashby (marianaminerals)

    private class AshbyFeed(private val jobs: JSONArray) : JsonFeed {
        override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject =
            JSONObject().put("jobs", jobs)

        override suspend fun objectPost(url: String, body: String, headers: Map<String, String>): JSONObject =
            throw UnsupportedOperationException("Ashby uses GET only")

        override suspend fun arrayAt(url: String, headers: Map<String, String>): JSONArray =
            throw UnsupportedOperationException("Ashby returns objects, not arrays")
    }

    @Test
    fun ashbyMarianaMineralsNormalisesAndNamespacesItsPostings() = runBlocking {
        val jobs = JSONArray()
            .put(
                JSONObject(
                    """
                    {"id":"a1","title":"Project Electrician","location":"Moab, UT",
                     "descriptionPlain":"Wire the process plant.","jobUrl":"https://jobs.ashbyhq.com/marianaminerals/a1"}
                    """.trimIndent(),
                ),
            )
            // The "don't see the job you're looking for?" placeholder is not a real posting.
            .put(
                JSONObject(
                    """
                    {"id":"a2","title":"General Application","location":"Moab, UT",
                     "descriptionPlain":"Speculative.","jobUrl":"https://jobs.ashbyhq.com/marianaminerals/a2","isListed":false}
                    """.trimIndent(),
                ),
            )
        val feed = AshbyFeed(jobs)

        val result = AshbyProvider(feed = feed, boards = listOf(AshbyBoard("marianaminerals", "Mariana Minerals")))
            .fetch(listOf("electrician"), 100)

        assertEquals(1, result.size)
        val job = result.single()
        assertEquals("ashby", job.providerId)
        assertEquals("marianaminerals-a1", job.externalId)
        assertEquals("Mariana Minerals", job.company)
        assertEquals("Moab, UT", job.location)
    }

    // ------------------------------------------------------------------ Workday (ksb / flsmidth / alfalaval / oneok)

    private class WorkdayFeed(private val pages: Map<Int, JSONArray>) : JsonFeed {
        val offsets = mutableListOf<Int>()

        override suspend fun objectPost(url: String, body: String, headers: Map<String, String>): JSONObject {
            val offset = JSONObject(body).optInt("offset")
            offsets += offset
            return JSONObject().put("jobPostings", pages[offset] ?: JSONArray())
        }

        override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject {
            return JSONObject(
                """
                {"jobPostingInfo":{"title":"Maintenance Technician","jobDescription":"<p>Service pumps.</p>",
                 "location":"Richmond","country":{"descriptor":"United States of America"},
                 "jobReqId":"R1","externalUrl":"$url","canApply":true,"startDate":"2026-09-28"}}
                """.trimIndent(),
            )
        }

        override suspend fun arrayAt(url: String, headers: Map<String, String>): JSONArray =
            throw UnsupportedOperationException("Workday uses POST for search")
    }

    private fun workdayPage(vararg rows: Pair<String, String>): JSONArray {
        val array = JSONArray()
        rows.forEach { (id, title) ->
            array.put(JSONObject().put("title", title).put("externalPath", "/job/site/$id"))
        }
        return array
    }

    @Test
    fun workdayNewSitesPageTheirSearchWithOffset() = runBlocking {
        val full = workdayPage(*(0 until 20).map { "p$it" to "Operator $it" }.toTypedArray())
        val feed = WorkdayFeed(mapOf(0 to full, 20 to workdayPage("t1" to "Maintenance Technician")))
        val site = WorkdaySite("ksb", "ksb.wd3.myworkdayjobs.com", "KSB_ExternalCareerSite", "KSB")

        WorkdayProvider(feed = feed, sites = listOf(site)).fetch(listOf("maintenance"), limit = 100)

        // A full page forces a second offset; the short second page ends the walk.
        assertEquals(listOf(0, 20), feed.offsets)
    }

    @Test
    fun workdayNewSitesResolveCountryAndNamespaceTheExternalId() = runBlocking {
        val feed = WorkdayFeed(mapOf(0 to workdayPage("R1" to "Maintenance Technician")))
        val site = WorkdaySite("flsmidth", "flsmidth.wd3.myworkdayjobs.com", "fls_global", "FLSmidth")

        val jobs = WorkdayProvider(feed = feed, sites = listOf(site)).fetch(listOf("maintenance"), limit = 100)

        val job = jobs.single()
        assertEquals("workday", job.providerId)
        assertEquals("flsmidth-R1", job.externalId)
        assertEquals("FLSmidth", job.company)
        // The free-text city is joined with the resolved country, which the eligibility engine needs.
        assertEquals("Richmond, United States of America", job.location)
    }

    @Test
    fun workdayNewSitesAreVisitedEvenUnderBoundedConcurrency() = runBlocking {
        val sites = listOf(
            WorkdaySite("ksb", "ksb.wd3.myworkdayjobs.com", "KSB_ExternalCareerSite", "KSB"),
            WorkdaySite("flsmidth", "flsmidth.wd3.myworkdayjobs.com", "fls_global", "FLSmidth"),
            WorkdaySite("alfalaval", "alfalaval.wd3.myworkdayjobs.com", "alfa_laval_jobs", "Alfa Laval"),
            WorkdaySite("oneok", "oneok.wd1.myworkdayjobs.com", "ONEOK", "ONEOK"),
        )
        val feed = object : JsonFeed {
            val tenants = mutableListOf<String>()
            override suspend fun objectPost(url: String, body: String, headers: Map<String, String>): JSONObject {
                tenants += url.substringAfter("/wday/cxs/").substringBefore('/')
                return JSONObject().put("jobPostings", workdayPage("R1" to "Maintenance Technician"))
            }
            override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject = JSONObject(
                """{"jobPostingInfo":{"title":"Maintenance Technician","jobDescription":"<p>x</p>","location":"Doha","country":{"descriptor":"Qatar"},"jobReqId":"R1","externalUrl":"$url"}}""",
            )
            override suspend fun arrayAt(url: String, headers: Map<String, String>): JSONArray = JSONArray()
        }

        val jobs = WorkdayProvider(feed = feed, sites = sites).fetch(listOf("maintenance"), limit = 100)

        // All four tenants were queried and each contributed exactly one namespaced posting.
        assertEquals(setOf("ksb", "flsmidth", "alfalaval", "oneok"), feed.tenants.toSet())
        assertEquals(setOf("ksb-R1", "flsmidth-R1", "alfalaval-R1", "oneok-R1"), jobs.map { it.externalId }.toSet())
    }

    @Test
    fun theDefaultProvidersAreStillConstructibleAfterTheExpansion() {
        GreenhouseProvider()
        LeverProvider()
        AshbyProvider()
        WorkdayProvider()
    }
}
