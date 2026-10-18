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
 * from so salaries stay comparable. Each market can be paged a few times so a single
 * strong market is not truncated at the first page.
 */
class AdzunaProvider(
    private val credentials: () -> AdzunaCredentials,
    private val feed: JsonFeed = HttpJsonFeed,
    private val countries: List<String> = AdzunaCountries.defaults,
    /** Pages requested per market. 1 is the documented default; higher widens one market. */
    private val pagesPerCountry: Int = 1,
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
        val effective = countries.ifEmpty { AdzunaCountries.defaults }
        val perCountry = (limit / effective.size).coerceAtLeast(5)
        val collected = mutableListOf<RawJob>()
        var lastError: Throwable? = null

        for (country in effective) {
            for (page in 1..pagesPerCountry.coerceAtLeast(1)) {
                val url = buildString {
                    append("$BASE_URL/$country/search/$page")
                    append("?app_id=${creds.appId}")
                    append("&app_key=${creds.appKey}")
                    append("&results_per_page=$perCountry")
                    append("&content-type=application/json")
                    if (what != null) append("&what=${java.net.URLEncoder.encode(what, "UTF-8")}")
                }
                val outcome = runCatching { feed.objectAt(url) }
                outcome.onSuccess { json -> collected += json.toRawJobs(country) }
                    .onFailure { lastError = it }
                // Stop paging a market as soon as a page fails or comes back short; asking
                // for an empty page just burns quota.
                val pageSize = outcome.getOrNull()?.optJSONArray("results")?.length() ?: 0
                if (outcome.isFailure || pageSize == 0) break
            }
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

    /**
     * Verifies the saved credentials against Adzuna's real endpoints without touching the
     * feed or inventing anything. It first calls the cheap `/version` endpoint to tell a bad
     * key apart from a bad search, then runs one small real search in the first market that
     * answers. The returned [AdzunaCheckResult] carries a plain-language outcome and, on
     * success, a sample of what Adzuna actually returned so the user can confirm the market
     * is live.
     */
    suspend fun checkConnection(): AdzunaCheckResult {
        val creds = credentials()
        if (!creds.isConfigured) {
            return AdzunaCheckResult(
                status = AdzunaCheckStatus.NOT_CONFIGURED,
                message = "Add your Adzuna app id and key first.",
            )
        }
        val markets = countries.ifEmpty { AdzunaCountries.defaults }
        var lastFailure: AdzunaCheckResult? = null
        for (country in markets) {
            val result = checkMarket(country, creds)
            if (result.isSuccess) return result
            // A rejected key will be rejected by every market, so stop at the first one.
            if (result.status == AdzunaCheckStatus.INVALID_CREDENTIALS) return result
            lastFailure = result
        }
        return lastFailure ?: AdzunaCheckResult(
            status = AdzunaCheckStatus.INVALID_RESPONSE,
            message = "Adzuna returned no usable response.",
        )
    }

    private suspend fun checkMarket(country: String, creds: AdzunaCredentials): AdzunaCheckResult {
        val label = AdzunaCountries.labelFor(country)
        return try {
            val version = feed.objectAt("$VERSION_URL?app_id=${creds.appId}&app_key=${creds.appKey}")
            val apiVersion = version.optString("api_version").ifBlank { "?" }
            val body = feed.objectAt(
                "$BASE_URL/$country/search/1?app_id=${creds.appId}&app_key=${creds.appKey}" +
                    "&results_per_page=1&content-type=application/json",
            )
            val results = body.optJSONArray("results")
            val total = body.optInt("count", results?.length() ?: 0)
            val sample = results?.optJSONObject(0)?.optString("title")?.takeIf { it.isNotBlank() }
            AdzunaCheckResult(
                status = AdzunaCheckStatus.OK,
                market = country,
                sampleTitle = sample,
                totalResults = total,
                message = buildString {
                    append("Adzuna accepted the credentials (API v$apiVersion). ")
                    append(
                        if (sample != null) {
                            "Market $label returned ads, e.g. \"$sample\"."
                        } else {
                            "Market $label is reachable but returned no ads right now."
                        },
                    )
                },
            )
        } catch (t: Throwable) {
            classifyCheck(t, country)
        }
    }

    private fun classifyCheck(t: Throwable, country: String): AdzunaCheckResult {
        val label = AdzunaCountries.labelFor(country)
        if (t is org.json.JSONException) {
            return AdzunaCheckResult(
                AdzunaCheckStatus.INVALID_RESPONSE,
                country,
                message = "Adzuna replied, but its response could not be understood.",
            )
        }
        return when (ProviderErrorClassifier.classify(id, t)) {
            ProviderErrorCategory.AUTHENTICATION -> AdzunaCheckResult(
                AdzunaCheckStatus.INVALID_CREDENTIALS, country,
                message = "Adzuna rejected the credentials. Check the app id and key, then save them again.",
            )
            ProviderErrorCategory.RATE_LIMITED -> AdzunaCheckResult(
                AdzunaCheckStatus.RATE_LIMITED, country,
                message = "Adzuna is rate limiting requests. Wait a minute, then test again.",
            )
            ProviderErrorCategory.TIMEOUT -> AdzunaCheckResult(
                AdzunaCheckStatus.TIMEOUT, country,
                message = "Adzuna did not answer in time. Try again.",
            )
            ProviderErrorCategory.NETWORK -> AdzunaCheckResult(
                AdzunaCheckStatus.NETWORK, country,
                message = "Adzuna could not be reached. Check your internet connection.",
            )
            ProviderErrorCategory.INVALID_RESPONSE -> AdzunaCheckResult(
                AdzunaCheckStatus.INVALID_RESPONSE, country,
                message = "Adzuna replied, but its response could not be understood.",
            )
            ProviderErrorCategory.HTTP_ERROR -> AdzunaCheckResult(
                AdzunaCheckStatus.SERVICE_UNAVAILABLE, country,
                message = "Adzuna answered with an error status for $label: ${t.message}",
            )
            ProviderErrorCategory.NOT_CONFIGURED -> AdzunaCheckResult(
                AdzunaCheckStatus.NOT_CONFIGURED, country,
                message = "Add your Adzuna app id and key first.",
            )
            ProviderErrorCategory.UNKNOWN -> AdzunaCheckResult(
                AdzunaCheckStatus.NETWORK, country,
                message = "The request to Adzuna failed: ${t.javaClass.simpleName}.",
            )
        }
    }

    private fun org.json.JSONObject.toRawJobs(country: String): List<RawJob> {
        val results = optJSONArray("results") ?: return emptyList()
        val currency = AdzunaCountries.currencyByCountry[country]
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
                        // Adzuna's structured fields carry no unit; only the free-text period is
                        // trusted, and an impossible unit (an annual figure labelled hourly) is
                        // flagged rather than shown.
                        salaryPeriod = if (min != null && !parsed.unitUncertain) parsed.period ?: SalaryPeriod.YEAR else null,
                        salaryPeriodInferred = parsed.periodInferred,
                        salaryUnitUncertain = parsed.unitUncertain,
                    ),
                )
            }
        }
    }

    private companion object {
        const val BASE_URL = "https://api.adzuna.com/v1/api/jobs"
        const val VERSION_URL = "https://api.adzuna.com/v1/api/version"
    }
}

