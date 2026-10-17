package com.aiglobal.jobfinder.data.jobs.provider

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
 * Workday careers sites of large mining, pump and industrial employers — Rio Tinto, Alcoa,
 * GE Vernova, SSR Mining, The Weir Group, Sulzer, EPSA and First Quantum Minerals. These
 * are [ProviderKind.EMPLOYER_BOARD] sources: the posting is the employer's own, so it is the
 * most trustworthy feed for expat industrial and mining roles, and it applies directly with
 * no middle party. The pump and earthmoving-service employers matter because they advertise
 * the very trades a Madagascar-based maintenance/mechanical/pump candidate targets
 * (millwright, field service technician, fitter, artisan) across Gulf and African sites.
 *
 * Workday's public CXS endpoint supports server-side keyword search and a per-posting
 * detail call. The search row carries only a free-text location ("Canga East"), so the
 * detail call is used to read the real country and the job description, which the
 * eligibility and matching engines need. Detail calls are bounded and run concurrently so
 * one slow employer cannot stall the refresh.
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
    )

    override suspend fun fetch(keywords: List<String>, limit: Int): List<RawJob> {
        val keyword = keywords.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_KEYWORD
        val collected = LinkedHashMap<String, RawJob>()
        var lastError: Throwable? = null
        var anyAnswered = false

        for (site in sites) {
            val postings = try {
                search(site, keyword)
            } catch (t: Throwable) {
                lastError = t
                continue
            }
            anyAnswered = true
            // Detail calls are the expensive part; bound them per site per query so a
            // keyword that matches hundreds of roles still finishes inside the timeout.
            val paths = postings.mapNotNull { it.optString("externalPath").takeIf { p -> p.isNotBlank() } }
                .take(DETAILS_PER_SITE)
            for (job in detailsFor(site, paths)) {
                collected.putIfAbsent(job.externalId, job)
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

    private suspend fun search(site: WorkdaySite, keyword: String): List<JSONObject> {
        val body = JSONObject()
            .put("appliedFacets", JSONObject())
            .put("limit", SEARCH_LIMIT)
            .put("offset", 0)
            .put("searchText", keyword)
            .toString()
        val json = feed.objectPost(site.searchUrl, body, HEADERS)
        val postings = json.optJSONArray("jobPostings") ?: return emptyList()
        return (0 until postings.length()).mapNotNull { postings.optJSONObject(it) }
    }

    /** Fetches the detail rows concurrently, skipping any that fail so one bad row cannot
     *  drop the rest of the employer's postings. */
    private suspend fun detailsFor(site: WorkdaySite, paths: List<String>): List<RawJob> = coroutineScope {
        paths.map { path ->
            async {
                runCatching { feed.objectAt(site.detailUrl(path), HEADERS) }
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
        const val DETAILS_PER_SITE = 4
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
        // Mining contractors and producers with African and Gulf sites, where expat trades
        // (artisan fitter, boilermaker, drill mechanic) are advertised.
        WorkdaySite("epsa", "epsa.wd103.myworkdayjobs.com", "ExternalCareerSite", "EPSA"),
        WorkdaySite("firstquantum", "firstquantum.wd3.myworkdayjobs.com", "First_Quantum_Careers", "First Quantum Minerals"),
    )
}
