package com.aiglobal.jobfinder.data.jobs.provider

import com.aiglobal.jobfinder.data.model.SalaryPeriod

/**
 * Remotive — a curated remote-jobs board. Its published terms require linking back to
 * the original posting and naming Remotive as the source, and ask for at most a few
 * requests per day; the repository caches the feed and the card always links out to
 * the Remotive URL, so both conditions are met.
 */
class RemotiveProvider(
    private val feed: JsonFeed = HttpJsonFeed,
) : JobProvider {

    override val id: String = "remotive"
    override val name: String = "Remotive"
    override val homepage: String = "https://remotive.com"
    override val kind: ProviderKind = ProviderKind.REMOTE_BOARD
    override val documentationUrl: String = "https://remotive.com/remote-jobs/api"
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        // Remotive documents a `search` parameter, but a live check on the production feed
        // returns the same newest-first list with or without it, so it is recorded as not
        // effective rather than claimed as a working filter.
        serverSideKeyword = false,
        countryFilter = false,
        pagination = false,
        employmentTypeFilter = false,
        applicationLinks = true,
        submissionEndpoint = false,
    )

    override suspend fun fetch(keywords: List<String>, limit: Int): List<RawJob> {
        val query = keywords.firstOrNull()?.takeIf { it.isNotBlank() }
        val url = if (query != null) {
            "$BASE_URL?limit=$limit&search=${java.net.URLEncoder.encode(query, "UTF-8")}"
        } else {
            "$BASE_URL?limit=$limit"
        }
        val json = try {
            feed.objectAt(url)
        } catch (t: Throwable) {
            throw JobProviderException(
                providerId = id,
                message = "Remotive did not respond",
                cause = t,
                category = ProviderErrorClassifier.classify(id, t),
            )
        }
        val jobs = json.optJSONArray("jobs")
            ?: throw JobProviderException(
                providerId = id,
                message = "Remotive returned an unexpected response shape",
                category = ProviderErrorCategory.INVALID_RESPONSE,
            )
        return buildList {
            for (i in 0 until jobs.length()) {
                val item = jobs.optJSONObject(i) ?: continue
                add(item.toRawJob())
            }
        }
    }

    private fun org.json.JSONObject.toRawJob(): RawJob {
        val salaryText = optString("salary")
        val parsed = SalaryParser.parse(salaryText)
        return RawJob(
            providerId = id,
            providerName = name,
            externalId = optString("id").ifBlank { optString("url") },
            title = optString("title"),
            company = optString("company_name"),
            location = optString("candidate_required_location").ifBlank { "Worldwide" },
            descriptionHtml = optString("description"),
            url = optString("url"),
            tags = optJSONArray("tags").toStringList(),
            employmentType = optString("job_type").ifBlank { null },
            postedAtEpochMillis = IsoDates.epochMillis(optString("publication_date")),
            isRemote = true,
            salaryMin = parsed.min,
            salaryMax = parsed.max,
            salaryCurrency = parsed.currency,
            salaryPeriod = parsed.period ?: if (parsed.min != null) SalaryPeriod.YEAR else null,
        )
    }

    private companion object {
        const val BASE_URL = "https://remotive.com/api/remote-jobs"
    }
}
