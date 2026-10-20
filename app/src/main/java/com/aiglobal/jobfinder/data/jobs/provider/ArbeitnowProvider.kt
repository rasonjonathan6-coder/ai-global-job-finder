package com.aiglobal.jobfinder.data.jobs.provider

/**
 * Arbeitnow — a free, public European job board API. Its terms ask for attribution,
 * which the app provides through the source badge and the "official posting" link.
 * Server-side search is not supported, so the feed is fetched and filtered locally.
 *
 * The board is paginated (`?page=N`) and its newest-first order is dominated by software and
 * office roles, so a single page rarely carries the industrial trades the candidate targets.
 * The provider therefore reads several pages and pools them: the app is fetching a broad
 * sample either way, and widening the sample is what lets the local relevance filter find the
 * maintenance roles that were sitting a few pages down. [maxPages] bounds the work.
 */
class ArbeitnowProvider(
    private val feed: JsonFeed = HttpJsonFeed,
    private val maxPages: Int = DEFAULT_MAX_PAGES,
) : JobProvider {

    override val id: String = "arbeitnow"
    override val name: String = "Arbeitnow"
    override val homepage: String = "https://www.arbeitnow.com"
    override val kind: ProviderKind = ProviderKind.AGGREGATOR
    override val documentationUrl: String = "https://www.arbeitnow.com/blog/arbeitnow-api"
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        serverSideKeyword = false,
        countryFilter = false,
        pagination = true,
        employmentTypeFilter = false,
        applicationLinks = true,
        submissionEndpoint = false,
    )

    override suspend fun fetch(keywords: List<String>, limit: Int): List<RawJob> {
        val collected = mutableListOf<RawJob>()
        var lastError: Throwable? = null
        var anyAnswered = false
        for (page in 1..maxPages) {
            val json = try {
                feed.objectAt("$BASE_URL?page=$page")
            } catch (t: Throwable) {
                // A page that fails after an earlier one succeeded is not a source failure: the
                // postings already read are real, so stop widening and return them rather than
                // discarding a good sample because a later page was slow or rate-limited.
                lastError = t
                break
            }
            val data = json.optJSONArray("data")
                ?: throw JobProviderException(
                    providerId = id,
                    message = "Arbeitnow returned an unexpected response shape",
                    category = ProviderErrorCategory.INVALID_RESPONSE,
                )
            anyAnswered = true
            if (data.length() == 0) break
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                collected += item.toRawJob()
            }
            if (collected.size >= limit) break
        }
        if (!anyAnswered && lastError != null) {
            throw JobProviderException(
                providerId = id,
                message = "Arbeitnow did not respond",
                cause = lastError,
                category = ProviderErrorClassifier.classify(id, lastError),
            )
        }
        return collected.take(limit)
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

        /** How many pages of the broad sample to pool before the local relevance filter runs. */
        const val DEFAULT_MAX_PAGES = 8
    }
}
