package com.aiglobal.jobfinder.data.jobs.provider

import com.aiglobal.jobfinder.data.jobs.search.RoleClassifier
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * One employer careers site hosted on Workday, addressed by its tenant and site name.
 */
data class WorkdaySite(
    /** Workday CXS tenant id, e.g. "riotinto". */
    val tenant: String,
    /** Workday host, e.g. "riotinto.wd3.myworkdayjobs.com". */
    val host: String,
    /** Careers site name, e.g. "RioTinto_Careers". */
    val site: String,
    /** Employer name shown on the card. */
    val label: String,
) {
    val searchUrl: String get() = "https://$host/wday/cxs/$tenant/$site/jobs"
    fun detailUrl(externalPath: String): String = "https://$host/wday/cxs/$tenant/$site$externalPath"
}

/**
 * Workday careers sites of large mining, energy, industrial, equipment and facilities
 * employers — Rio Tinto, Alcoa, GE Vernova, SSR Mining, Weir, Sulzer, KSB, FLSmidth, Alfa Laval,
 * EPSA and First Quantum, plus the oilfield-services, power-generation, industrial-equipment and
 * facilities employers verified in the P0 expansion (Baker Hughes, ABB, ChampionX, Air Products,
 * Marmon, JLL, Shell, TC Energy, Williams, Ameresco, Duke Energy, Micron, Siemens Healthineers,
 * Permian Resources and AngloGold Ashanti) and the pump/rotating-equipment and midstream employers
 * added in the P1 expansion (KSB, FLSmidth, Alfa Laval, ONEOK). These are
 * [ProviderKind.EMPLOYER_BOARD] sources: the posting is the
 * employer's own, so it is the most trustworthy feed for expat industrial and mining roles, and
 * it applies directly with no middle party. The pump, rotating-equipment and earthmoving-service
 * employers matter because they advertise the very trades a Madagascar-based
 * maintenance/mechanical/pump candidate targets (millwright, field service technician, fitter,
 * artisan) across Gulf, African and international sites.
 *
 * Workday's public CXS endpoint supports server-side keyword search, `offset` paging and a
 * per-posting detail call. The search row carries only a free-text location ("Canga East"), so
 * the detail call is used to read the real country and the job description, which the
 * eligibility and matching engines need.
 *
 * A refresh is kept bounded two ways. Each site's search is paged a small, fixed number of
 * times ([PAGES_PER_SITE]) so a keyword matching hundreds of roles still stops early, and the
 * sites are visited with bounded concurrency ([SITE_CONCURRENCY]) instead of one after another,
 * so the growing employer list does not push a single refresh past the pipeline's timeout — the
 * detail calls within a site already run concurrently. Both caps were sized against measured
 * latency, not guessed.
 */
