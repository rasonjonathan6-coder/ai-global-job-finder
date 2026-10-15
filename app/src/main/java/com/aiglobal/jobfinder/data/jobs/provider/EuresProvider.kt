package com.aiglobal.jobfinder.data.jobs.provider

import org.json.JSONArray
import org.json.JSONObject

/**
 * EURES — the official European Employment Services job portal run by the European
 * Labour Authority. It aggregates vacancies from the public employment services of 31
 * European countries plus employer postings, and is the canonical official source for
 * "can I work in Europe" searches.
 *
 * The public search API returns the full description inline, so no per-vacancy request is
 * needed. Country and language come from the location map and the posting's own language,
 * which is exactly what the eligibility engine needs to judge whether a Madagascar-based
 * applicant can realistically apply.
 *
 * The endpoint narrows by keyword but exposes no per-country query, so the app sends one
 * target title per request and the results are pooled.
 */
class EuresProvider(
    private val feed: JsonFeed = HttpJsonFeed,
    /** Target role titles, sent one at a time so each source query is a real search. */
    private val searchKeywords: List<String> = DEFAULT_KEYWORDS,
) : JobProvider {

    override val id: String = "eures"
    override val name: String = "EURES"
    override val homepage: String = "https://europa.eu/eures"
    override val kind: ProviderKind = ProviderKind.GOVERNMENT
    override val documentationUrl: String = "https://europa.eu/eures"
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        // The endpoint takes a keyword list, but the app sends one target title per query
        // so each response is a genuine search rather than a broad sample.
        serverSideKeyword = true,
        countryFilter = false,
        pagination = true,
        employmentTypeFilter = false,
        applicationLinks = true,
        submissionEndpoint = false,
    )

    override suspend fun fetch(keywords: List<String>, limit: Int): List<RawJob> {
        val queries = (if (keywords.isNotEmpty()) keywords else searchKeywords)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_QUERIES)

        val perQuery = ((limit.coerceAtLeast(1) + queries.size - 1) / queries.size)
            .coerceIn(MIN_PER_QUERY, MAX_PER_QUERY)

        val collected = LinkedHashMap<String, RawJob>()
        var lastError: Throwable? = null
        var anyAnswered = false

        for (query in queries) {
            val json = try {
                feed.objectPost(BASE_URL, bodyFor(query, perQuery), HEADERS)
            } catch (t: Throwable) {
                lastError = t
                continue
            }
            val jvs = json.optJSONArray("jvs") ?: continue
            anyAnswered = true
            for (i in 0 until jvs.length()) {
                val item = jvs.optJSONObject(i) ?: continue
                val job = item.toRawJob() ?: continue
                collected.putIfAbsent(job.externalId, job)
                if (collected.size >= limit) break
            }
            if (collected.size >= limit) break
        }

        if (!anyAnswered && lastError != null) {
            throw JobProviderException(
                providerId = id,
                message = "EURES did not respond",
                cause = lastError,
                category = ProviderErrorClassifier.classify(id, lastError),
            )
        }
        return collected.values.take(limit)
    }

    private fun bodyFor(keyword: String, perPage: Int): String = JSONObject()
        .put("resultsPerPage", perPage)
        .put("page", 1)
        .put("sortSearch", "MOST_RECENT")
        .put("keywords", JSONArray().put(JSONObject().put("keyword", keyword).put("specificSearchCode", "EVERYWHERE")))
        .put("publicationPeriod", JSONObject.NULL)
        .put("occupationUris", JSONArray())
        .put("skillUris", JSONArray())
        .put("requiredExperienceCodes", JSONArray())
        .put("positionScheduleCodes", JSONArray())
        .put("sectorCodes", JSONArray())
        .put("educationAndQualificationLevelCodes", JSONArray())
        .put("positionOfferingCodes", JSONArray())
        .put("locationCodes", JSONArray())
        .put("euresFlagCodes", JSONArray())
        .put("otherBenefitsCodes", JSONArray())
        .put("requiredLanguages", JSONArray())
        .put("minNumberPost", JSONObject.NULL)
        .put("sessionId", "ai-global-job-finder")
        .put("requestLanguage", "en")
        .toString()

    private fun JSONObject.toRawJob(): RawJob? {
        val id = optString("id").ifBlank { return null }
        val title = optString("title").trim()
        if (title.isEmpty()) return null

        val description = optString("description")
        if (description.isBlank()) return null

        val location = locationText()
        if (location.isBlank()) return null

        val employer = optJSONObject("employer")
        val company = employer?.optString("name").orEmpty().trim().ifBlank { "Unknown employer" }

        return RawJob(
            providerId = this@EuresProvider.id,
            providerName = this@EuresProvider.name,
            externalId = id,
            title = title,
            company = company,
            location = location,
            descriptionHtml = description,
            url = "$VACANCY_URL/$id",
            tags = jobCategoriesCodes(),
            employmentType = positionScheduleCodes().firstOrNull(),
            postedAtEpochMillis = optLong("creationDate").takeIf { it > 0 },
            isRemote = false,
        )
    }

    /** Joins the country codes and their regions into one "Belgium (BE234)" location. */
    private fun JSONObject.locationText(): String {
        val map = optJSONObject("locationMap") ?: return ""
        return buildList {
            for (countryCode in map.keys()) {
                val country = EuresCountryNames.nameFor(countryCode) ?: countryCode
                val regions = map.optJSONArray(countryCode).toStringList()
                val suffix = regions.take(2).joinToString(", ")
                add(if (suffix.isBlank()) country else "$country ($suffix)")
            }
        }.joinToString("; ")
    }

    private fun JSONObject.jobCategoriesCodes(): List<String> =
        optJSONArray("jobCategoriesCodes").toStringList().map { uri ->
            uri.substringAfterLast('/')
        }

    private fun JSONObject.positionScheduleCodes(): List<String> =
        optJSONArray("positionScheduleCodes").toStringList()

    private companion object {
        const val BASE_URL = "https://europa.eu/eures/api/jv-searchengine/public/jv-search/search"
        const val VACANCY_URL = "https://europa.eu/eures/portal/jv-se/vacancy"

        /** One request per target title keeps each query a real, narrow search. */
        const val MAX_QUERIES = 4
        const val MIN_PER_QUERY = 20
        const val MAX_PER_QUERY = 50

        val HEADERS = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        )

        /** The candidate's target roles, so the source returns trade/industrial vacancies. */
        val DEFAULT_KEYWORDS = listOf(
            "maintenance technician",
            "industrial mechanic",
            "millwright",
            "heavy equipment operator",
        )
    }
}

