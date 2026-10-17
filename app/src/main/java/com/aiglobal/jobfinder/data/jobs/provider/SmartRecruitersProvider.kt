package com.aiglobal.jobfinder.data.jobs.provider

import com.aiglobal.jobfinder.data.jobs.search.RoleClassifier
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/** One employer careers site hosted on SmartRecruiters, addressed by its company identifier. */
data class SmartRecruitersBoard(
    /** SmartRecruiters company identifier, e.g. "SGS". */
    val identifier: String,
    /** Employer name shown on the card. */
    val label: String,
) {
    fun postingsUrl(query: String): String =
        "https://api.smartrecruiters.com/v1/companies/$identifier/postings" +
            "?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=$PAGE_LIMIT"

    fun detailUrl(postingId: String): String =
        "https://api.smartrecruiters.com/v1/companies/$identifier/postings/$postingId"

    private companion object {
        /** The public posting API caps a page at 100. */
        const val PAGE_LIMIT = 100
    }
}

/**
 * SmartRecruiters careers sites of inspection, testing, industrial-service and equipment
 * employers — SGS, Wabtec, Konecranes and Bosch. These are [ProviderKind.EMPLOYER_BOARD]
 * sources: the posting is the employer's own, so it is the most trustworthy feed for expat
 * industrial and mining trades and it applies directly with no middle party.
 *
 * SmartRecruiters publishes a **keyless public posting API** for employers whose plan has
 * the public feed enabled. It supports server-side keyword search (`q=`) and a country
 * filter (`country=`, an ISO-3166 alpha-2 code). This is what makes the source worth adding
 * where the earlier review skipped it: SGS alone carries maintenance, inspection and
 * mechanical-inspector vacancies across Saudi Arabia, the UAE, Mozambique, South Africa and
 * Kenya, and Wabtec and Konecranes carry fitter/technician roles in South Africa — the very
 * trades and geographies a Madagascar-based maintenance candidate targets.
 *
 * The list row carries the title, employer and location but not the description, so a
 * bounded, concurrent set of per-posting detail calls reads the job text the matching and
 * eligibility engines need. Because that budget is bounded, trade titles are read first (see
 * [pickDetailIds]) so an employer's maintenance vacancies are not crowded out by its latest
 * internship — the same rule the other employer-board providers use.
 *
 * A company whose plan has no public feed answers with an empty result rather than an error;
 * that is treated as "this employer published nothing", never as a failure of the source.
 */