class WorkdayProvider(
    private val feed: JsonFeed = HttpJsonFeed,
    private val sites: List<WorkdaySite> = WorkdayBoards.SITES,
) : JobProvider {

    override val id: String = "workday"
    override val name: String = "Employer career sites"
    override val homepage: String = "https://www.myworkdayjobs.com"
    override val kind: ProviderKind = ProviderKind.EMPLOYER_BOARD
    override val documentationUrl: String = "https://www.myworkdayjobs.com"
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        serverSideKeyword = true,
        countryFilter = false,
        pagination = true,
        employmentTypeFilter = false,
        applicationLinks = true,
        submissionEndpoint = false,
        // The feed reads only the first keyword but still walks all curated sites for each
        // call, so a six-role plan would re-run the whole 24-site sweep six times for six
        // largely overlapping trade terms. One sweep keeps the highest-value keyword and
        // removes the redundant site walks.
        keywordSweepBudget = 1,
    )

    override suspend fun fetch(keywords: List<String>, limit: Int): List<RawJob> {
        val keyword = keywords.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_KEYWORD
        val collected = LinkedHashMap<String, RawJob>()
        var lastError: Throwable? = null
        var anyAnswered = false

        // Visit the sites a bounded batch at a time so the whole list still finishes inside
        // the pipeline timeout, while a single slow employer cannot stall the others.
        for (chunk in sites.chunked(SITE_CONCURRENCY)) {
            val results = coroutineScope {
                chunk.map { site ->
                    async { site to runCatchingCancellable { fetchSite(site, keyword) } }
                }.awaitAll()
            }
            for ((_, outcome) in results) {
                outcome.onSuccess { jobs ->
                    anyAnswered = true
                    for (job in jobs) collected.putIfAbsent(job.externalId, job)
                }.onFailure { lastError = it }
            }
            if (collected.size >= limit) break
        }

        if (!anyAnswered && lastError != null) {
            throw JobProviderException(
                providerId = id,
                message = "The employer career sites did not respond",
                cause = lastError,
                category = ProviderErrorClassifier.classify(id, lastError),
            )
        }
        return collected.values.take(limit)
    }

    /** Reads one site's search pages, then the bounded, concurrent detail rows for its trades. */
    private suspend fun fetchSite(site: WorkdaySite, keyword: String): List<RawJob> {
        val postings = search(site, keyword)
        return detailsFor(site, pickDetailPaths(postings))
    }

    /**
     * Chooses which search rows get the bounded, expensive per-posting detail read. The search
     * row carries the title, so the trade titles are read first — using the same [RoleClassifier]
     * the rest of the pipeline uses to define relevance — and the remaining rows fill the rest of
     * the budget. This only reorders the read: the same number of detail calls are made and no
     * posting is invented. When no title reads as a trade role the search order is kept, so a
     * site whose keyword results are all trades still reads its newest rows.
     */
    private fun pickDetailPaths(postings: List<JSONObject>): List<String> {
        val titled = postings.mapNotNull { posting ->
            val path = posting.optString("externalPath").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            path to posting.optString("title")
        }
        val (relevant, rest) = titled.partition { (_, title) ->
            RoleClassifier.classify(title).isMaintenanceFamily
        }
        return (relevant + rest).map { it.first }.take(DETAILS_PER_SITE)
    }

    /**
     * Pages the site's search a fixed number of times. A page shorter than [SEARCH_LIMIT] is the
     * last one, and an empty page stops the walk, so the provider never asks for a page it knows
     * is empty. Workday rejects `limit` greater than 20 with HTTP 400, so the page size is fixed
     * at that documented maximum.
     */
    private suspend fun search(site: WorkdaySite, keyword: String): List<JSONObject> {
        val postings = mutableListOf<JSONObject>()
        for (page in 0 until PAGES_PER_SITE) {
            val body = JSONObject()
                .put("appliedFacets", JSONObject())
                .put("limit", SEARCH_LIMIT)
                .put("offset", page * SEARCH_LIMIT)
                .put("searchText", keyword)
                .toString()
            val json = feed.objectPost(site.searchUrl, body, HEADERS)
            val page_ = json.optJSONArray("jobPostings") ?: break
            if (page_.length() == 0) break
            postings += (0 until page_.length()).mapNotNull { page_.optJSONObject(it) }
            if (page_.length() < SEARCH_LIMIT) break
        }
        return postings
    }

    /** Fetches the detail rows concurrently, skipping any that fail so one bad row cannot
     *  drop the rest of the employer's postings. */
    private suspend fun detailsFor(site: WorkdaySite, paths: List<String>): List<RawJob> = coroutineScope {
        paths.map { path ->
            async {
                runCatchingCancellable { feed.objectAt(site.detailUrl(path), HEADERS) }
                    .getOrNull()
                    ?.optJSONObject("jobPostingInfo")
                    ?.let { info -> info.toRawJob(site) }
            }
        }.awaitAll().filterNotNull()
    }

    private fun JSONObject.toRawJob(site: WorkdaySite): RawJob? {
        val title = optString("title").trim()
        if (title.isEmpty()) return null

        val description = optString("jobDescription")
        if (description.isBlank()) return null

        val city = optString("location").trim()
        val country = optJSONObject("country")?.optString("descriptor").orEmpty().trim()
        // "Canga East, Guinea" resolves to the country; a city alone is left as published.
        val location = listOf(city, country)
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .joinToString(", ")

        val url = optString("externalUrl").ifBlank { site.detailUrl(optString("externalPath")) }
        val reqId = optString("jobReqId").ifBlank { optString("id") }

        // Workday states whether the posting still accepts applicants. When it says no, the
        // posting is closed and must not be shown as if it were open. The field is absent on
        // some rows; absent means "not stated", never "closed". `openToApplicants` records
        // the affirmative case so the verification read can confirm a live vacancy from the
        // source's own flag rather than inferring it from age.
        val canApply = if (has("canApply")) optBoolean("canApply") else null
        val closedToNewApplicants = canApply == false

        return RawJob(
            providerId = this@WorkdayProvider.id,
            providerName = this@WorkdayProvider.name,
            externalId = "${site.tenant}-$reqId",
            title = title,
            company = site.label,
            location = location,
            descriptionHtml = description,
            url = url,
            employmentType = optString("timeType").takeIf { it.isNotBlank() },
            postedAtEpochMillis = IsoDates.epochMillis(optString("startDate")),
            isRemote = false,
            closedToNewApplicants = closedToNewApplicants,
            openToApplicants = canApply,
        )
    }

    private companion object {
        /** Workday rejects limit > 20 with HTTP 400. */
        const val SEARCH_LIMIT = 20

        /** Search pages read per site. Two pages (40 candidate rows) then the detail budget. */
        const val PAGES_PER_SITE = 2

        /** Detail rows read per site per query — the trade titles first, then the rest. */
        const val DETAILS_PER_SITE = 6

        /** Sites visited at once. Sized so the whole list finishes inside the pipeline timeout. */
        const val SITE_CONCURRENCY = 6

        const val DEFAULT_KEYWORD = "maintenance"

        val HEADERS = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        )
    }
}

