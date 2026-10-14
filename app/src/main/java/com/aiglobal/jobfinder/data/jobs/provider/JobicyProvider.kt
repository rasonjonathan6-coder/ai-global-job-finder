package com.aiglobal.jobfinder.data.jobs.provider

import com.aiglobal.jobfinder.data.model.SalaryPeriod

/**
 * Jobicy — a remote-jobs API with structured salary fields and an explicit geo field,
 * which makes it the most reliable of the three for salary and location data.
 */
class JobicyProvider(
    private val feed: JsonFeed = HttpJsonFeed,
) : JobProvider {

    override val id: String = "jobicy"
    override val name: String = "Jobicy"
    override val homepage: String = "https://jobicy.com"
    override val kind: ProviderKind = ProviderKind.REMOTE_BOARD
    override val documentationUrl: String = "https://jobicy.com/jobs-rss-feed"

    override suspend fun fetch(keywords: List<String>, limit: Int): List<RawJob> {
        val url = "$BASE_URL?count=${limit.coerceIn(1, 50)}"
        val json = try {
            feed.objectAt(url)
        } catch (t: Throwable) {
            throw JobProviderException(
                providerId = id,
                message = "Jobicy did not respond",
                cause = t,
                category = ProviderErrorClassifier.classify(id, t),
            )
        }
        val jobs = json.optJSONArray("jobs")
            ?: throw JobProviderException(
                providerId = id,
                message = "Jobicy returned an unexpected response shape",
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
        val min = optInt("salaryMin").takeIf { it > 0 }
        val max = optInt("salaryMax").takeIf { it > 0 }
        return RawJob(
            providerId = id,
            providerName = name,
            externalId = optString("id").ifBlank { optString("jobSlug") },
            title = optString("jobTitle"),
            company = optString("companyName"),
            location = optString("jobGeo").ifBlank { "Worldwide" },
            descriptionHtml = optString("jobDescription").ifBlank { optString("jobExcerpt") },
            url = optString("url"),
            tags = optJSONArray("jobIndustry").toStringList() + optJSONArray("jobType").toStringList(),
            employmentType = optJSONArray("jobType").toStringList().firstOrNull(),
            postedAtEpochMillis = IsoDates.epochMillis(optString("pubDate")),
            isRemote = true,
            salaryMin = min,
            salaryMax = max ?: min,
            salaryCurrency = optString("salaryCurrency").ifBlank { null },
            salaryPeriod = periodOf(optString("salaryPeriod")) ?: if (min != null) SalaryPeriod.YEAR else null,
        )
    }

    private fun periodOf(raw: String): SalaryPeriod? = when (raw.lowercase()) {
        "yearly", "year", "annual", "annually" -> SalaryPeriod.YEAR
        "monthly", "month" -> SalaryPeriod.MONTH
        "daily", "day" -> SalaryPeriod.DAY
        "hourly", "hour" -> SalaryPeriod.HOUR
        else -> null
    }

    private companion object {
        const val BASE_URL = "https://jobicy.com/api/v2/remote-jobs"
    }
}