/**
 * ISO 3166-1 alpha-2 codes used in the EURES location map, mapped to the canonical
 * country names the rest of the app resolves against. EURES covers the EEA plus
 * Switzerland, so the set is the EU-27, EFTA and the UK.
 */
internal object EuresCountryNames {
    private val byCode: Map<String, String> = mapOf(
        "AT" to "Austria", "BE" to "Belgium", "BG" to "Bulgaria", "HR" to "Croatia",
        "CY" to "Cyprus", "CZ" to "Czechia", "DK" to "Denmark", "EE" to "Estonia",
        "FI" to "Finland", "FR" to "France", "DE" to "Germany", "GR" to "Greece",
        "HU" to "Hungary", "IE" to "Ireland", "IT" to "Italy", "LV" to "Latvia",
        "LT" to "Lithuania", "LU" to "Luxembourg", "MT" to "Malta", "NL" to "Netherlands",
        "PL" to "Poland", "PT" to "Portugal", "RO" to "Romania", "SK" to "Slovakia",
        "SI" to "Slovenia", "ES" to "Spain", "SE" to "Sweden",
        "IS" to "Iceland", "LI" to "Liechtenstein", "NO" to "Norway", "CH" to "Switzerland",
        "GB" to "United Kingdom",
    )

    fun nameFor(code: String): String? = byCode[code.uppercase()]
}
