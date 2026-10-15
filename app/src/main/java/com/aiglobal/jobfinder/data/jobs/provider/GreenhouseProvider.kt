package com.aiglobal.jobfinder.data.jobs.provider

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/** One employer careers site hosted on Greenhouse, addressed by its board token. */
data class GreenhouseBoard(
    /** Greenhouse board token, e.g. "redwoodmaterials". */
    val token: String,
    /** Employer name shown on the card. */
    val label: String,
) {
    val listUrl: String get() = "https://boards-api.greenhouse.io/v1/boards/$token/jobs"
    fun detailUrl(id: String): String = "https://boards-api.greenhouse.io/v1/boards/$token/jobs/$id"
}

/**
 * Greenhouse careers sites of industrial and energy employers — Redwood Materials,
 * Lucid Motors and KoBold Metals. These are [ProviderKind.EMPLOYER_BOARD] sources, so the
 * posting is the employer's own and applies directly.
 *
 * Greenhouse's public board API has no server-side keyword search: the list is fetched and
 * filtered locally. The list row omits the description, so a bounded, concurrent set of
 * per-posting detail calls reads the country and the job text the matching engine needs.
 */
class GreenhouseProvider(
    private val feed: JsonFeed = HttpJsonFeed,
    private val boards: List<GreenhouseBoard> = GreenhouseBoards.BOARDS,
) : JobProvider {

    override val id: String = "greenhouse"
    override val name: String = "Industrial career sites"
    override val homepage: String = "https://boards.greenhouse.io"
    override val kind: ProviderKind = ProviderKind.EMPLOYER_BOARD
    override val documentationUrl: String = "https://developers.greenhouse.io/job-board.html"
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        serverSideKeyword = false,
        countryFilter = false,
        pagination = false,
        employmentTypeFilter = false,
        applicationLinks = true,
        submissionEndpoint = false,
    )

    override suspend fun fetch(keywords: List<String>, limit: Int): List<RawJob> {
        val collected = LinkedHashMap<String, RawJob>()
        var lastError: Throwable? = null
        var anyAnswered = false

        for (board in boards) {
            val rows = try {
                list(board)
            } catch (t: Throwable) {
                lastError = t
                continue
            }
            anyAnswered = true
            val ids = rows.mapNotNull { it.optString("id").takeIf { id -> id.isNotBlank() } }
                .take(DETAILS_PER_BOARD)
            for (job in detailsFor(board, ids)) {
                collected.putIfAbsent(job.externalId, job)
            }
            if (collected.size >= limit) break
        }

        if (!anyAnswered && lastError != null) {
            throw JobProviderException(
                providerId = id,
                message = "The industrial career sites did not respond",
                cause = lastError,
                category = ProviderErrorClassifier.classify(id, lastError),
            )
        }
        return collected.values.take(limit)
    }

    private suspend fun list(board: GreenhouseBoard): List<JSONObject> {
        val json = feed.objectAt(board.listUrl, HEADERS)
        val jobs = json.optJSONArray("jobs") ?: return emptyList()
        return (0 until jobs.length()).mapNotNull { jobs.optJSONObject(it) }
    }

    private suspend fun detailsFor(board: GreenhouseBoard, ids: List<String>): List<RawJob> = coroutineScope {
        ids.map { id ->
            async {
                runCatching { feed.objectAt(board.detailUrl(id), HEADERS) }
                    .getOrNull()
                    ?.let { info -> info.toRawJob(board) }
            }
        }.awaitAll().filterNotNull()
    }

    private fun JSONObject.toRawJob(board: GreenhouseBoard): RawJob? {
        val title = optString("title").trim()
        if (title.isEmpty()) return null

        val description = optString("content")
        if (description.isBlank()) return null

        val location = optJSONObject("location")?.optString("name").orEmpty().trim()
        if (location.isEmpty()) return null

        val company = optString("company_name").trim().ifBlank { board.label }
        val url = optString("absolute_url")
        val posted = IsoDates.epochMillis(optString("first_published"))
            ?: IsoDates.epochMillis(optString("updated_at"))

        return RawJob(
            providerId = this@GreenhouseProvider.id,
            providerName = this@GreenhouseProvider.name,
            externalId = "${board.token}-${optString("id")}",
            title = title,
            company = company,
            location = location,
            descriptionHtml = description,
            url = url,
            postedAtEpochMillis = posted,
            isRemote = location.contains("remote", ignoreCase = true),
        )
    }

    private companion object {
        const val DETAILS_PER_BOARD = 6

        val HEADERS = mapOf("Accept" to "application/json")
    }
}

/** Curated Greenhouse boards of industrial and energy employers, verified to answer. */
internal object GreenhouseBoards {
    val BOARDS: List<GreenhouseBoard> = listOf(
        GreenhouseBoard("redwoodmaterials", "Redwood Materials"),
        GreenhouseBoard("lucidmotors", "Lucid Motors"),
        GreenhouseBoard("koboldmetals", "KoBold Metals"),
    )
}
