package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.provider.JobProvider
import com.aiglobal.jobfinder.data.jobs.provider.JobTechDevProvider
import com.aiglobal.jobfinder.data.jobs.provider.JsonFeed
import com.aiglobal.jobfinder.data.jobs.provider.LandingJobsProvider
import com.aiglobal.jobfinder.data.jobs.provider.ProviderKind
import com.aiglobal.jobfinder.data.jobs.provider.RemoteOkProvider
import com.aiglobal.jobfinder.data.jobs.provider.TheMuseProvider
import com.aiglobal.jobfinder.data.jobs.provider.WorkingNomadsProvider
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
 * Stage 97 — the five key-free public boards added to widen coverage.
 *
 * Each new source is exercised behind the same keyless [JsonFeed] seam the rest of the
 * provider tests use, so these checks run with no network. What is pinned is that every
 * field the app relies on downstream (title, company, location, url, salary, dates,
 * deadline) is read from the source's real response shape, and that the honesty rules hold:
 * RemoteOK's legal-notice row is not shown as a job, The Muse's ineffective filters are not
 * claimed, and a source with no company field does not invent one.
 *
 * Robolectric supplies the real `org.json` implementation on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage97ProviderExpansionTest {

    /** A feed that answers each requested URL with a canned body and records what was asked. */
    private class FakeFeed(private val responses: Map<String, String>) : JsonFeed {
        val requestedUrls = mutableListOf<String>()

        override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject {
            requestedUrls += url
            val body = responses[url] ?: throw java.io.IOException("no canned response for $url")
            return JSONObject(body)
        }

        override suspend fun arrayAt(url: String, headers: Map<String, String>): JSONArray {
            requestedUrls += url
            val body = responses[url] ?: throw java.io.IOException("no canned response for $url")
            return JSONArray(body)
        }

        override suspend fun objectPost(
            url: String,
            body: String,
            headers: Map<String, String>,
        ): JSONObject = throw UnsupportedOperationException("these boards are GET-only")
    }

    // ------------------------------------------------------------------ The Muse

    private fun museJob(id: String, title: String, company: String, location: String) = """
        {"id":$id,"name":"$title","type":"Full Time","publication_date":"2026-10-01T00:00:00Z",
         "contents":"<p>Role</p>","locations":[{"name":"$location"}],
         "categories":[{"name":"Engineering"}],"tags":["linux"],
         "company":{"name":"$company"},
         "refs":{"landing_page":"https://www.themuse.com/jobs/$id"}}
    """.trimIndent()

    @Test
    fun theMuseReadsEveryFieldTheAppNeedsAndPagesUntilTheLimit() = runBlocking {
        val feed = FakeFeed(
            mapOf(
                "https://www.themuse.com/api/public/jobs?page=1" to
                    """{"results":[${museJob("1", "Maintenance Technician", "Alfa Laval", "Lund, Sweden")},
                                   ${museJob("2", "Mechanical Fitter", "Sulzer", "Zurich, Switzerland")}]}""",
                "https://www.themuse.com/api/public/jobs?page=2" to
                    """{"results":[${museJob("3", "Pump Technician", "KSB", "Frankenthal, Germany")},
                                   ${museJob("4", "Millwright", "Weir", "Glasgow, UK")}]}""",
            ),
        )
        val jobs = TheMuseProvider(feed = feed).fetch(limit = 3)

        assertEquals(3, jobs.size)
        val first = jobs.first()
        assertEquals("Maintenance Technician", first.title)
        assertEquals("Alfa Laval", first.company)
        assertEquals("Lund, Sweden", first.location)
        assertEquals("https://www.themuse.com/jobs/1", first.url)
        assertTrue(first.tags.contains("Engineering"))
        assertEquals(
            listOf(
                "https://www.themuse.com/api/public/jobs?page=1",
                "https://www.themuse.com/api/public/jobs?page=2",
            ),
            feed.requestedUrls,
        )
    }

    @Test
    fun theMuseDoesNotClaimFiltersItsApiDoesNotHonour() {
        val capabilities = TheMuseProvider().capabilities
        assertFalse(capabilities.serverSideKeyword)
        assertFalse(capabilities.countryFilter)
        assertTrue(capabilities.pagination)
    }

    // ------------------------------------------------------------------ RemoteOK

    @Test
    fun remoteOkSkipsTheLegalNoticeRowAndReadsSalary() = runBlocking {
        val feed = FakeFeed(
            mapOf(
                "https://remoteok.com/api" to """
                    [{"last_updated":"2026-10-22T00:00:00Z","legal":"attribution notice"},
                     {"id":"1137551","slug":"remote-senior-backend-duckduckgo",
                      "company":"DuckDuckGo","position":"Maintenance Planner",
                      "tags":["maintenance","mechanical"],
                      "description":"<p>Plan the plant.</p>",
                      "url":"https://remoteok.com/x","apply_url":"https://remoteok.com/apply",
                      "salary_min":190000,"salary_max":190000,"salary_currency":"USD",
                      "date":"2026-10-21T14:29:21+00:00"}]
                """.trimIndent(),
            ),
        )
        val jobs = RemoteOkProvider(feed = feed).fetch(limit = 10)

        assertEquals("the legal-notice row must not be shown as a job", 1, jobs.size)
        val job = jobs.single()
        assertEquals("Maintenance Planner", job.title)
        assertEquals("DuckDuckGo", job.company)
        assertEquals("https://remoteok.com/apply", job.url)
        assertEquals(190000, job.salaryMin)
        assertEquals("USD", job.salaryCurrency)
        assertTrue(job.isRemote)
    }

    // ------------------------------------------------------------------ Working Nomads

    @Test
    fun workingNomadsReadsRegionsCompanyAndPublicationDate() = runBlocking {
        val feed = FakeFeed(
            mapOf(
                "https://www.workingnomads.com/api/exposed_jobs/" to """
                    [{"url":"https://www.workingnomads.com/job/go/1/",
                      "title":"Rotating Equipment Engineer",
                      "description":"<p>Pumps.</p>",
                      "company_name":"Atlas Energy",
                      "category_name":"Engineering",
                      "tags":["mechanical"],
                      "location":"Europe, LATAM, APAC",
                      "pub_date":"2026-10-21T14:06:17-04:00"}]
                """.trimIndent(),
            ),
        )
        val jobs = WorkingNomadsProvider(feed = feed).fetch(limit = 10)

        val job = jobs.single()
        assertEquals("Rotating Equipment Engineer", job.title)
        assertEquals("Atlas Energy", job.company)
        assertEquals("Europe, LATAM, APAC", job.location)
        assertTrue(job.tags.contains("Engineering"))
        assertTrue("pub_date must parse", job.postedAtEpochMillis != null)
    }

    // ------------------------------------------------------------------ Landing.jobs

    @Test
    fun landingJobsReadsSalaryCitiesExpiryAndDerivesCompanyFromUrl() = runBlocking {
        val feed = FakeFeed(
            mapOf(
                "https://landing.jobs/api/v1/jobs" to """
                    [{"id":19066,"title":"Mechanical Engineer",
                      "url":"https://landing.jobs/at/inscale/mechanical-engineer-lisbon",
                      "locations":[{"city":"Lisbon","country_code":"PT"}],
                      "remote":false,"type":"Full-time",
                      "gross_salary_low":50000,"gross_salary_high":67000,
                      "currency_code":"EUR","relocation_paid":true,
                      "published_at":"2026-02-26T09:38:38.127Z","expires_at":"2027-03-02",
                      "role_description":"<p>Maintain the line.</p>","tags":["mechanical"]}]
                """.trimIndent(),
            ),
        )
        val jobs = LandingJobsProvider(feed = feed).fetch(limit = 10)

        val job = jobs.single()
        assertEquals("Mechanical Engineer", job.title)
        assertEquals("Inscale", job.company)
        assertEquals("Lisbon, PT", job.location)
        assertEquals(50000, job.salaryMin)
        assertEquals(67000, job.salaryMax)
        assertEquals("EUR", job.salaryCurrency)
        assertTrue("expiry must parse", job.expiresAtEpochMillis != null)
    }

    // ------------------------------------------------------------------ JobTech Dev

    @Test
    fun jobTechDevSendsTheKeywordAndReadsTheGovernmentFeed() = runBlocking {
        val url = "https://jobsearch.api.jobtechdev.se/search?limit=20&q=" +
            java.net.URLEncoder.encode("maintenance technician", "UTF-8")
        val feed = FakeFeed(
            mapOf(
                url to """
                    {"hits":[{"id":"31590975","headline":"Maintenance Technician",
                      "webpage_url":"https://arbetsformedlingen.se/platsbanken/annonser/31590975",
                      "employer":{"name":"Prysmian Group Sverige AB"},
                      "workplace_address":{"municipality":"Nassjo","region":"Jonkoping","country":"Sverige"},
                      "description":{"text":"Maintain the plant."},
                      "occupation":{"label":"Underhallstekniker"},
                      "employment_type":{"label":"Tillsvidareanstallning"},
                      "publication_date":"2026-10-13T15:04:20",
                      "application_deadline":"2026-11-15T23:59:59"}]}
                """.trimIndent(),
            ),
        )
        val jobs = JobTechDevProvider(feed = feed).fetch(keywords = listOf("maintenance technician"), limit = 20)

        assertTrue("the keyword must be sent server-side", feed.requestedUrls.single().contains("q=maintenance"))
        val job = jobs.single()
        assertEquals("Maintenance Technician", job.title)
        assertEquals("Prysmian Group Sverige AB", job.company)
        assertTrue(job.location.contains("Nassjo"))
        assertTrue("deadline must parse", job.expiresAtEpochMillis != null)
        assertEquals(ProviderKind.GOVERNMENT, JobTechDevProvider().kind)
    }

    @Test
    fun jobTechDevClaimsItsServerSideKeywordSupport() {
        assertTrue(JobTechDevProvider().capabilities.serverSideKeyword)
    }

    // ------------------------------------------------------------------ registration

    @Test
    fun theFiveNewKeyFreeBoardsAreRegisteredInTheApp() {
        val providers: List<JobProvider> = com.aiglobal.jobfinder.AppContainer(
            org.robolectric.RuntimeEnvironment.getApplication(),
        ).buildJobProviders()
        val ids = providers.map { it.id }
        listOf("themuse", "remoteok", "workingnomads", "landingjobs", "jobtechdev").forEach { id ->
            assertTrue("$id must be registered", id in ids)
        }
        assertEquals("no duplicate source id", ids.distinct().size, ids.size)
        // The original twelve sources must still be registered alongside the new five.
        assertEquals(17, ids.size)
    }
}