class SmartRecruitersProvider(
    private val feed: JsonFeed = HttpJsonFeed,
    private val boards: List<SmartRecruitersBoard> = SmartRecruitersBoards.BOARDS,
) : JobProvider {

    override val id: String = "smartrecruiters"
    override val name: String = "Employer job boards"
    override val homepage: String = "https://jobs.smartrecruiters.com"
    override val kind: ProviderKind = ProviderKind.EMPLOYER_BOARD
    override val documentationUrl: String = "https://developers.smartrecruiters.com/docs/job-postings-api"
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

        for (board in boards) {
            val rows = try {
                search(board, keyword)
            } catch (t: Throwable) {
                lastError = t
                continue
            }
            anyAnswered = true
            for (job in detailsFor(board, pickDetailIds(rows))) {
                collected.putIfAbsent(job.externalId, job)
            }
            if (collected.size >= limit) break
        }

        if (!anyAnswered && lastError != null) {
            throw JobProviderException(
                providerId = id,
                message = "The employer job boards did not respond",
                cause = lastError,
                category = ProviderErrorClassifier.classify(id, lastError),
            )
        }
        return collected.values.take(limit)
    }

    /**
     * Chooses which list rows get the bounded, expensive per-posting detail read. Trade
     * titles are read first, using the same [RoleClassifier] the rest of the pipeline uses
     * to define relevance, and the remaining rows fill the rest of the budget. This changes
     * only the order; the same number of requests are made and no posting is invented. When
     * no title reads as a trade role the result is the source's own order, unchanged.
     */
    private fun pickDetailIds(rows: List<JSONObject>): List<String> {
        val titled = rows.mapNotNull { row ->
            val id = row.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to row.optString("name")
        }
        val (relevant, rest) = titled.partition { (_, title) ->
            RoleClassifier.classify(title).isMaintenanceFamily
        }
        return (relevant + rest).map { it.first }.take(DETAILS_PER_BOARD)
    }

    private suspend fun search(board: SmartRecruitersBoard, keyword: String): List<JSONObject> {
        val json = feed.objectAt(board.postingsUrl(keyword), HEADERS)
        val content = json.optJSONArray("content") ?: return emptyList()
        return (0 until content.length()).mapNotNull { content.optJSONObject(it) }
    }

    /** Fetches the detail rows concurrently, skipping any that fail so one bad row cannot
     *  drop the rest of the employer's postings. */
    private suspend fun detailsFor(board: SmartRecruitersBoard, ids: List<String>): List<RawJob> = coroutineScope {
        ids.map { id ->
            async {
                runCatching { feed.objectAt(board.detailUrl(id), HEADERS) }
                    .getOrNull()
                    ?.let { detail -> detail.toRawJob(board) }
            }
        }.awaitAll().filterNotNull()
    }

    private fun JSONObject.toRawJob(board: SmartRecruitersBoard): RawJob? {
        // A posting the employer has taken down is not shown as if it were open.
        if (has("active") && !optBoolean("active", true)) return null

        val title = optString("name").trim()
        if (title.isEmpty()) return null

        val description = descriptionText()
        if (description.isBlank()) return null

        val location = locationText()
        if (location.isBlank()) return null

        val postingId = optString("id")
        val url = optString("postingUrl").ifBlank { board.detailUrl(postingId) }
        val employmentType = optJSONObject("typeOfEmployment")?.optString("label")?.takeIf { it.isNotBlank() }

        return RawJob(
            providerId = this@SmartRecruitersProvider.id,
            providerName = this@SmartRecruitersProvider.name,
            externalId = "${board.identifier}-$postingId",
            title = title,
            company = optJSONObject("company")?.optString("name")?.takeIf { it.isNotBlank() } ?: board.label,
            location = location,
            descriptionHtml = description,
            url = url,
            employmentType = employmentType,
            postedAtEpochMillis = IsoDates.epochMillis(optString("releasedDate")),
            isRemote = optJSONObject("location")?.optBoolean("remote", false) ?: false,
        )
    }

    /** Joins the four description sections SmartRecruiters publishes, dropping empty ones. */
    private fun JSONObject.descriptionText(): String {
        val sections = optJSONObject("jobAd")?.optJSONObject("sections") ?: return ""
        return SECTION_KEYS.mapNotNull { key ->
            sections.optJSONObject(key)?.optString("text")?.takeIf { it.isNotBlank() }
        }.joinToString("\n")
    }

    /** "City, Region, Country" from the structured location, or the published free text. */
    private fun JSONObject.locationText(): String {
        val location = optJSONObject("location") ?: return ""
        val full = location.optString("fullLocation").trim()
        if (full.isNotBlank()) return full
        return listOf("city", "region", "country")
            .map { location.optString(it).trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .joinToString(", ")
    }

    private companion object {
        const val DETAILS_PER_BOARD = 4
        const val DEFAULT_KEYWORD = "maintenance"

        val SECTION_KEYS = listOf("jobDescription", "qualifications", "additionalInformation", "companyDescription")

        val HEADERS = mapOf("Accept" to "application/json")
    }
}

/**
 * Curated SmartRecruiters employers, each verified to answer the keyless public posting API.
 * The set is deliberately trade-led: inspection/testing and industrial-equipment employers
 * publish the maintenance, fitter, millwright and pump roles the candidate targets, and the
 * large ones reach Africa and the Gulf, not only North America and Europe.
 */
internal object SmartRecruitersBoards {
    val BOARDS: List<SmartRecruitersBoard> = listOf(
        SmartRecruitersBoard("SGS", "SGS"),
        SmartRecruitersBoard("Wabtec", "Wabtec"),
        SmartRecruitersBoard("Konecranes", "Konecranes"),
        SmartRecruitersBoard("BoschGroup", "Bosch"),
    )
}
