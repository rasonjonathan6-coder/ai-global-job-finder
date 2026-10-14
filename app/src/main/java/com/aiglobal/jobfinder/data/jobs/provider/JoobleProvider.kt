package com.aiglobal.jobfinder.data.jobs.provider

import com.aiglobal.jobfinder.data.model.SalaryPeriod
import org.json.JSONObject

/**
 * Jooble — a worldwide aggregator that indexes national job boards across dozens of
 * countries, which is where visa-sponsored roles are usually advertised. Its API is
 * a JSON POST keyed by an API key the user requests for free, so the provider stays
 * dormant until a key is saved in Settings.
 *
 * Jooble returns a free-text `salary` and `type`, so both go through the shared
 * parsers rather than being trusted as structured values.
 */
class JoobleProvider(
    private val apiKey: () -> String,
    private val feed: JsonFeed = HttpJsonFeed,
) : JobProvider {

    override val id: String = "jooble"
    override val name: String = "Jooble"
    override val homepage: String = "https://jooble.org"
    override val kind: ProviderKind = ProviderKind.AGGREGATOR

    override suspend fun fetch(keywords: List<String>, limit: Int): List<RawJob> {
        val key = apiKey().trim()
        if (key.isEmpty()) {
            throw JobProviderException(id, "Add your Jooble API key in Settings to search this source")
        }
        val body = JSONObject().apply {
            put("keywords", keywords.firstOrNull().orEmpty())
            // Empty location asks Jooble for the best matches worldwide, which is the
            // right default for someone applying from outside the target market.
            put("location", "")
            put("page", "1")
        }
        val json = try {
            feed.objectPost("$BASE_URL/$key", body.toString())
        } catch (t: Throwable) {
            throw JobProviderException(id, "Jooble did not respond", t)
        }
        val jobs = json.optJSONArray("jobs") ?: return emptyList()
        return buildList {
            for (i in 0 until jobs.length()) {
                val item = jobs.optJSONObject(i) ?: continue
                add(item.toRawJob())
            }
        }.take(limit)
    }

    private fun JSONObject.toRawJob(): RawJob {
        val parsed = SalaryParser.parse(optString("salary"))
        return RawJob(
            providerId = id,
            providerName = name,
            externalId = optString("id").ifBlank { optString("link") },
            title = optString("title"),
            company = optString("company"),
            location = optString("location").ifBlank { "Worldwide" },
            descriptionHtml = optString("snippet"),
            url = optString("link"),
            tags = listOfNotNull(optString("source").takeIf { it.isNotBlank() }),
            employmentType = optString("type").ifBlank { null },
            postedAtEpochMillis = IsoDates.epochMillis(optString("updated")),
            isRemote = optString("title").contains("remote", ignoreCase = true) ||
                optString("location").contains("remote", ignoreCase = true),
            salaryMin = parsed.min,
            salaryMax = parsed.max,
            salaryCurrency = parsed.currency,
            salaryPeriod = if (parsed.min != null) parsed.period ?: SalaryPeriod.YEAR else null,
        )
    }

    private companion object {
        const val BASE_URL = "https://jooble.org/api"
    }
}
