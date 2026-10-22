package com.aiglobal.jobfinder.data.jobs.provider

import kotlin.coroutines.cancellation.CancellationException

/**
 * The Muse — a key-free public job API covering large employers worldwide. Each posting
 * carries the employer's own landing page, which is where the application goes.
 *
 * The API documents `location` and `category` filters, but a live check shows neither
 * actually narrows the feed (Canada, Germany and France return the same list, and US roles
 * come back for a Canada query), so both are recorded as ineffective and the feed is read
 * as a broad newest-first sample, paged until the requested limit is reached.
 */
class TheMuseProvider(
    private val feed: JsonFeed = HttpJsonFeed,
) : JobProvider {

    override val id: String = "themuse"
    override val name: String = "The Muse"
    override val homepage: String = "https://www.themuse.com"
    override val kind: ProviderKind = ProviderKind.AGGREGATOR
    override val documentationUrl: String = "https://www.themuse.com/developers/api/v2"
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        serverSideKeyword = false,
        countryFilter = false,
        pagination = true,
        employmentTypeFilter = false,
        applicationLinks = true,
        submissionEndpoint = false,
    )

    override suspend fun fetch(keywords: List<String>, limit: Int): List<RawJob> {
        return buildList {
            var page = 1
            while (size < limit && page <= MAX_PAGES) {
                val url = "$BASE_URL?page=$page"
                val json = try {
                    feed.objectAt(url)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    // A later page failing keeps the pages already read rather than losing all.
                    if (page == 1) {
                        throw JobProviderException(
                            providerId = id,
                            message = "The Muse did not respond",
                            cause = t,
                            category = ProviderErrorClassifier.classify(id, t),
                        )
                    }
                    break
                }
                val results = json.optJSONArray("results") ?: break
                if (results.length() == 0) break
                for (i in 0 until results.length()) {
                    if (size >= limit) break
                    val item = results.optJSONObject(i) ?: continue
                    add(item.toRawJob())
                }
                page++
            }
        }
    }

    private fun org.json.JSONObject.toRawJob(): RawJob {
        val locations = optJSONArray("locations")
        val locationNames = buildList {
            if (locations != null) {
                for (i in 0 until locations.length()) {
                    locations.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }
        val categories = optJSONArray("categories")
        val categoryNames = buildList {
            if (categories != null) {
                for (i in 0 until categories.length()) {
                    categories.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }
        val company = optJSONObject("company")
        return RawJob(
            providerId = id,
            providerName = name,
            externalId = optString("id").ifBlank { optString("short_name") },
            title = optString("name"),
            company = company?.optString("name").orEmpty(),
            location = locationNames.joinToString(", ").ifBlank { "Worldwide" },
            descriptionHtml = optString("contents"),
            url = optJSONObject("refs")?.optString("landing_page").orEmpty(),
            tags = categoryNames + optJSONArray("tags").toStringList(),
            employmentType = optString("type").ifBlank { null },
            postedAtEpochMillis = IsoDates.epochMillis(optString("publication_date")),
            isRemote = locationNames.any { it.contains("remote", ignoreCase = true) } ||
                optString("name").contains("remote", ignoreCase = true),
        )
    }

    private companion object {
        const val BASE_URL = "https://www.themuse.com/api/public/jobs"

        /** The API returns 20 per page; this bounds how many pages one refresh may read. */
        const val MAX_PAGES = 5
    }
}
