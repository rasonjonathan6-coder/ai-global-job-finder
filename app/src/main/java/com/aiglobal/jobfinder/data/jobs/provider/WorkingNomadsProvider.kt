package com.aiglobal.jobfinder.data.jobs.provider

import kotlin.coroutines.cancellation.CancellationException

/**
 * Working Nomads — a key-free remote-jobs feed. It publishes the regions each posting is
 * open to (often a list such as "Europe, LATAM, APAC"), which is a genuine eligibility
 * signal for an applicant outside those regions, so the field is kept as published.
 */
class WorkingNomadsProvider(
    private val feed: JsonFeed = HttpJsonFeed,
) : JobProvider {

    override val id: String = "workingnomads"
    override val name: String = "Working Nomads"
    override val homepage: String = "https://www.workingnomads.com"
    override val kind: ProviderKind = ProviderKind.REMOTE_BOARD
    override val documentationUrl: String = "https://www.workingnomads.com/api"
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        serverSideKeyword = false,
        countryFilter = false,
        pagination = false,
        employmentTypeFilter = false,
        applicationLinks = true,
        submissionEndpoint = false,
    )

    override suspend fun fetch(keywords: List<String>, limit: Int): List<RawJob> {
        val array = try {
            feed.arrayAt(BASE_URL)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            throw JobProviderException(
                providerId = id,
                message = "Working Nomads did not respond",
                cause = t,
                category = ProviderErrorClassifier.classify(id, t),
            )
        }
        return buildList {
            for (i in 0 until array.length()) {
                if (size >= limit) break
                val item = array.optJSONObject(i) ?: continue
                if (item.optString("title").isBlank()) continue
                add(item.toRawJob())
            }
        }
    }

    private fun org.json.JSONObject.toRawJob(): RawJob = RawJob(
        providerId = id,
        providerName = name,
        externalId = optString("url").ifBlank { optString("title") },
        title = optString("title"),
        company = optString("company_name"),
        location = optString("location").ifBlank { "Worldwide" },
        descriptionHtml = optString("description"),
        url = optString("url"),
        tags = optJSONArray("tags").toStringList() +
            listOfNotNull(optString("category_name").takeIf { it.isNotBlank() }),
        postedAtEpochMillis = IsoDates.epochMillis(optString("pub_date")),
        isRemote = true,
    )

    private companion object {
        const val BASE_URL = "https://www.workingnomads.com/api/exposed_jobs/"
    }
}
