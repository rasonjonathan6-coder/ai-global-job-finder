package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.expat.ExpatBenefitExtractor
import com.aiglobal.jobfinder.data.geo.CountryCatalog
import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.engine.RecordRejectionReason
import com.aiglobal.jobfinder.data.jobs.engine.RecordValidationEngine
import com.aiglobal.jobfinder.data.jobs.freshness.Freshness
import com.aiglobal.jobfinder.data.jobs.freshness.JobFreshnessEngine
import com.aiglobal.jobfinder.data.jobs.provider.JsonFeed
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.jobs.provider.WorkdayProvider
import com.aiglobal.jobfinder.data.jobs.provider.WorkdaySite
import com.aiglobal.jobfinder.data.jobs.search.RoleFamily
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
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
 * Stage 37 — verify that the offers the employer boards return are current, relevant to the
 * CV, and carry reliable information for applying from Madagascar.
 *
 * Three claims are proven here, each against the real engines the app ships, with no network:
 *
 *  1. **Current.** Workday's `startDate` is read into the posting and classified by the real
 *     [JobFreshnessEngine] as recent, not "date not stated". And a posting the source marks as
 *     closed (`canApply = false`) is dropped by [RecordValidationEngine] instead of shown.
 *  2. **Relevant.** The real pipeline puts a Weir/Sulzer/EPSA trade role in the maintenance
 *     family with the CV's own signal, and keeps a clearly different profession out.
 *  3. **Applicable.** Every posting carries a resolved country + flag, the employer's own
 *     application URL, and the employer's name, which is exactly what a candidate applying
 *     from Madagascar needs to act without guessing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage37OfferVerificationTest {

    /** Replays canned Workday responses, routed by URL. */
    private class RoutingFeed(
        private val getResponses: Map<String, JSONObject> = emptyMap(),
        private val postResponses: Map<String, JSONObject> = emptyMap(),
    ) : JsonFeed {
        override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject =
            getResponses[url] ?: throw IllegalStateException("no GET response for $url")

        override suspend fun arrayAt(url: String, headers: Map<String, String>): JSONArray =
            throw IllegalStateException("no array response expected")

        override suspend fun objectPost(url: String, body: String, headers: Map<String, String>): JSONObject =
            postResponses[url] ?: throw IllegalStateException("no POST response for $url")
    }

    private val profile = CandidateProfile(
        fullName = "Jonathan",
        currentTitle = "Maintenance Technician",
        previousTitles = listOf("Mechanical Technician"),
        yearsOfExperience = 8,
        skills = listOf(
            "preventive maintenance", "hydraulics", "pneumatics", "pumps", "bearings",
            "alignment", "welding", "troubleshooting", "mechanical",
        ),
        preferredRoles = listOf("Maintenance Technician", "Mechanical Fitter"),
    )

    private val mapper = JobMapper(
        matchingEngine = MatchingEngine(),
        eligibilityEngine = EligibilityEngine(),
        benefitExtractor = ExpatBenefitExtractor(),
    )

    private fun weirSite() = WorkdaySite(
        "weir", "weir.wd3.myworkdayjobs.com", "Weir_External_Careers", "Weir Group",
    )

    private fun searchRow(title: String, path: String) = JSONObject(
        """{"title":"$title","externalPath":"$path","locationsText":"Port Elizabeth"}""",
    )

    private fun searchObject(vararg rows: JSONObject) = JSONObject().apply {
        put("total", rows.size)
        put("jobPostings", JSONArray(rows.toList()))
    }

    private fun weirDetail(
        title: String,
        path: String,
        startDate: String,
        canApply: Boolean? = true,
        country: String = "South Africa",
        reqId: String = "R0039589",
    ) = JSONObject(
        """{"jobPostingInfo":{
            "title":"$title",
            "jobDescription":"<p>Maintain pumps and rotating equipment on a mining site.</p>",
            "location":"Port Elizabeth",
            "country":{"descriptor":"$country"},
            "jobReqId":"$reqId",
            "startDate":"$startDate",
            "timeType":"Full time",
            "canApply":${canApply ?: "true"},
            "externalUrl":"https://weir.wd3.myworkdayjobs.com/Weir_External_Careers$path"
        }}""".trimIndent(),
    )

    // ---- 1. Current: the publication date is read, not lost -----------------

    @Test
    fun workdayStartDateIsReadSoTheFeedKnowsThePostingIsRecent() = runBlocking {
        // The live probe found Weir posts "Maintenance Technician" in Port Elizabeth with a
        // 2026-10-12 startDate. Real Workday rows always carry startDate; if it were dropped
        // the freshness engine would honestly say "date not stated" and the card would look
        // stale. This pins that it is carried through and classified as recent.
        val site = weirSite()
        val path = "/job/Port-Elizabeth/Maintenance-Technician_R0039589"
        val feed = RoutingFeed(
            getResponses = mapOf(site.detailUrl(path) to weirDetail("Maintenance Technician", path, "2026-10-12")),
            postResponses = mapOf(site.searchUrl to searchObject(searchRow("Maintenance Technician", path))),
        )
        val raw = WorkdayProvider(feed = feed, sites = listOf(site)).fetch(keywords = listOf("maintenance"), limit = 5).single()

        val job = mapper.toJob(raw, profile)
        val now = java.time.Instant.parse("2026-10-16T00:00:00Z").toEpochMilli()
        val freshness = JobFreshnessEngine().assess(job, now = now)

        assertTrue("startDate must be carried into postedAtEpochMillis", job.postedAtEpochMillis != null)
        assertFalse(
            "a posting with a real employer date must never be reported as undated",
            freshness.freshness == Freshness.UNKNOWN,
        )
        assertTrue(
            "a four-day-old posting is recent, not stale",
            freshness.freshness == Freshness.FRESH || freshness.freshness == Freshness.RECENT,
        )
    }

    @Test
    fun aPostingWithNoPublicationDateIsNeverClaimedFresh() {
        // The freshness rule that must survive any provider change: absence of a stated date
        // is "date not stated", never "fresh". A synthetic RawJob stands in for any source
        // that omits the date.
        val raw = RawJob(
            providerId = "x", providerName = "X", externalId = "1",
            title = "Maintenance Technician", company = "Acme", location = "Zambia",
            descriptionHtml = "<p>Maintain plant.</p>", url = "https://example.com/1",
            postedAtEpochMillis = null,
        )
        val job = mapper.toJob(raw, profile)
        val freshness = JobFreshnessEngine().assess(job)

        assertEquals(Freshness.UNKNOWN, freshness.freshness)
        assertTrue(freshness.label.contains("not stated", ignoreCase = true))
    }

    @Test
    fun aPostingTheSourceSaysIsClosedIsRejectedNotShown() {
        // Workday's own `canApply = false` means the employer has stopped taking applicants.
        // Such a posting must be filtered out with a stated reason, so the feed only carries
        // offers the candidate can still act on.
        val closed = raw(id = "closed", closed = true)
        val open = raw(id = "open", closed = false)
        val engine = RecordValidationEngine()
        val now = 1_700_000_000_000L

        val closedValidation = engine.validate(closed, now)
        assertFalse(closedValidation.accepted)
        assertTrue(closedValidation.reasons.contains(RecordRejectionReason.CLOSED_TO_APPLICANTS))

        val openValidation = engine.validate(open, now)
        assertTrue("an open posting is untouched", openValidation.accepted)

        val result = engine.deduplicate(listOf(closed, open), now)
        assertEquals(listOf("open"), result.kept.map { it.externalId })
        assertEquals(1, result.summaryFor("test")?.rejectedByReason?.get(RecordRejectionReason.CLOSED_TO_APPLICANTS))
    }

    @Test
    fun workdayMarksPostingsClosedOnlyWhenTheSourceSaysSo() = runBlocking {
        val site = weirSite()
        val openPath = "/job/Port-Elizabeth/Maintenance-Technician_R1"
        val closedPath = "/job/Port-Elizabeth/Fitter_R2"
        val feed = RoutingFeed(
            getResponses = mapOf(
                site.detailUrl(openPath) to weirDetail("Maintenance Technician", openPath, "2026-10-12", canApply = true, reqId = "R1"),
                site.detailUrl(closedPath) to weirDetail("Mechanical Fitter", closedPath, "2026-09-01", canApply = false, reqId = "R2"),
            ),
            postResponses = mapOf(
                site.searchUrl to searchObject(
                    searchRow("Maintenance Technician", openPath),
                    searchRow("Mechanical Fitter", closedPath),
                ),
            ),
        )
        val raws = WorkdayProvider(feed = feed, sites = listOf(site)).fetch(keywords = listOf("maintenance"), limit = 10).associateBy { it.title }

        assertFalse("canApply=true is not closed", raws.getValue("Maintenance Technician").closedToNewApplicants)
        assertTrue("canApply=false is closed", raws.getValue("Mechanical Fitter").closedToNewApplicants)
    }

    @Test
    fun anAbsentCanApplyFlagMeansNotStatedNeverClosed() = runBlocking {
        // Some rows omit canApply entirely. Absence must not be read as "closed", or the app
        // would silently hide real openings whose source simply does not state the field.
        val site = weirSite()
        val path = "/job/Port-Elizabeth/Maintenance-Technician_R3"
        val detail = JSONObject(
            """{"jobPostingInfo":{
                "title":"Maintenance Technician",
                "jobDescription":"<p>Maintain pumps.</p>",
                "location":"Port Elizabeth","country":{"descriptor":"South Africa"},
                "jobReqId":"R3","startDate":"2026-10-12","timeType":"Full time",
                "externalUrl":"https://weir.wd3.myworkdayjobs.com/Weir_External_Careers$path"
            }}""".trimIndent(),
        )
        val feed = RoutingFeed(
            getResponses = mapOf(site.detailUrl(path) to detail),
            postResponses = mapOf(site.searchUrl to searchObject(searchRow("Maintenance Technician", path))),
        )
        val raw = WorkdayProvider(feed = feed, sites = listOf(site)).fetch(keywords = listOf("maintenance"), limit = 5).single()
        assertFalse(raw.closedToNewApplicants)
    }

    // ---- 2. Relevant: the CV's own trade signal is recognised --------------

    @Test
    fun aWeirTradeRoleIsRecognisedAsTheCandidatesTrade() {
        // The exact titles the live Weir board returned for a Madagascar maintenance candidate.
        val titles = listOf(
            "Maintenance Technician", "Industrial Maintenance Technician", "Mechanical Fitter",
            "Millwright", "Pump Mechanic",
        )
        titles.forEach { title ->
            val job = mapper.toJob(raw(title = title, id = title), profile)
            assertTrue(
                "expected \"$title\" to be the candidate's trade, got ${job.roleFamily}",
                job.roleFamily == RoleFamily.TARGET || job.roleFamily == RoleFamily.MAINTENANCE_FAMILY,
            )
            assertTrue(job.isMaintenanceRelevant)
        }
    }

    @Test
    fun aClearlyDifferentProfessionAtTheSameEmployerIsKeptOut() {
        // Employers on these boards also advertise roles outside the trade. They must not be
        // presented as relevant just because the employer is industrial: relevance is read
        // from the posting's own title and text, not from who posted it.
        val unrelated = listOf(
            "Head of Commercial Operations",
            "Financial Controller",
            "Human Resources Manager",
        )
        unrelated.forEach { title ->
            val job = mapper.toJob(raw(title = title, id = title), profile)
            assertFalse(
                "\"$title\" is not a maintenance trade role",
                job.roleFamily == RoleFamily.TARGET || job.roleFamily == RoleFamily.MAINTENANCE_FAMILY,
            )
            assertFalse(job.isMaintenanceRelevant)
        }
    }

    // ---- 3. Applicable: country, link and employer are all present ----------

    @Test
    fun everyBoardingPostsCarriesWhatTheCandidateNeedsToApply() = runBlocking {
        // The minimum for applying "from Madagascar" without guessing: the real country (for
        // eligibility), the employer (for verification) and the employer's own application
        // link. All three come off the Workday detail call.
        val site = weirSite()
        val path = "/job/Port-Elizabeth/Shift-Leader--Maintenance_R0039589"
        val feed = RoutingFeed(
            getResponses = mapOf(
                site.detailUrl(path) to weirDetail("Shift Leader: Maintenance", path, "2026-10-12", country = "South Africa"),
            ),
            postResponses = mapOf(site.searchUrl to searchObject(searchRow("Shift Leader: Maintenance", path))),
        )
        val job = mapper.toJob(WorkdayProvider(feed = feed, sites = listOf(site)).fetch(keywords = listOf("maintenance"), limit = 5).single(), profile)

        assertEquals("South Africa", job.country)
        assertEquals("🇿🇦", job.countryFlag)
        assertTrue(job.sourceUrl.startsWith("https://weir.wd3.myworkdayjobs.com/"))
        assertEquals("Weir Group", job.company)
        assertTrue("the country must be a known one so eligibility can be judged", CountryCatalog.isKnownCountry(job.country))
    }

    @Test
    fun aGulfPostingResolvesTheCountryEligibilityDependsOn() = runBlocking {
        // EPSA's Saudi maintenance roles are the ones a Madagascar candidate can most
        // realistically reach; the country must resolve from the detail call.
        val site = WorkdaySite("epsa", "epsa.wd103.myworkdayjobs.com", "ExternalCareerSite", "EPSA")
        val path = "/job/4404---Bulghah/Maintenance-Engineer--Turaif-_JR100437-1"
        val detail = JSONObject(
            """{"jobPostingInfo":{
                "title":"Maintenance Engineer (Madinah)",
                "jobDescription":"<p>We are seeking a talented maintenance engineer for our Saudi operations.</p>",
                "location":"4404 - Bulghah, Sukhaybarat & Humaymah",
                "country":{"descriptor":"Saudi Arabia"},
                "jobReqId":"JR100437","startDate":"2026-09-08","timeType":"Full time","canApply":true,
                "externalUrl":"https://epsa.wd103.myworkdayjobs.com/ExternalCareerSite$path"
            }}""".trimIndent(),
        )
        val search = searchObject(searchRow("Maintenance Engineer (Madinah)", path))
        val feed = RoutingFeed(
            getResponses = mapOf(site.detailUrl(path) to detail),
            postResponses = mapOf(site.searchUrl to search),
        )
        val job = mapper.toJob(WorkdayProvider(feed = feed, sites = listOf(site)).fetch(keywords = listOf("maintenance"), limit = 5).single(), profile)

        assertEquals("Saudi Arabia", job.country)
        assertEquals("🇸🇦", job.countryFlag)
        assertTrue(job.eligibility == MadagascarEligibility.ELIGIBLE || job.eligibility == MadagascarEligibility.VISA_SPONSORED ||
            job.eligibility == MadagascarEligibility.UNCLEAR)
    }

    @Test
    fun theAfricaAndGulfCountriesTheseBoardsPublishAreAllRecognised() {
        // Live-probe finding: the employer boards publish African and Gulf expat-mining sites
        // whose country the catalog did not know, so eligibility could not be judged. Each
        // must now resolve — using the exact location strings Workday returned.
        val samples = mapOf(
            "4601 -Yaoure Gold Mine 2, Côte d'Ivoire" to "Côte d'Ivoire",
            "4503 - Karo Platinum Mine, Zimbabwe" to "Zimbabwe",
            "Cobre Panama, Panama" to "Panama",
            "Port Elizabeth, South Africa" to "South Africa",
            "4404 - Bulghah, Sukhaybarat & Humaymah, Saudi Arabia" to "Saudi Arabia",
        )
        samples.forEach { (location, expected) ->
            val country = CountryCatalog.resolve(location)
            assertEquals(expected, country.name)
            assertTrue("$location must resolve to a real country", country.isRecognized)
        }
        // Workday spells the US out in full; it must map to the same canonical country.
        assertEquals(
            CountryCatalog.resolve("United States").name,
            CountryCatalog.resolve("United States of America").name,
        )
    }

    private fun raw(
        id: String,
        title: String = "Maintenance Technician",
        closed: Boolean = false,
    ) = RawJob(
        providerId = "test",
        providerName = "Workday",
        externalId = id,
        title = title,
        company = "Weir Group",
        location = "Port Elizabeth, South Africa",
        descriptionHtml = "<p>Maintain pumps, bearings and rotating equipment on site.</p>",
        url = "https://example.com/$id",
        postedAtEpochMillis = 1_700_000_000_000L,
        closedToNewApplicants = closed,
    )
}
