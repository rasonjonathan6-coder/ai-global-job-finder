package com.aiglobal.jobfinder.data.jobs.provider

import com.aiglobal.jobfinder.data.model.SalaryPeriod
import kotlin.coroutines.cancellation.CancellationException

/**
 * Landing.jobs — a key-free European tech-jobs feed. It publishes gross salary ranges, a
 * currency, whether relocation is paid, an expiry date and the posting's cities, all of
 * which feed the salary, freshness and eligibility signals directly.
 */
class LandingJobsProvider(
    private val feed: JsonFeed = HttpJsonFeed,
) : JobProvider {

    override val id: String = "landingjobs"
    override val name: String = "Landing.jobs"
    override val homepage: String = "https://landing.jobs"
    override val kind: ProviderKind = ProviderKind.AGGREGATOR
    override val documentationUrl: String = "https://landing.jobs/api/v1/jobs"
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
                message = "Landing.jobs did not respond",
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

    private fun org.json.JSONObject.toRawJob(): RawJob {
        val locations = optJSONArray("locations")
        val cities = buildList {
            if (locations != null) {
                for (i in 0 until locations.length()) {
                    val row = locations.optJSONObject(i) ?: continue
                    val city = row.optString("city").takeIf { it.isNotBlank() }
                    val country = row.optString("country_code").takeIf { it.isNotBlank() }
                    listOfNotNull(city, country).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(", ")) }
                }
            }
        }
        val min = optInt("gross_salary_low").takeIf { it > 0 }
        val max = optInt("gross_salary_high").takeIf { it > 0 }
        val url = optString("url")
        return RawJob(
            providerId = id,
            providerName = name,
            externalId = optString("id").ifBlank { url },
            title = optString("title"),
            // The list API publishes no company field; the employer slug sits in the URL
            // (`https://landing.jobs/at/<company>/<job>`), so it is read from there.
            company = companyFromUrl(url),
            location = cities.joinToString(" · ").ifBlank { if (optBoolean("remote")) "Remote" else "Worldwide" },
            descriptionHtml = optString("role_description"),
            url = url,
            tags = optJSONArray("tags").toStringList(),
            employmentType = optString("type").ifBlank { null },
            postedAtEpochMillis = IsoDates.epochMillis(optString("published_at")),
            expiresAtEpochMillis = IsoDates.epochMillis(optString("expires_at")),
            isRemote = optBoolean("remote"),
            salaryMin = min,
            salaryMax = max ?: min,
            salaryCurrency = optString("currency_code").ifBlank { null },
            salaryPeriod = if (min != null) SalaryPeriod.YEAR else null,
        )
    }

    /** Reads the employer slug out of `/at/<company>/<job>` and humanises it. */
    private fun companyFromUrl(url: String): String {
        val marker = "/at/"
        val start = url.indexOf(marker)
        if (start < 0) return ""
        val rest = url.substring(start + marker.length)
        val slug = rest.substringBefore('/')
        if (slug.isBlank()) return ""
        return slug.split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
    }

    private companion object {
        const val BASE_URL = "https://landing.jobs/api/v1/jobs"
    }
}
