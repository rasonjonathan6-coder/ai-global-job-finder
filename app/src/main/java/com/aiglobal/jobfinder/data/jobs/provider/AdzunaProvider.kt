package com.aiglobal.jobfinder.data.jobs.provider

import com.aiglobal.jobfinder.data.model.SalaryPeriod

/**
 * Adzuna — a commercial aggregator covering national job markets. It is the only
 * source here that reaches beyond remote boards into ordinary local vacancies, which
 * is where visa-sponsored roles actually live. It requires a free API app id and key,
 * so the provider stays dormant until the user enters credentials in Settings.
 *
 * Each configured country is queried in its own request because Adzuna's country is a
 * path segment, and every posting is tagged with the currency of the market it came
 * from so salaries stay comparable.
 */
class AdzunaProvider(
    private val credentials: () -> AdzunaCredentials,
    private val feed: JsonFeed = HttpJsonFeed,
    private val countries: List<String> = DEFAULT_COUNTRIES,
) : JobProvider {

    override val id: String = "adzuna"
    override val name: String = "Adzuna"
    override val homepage: String = "https://www.adzuna.com"
    override val kind: ProviderKind = ProviderKind.AGGREGATOR
    override val documentationUrl: String = "https://developer.adzuna.com/docs/search"
    override val requiresCredentials: Boolean = true
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        // Adzuna's /search endpoint accepts `what` (keywords) and `where`/`country`, and it
        // pages, so it is the one source that can genuinely run a targeted, country-scoped
        // search. It returns the advertiser's own application URL.
        serverSideKeyword = true,
        countryFilter = true,
        pagination = true,
        employmentTypeFilter = false,
        applicationLinks = true,
        submissionEndpoint = false,
    )

    override suspend fun fetch(keywords: List<String>, limit: Int): List<RawJob> {
        val creds = credentials()
        if (!creds.isConfigured) {
            throw JobProviderException(
                providerId = id,
                message = "Add your Adzuna app id and key in Settings to search this source",
                category = ProviderErrorCategory.NOT_CONFIGURED,
            )
        }
        val what = keywords.firstOrNull()?.takeIf { it.isNotBlank() }
        val perCountry = (limit / countries.size).coerceAtLeast(5)
        val collected = mutableListOf<RawJob>()
        var lastError: Throwable? = null

        for (country in countries) {
            val url = buildString {
                append("$BASE_URL/$country/search/1")
                append("?app_id=${creds.appId}")
                append("&app_key=${creds.appKey}")
                append("&results_per_page=$perCountry")
                append("&content-type=application/json")
                if (what != null) append("&what=${java.net.URLEncoder.encode(what, "UTF-8")}")
            }
            runCatching { feed.objectAt(url) }
                .onSuccess { json -> collected += json.toRawJobs(country) }
                .onFailure { lastError = it }
        }

        // Only surface an error when every country failed, so one throttled market
        // does not hide the postings the others returned.
        if (collected.isEmpty() && lastError != null) {
            throw JobProviderException(
                providerId = id,
                message = "Adzuna did not respond",
                cause = lastError,
                category = ProviderErrorClassifier.classify(id, lastError),
            )
        }
        return collected.take(limit)
    }

    private fun org.json.JSONObject.toRawJobs(country: String): List<RawJob> {
        val results = optJSONArray("results") ?: return emptyList()
        val currency = CURRENCY_BY_COUNTRY[country]
        return buildList {
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val description = item.optString("description")
                // Adzuna's structured salary fields are often blank; the amount is
                // usually stated in the description, so parse it as a fallback.
                val parsed = SalaryParser.parse(description)
                val min = item.optDouble("salary_min").takeIf { it > 0 }?.toInt() ?: parsed.min
                val max = item.optDouble("salary_max").takeIf { it > 0 }?.toInt() ?: parsed.max
                add(
                    RawJob(
                        providerId = id,
                        providerName = name,
                        externalId = item.optString("id").ifBlank { item.optString("redirect_url") },
                        title = item.optString("title"),
                        company = item.optJSONObject("company")?.optString("display_name").orEmpty(),
                        location = item.optJSONObject("location")?.optString("display_name").orEmpty(),
                        descriptionHtml = description,
                        url = item.optString("redirect_url"),
                        tags = listOfNotNull(item.optJSONObject("category")?.optString("label")?.takeIf { it.isNotBlank() }),
                        employmentType = item.optString("contract_time").ifBlank { item.optString("contract_type") }.ifBlank { null },
                        postedAtEpochMillis = IsoDates.epochMillis(item.optString("created")),
                        isRemote = item.optString("title").contains("remote", ignoreCase = true),
                        salaryMin = min,
                        salaryMax = max,
                        salaryCurrency = if (min != null) currency ?: parsed.currency else null,
                        salaryPeriod = if (min != null) parsed.period ?: SalaryPeriod.YEAR else null,
                    ),
                )
            }
        }
    }

    private companion object {
        const val BASE_URL = "https://api.adzuna.com/v1/api/jobs"

        /** Markets most likely to sponsor a Madagascar-based applicant. */
        val DEFAULT_COUNTRIES = listOf("gb", "ca", "de", "fr", "nl", "us", "au")

        /** Adzuna salaries are stated in the local currency of the market queried. */
        val CURRENCY_BY_COUNTRY = mapOf(
            "gb" to "GBP", "us" to "USD", "ca" to "CAD", "de" to "EUR", "fr" to "EUR",
            "nl" to "EUR", "at" to "EUR", "be" to "EUR", "ch" to "CHF", "es" to "EUR",
            "it" to "EUR", "au" to "AUD", "nz" to "NZD", "pl" to "PLN", "sg" to "SGD",
            "za" to "ZAR", "br" to "BRL", "mx" to "MXN", "in" to "INR",
        )
    }
}

/** Adzuna API credentials, entered by the user in Settings. */
data class AdzunaCredentials(
    val appId: String = "",
    val appKey: String = "",
) {
    val isConfigured: Boolean get() = appId.isNotBlank() && appKey.isNotBlank()
}
