package com.aiglobal.jobfinder.data.jobs.provider

import kotlin.coroutines.cancellation.CancellationException

/**
 * JobTech Dev — the open API of the Swedish Public Employment Service (Arbetsförmedlingen),
 * a government feed of the national vacancy database. It is the one new source with a real
 * server-side keyword search, so the candidate's own trade can be asked for directly, and it
 * publishes a stated application deadline and a structured workplace address.
 */
class JobTechDevProvider(
    private val feed: JsonFeed = HttpJsonFeed,
) : JobProvider {

    override val id: String = "jobtechdev"
    override val name: String = "JobTech Dev (Sweden)"
    override val homepage: String = "https://arbetsformedlingen.se/platsbanken"
    override val kind: ProviderKind = ProviderKind.GOVERNMENT
    override val documentationUrl: String = "https://jobtechdev.se/en/products/jobsearch"
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        serverSideKeyword = true,
        countryFilter = false,
        pagination = false,
        employmentTypeFilter = false,
        applicationLinks = true,
        submissionEndpoint = false,
    )

    override suspend fun fetch(keywords: List<String>, limit: Int): List<RawJob> {
        val query = keywords.firstOrNull()?.takeIf { it.isNotBlank() }
        val url = buildString {
            append(BASE_URL)
            append("?limit=").append(limit.coerceIn(1, 100))
            if (query != null) append("&q=").append(java.net.URLEncoder.encode(query, "UTF-8"))
        }
        val json = try {
            feed.objectAt(url)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            throw JobProviderException(
                providerId = id,
                message = "JobTech Dev did not respond",
                cause = t,
                category = ProviderErrorClassifier.classify(id, t),
            )
        }
        val hits = json.optJSONArray("hits")
            ?: throw JobProviderException(
                providerId = id,
                message = "JobTech Dev returned an unexpected response shape",
                category = ProviderErrorCategory.INVALID_RESPONSE,
            )
        return buildList {
            for (i in 0 until hits.length()) {
                val item = hits.optJSONObject(i) ?: continue
                if (item.optString("headline").isBlank()) continue
                add(item.toRawJob())
            }
        }
    }

    private fun org.json.JSONObject.toRawJob(): RawJob {
        val address = optJSONObject("workplace_address")
        val location = buildList {
            address?.optString("municipality")?.takeIf { it.isNotBlank() }?.let { add(it) }
            address?.optString("region")?.takeIf { it.isNotBlank() }?.let { add(it) }
            address?.optString("country")?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(", ")
        return RawJob(
            providerId = id,
            providerName = name,
            externalId = optString("id").ifBlank { optString("webpage_url") },
            title = optString("headline"),
            company = optJSONObject("employer")?.optString("name").orEmpty(),
            location = location.ifBlank { "Sweden" },
            descriptionHtml = optJSONObject("description")?.optString("text").orEmpty(),
            url = optString("webpage_url"),
            tags = listOfNotNull(optJSONObject("occupation")?.optString("label")?.takeIf { it.isNotBlank() }),
            employmentType = optJSONObject("employment_type")?.optString("label")?.takeIf { it.isNotBlank() },
            postedAtEpochMillis = IsoDates.epochMillis(optString("publication_date")),
            expiresAtEpochMillis = IsoDates.epochMillis(optString("application_deadline")),
        )
    }

    private companion object {
        const val BASE_URL = "https://jobsearch.api.jobtechdev.se/search"
    }
}
