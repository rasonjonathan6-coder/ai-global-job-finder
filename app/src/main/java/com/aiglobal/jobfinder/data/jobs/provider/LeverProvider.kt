package com.aiglobal.jobfinder.data.jobs.provider

import org.json.JSONObject

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
                list(board)
            } catch (t: Throwable) {
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

    private suspend fun list(board: LeverBoard): List<JSONObject> {
        val jobs = mutableListOf<JSONObject>()
        for (page in 0 until MAX_PAGES) {
            val array = feed.arrayAt(board.pageUrl(skip = page * PAGE_SIZE, pageSize = PAGE_SIZE), HEADERS)
            if (array.length() == 0) break
            jobs += (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            if (array.length() < PAGE_SIZE) break
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

        /** Target roles, used to keep only the board rows worth showing. */
        val DEFAULT_KEYWORDS = listOf(
            "maintenance", "mechanic", "mechanical", "fitter", "millwright",
            "technician", "operator", "electrician", "welder",
        )
    }
}

/** Curated Lever boards of large industrial and defence-tech employers. */
internal object LeverBoards {
    val BOARDS: List<LeverBoard> = listOf(
        LeverBoard("shieldai", "Shield AI"),
        LeverBoard("sila", "Sila"),
    )
}
