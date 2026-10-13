package com.aiglobal.jobfinder.data.jobs.provider

/**
 * Arbeitnow — a free, public European job board API. Its terms ask for attribution,
 * which the app provides through the source badge and the "official posting" link.
 * Server-side search is not supported, so the feed is fetched and filtered locally.
 */
class ArbeitnowProvider(
    private val feed: JsonFeed = HttpJsonFeed,
) : JobProvider {

    override val id: String = "arbeitnow"
    override val name: String = "Arbeitnow"
    override val homepage: String = "https://www.arbeitnow.com"
    override val kind: ProviderKind = ProviderKind.AGGREGATOR

    override suspend fun fetch(keywords: List<String>, limit: Int): List<RawJob> {
        val json = try {
            feed.objectAt("$BASE_URL?page=1")
        } catch (t: Throwable) {
            throw JobProviderException(id, "Arbeitnow did not respond", t)
        }
        val data = json.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                add(item.toRawJob())
            }
        }.take(limit)
    }

    private fun org.json.JSONObject.toRawJob(): RawJob {
        val slug = optString("slug")
        val jobTypes = optJSONArray("job_types").toStringList()
        val remote = optBoolean("remote", false)
        val location = optString("location").ifBlank { if (remote) "Remote" else "" }
        return RawJob(
            providerId = id,
            providerName = name,
            externalId = slug.ifBlank { optString("url") },
            title = optString("title"),
            company = optString("company_name"),
            location = location,
            descriptionHtml = optString("description"),
            url = optString("url"),
            tags = optJSONArray("tags").toStringList() + jobTypes,
            employmentType = jobTypes.firstOrNull(),
            postedAtEpochMillis = optLong("created_at").takeIf { it > 0 }?.times(1000),
            isRemote = remote,
        )
    }

    private companion object {
        const val BASE_URL = "https://arbeitnow.com/api/job-board-api"
    }
}
