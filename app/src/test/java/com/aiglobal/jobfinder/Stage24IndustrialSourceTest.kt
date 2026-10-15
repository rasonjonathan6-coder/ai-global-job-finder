package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.geo.CountryCatalog
import com.aiglobal.jobfinder.data.jobs.engine.RecordValidationEngine
import com.aiglobal.jobfinder.data.jobs.provider.AshbyBoard
import com.aiglobal.jobfinder.data.jobs.provider.AshbyProvider
import com.aiglobal.jobfinder.data.jobs.provider.EuresProvider
import com.aiglobal.jobfinder.data.jobs.provider.GreenhouseBoard
import com.aiglobal.jobfinder.data.jobs.provider.GreenhouseProvider
import com.aiglobal.jobfinder.data.jobs.provider.JsonFeed
import com.aiglobal.jobfinder.data.jobs.provider.JobProviderException
import com.aiglobal.jobfinder.data.jobs.provider.LeverBoard
import com.aiglobal.jobfinder.data.jobs.provider.LeverProvider
import com.aiglobal.jobfinder.data.jobs.provider.ProviderKind
import com.aiglobal.jobfinder.data.jobs.provider.WorkdayBoards
import com.aiglobal.jobfinder.data.jobs.provider.WorkdayProvider
import com.aiglobal.jobfinder.data.jobs.provider.WorkdaySite
import com.aiglobal.jobfinder.data.jobs.search.RoleClassifier
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 24 — industrial, mining and expat sources.
 *
 * These tests exercise the new EURES (official EU) and employer applicant-tracking-system
 * (Workday, Greenhouse, Ashby) providers through the [JsonFeed] seam, so the mapping is
 * verified without touching the network. The properties that matter for a Madagascar-based
 * applicant are checked directly: the resolved country must be real (so eligibility can be
 * judged), the description must be present (so records survive validation), and the
 * employer's own application link must be used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage24IndustrialSourceTest {

    /** Replays canned responses, routing by URL so one provider's many calls can differ. */
    private class RoutingFeed(
        private val getResponses: Map<String, JSONObject> = emptyMap(),
        private val arrayResponses: Map<String, JSONArray> = emptyMap(),
        private val postResponses: Map<String, JSONObject> = emptyMap(),
    ) : JsonFeed {
        val getUrls = mutableListOf<String>()
        val postBodies = mutableListOf<String>()

        override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject =
            getResponses[url] ?: throw IllegalStateException("no GET response for $url")

        override suspend fun arrayAt(url: String, headers: Map<String, String>): JSONArray =
            arrayResponses[url] ?: throw IllegalStateException("no array response for $url")

        override suspend fun objectPost(url: String, body: String, headers: Map<String, String>): JSONObject {
            postBodies += body
            return postResponses[url] ?: throw IllegalStateException("no POST response for $url")
        }
    }

    private val validationEngine = RecordValidationEngine()
    private val now = 1_700_000_000_000L

    // ---- EURES (official EU source) ----------------------------------------

    @Test
    fun euresResolvesIsoCountryAndUsesTheOfficialVacancyLink() = runBlocking {
        val json = JSONObject(
            """
            {"numberRecords":1,"jvs":[{
              "id":"ABC123","title":"Maintenance Technician","numberOfPosts":2,
              "creationDate":1699999999000,
              "locationMap":{"DE":["DE111"]},
              "employer":{"name":"Nordwerke GmbH"},
              "positionScheduleCodes":["fulltime"],
              "jobCategoriesCodes":["http://data.europa.eu/esco/occupation/xyz"],
              "description":"<p>Maintain pumps and rotating equipment on a production line.</p>"
            }]}
            """.trimIndent(),
        )
        val provider = EuresProvider(RoutingFeed(postResponses = mapOf(EU_BASE to json)))
        val jobs = provider.fetch(limit = 10)

        assertEquals(1, jobs.size)
        val job = jobs.single()
        assertEquals("Maintenance Technician", job.title)
        assertEquals("Nordwerke GmbH", job.company)
        // The location must resolve to a real country, not a bare "DE" code.
        val country = CountryCatalog.resolve(job.location)
        assertEquals("Germany", country.name)
        assertTrue(country.isRecognized)
        assertEquals("https://europa.eu/eures/portal/jv-se/vacancy/ABC123", job.url)
        assertTrue(job.descriptionHtml.isNotBlank())
        assertEquals(ProviderKind.GOVERNMENT, provider.kind)

        // The record survives the real validation rules — no missing-description rejection.
        assertTrue(validationEngine.validate(job, now).accepted)
    }

    @Test
    fun euresDropsRecordsWithNoDescriptionRatherThanFakingThem() = runBlocking {
        val json = JSONObject(
            """
            {"numberRecords":1,"jvs":[{
              "id":"NODESC","title":"Fitter","locationMap":{"FR":["FR101"]},
              "employer":{"name":"Atelier"},"description":""
            }]}
            """.trimIndent(),
        )
        val provider = EuresProvider(RoutingFeed(postResponses = mapOf(EU_BASE to json)))
        assertTrue(provider.fetch(limit = 10).isEmpty())
    }

    @Test
    fun euresCountryCoverageResolvesToRealCountries() {
        // EURES covers the whole EEA plus Switzerland; each of these must resolve to a real
        // country so eligibility can be judged rather than falling back to an unresolved label.
        listOf(
            "Slovakia", "Slovenia", "Bulgaria", "Cyprus", "Malta",
            "Liechtenstein", "Latvia", "Lithuania", "Estonia", "Croatia",
        ).forEach { name ->
            assertTrue("expected \"$name\" to be a known country", CountryCatalog.isKnownCountry(name))
            assertTrue(CountryCatalog.resolve(name).isRecognized)
        }
    }

    // ---- Workday (mining & industrial majors) ------------------------------

    @Test
    fun workdayReadsCountryAndDescriptionFromTheDetailCall() = runBlocking {
        val site = WorkdaySite("riotinto", "riotinto.wd3.myworkdayjobs.com", "RioTinto_Careers", "Rio Tinto")
        val search = JSONObject(
            """
            {"total":1,"jobPostings":[{"title":"Maintenance Technician",
              "externalPath":"/job/Canga-East/Maintenance_R67736","locationsText":"Canga East"}]}
            """.trimIndent(),
        )
        val detail = JSONObject(
            """
            {"jobPostingInfo":{"title":"Maintenance Technician",
              "jobDescription":"<p>Maintain the fixed plant at an open-pit mine.</p>",
              "location":"Canga East","country":{"descriptor":"Guinea"},
              "jobReqId":"R67736","startDate":"2026-10-15","timeType":"Full time",
              "externalUrl":"https://wd3.myworkdaysite.com/recruiting/riotinto/RioTinto_Careers/job/Canga-East/Maintenance_R67736"}}
            """.trimIndent(),
        )
        val feed = RoutingFeed(
            getResponses = mapOf(site.detailUrl("/job/Canga-East/Maintenance_R67736") to detail),
            postResponses = mapOf(site.searchUrl to search),
        )
        val jobs = WorkdayProvider(feed = feed, sites = listOf(site)).fetch(limit = 10)

        assertEquals(1, jobs.size)
        val job = jobs.single()
        assertEquals("Rio Tinto", job.company)
        assertEquals("Canga East, Guinea", job.location)
        assertEquals("Guinea", CountryCatalog.resolve(job.location).name)
        assertEquals(ProviderKind.EMPLOYER_BOARD, WorkdayProvider(feed = feed).kind)
        assertTrue(job.descriptionHtml.contains("open-pit"))
        assertTrue(validationEngine.validate(job, now).accepted)
        // The keyword really was sent server-side.
        assertTrue(feed.postBodies.single().contains("maintenance", ignoreCase = true))
    }

    @Test
    fun workdayCurationIsAllWellFormedSites() {
        assertTrue(WorkdayBoards.SITES.isNotEmpty())
        WorkdayBoards.SITES.forEach { site ->
            assertTrue(site.host.contains(".myworkdayjobs.com"))
            assertTrue(site.searchUrl.startsWith("https://"))
            assertTrue(site.label.isNotBlank())
        }
    }

    // ---- Greenhouse (industrial employers) ---------------------------------

    @Test
    fun greenhouseUsesTheEmployerApplicationLinkAndInlineDetail() = runBlocking {
        val board = GreenhouseBoard("redwoodmaterials", "Redwood Materials")
        val list = JSONObject("""{"jobs":[{"id":5894704004,"title":"Process Technician"}]}""")
        val detail = JSONObject(
            """
            {"id":5894704004,"title":"Process Technician",
             "company_name":"Redwood Materials",
             "location":{"name":"McCarran, NV"},
             "absolute_url":"https://boards.greenhouse.io/redwoodmaterials/jobs/5894704004",
             "first_published":"2026-04-09T16:59:15-04:00",
             "content":"<p>Operate and maintain battery-recycling process equipment.</p>"}
            """.trimIndent(),
        )
        val feed = RoutingFeed(
            getResponses = mapOf(
                board.listUrl to list,
                board.detailUrl("5894704004") to detail,
            ),
        )
        val jobs = GreenhouseProvider(feed = feed, boards = listOf(board)).fetch(limit = 10)

        assertEquals(1, jobs.size)
        val job = jobs.single()
        assertEquals("Redwood Materials", job.company)
        // "McCarran, NV" is a city and a state, not a country: the catalog must not invent one.
        assertEquals(CountryCatalog.UNRESOLVED_FLAG, CountryCatalog.resolve(job.location).flag)
        assertEquals("https://boards.greenhouse.io/redwoodmaterials/jobs/5894704004", job.url)
        assertTrue(validationEngine.validate(job, now).accepted)
    }

    // ---- Ashby (advanced manufacturing) ------------------------------------

    @Test
    fun ashbyReadsInlineDescriptionAndSecondaryLocations() = runBlocking {
        val board = AshbyBoard("formenergy", "Form Energy")
        val list = JSONObject(
            """
            {"jobs":[
              {"id":"j1","title":"Process Technician","location":"Weirton, WV",
               "secondaryLocations":[{"location":"Berkeley, CA"}],
               "employmentType":"FullTime","isRemote":false,"workplaceType":"OnSite",
               "jobUrl":"https://jobs.ashbyhq.com/formenergy/j1",
               "descriptionPlain":"Operate and maintain electrode manufacturing equipment."},
              {"id":"j2","title":"Don't see the job you're looking for?","location":"Weirton, WV",
               "descriptionPlain":"Placeholder","isListed":false}
            ]}
            """.trimIndent(),
        )
        val feed = RoutingFeed(getResponses = mapOf(board.listUrl to list))
        val jobs = AshbyProvider(feed = feed, boards = listOf(board)).fetch(limit = 10)

        assertEquals(1, jobs.size)
        val job = jobs.single()
        assertEquals("Process Technician", job.title)
        assertEquals("Form Energy", job.company)
        assertEquals("Weirton, WV; Berkeley, CA", job.location)
        assertEquals("https://jobs.ashbyhq.com/formenergy/j1", job.url)
        assertTrue(validationEngine.validate(job, now).accepted)
    }

    // ---- Lever (large industrial employers) --------------------------------

    @Test
    fun leverKeepsOnlyTargetTitlesAndReadsInlineDetail() = runBlocking {
        val board = LeverBoard("shieldai", "Shield AI")
        val page = JSONArray(
            """
            [
              {"id":"a1","text":"Maintenance Technician (R1)",
               "categories":{"commitment":"Full-time","department":"Manufacturing",
                 "location":"Dallas, Texas","allLocations":["Dallas, Texas","Abu Dhabi"]},
               "createdAt":1791494285838,"workplaceType":"onsite",
               "hostedUrl":"https://jobs.lever.co/shieldai/a1",
               "descriptionPlain":"Maintain production and test equipment."},
              {"id":"a2","text":"Staff Software Engineer (R2)",
               "categories":{"location":"Seattle, Washington"},
               "descriptionPlain":"Build the autonomy stack."}
            ]
            """.trimIndent(),
        )
        val feed = RoutingFeed(arrayResponses = mapOf(board.pageUrl(0, 100) to page))
        val jobs = LeverProvider(feed = feed, boards = listOf(board)).fetch(limit = 10)

        assertEquals(1, jobs.size)
        val job = jobs.single()
        assertEquals("Maintenance Technician (R1)", job.title)
        assertEquals("Shield AI", job.company)
        assertEquals("Dallas, Texas; Abu Dhabi", job.location)
        assertEquals("https://jobs.lever.co/shieldai/a1", job.url)
        assertEquals("Full-time", job.employmentType)
        assertEquals(ProviderKind.EMPLOYER_BOARD, LeverProvider(feed = feed).kind)
        assertTrue(validationEngine.validate(job, now).accepted)
    }

    @Test
    fun leverPagesTheBoardAndStopsAtTheEnd() = runBlocking {
        val board = LeverBoard("acme", "Acme")
        val pageOne = JSONArray(
            (0 until 100).joinToString(",", "[", "]") { i ->
                """{"id":"p$i","text":"Operator $i","categories":{"location":"Perth"},"descriptionPlain":"Run the plant."}"""
            },
        )
        val feed = RoutingFeed(
            arrayResponses = mapOf(
                board.pageUrl(0, 100) to pageOne,
                board.pageUrl(100, 100) to JSONArray("[]"),
            ),
        )
        val jobs = LeverProvider(feed = feed, boards = listOf(board)).fetch(limit = 200)
        // The second, empty page stops the walk: no third request is made.
        assertEquals(100, jobs.size)
    }

    // ---- Cross-cutting guarantees ------------------------------------------

    @Test
    fun relevantIndustrialTitlesClassifyAsMaintenanceFamily() {
        // "Process Technician" is deliberately absent: the classifier treats a lone
        // "technician" with no trade word as unrelated, and this stage must not loosen
        // that rule — a broad remote feed full of generic "technician" roles would then
        // flood the shortlist. The other industrial titles must still be recognised.
        listOf(
            "Maintenance Technician",
            "Millwright",
            "Mechanical Fitter",
            "Rotating Equipment Technician",
            "Industrial Mechanic",
        ).forEach { title ->
            assertTrue(
                "expected \"$title\" to be a target/maintenance role",
                RoleClassifier.classify(title).isMaintenanceFamily,
            )
        }
    }

    @Test
    fun providersReportNoAnswerAsAUsableFailureNotAnEmptyResult() = runBlocking {
        val broken = object : JsonFeed {
            override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject =
                throw java.io.IOException("unreachable")
            override suspend fun arrayAt(url: String, headers: Map<String, String>): JSONArray =
                throw java.io.IOException("unreachable")
            override suspend fun objectPost(url: String, body: String, headers: Map<String, String>): JSONObject =
                throw java.io.IOException("unreachable")
        }
        assertFailsWithProviderException { EuresProvider(broken).fetch() }
        assertFailsWithProviderException { WorkdayProvider(feed = broken, sites = listOf(WorkdayBoards.SITES.first())).fetch() }
        assertFailsWithProviderException { GreenhouseProvider(feed = broken, boards = listOf(GreenhouseBoard("x", "X"))).fetch() }
        assertFailsWithProviderException { AshbyProvider(feed = broken, boards = listOf(AshbyBoard("x", "X"))).fetch() }
        assertFailsWithProviderException { LeverProvider(feed = broken, boards = listOf(LeverBoard("x", "X"))).fetch() }
    }

    private fun assertFailsWithProviderException(block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("Expected a JobProviderException")
        } catch (e: JobProviderException) {
            assertNotNull(e.message)
            assertFalse(e.message.isNullOrBlank())
        }
    }

    private companion object {
        const val EU_BASE = "https://europa.eu/eures/api/jv-searchengine/public/jv-search/search"
    }
}
