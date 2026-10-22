package com.aiglobal.jobfinder.data.jobs.provider

import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException

/** One employer careers site hosted on Ashby, addressed by its board name. */
data class AshbyBoard(
    /** Ashby job-board name, e.g. "formenergy". */
    val board: String,
    /** Employer name shown on the card. */
    val label: String,
) {
    val listUrl: String get() = "https://api.ashbyhq.com/posting-api/job-board/$board"
}

/**
 * Ashby careers sites of industrial and energy employers — Form Energy and Sila. These are
 * [ProviderKind.EMPLOYER_BOARD] sources, so the posting is the employer's own and applies
 * directly.
 *
 * Ashby's public posting API returns the full description and every location inline, so a
 * single request per employer is enough — no per-posting detail call is needed.
 */
class AshbyProvider(
    private val feed: JsonFeed = HttpJsonFeed,
    private val boards: List<AshbyBoard> = AshbyBoards.BOARDS,
) : JobProvider {

    override val id: String = "ashby"
    override val name: String = "Advanced manufacturing sites"
    override val homepage: String = "https://www.ashbyhq.com"
    override val kind: ProviderKind = ProviderKind.EMPLOYER_BOARD
    override val documentationUrl: String = "https://developers.ashbyhq.com/docs/public-job-posting-api"
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
            val jobs = try {
                list(board)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastError = t
                continue
            }
            anyAnswered = true
            for (item in jobs) {
                val job = item.toRawJob(board) ?: continue
                collected.putIfAbsent(job.externalId, job)
            }
            if (collected.size >= limit) break
        }

        if (!anyAnswered && lastError != null) {
            throw JobProviderException(
                providerId = id,
                message = "The manufacturing career sites did not respond",
                cause = lastError,
                category = ProviderErrorClassifier.classify(id, lastError),
            )
        }
        return collected.values.take(limit)
    }

    private suspend fun list(board: AshbyBoard): List<JSONObject> {
        val json = feed.objectAt(board.listUrl, HEADERS)
        val jobs = json.optJSONArray("jobs") ?: return emptyList()
        return (0 until jobs.length()).mapNotNull { jobs.optJSONObject(it) }
    }

    private fun JSONObject.toRawJob(board: AshbyBoard): RawJob? {
        // "Don't see the job you're looking for?" placeholders are not real postings.
        if (optBoolean("isListed", true).not()) return null

        val title = optString("title").trim()
        if (title.isEmpty()) return null

        val description = optString("descriptionPlain").ifBlank { optString("descriptionHtml") }
        if (description.isBlank()) return null

        val location = locationText()
        if (location.isBlank()) return null

        val employmentType = optString("employmentType").takeIf { it.isNotBlank() }
        val remote = optBoolean("isRemote", false) ||
            optString("workplaceType").equals("Remote", ignoreCase = true)

        return RawJob(
            providerId = this@AshbyProvider.id,
            providerName = this@AshbyProvider.name,
            externalId = "${board.board}-${optString("id")}",
            title = title,
            company = board.label,
            location = location,
            descriptionHtml = description,
            url = optString("jobUrl").ifBlank { optString("applyUrl") },
            tags = listOfNotNull(
                optString("department").takeIf { it.isNotBlank() && it != "No Department" },
                optString("team").takeIf { it.isNotBlank() },
            ),
            employmentType = employmentType,
            postedAtEpochMillis = IsoDates.epochMillis(optString("publishedAt")),
            isRemote = remote,
        )
    }

    /** Joins the primary location with any secondary locations into one label. */
    private fun JSONObject.locationText(): String {
        val primary = optString("location").trim()
        val secondary = optJSONArray("secondaryLocations")
        val extras = (0 until (secondary?.length() ?: 0)).mapNotNull { index ->
            secondary?.optJSONObject(index)?.optString("location")?.trim()?.takeIf { it.isNotBlank() }
        }
        return (listOf(primary).filter { it.isNotBlank() } + extras)
            .distinct()
            .joinToString("; ")
    }

    private companion object {
        val HEADERS = mapOf("Accept" to "application/json")
    }
}

/** Curated Ashby boards of industrial and advanced-manufacturing employers. */
internal object AshbyBoards {
    val BOARDS: List<AshbyBoard> = listOf(
        AshbyBoard("formenergy", "Form Energy"),
        // Critical-minerals developer (Moab/Houston): process, piping and electrical trades
        // (project electrician, mechatronics engineer, maintenance).
        AshbyBoard("marianaminerals", "Mariana Minerals"),
        AshbyBoard("twelve", "Twelve"),
    )
}
