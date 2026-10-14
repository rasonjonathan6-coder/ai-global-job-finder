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

    override suspend fun fetch(keywords: List<String>, limit: Int): List<RawJob> {
        val url = "$BASE_URL?limit=${limit.coerceIn(1, 100)}"
        val json = try {
            feed.objectAt(url)
        } catch (t: Throwable) {
            throw JobProviderException(id, "Himalayas did not respond", t)
        }
        val jobs = json.optJSONArray("jobs") ?: return emptyList()
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
