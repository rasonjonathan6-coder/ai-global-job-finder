package com.aiglobal.jobfinder.data.jobs.provider

import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** One employer careers site hosted on Lever, addressed by its board token. */
data class LeverBoard(
    /** Lever postings token, e.g. "shieldai". */
    val token: String,
    /** Employer name shown on the card. */
    val label: String,
) {
    fun pageUrl(skip: Int, pageSize: Int): String =
        "https://api.lever.co/v0/postings/$token?mode=json&limit=$pageSize&skip=$skip"
}

/**
 * Lever careers sites of large industrial and defence-tech employers — Shield AI and
 * Sila. These are [ProviderKind.EMPLOYER_BOARD] sources, so the posting is the employer's
 * own and applies directly.
 *
 * Lever's public postings endpoint returns the board as a JSON array with the full
 * description and every location inline, and it has no server-side keyword search. A
 * board can be very large (Shield AI publishes ~600 roles, which is ~9 MB of JSON), so
 * the feed is read a bounded number of pages at a time rather than in one request, and
 * each page is filtered locally against the candidate's target titles. The page cap keeps
 * a mobile refresh bounded; a target role that only appears deep in a very large board
 * may therefore be missed, which is an accepted trade-off against a multi-megabyte
 * download on every refresh.
 */
