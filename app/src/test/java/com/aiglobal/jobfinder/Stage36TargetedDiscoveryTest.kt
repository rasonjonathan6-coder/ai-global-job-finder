package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.geo.CountryCatalog
import com.aiglobal.jobfinder.data.jobs.provider.JsonFeed
import com.aiglobal.jobfinder.data.jobs.provider.WorkdayBoards
import com.aiglobal.jobfinder.data.jobs.provider.WorkdayProvider
import com.aiglobal.jobfinder.data.jobs.provider.WorkdaySite
import com.aiglobal.jobfinder.data.model.BenefitEvidence
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.ExpatBenefitsAssessment
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobBenefitTypes
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import com.aiglobal.jobfinder.viewmodel.JobSort
import com.aiglobal.jobfinder.viewmodel.JobsUiState
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
 * Stage 36 — targeted international discovery and verifiable expat ranking.
 *
 * Two things are proven here without touching the network. First, the Workday curation
 * now carries pump-service employers (Weir, Sulzer) and African/Gulf mining employers
 * (EPSA, First Quantum) alongside the existing mining majors, and those boards resolve the
 * real country from the detail call — the fact eligibility depends on. Second, the new
 * "Expat fit" sort orders the feed by recorded facts (documented Expat Benefits Score, then
 * a real Madagascar path, then documented sponsorship, then CV match), so the most
 * applicable roles surface first instead of merely the newest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage36TargetedDiscoveryTest {

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

    private companion object {
        const val DAY = 86_400_000L
        const val NOW = 1_760_000_000_000L
    }

    private fun job(
        id: String,
        matchScore: Int = 70,
        eligibility: MadagascarEligibility = MadagascarEligibility.UNCLEAR,
        visaDocumented: Boolean = false,
        postedAt: Long = NOW - 5 * DAY,
    ) = Job(
        id = id,
        title = "Maintenance Technician",
        company = "Acme",
        companyAccent = 0xFF1E4FD8,
        country = "Saudi Arabia",
        countryFlag = "🇸🇦",
        city = "Dammam",
        salaryMin = 60_000,
        salaryMax = 60_000,
        currencyCode = "SAR",
        salaryPeriod = SalaryPeriod.YEAR,
        matchScore = matchScore,
        eligibility = eligibility,
        visaSupport = VisaSupport.NOT_MENTIONED,
        scamRisk = ScamRisk.LOW,
        postedAtEpochMillis = postedAt,
        source = "Workday",
        sourceUrl = "https://example.com/$id",
        workMode = WorkMode.ONSITE,
        employmentType = EmploymentType.FULL_TIME,
        experienceYears = 3,
        matchedSkills = listOf("Preventive maintenance"),
        missingSkills = emptyList(),
        languages = listOf("English"),
        summary = "Summary for $id",
        responsibilities = emptyList(),
        benefits = emptyList(),
        aiInsight = "insight",
        providerId = "workday",
        benefitEvidence = if (visaDocumented) {
            listOf(
                BenefitEvidence(
                    type = JobBenefitTypes.VISA_SPONSORSHIP,
                    status = BenefitStatus.CONFIRMED,
                    evidence = "Visa sponsorship provided.",
                )
            )
        } else {
            emptyList()
        },
    )

    private fun assessment(score: Int) = ExpatBenefitsAssessment(
        score = score,
        evidenceCoverage = 80,
        components = emptyList(),
        explanation = "score $score",
        unmetEssentials = emptyList(),
        lowEvidence = false,
    )

    // ---- Workday curation (new free employer sources) ----------------------

    @Test
    fun workdayCurationAddsPumpAndAfricaMiningEmployers() {
        val tenants = WorkdayBoards.SITES.map { it.tenant }
        // The trades a maintenance/pump candidate applies for are advertised by these
        // employers specifically; without them the board list misses the pump and the
        // African-site mining work.
        listOf("weir", "sulzer", "epsa", "firstquantum").forEach {
            assertTrue("expected curated site $it", tenants.contains(it))
        }
    }

    @Test
    fun workdayCurationHasNoDuplicateSites() {
        val searchUrls = WorkdayBoards.SITES.map { it.searchUrl }
        assertEquals(searchUrls.size, searchUrls.distinct().size)
        val tenants = WorkdayBoards.SITES.map { it.tenant }
        assertEquals(tenants.size, tenants.distinct().size)
    }

    @Test
    fun workdayResolvesGulfCountryFromTheDetailCall() = runBlocking {
        // Sulzer advertises field service roles in Abu Dhabi; the search row shows only the
        // city, so the country must come from the detail call for eligibility to be judged.
        val site = WorkdaySite("sulzer", "sulzer.wd502.myworkdayjobs.com", "SulzerJobs", "Sulzer")
        val search = JSONObject(
            """{"total":1,"jobPostings":[{"title":"Gas Turbine Fitter",
              "externalPath":"/job/Abu-Dhabi/Gas-Turbine-Fitter_JR1","locationsText":"Abu Dhabi"}]}""".trimIndent(),
        )
        val detail = JSONObject(
            """{"jobPostingInfo":{"title":"Gas Turbine Fitter",
              "jobDescription":"<p>Overhaul pumps, turbines and rotating equipment.</p>",
              "location":"Abu Dhabi","country":{"descriptor":"United Arab Emirates"},
              "jobReqId":"JR1","startDate":"2026-10-15","timeType":"Full time",
              "externalUrl":"https://sulzer.wd502.myworkdayjobs.com/SulzerJobs/job/Abu-Dhabi/JR1"}}""".trimIndent(),
        )
        val feed = RoutingFeed(
            getResponses = mapOf(site.detailUrl("/job/Abu-Dhabi/Gas-Turbine-Fitter_JR1") to detail),
            postResponses = mapOf(site.searchUrl to search),
        )
        val jobs = WorkdayProvider(feed = feed, sites = listOf(site)).fetch(limit = 10)

        val job = jobs.single()
        assertEquals("Sulzer", job.company)
        assertEquals("United Arab Emirates", CountryCatalog.resolve(job.location).name)
        assertTrue(job.descriptionHtml.contains("rotating equipment"))
    }

    @Test
    fun workdayResolvesAfricanCountryFromTheDetailCall() = runBlocking {
        // First Quantum's Zambian copper operations advertise artisan trades; the country
        // must resolve so a candidate applying from Madagascar is scored honestly.
        val site = WorkdaySite("firstquantum", "firstquantum.wd3.myworkdayjobs.com", "First_Quantum_Careers", "First Quantum Minerals")
        val search = JSONObject(
            """{"total":1,"jobPostings":[{"title":"Artisan Fitter",
              "externalPath":"/job/Solwezi/Artisan-Fitter_JR2","locationsText":"Solwezi"}]}""".trimIndent(),
        )
        val detail = JSONObject(
            """{"jobPostingInfo":{"title":"Artisan Fitter",
              "jobDescription":"<p>Maintain fixed plant in an underground copper mine.</p>",
              "location":"Solwezi","country":{"descriptor":"Zambia"},
              "jobReqId":"JR2","startDate":"2026-10-15","timeType":"Full time",
              "externalUrl":"https://firstquantum.wd3.myworkdayjobs.com/First_Quantum_Careers/job/Solwezi/JR2"}}""".trimIndent(),
        )
        val feed = RoutingFeed(
            getResponses = mapOf(site.detailUrl("/job/Solwezi/Artisan-Fitter_JR2") to detail),
            postResponses = mapOf(site.searchUrl to search),
        )
        val jobs = WorkdayProvider(feed = feed, sites = listOf(site)).fetch(limit = 10)

        val job = jobs.single()
        assertEquals("Zambia", CountryCatalog.resolve(job.location).name)
        assertTrue(job.descriptionHtml.contains("copper mine"))
    }

    // ---- Verifiable expat ranking ------------------------------------------

    @Test
    fun expatFitPutsTheBestDocumentedBenefitsFirst() {
        val low = job(id = "low", matchScore = 95)
        val high = job(id = "high", matchScore = 40)
        val state = JobsUiState(nowMillis = NOW, 
            jobs = listOf(low, high),
            sort = JobSort.EXPAT_FIT,
            expatAssessments = mapOf("low" to assessment(20), "high" to assessment(85)),
        )

        // CV match does not decide this order: the posting with the documented benefits does.
        assertEquals(listOf("high", "low"), state.visibleJobs.map { it.id })
    }

    @Test
    fun expatFitBreaksTiesByMadagascarEligibilityThenSponsorship() {
        val unclear = job(id = "unclear", eligibility = MadagascarEligibility.UNCLEAR)
        val restricted = job(id = "restricted", eligibility = MadagascarEligibility.RESTRICTED)
        val sponsored = job(id = "sponsored", eligibility = MadagascarEligibility.VISA_SPONSORED)
        val eligible = job(id = "eligible", eligibility = MadagascarEligibility.ELIGIBLE, visaDocumented = true)
        val state = JobsUiState(nowMillis = NOW, 
            jobs = listOf(unclear, restricted, sponsored, eligible),
            sort = JobSort.EXPAT_FIT,
        )

        // With no benefits scored, a direct/sponsored route outranks unclear, which outranks
        // postings that rule expats out.
        assertEquals(listOf("eligible", "sponsored", "unclear", "restricted"), state.visibleJobs.map { it.id })
    }

    @Test
    fun expatFitFallsBackToCvMatchForEquallyApplicableJobs() {
        val strong = job(id = "strong", matchScore = 88, eligibility = MadagascarEligibility.ELIGIBLE)
        val weak = job(id = "weak", matchScore = 55, eligibility = MadagascarEligibility.ELIGIBLE)
        val state = JobsUiState(nowMillis = NOW, jobs = listOf(weak, strong), sort = JobSort.EXPAT_FIT)

        assertEquals(listOf("strong", "weak"), state.visibleJobs.map { it.id })
    }

    @Test
    fun eligibilityRankOrdersTheVerdictsByApplicability() {
        assertEquals(3, MadagascarEligibility.ELIGIBLE.rank)
        assertEquals(2, MadagascarEligibility.VISA_SPONSORED.rank)
        assertEquals(1, MadagascarEligibility.UNCLEAR.rank)
        assertEquals(0, MadagascarEligibility.RESTRICTED.rank)
    }

    @Test
    fun expatFitIsOfferedAsASortOrder() {
        assertTrue(JobSort.entries.any { it == JobSort.EXPAT_FIT })
    }
}
