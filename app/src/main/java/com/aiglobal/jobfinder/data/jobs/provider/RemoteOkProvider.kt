package com.aiglobal.jobfinder.data.jobs.provider

import com.aiglobal.jobfinder.data.model.SalaryPeriod
import kotlin.coroutines.cancellation.CancellationException

/**
 * RemoteOK — a key-free remote-jobs board. Its API returns a top-level array whose first
 * element is a legal/attribution notice rather than a posting, so that row is skipped. Many
 * postings publish a structured salary, which is kept with its unit inferred from the amount.
 */
class RemoteOkProvider(
    private val feed: JsonFeed = HttpJsonFeed,
) : JobProvider {

    override val id: String = "remoteok"
    override val name: String = "RemoteOK"
    override val homepage: String = "https://remoteok.com"
    override val kind: ProviderKind = ProviderKind.REMOTE_BOARD
    override val documentationUrl: String = "https://remoteok.com/api"
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        // The feed is a full newest-first dump with no keyword parameter.
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
                message = "RemoteOK did not respond",
                cause = t,
                category = ProviderErrorClassifier.classify(id, t),
            )
        }
        return buildList {
            for (i in 0 until array.length()) {
                if (size >= limit) break
                val item = array.optJSONObject(i) ?: continue
                // The first element is RemoteOK's legal notice, not a posting: it carries no
                // position and no id, so it is skipped rather than shown as an empty job.
                if (item.optString("position").isBlank()) continue
                add(item.toRawJob())
            }
        }
    }

    private fun org.json.JSONObject.toRawJob(): RawJob {
        val min = optInt("salary_min").takeIf { it > 0 }
        val max = optInt("salary_max").takeIf { it > 0 }
        return RawJob(
            providerId = id,
            providerName = name,
            externalId = optString("id").ifBlank { optString("slug") },
            title = optString("position"),
            company = optString("company"),
            location = optString("location").ifBlank { "Worldwide" },
            descriptionHtml = optString("description"),
            url = optString("apply_url").ifBlank { optString("url") },
            tags = optJSONArray("tags").toStringList(),
            postedAtEpochMillis = IsoDates.epochMillis(optString("date")),
            isRemote = true,
            salaryMin = min,
            salaryMax = max ?: min,
            salaryCurrency = optString("salary_currency").ifBlank { null },
            salaryPeriod = if (min != null) SalaryPeriod.YEAR else null,
            salaryPeriodInferred = min != null && optString("salary_currency").isBlank(),
        )
    }

    private companion object {
        const val BASE_URL = "https://remoteok.com/api"
    }
}
