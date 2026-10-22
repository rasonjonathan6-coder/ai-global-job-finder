package com.aiglobal.jobfinder.data.jobs.provider

import com.aiglobal.jobfinder.data.jobs.search.RoleClassifier
import kotlin.coroutines.cancellation.CancellationException
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
    /**
     * The Gulf, Southern- and East-African markets this employer actually publishes in, as
     * ISO-3166 alpha-2 codes. Empty means the employer has no measured presence in those
     * markets and the board is read with its unfiltered keyword feed only, so no request is
     * spent on a country the employer does not recruit in.
     */
    val countries: List<String> = emptyList(),
) {
    fun postingsUrl(query: String, country: String? = null): String = buildString {
        append("https://api.smartrecruiters.com/v1/companies/$identifier/postings")
        append("?q=").append(java.net.URLEncoder.encode(query, "UTF-8"))
        append("&limit=").append(PAGE_LIMIT)
        if (!country.isNullOrBlank()) append("&country=").append(country)
    }

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
        // The public API narrows by `country=` (ISO-3166 alpha-2), which is what surfaces the
        // Gulf and Southern/East-African vacancies an unfiltered page buries.
        countryFilter = true,
        pagination = true,
        employmentTypeFilter = false,
        applicationLinks = true,
        submissionEndpoint = false,
        // The feed reads only the first keyword but each call sweeps every configured country
        // for every board. A second set therefore re-runs the identical country sweep for the
        // identical keyword: it adds no coverage, only duplicate requests. One set keeps the
        // whole country sweep exactly once.
        keywordSweepBudget = 1,
    )

    override suspend fun fetch(keywords: List<String>, limit: Int): List<RawJob> {
        val keyword = keywords.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_KEYWORD
        val collected = LinkedHashMap<String, RawJob>()
        var lastError: Throwable? = null
        var anyAnswered = false

        // Boards are visited with bounded concurrency: the country sweep multiplies the request
        // count, so walking the boards one after another would not finish inside the pipeline
        // timeout. Each board's own detail calls already run concurrently.
        for (chunk in boards.chunked(BOARD_CONCURRENCY)) {
            val results = coroutineScope {
                chunk.map { board ->
                    async { board to runCatchingCancellable { fetchBoard(board, keyword) } }
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
                message = "The employer job boards did not respond",
                cause = lastError,
                category = ProviderErrorClassifier.classify(id, lastError),
            )
        }
        return collected.values.take(limit)
    }

    /**
     * Reads one employer. The unfiltered keyword feed is read first, then one country-filtered
     * feed per market the employer actually publishes in, so an applicable Gulf or
     * Southern/East-African vacancy buried past the first page of the unfiltered feed is still
     * surfaced — SGS alone carries maintenance roles in South Africa, Mozambique, the UAE and
     * Saudi Arabia that a top-100 unfiltered page never reaches. A country the employer does not
     * recruit in returns nothing and is simply skipped, and a single country feed that fails is
     * ignored rather than losing the rows already collected. Rows are deduplicated by posting id,
     * and the trade-first detail budget is shared across all of them so the number of expensive
     * detail calls stays bounded.
     */
    private suspend fun fetchBoard(board: SmartRecruitersBoard, keyword: String): List<RawJob> {
        val rows = LinkedHashMap<String, JSONObject>()
        for (row in search(board, keyword, country = null)) {
            rows.putIfAbsent(row.optString("id"), row)
        }
        for (country in board.countries) {
            val countryRows = runCatchingCancellable { search(board, keyword, country) }.getOrDefault(emptyList())
            for (row in countryRows) {
                rows.putIfAbsent(row.optString("id"), row)
            }
        }
        return detailsFor(board, pickDetailIds(rows.values.toList()))
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

    private suspend fun search(board: SmartRecruitersBoard, keyword: String, country: String?): List<JSONObject> {
        val json = feed.objectAt(board.postingsUrl(keyword, country), HEADERS)
        val content = json.optJSONArray("content") ?: return emptyList()
        return (0 until content.length()).mapNotNull { content.optJSONObject(it) }
    }

    /** Fetches the detail rows concurrently, skipping any that fail so one bad row cannot
     *  drop the rest of the employer's postings. */
    private suspend fun detailsFor(board: SmartRecruitersBoard, ids: List<String>): List<RawJob> = coroutineScope {
        ids.map { id ->
            async {
                runCatchingCancellable { feed.objectAt(board.detailUrl(id), HEADERS) }
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
        /** Detail rows read per employer — the trade titles first, then the rest. */
        const val DETAILS_PER_BOARD = 8

        /** Employers read at once. Sized so the country sweep finishes inside the pipeline timeout. */
        const val BOARD_CONCURRENCY = 4

        const val DEFAULT_KEYWORD = "maintenance"

        val SECTION_KEYS = listOf("jobDescription", "qualifications", "additionalInformation", "companyDescription")

        val HEADERS = mapOf("Accept" to "application/json")
    }
}

/**
 * Curated SmartRecruiters employers, each verified to answer the keyless public posting API.
 * The set is deliberately trade-led: inspection/testing, industrial-equipment, engineering and
 * mining employers publish the maintenance, fitter, millwright and pump roles the candidate
 * targets, and the large ones reach Africa and the Gulf, not only North America and Europe.
 *
 * [SmartRecruitersBoard.countries] lists only the Gulf and African markets each employer was
 * measured to publish in, so the country sweep spends requests where applicable vacancies
 * actually exist rather than probing markets the employer does not recruit in.
 */
internal object SmartRecruitersBoards {
    val BOARDS: List<SmartRecruitersBoard> = listOf(
        SmartRecruitersBoard(
            "SGS", "SGS",
            countries = listOf("ae", "sa", "qa", "om", "za", "mz", "na", "ke"),
        ),
        SmartRecruitersBoard("Wabtec", "Wabtec", countries = listOf("za")),
        SmartRecruitersBoard("Konecranes", "Konecranes", countries = listOf("za")),
        SmartRecruitersBoard("BoschGroup", "Bosch"),
        SmartRecruitersBoard(
            "EgisGroup", "Egis",
            countries = listOf("ae", "sa", "qa", "kw", "zm"),
        ),
        SmartRecruitersBoard("ValterraPlatinum1", "Valterra Platinum", countries = listOf("za")),
        SmartRecruitersBoard("prosidianconsulting", "ProSidian", countries = listOf("za")),
        SmartRecruitersBoard("IOTAGROUP", "IOTA"),
        SmartRecruitersBoard("ContinentalGroupSectorContiTech", "Continental ContiTech"),
        SmartRecruitersBoard("PlantsAndGoodwinInc", "Plants & Goodwin"),
    )
}
