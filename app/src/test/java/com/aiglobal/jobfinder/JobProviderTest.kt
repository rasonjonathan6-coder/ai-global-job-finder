package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.provider.AdzunaCredentials
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaProvider
import com.aiglobal.jobfinder.data.jobs.provider.HimalayasProvider
import com.aiglobal.jobfinder.data.jobs.provider.JobProviderException
import com.aiglobal.jobfinder.data.jobs.provider.JoobleProvider
import com.aiglobal.jobfinder.data.jobs.provider.JsonFeed
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The provider layer only translates a feed into [com.aiglobal.jobfinder.data.jobs.provider.RawJob].
 * These tests feed canned JSON through the [JsonFeed] seam so the mapping is verified
 * without touching the network, and assert that the credential-gated sources fail with a
 * message the user can act on rather than silently returning nothing.
 *
 * Robolectric supplies the real `org.json` implementation on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class JobProviderTest {

    /** Records the last request and replays a canned response. */
    private class FakeFeed(
        private val response: JSONObject,
        private val onObject: (String) -> JSONObject = { response },
    ) : JsonFeed {
        var lastUrl: String? = null
        var lastBody: String? = null

        override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject {
            lastUrl = url
            return onObject(url)
        }

        override suspend fun objectPost(url: String, body: String, headers: Map<String, String>): JSONObject {
            lastUrl = url
            lastBody = body
            return response
        }

        override suspend fun arrayAt(url: String, headers: Map<String, String>): JSONArray =
            throw UnsupportedOperationException("array feed not used")
    }

    // ---- Himalayas ---------------------------------------------------------

    @Test
    fun himalayasMapsSalaryAndTreatsEmptyRestrictionsAsWorldwide() = runBlocking {
        val json = JSONObject(
            """
            {"jobs":[
              {"guid":"h1","title":"Maintenance Technician","companyName":"Northline",
               "excerpt":"Fix the line","applicationLink":"https://himalayas.app/jobs/h1",
               "minSalary":60000,"maxSalary":75000,"currency":"CAD","salaryPeriod":"annual",
               "locationRestrictions":[],"categories":["Engineering"],"pubDate":1700000000}
            ]}
            """.trimIndent(),
        )
        val provider = HimalayasProvider(FakeFeed(json))
        val jobs = provider.fetch(limit = 10)

        assertEquals(1, jobs.size)
        val job = jobs.single()
        assertEquals("Maintenance Technician", job.title)
        assertEquals("Northline", job.company)
        assertEquals("Worldwide", job.location)
        assertEquals(60000, job.salaryMin)
        assertEquals(75000, job.salaryMax)
        assertEquals("CAD", job.salaryCurrency)
        assertEquals(SalaryPeriod.YEAR, job.salaryPeriod)
        assertTrue(job.isRemote)
    }

    @Test
    fun himalayasJoinsLocationRestrictionsIntoTheLocation() = runBlocking {
        val json = JSONObject(
            """
            {"jobs":[{"guid":"h2","title":"Fitter","companyName":"Acme",
              "locationRestrictions":["Canada","United States"],"applicationLink":"https://himalayas.app/jobs/h2"}]}
            """.trimIndent(),
        )
        val job = HimalayasProvider(FakeFeed(json)).fetch().single()
        assertEquals("Canada, United States", job.location)
    }

    // ---- Adzuna ------------------------------------------------------------

    @Test
    fun adzunaWithoutCredentialsExplainsWhatIsMissing() = runBlocking {
        val provider = AdzunaProvider(credentials = { AdzunaCredentials() }, feed = FakeFeed(JSONObject()))
        try {
            provider.fetch()
            fail("Expected a JobProviderException")
        } catch (e: JobProviderException) {
            assertTrue(e.message!!.contains("Settings"))
        }
    }

    @Test
    fun adzunaMapsStructuredSalaryWithTheMarketCurrency() = runBlocking {
        val json = JSONObject(
            """
            {"results":[
              {"id":"a1","title":"Maintenance Technician","description":"Great role",
               "redirect_url":"https://adzuna.com/a1","created":"2024-01-05T10:00:00Z",
               "company":{"display_name":"Northline"},
               "location":{"display_name":"Toronto, Ontario"},
               "category":{"label":"Engineering Jobs"},
               "salary_min":55000,"salary_max":70000,"contract_time":"full_time"}
            ]}
            """.trimIndent(),
        )
        val provider = AdzunaProvider(
            credentials = { AdzunaCredentials("id", "key") },
            feed = FakeFeed(json),
            countries = listOf("ca"),
        )
        val job = provider.fetch().single()

        assertEquals("Northline", job.company)
        assertEquals("Toronto, Ontario", job.location)
        assertEquals(55000, job.salaryMin)
        assertEquals("CAD", job.salaryCurrency)
        assertEquals("full_time", job.employmentType)
    }

    @Test
    fun adzunaFallsBackToSalaryInTheDescription() = runBlocking {
        val json = JSONObject(
            """
            {"results":[
              {"id":"a2","title":"Fitter","description":"Pay: 45000 EUR per year","redirect_url":"https://adzuna.com/a2",
               "company":{"display_name":"Acme"},"location":{"display_name":"Berlin"}}
            ]}
            """.trimIndent(),
        )
        val provider = AdzunaProvider(
            credentials = { AdzunaCredentials("id", "key") },
            feed = FakeFeed(json),
            countries = listOf("de"),
        )
        val job = provider.fetch().single()
        assertEquals(45000, job.salaryMin)
        assertEquals("EUR", job.salaryCurrency)
        assertEquals(SalaryPeriod.YEAR, job.salaryPeriod)
    }

    @Test
    fun adzunaQueriesEachCountryAndReturnsWhatItCan() = runBlocking {
        val feed = FakeFeed(JSONObject()) { url ->
            if (url.contains("/gb/")) {
                JSONObject(
                    """{"results":[{"id":"gb1","title":"Engineer","redirect_url":"https://adzuna.com/gb1",
                       "company":{"display_name":"A"},"location":{"display_name":"London"}}]}""",
                )
            } else {
                throw java.io.IOException("throttled")
            }
        }
        val provider = AdzunaProvider(
            credentials = { AdzunaCredentials("id", "key") },
            feed = feed,
            countries = listOf("gb", "de"),
        )
        val jobs = provider.fetch()
        assertEquals(1, jobs.size)
        assertNull(feed.lastBody)
    }

    // ---- Jooble ------------------------------------------------------------

    @Test
    fun joobleWithoutAKeyExplainsWhatIsMissing() = runBlocking {
        val provider = JoobleProvider(apiKey = { "" }, feed = FakeFeed(JSONObject()))
        try {
            provider.fetch()
            fail("Expected a JobProviderException")
        } catch (e: JobProviderException) {
            assertTrue(e.message!!.contains("Settings"))
        }
    }

    @Test
    fun jooblePostsTheKeywordsAndParsesTheSalaryText() = runBlocking {
        val json = JSONObject(
            """
            {"jobs":[
              {"id":"j1","title":"Mechanical Fitter","company":"Acme","location":"Munich, Germany",
               "snippet":"Fault finding on production lines","salary":"€ 55,000 per year",
               "link":"https://jooble.org/j1","type":"Full-time","updated":"2024-02-01T00:00:00"}
            ]}
            """.trimIndent(),
        )
        val feed = FakeFeed(json)
        val provider = JoobleProvider(apiKey = { "secret" }, feed = feed)
        val job = provider.fetch(keywords = listOf("maintenance technician")).single()

        assertEquals("Mechanical Fitter", job.title)
        assertEquals("Acme", job.company)
        assertEquals("Munich, Germany", job.location)
        assertEquals(55000, job.salaryMin)
        assertEquals("EUR", job.salaryCurrency)
        assertTrue(feed.lastUrl!!.endsWith("/secret"))
        assertTrue(feed.lastBody!!.contains("maintenance technician"))
    }
}
