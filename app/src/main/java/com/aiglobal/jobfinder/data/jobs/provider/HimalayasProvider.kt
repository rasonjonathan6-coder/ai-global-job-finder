package com.aiglobal.jobfinder.data.jobs.provider

import com.aiglobal.jobfinder.data.model.SalaryPeriod

/**
 * Himalayas — a remote-jobs board with a public, key-free JSON feed and structured
 * salary fields. Its terms ask that the source be named and the posting linked, which
 * the card does through the source badge and the "official posting" link.
 *
 * The feed carries `locationRestrictions`, which is exactly the signal the eligibility
 * engine needs: a posting restricted to one country is not a realistic target from
 * Madagascar, while an empty restriction list means the employer accepts remote
 * applicants from anywhere.
 */
class HimalayasProvider(
    private val feed: JsonFeed = HttpJsonFeed,
) : JobProvider {

    override val id: String = "himalayas"
    override val name: String = "Himalayas"
    override val homepage: String = "https://himalayas.app"
    override val kind: ProviderKind = ProviderKind.REMOTE_BOARD
    override val documentationUrl: String = "https://himalayas.app/jobs/api"
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        // Himalayas documents no keyword parameter on its public jobs endpoint; the feed is
        // a broad newest-first sample, so it is filtered locally.
        serverSideKeyword = false,
        countryFilter = false,
        pagination = false,
        employmentTypeFilter = false,
        applicationLinks = true,
        submissionEndpoint = false,
    )

    override suspend fun fetch(keywords: List<String>, limit: Int): List<RawJob> {
        val url = "$BASE_URL?limit=${limit.coerceIn(1, 100)}"
        val json = try {
            feed.objectAt(url)
        } catch (t: Throwable) {
            throw JobProviderException(
                providerId = id,
                message = "Himalayas did not respond",
                cause = t,
                category = ProviderErrorClassifier.classify(id, t),
            )
        }
        // The feed always carries a "jobs" array, even when empty. A body without one is
        // an unexpected shape, not an empty result, so the two are not confused.
        val jobs = json.optJSONArray("jobs")
            ?: throw JobProviderException(
                providerId = id,
                message = "Himalayas returned an unexpected response shape",
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
        val restrictions = optJSONArray("locationRestrictions").toStringList()
        val min = optInt("minSalary").takeIf { it > 0 }
        val max = optInt("maxSalary").takeIf { it > 0 }
        return RawJob(
            providerId = id,
            providerName = name,
            externalId = optString("guid").ifBlank { optString("applicationLink") },
            title = optString("title"),
            company = optString("companyName"),
            // An empty restriction list means "hire from anywhere", which is the
            // realistic case for a Madagascar-based applicant.
            location = restrictions.joinToString(", ").ifBlank { "Worldwide" },
            descriptionHtml = optString("description").ifBlank { optString("excerpt") },
            url = optString("applicationLink").ifBlank { optString("guid") },
            tags = optJSONArray("categories").toStringList() +
                optJSONArray("parentCategories").toStringList() +
                optJSONArray("seniority").toStringList(),
            employmentType = optString("employmentType").ifBlank { null },
            postedAtEpochMillis = optLong("pubDate").takeIf { it > 0 }?.times(1000),
            isRemote = true,
            salaryMin = min,
            salaryMax = max ?: min,
            salaryCurrency = optString("currency").ifBlank { null },
            salaryPeriod = periodOf(optString("salaryPeriod")) ?: if (min != null) SalaryPeriod.YEAR else null,
        )
    }

    private fun periodOf(raw: String): SalaryPeriod? = when (raw.lowercase()) {
        "annual", "annually", "year", "yearly" -> SalaryPeriod.YEAR
        "monthly", "month" -> SalaryPeriod.MONTH
        "daily", "day" -> SalaryPeriod.DAY
        "hourly", "hour" -> SalaryPeriod.HOUR
        else -> null
    }

    private companion object {
        const val BASE_URL = "https://himalayas.app/jobs/api"
    }
}