class LeverProvider(
    private val feed: JsonFeed = HttpJsonFeed,
    private val boards: List<LeverBoard> = LeverBoards.BOARDS,
    /** One board may spend at most this long before its remaining pages are skipped. */
    private val boardTimeout: Duration = BOARD_TIMEOUT,
) : JobProvider {

    override val id: String = "lever"
    override val name: String = "Industrial employer boards"
    override val homepage: String = "https://www.lever.co"
    override val kind: ProviderKind = ProviderKind.EMPLOYER_BOARD
    override val documentationUrl: String = "https://github.com/lever/postings-api"
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        serverSideKeyword = false,
        countryFilter = false,
        pagination = true,
        employmentTypeFilter = false,
        applicationLinks = true,
        submissionEndpoint = false,
    )

    override suspend fun fetch(keywords: List<String>, limit: Int): List<RawJob> {
        val plan = (if (keywords.isNotEmpty()) keywords else DEFAULT_KEYWORDS)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

        val collected = LinkedHashMap<String, RawJob>()
        var lastError: Throwable? = null
        var anyAnswered = false

        for (board in boards) {
            val jobs = try {
                list(board, plan)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastError = t
                continue
            }
            anyAnswered = true
            for (item in jobs) {
                val job = item.toRawJob(board, plan) ?: continue
                collected.putIfAbsent(job.externalId, job)
            }
            if (collected.size >= limit) break
        }

        if (!anyAnswered && lastError != null) {
            throw JobProviderException(
                providerId = id,
                message = "The employer boards did not respond",
                cause = lastError,
                category = ProviderErrorClassifier.classify(id, lastError),
            )
        }
        return collected.values.take(limit)
    }

    /**
     * Reads a board page by page, stopping as soon as the board has produced enough of the
     * candidate's titles. Lever returns the full description of every posting inline, so a large
     * board is a multi-megabyte download; once a board has yielded [MATCHES_PER_BOARD] target
     * titles there is nothing a further page adds to the shortlist, so the remaining pages are
     * not fetched. A board that has matched nothing on its first page is unlikely to be a trade
     * board at all, so it is read to [SPARSE_PAGES] only; a board that is already productive, or
     * that has matched on no page, is read to [MAX_PAGES] so depth is preserved where it pays.
     * Two further guards bound the cost regardless: one slow board cannot spend more than
     * [BOARD_TIMEOUT] of the provider's refresh, and paging stops once a board has returned
     * [MAX_BOARD_ROWS] rows, so a multi-megabyte board cannot be read whole.
     * A short page is the end of the board.
     */
    private suspend fun list(board: LeverBoard, plan: List<String>): List<JSONObject> {
        val jobs = mutableListOf<JSONObject>()
        var matches = 0
        var bytes = 0L
        var pageCap = MAX_PAGES
        val startedAt = TimeSource.Monotonic.markNow()
        for (page in 0 until MAX_PAGES) {
            if (page >= pageCap) break
            if (startedAt.elapsedNow() >= boardTimeout) break
            val array = feed.arrayAt(board.pageUrl(skip = page * PAGE_SIZE, pageSize = PAGE_SIZE), HEADERS)
            if (array.length() == 0) break
            val rows = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            jobs += rows
            bytes += array.length()
            matches += rows.count { row ->
                val title = row.optString("text").lowercase()
                plan.isNotEmpty() && plan.any { title.contains(it) }
            }
            // A board that showed no target title at all on its first page is unlikely to be a
            // trade board; cap it early. A board that already matched keeps the full depth.
            if (page == 0 && matches == 0) pageCap = SPARSE_PAGES
            if (array.length() < PAGE_SIZE) break
            if (matches >= MATCHES_PER_BOARD) break
            if (bytes >= MAX_BOARD_ROWS) break
        }
        return jobs
    }

    private fun JSONObject.toRawJob(board: LeverBoard, plan: List<String>): RawJob? {
        // Unlisted postings are internal/closed and must never surface.
        if (has("isListed") && !optBoolean("isListed", true)) return null

        val title = optString("text").trim()
        if (title.isEmpty()) return null

        // Lever has no server-side search, so the board is filtered locally: a posting
        // must carry a target title to be worth the candidate's attention.
        if (plan.isNotEmpty() && plan.none { title.lowercase().contains(it) }) return null

        val description = optString("descriptionPlain").ifBlank { optString("description") }
        if (description.isBlank()) return null

        val categories = optJSONObject("categories")
        val location = locationText(categories)
        if (location.isBlank()) return null

        val workplace = optString("workplaceType")
        val remote = optBoolean("isRemote", false) ||
            workplace.equals("remote", ignoreCase = true) ||
            location.contains("remote", ignoreCase = true)

        val commitment = categories?.optString("commitment").orEmpty().takeIf { it.isNotBlank() }

        return RawJob(
            providerId = this@LeverProvider.id,
            providerName = this@LeverProvider.name,
            externalId = "${board.token}-${optString("id")}",
            title = title,
            company = board.label,
            location = location,
            descriptionHtml = description,
            url = optString("hostedUrl").ifBlank { optString("applyUrl") },
            tags = listOfNotNull(
                categories?.optString("department").orEmpty().takeIf { it.isNotBlank() },
                categories?.optString("team").orEmpty().takeIf { it.isNotBlank() },
            ),
            employmentType = commitment,
            postedAtEpochMillis = optLong("createdAt").takeIf { it > 0 },
            isRemote = remote,
        )
    }

    /** Joins the primary location with every secondary location into one label. */
    private fun JSONObject.locationText(categories: JSONObject?): String {
        val primary = categories?.optString("location").orEmpty().trim()
        val all = categories?.optJSONArray("allLocations").toStringList()
        return (listOf(primary).filter { it.isNotBlank() } + all)
            .distinct()
            .joinToString("; ")
    }

    private companion object {
        val HEADERS = mapOf("Accept" to "application/json")

        /** Lever pages the board; 100 is its default page size and keeps each request small. */
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 3

        /**
         * A board that matches nothing on its first page is read this far only. A trade board
         * almost always shows a target title near the top; a board that shows none is usually
         * off-domain, and reading its remaining pages costs a multi-megabyte download for
         * nothing.
         */
        const val SPARSE_PAGES = 2

        /** Stop paging a board once this many target titles have been seen; more adds nothing. */
        const val MATCHES_PER_BOARD = 12

        /** Stop paging a board once this many rows have been read, bounding the inline payload. */
        const val MAX_BOARD_ROWS = 250

        /** One board may spend at most this long before its remaining pages are skipped. */
        val BOARD_TIMEOUT: Duration = 12.seconds

        /** Target roles, used to keep only the board rows worth showing. */
        val DEFAULT_KEYWORDS = listOf(
            "maintenance", "mechanic", "mechanical", "fitter", "millwright",
            "technician", "operator", "electrician", "welder",
        )
    }
}

/**
 * Curated Lever boards. Lever publishes the full description and every location inline, so
 * these are read whole and filtered locally. The employers kept here are the ones whose live
 * boards carried maintenance, mechanical, fitter or rotating-equipment titles at curation
 * time; a defence-tech board with no trade role (e.g. Palantir) is deliberately excluded so
 * the source spends its pages where the candidate's trades actually are.
 */
internal object LeverBoards {
    val BOARDS: List<LeverBoard> = listOf(
        LeverBoard("shieldai", "Shield AI"),
        // Elk Valley Resources (steelmaking-coal mines, British Columbia): millwright,
        // maintenance-coordinator and automotive/heavy-equipment mechanic trades.
        LeverBoard("evr", "Elk Valley Resources"),
        LeverBoard("sila", "Sila"),
        LeverBoard("hermeus", "Hermeus"),
        LeverBoard("zoox", "Zoox"),
        LeverBoard("hive", "Hive"),
    )
}