/** The curated set of Workday employer sites verified to answer the public CXS endpoint. */
internal object WorkdayBoards {
    val SITES: List<WorkdaySite> = listOf(
        WorkdaySite("riotinto", "riotinto.wd3.myworkdayjobs.com", "RioTinto_Careers", "Rio Tinto"),
        WorkdaySite("alcoa", "alcoa.wd5.myworkdayjobs.com", "Careers", "Alcoa"),
        WorkdaySite("gevernova", "gevernova.wd5.myworkdayjobs.com", "Vernova_ExternalSite", "GE Vernova"),
        WorkdaySite("ssrmining", "ssrmining.wd108.myworkdayjobs.com", "SSR_Mining_Careers", "SSR Mining"),
        // Pump and industrial-service employers, whose exact trade roles (millwright, field
        // service technician, fitter) are what a maintenance candidate can apply for.
        WorkdaySite("weir", "weir.wd3.myworkdayjobs.com", "Weir_External_Careers", "Weir Group"),
        WorkdaySite("sulzer", "sulzer.wd502.myworkdayjobs.com", "SulzerJobs", "Sulzer"),
        // Pump, rotating-equipment and minerals-processing employers verified in the P1
        // expansion (KSB, FLSmidth, Alfa Laval) plus a midstream energy operator (ONEOK).
        // Their boards carry the candidate's exact trades — pump service technician,
        // millwright, mechanical fitter, maintenance technician — across Africa, Europe,
        // the Gulf and North America.
        WorkdaySite("ksb", "ksb.wd3.myworkdayjobs.com", "KSB_ExternalCareerSite", "KSB"),
        WorkdaySite("flsmidth", "flsmidth.wd3.myworkdayjobs.com", "fls_global", "FLSmidth"),
        WorkdaySite("alfalaval", "alfalaval.wd3.myworkdayjobs.com", "alfa_laval_jobs", "Alfa Laval"),
        WorkdaySite("oneok", "oneok.wd1.myworkdayjobs.com", "ONEOK", "ONEOK"),
        // Mining contractors and producers with African and Gulf sites, where expat trades
        // (artisan fitter, boilermaker, drill mechanic) are advertised.
        WorkdaySite("epsa", "epsa.wd103.myworkdayjobs.com", "ExternalCareerSite", "EPSA"),
        WorkdaySite("firstquantum", "firstquantum.wd3.myworkdayjobs.com", "First_Quantum_Careers", "First Quantum Minerals"),
        // Oilfield services, industrial automation and rotating equipment: the employers whose
        // maintenance/mechanic/fitter roles sit across the Gulf and international service sites.
        WorkdaySite("bakerhughes", "bakerhughes.wd5.myworkdayjobs.com", "BakerHughes", "Baker Hughes"),
        WorkdaySite("abb", "abb.wd3.myworkdayjobs.com", "External_Career_Page", "ABB"),
        WorkdaySite("championx", "championx.wd1.myworkdayjobs.com", "ChampionX_External", "ChampionX"),
        WorkdaySite("airproducts", "airproducts.wd5.myworkdayjobs.com", "AP0001", "Air Products"),
        WorkdaySite("marmon", "marmon.wd501.myworkdayjobs.com", "Marmon_Careers", "Marmon"),
        // Power, energy, semiconductor and facilities employers, whose plant-maintenance and
        // mechanical trades are advertised worldwide, including the Gulf.
        WorkdaySite("shell", "shell.wd3.myworkdayjobs.com", "ShellCareers", "Shell"),
        WorkdaySite("tcenergy", "tcenergy.wd3.myworkdayjobs.com", "CAREER_SITE_TC", "TC Energy"),
        WorkdaySite("williams", "williams.wd5.myworkdayjobs.com", "External", "Williams"),
        WorkdaySite("ameresco", "ameresco.wd5.myworkdayjobs.com", "Ameresco", "Ameresco"),
        WorkdaySite("dukeenergy", "dukeenergy.wd1.myworkdayjobs.com", "Search", "Duke Energy"),
        WorkdaySite("micron", "micron.wd1.myworkdayjobs.com", "External", "Micron"),
        WorkdaySite("onehealthineers", "onehealthineers.wd3.myworkdayjobs.com", "SHSJB", "Siemens Healthineers"),
        WorkdaySite("jll", "jll.wd1.myworkdayjobs.com", "jllcareers", "JLL"),
    )
}