/** Adzuna API credentials, entered by the user in Settings. */
data class AdzunaCredentials(
    val appId: String = "",
    val appKey: String = "",
) {
    val isConfigured: Boolean get() = appId.isNotBlank() && appKey.isNotBlank()
}

/** How an Adzuna connection test ended. Coarse and explicit so the UI can be specific. */
enum class AdzunaCheckStatus {
    OK,
    NOT_CONFIGURED,
    INVALID_CREDENTIALS,
    RATE_LIMITED,
    TIMEOUT,
    NETWORK,
    INVALID_RESPONSE,
    SERVICE_UNAVAILABLE,
}

/**
 * The outcome of a real Adzuna connection test. On success it carries what Adzuna actually
 * returned (the market, a sample ad title and the reported total), so the user can tell a
 * live key from a plausible-looking failure. No posting is ever fabricated: an empty market
 * is reported as empty, not filled in.
 */
data class AdzunaCheckResult(
    val status: AdzunaCheckStatus,
    val market: String = "",
    val sampleTitle: String? = null,
    val totalResults: Int = 0,
    val message: String = "",
) {
    val isSuccess: Boolean get() = status == AdzunaCheckStatus.OK
}

/**
 * The Adzuna markets the app knows how to query, and the currency each one reports salaries
 * in. Kept in one place so the provider, the settings screen and the country picker cannot
 * drift apart. The defaults are the markets most likely to sponsor a Madagascar-based
 * applicant; the user can narrow or widen the set in Profile → Job sources.
 */
object AdzunaCountries {

    /** Country code → display label. Order is the display order. */
    val supported: List<Pair<String, String>> = listOf(
        "gb" to "United Kingdom",
        "ca" to "Canada",
        "de" to "Germany",
        "fr" to "France",
        "nl" to "Netherlands",
        "us" to "United States",
        "au" to "Australia",
        "nz" to "New Zealand",
        "at" to "Austria",
        "be" to "Belgium",
        "ch" to "Switzerland",
        "es" to "Spain",
        "it" to "Italy",
        "pl" to "Poland",
        "sg" to "Singapore",
        "za" to "South Africa",
        "br" to "Brazil",
        "mx" to "Mexico",
        "in" to "India",
    )

    /** The markets searched when the user has not chosen any. */
    val defaults: List<String> = listOf("gb", "ca", "de", "fr", "nl", "us", "au")

    /** Adzuna salaries are stated in the local currency of the market queried. */
    val currencyByCountry: Map<String, String> = mapOf(
        "gb" to "GBP", "us" to "USD", "ca" to "CAD", "de" to "EUR", "fr" to "EUR",
        "nl" to "EUR", "at" to "EUR", "be" to "EUR", "ch" to "CHF", "es" to "EUR",
        "it" to "EUR", "au" to "AUD", "nz" to "NZD", "pl" to "PLN", "sg" to "SGD",
        "za" to "ZAR", "br" to "BRL", "mx" to "MXN", "in" to "INR",
    )

    fun isSupported(code: String): Boolean = supported.any { it.first == code }

    fun labelFor(code: String): String = supported.firstOrNull { it.first == code }?.second ?: code.uppercase()
}
