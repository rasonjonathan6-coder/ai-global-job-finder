package com.aiglobal.jobfinder.data.assistant

import com.aiglobal.jobfinder.data.model.CountryOpportunity
import com.aiglobal.jobfinder.data.model.ExpatIntelligence
import com.aiglobal.jobfinder.data.model.ShortlistEntry
import com.aiglobal.jobfinder.data.model.ShortlistState

/**
 * Rolls the per-posting shortlist up into a whole-feed digest: which countries actually
 * have routes in for an applicant in Madagascar, which profile gaps keep recurring, and
 * which few postings are worth acting on first.
 *
 * Like the rest of the pipeline, it only counts what it can see. If the feed holds no
 * postings open to Madagascar, the summary says so rather than implying opportunity.
 */
class ExpatIntelligenceEngine(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun build(shortlist: ShortlistState, limit: Int = DEFAULT_COUNTRY_LIMIT): ExpatIntelligence {
        val entries = shortlist.entries
        val actionable = entries.filter { it.job.applyableFromMadagascar }

        val countries = actionable
            .groupBy { it.job.country }
            .map { (country, group) ->
                CountryOpportunity(
                    country = country,
                    countryFlag = group.first().job.countryFlag,
                    actionableCount = group.size,
                    totalCount = entries.count { it.job.country == country },
                    bestReadiness = group.maxOf { it.readinessScore },
                    hasOfficialRoute = group.any { it.hasOfficialRoute },
                )
            }
            .sortedWith(
                compareByDescending<CountryOpportunity> { it.bestReadiness }
                    .thenByDescending { it.actionableCount }
                    .thenBy { it.country },
            )
            .take(limit)

        val topOpportunities = entries
            .sortedByDescending { it.readinessScore }
            .take(TOP_LIMIT)

        return ExpatIntelligence(
            totalJobs = shortlist.totalJobs.takeIf { it > 0 } ?: entries.size,
            actionableCount = actionable.size,
            priorityCount = entries.count { it.readiness.isActionable },
            officialRouteCount = entries.count { it.hasOfficialRoute },
            countries = countries,
            recurringProfileGaps = shortlist.recurringProfileGaps.take(GAP_LIMIT),
            topOpportunities = topOpportunities,
            summary = summaryFor(actionable.size, countries, shortlist),
            generatedAtEpochMillis = clock(),
        )
    }

    private fun summaryFor(
        actionableCount: Int,
        countries: List<CountryOpportunity>,
        shortlist: ShortlistState,
    ): String = when {
        shortlist.entries.isEmpty() ->
            "No postings are loaded yet. Refresh the feed to see where you can apply from Madagascar."
        actionableCount == 0 ->
            "None of the ${shortlist.entries.size} postings seen so far states a route in from Madagascar. " +
                "That is a gap in the listings, not a verdict on you — refresh for new ones."
        countries.isEmpty() -> "$actionableCount postings look open to you right now."
        else -> {
            val top = countries.first()
            val official = countries.count { it.hasOfficialRoute }
            buildString {
                append("$actionableCount postings look open to you from Madagascar, across ")
                append("${countries.size} ${if (countries.size == 1) "country" else "countries"}. ")
                append("${top.countryFlag} ${top.country} leads with a best readiness of ${top.bestReadiness}%.")
                if (official > 0) {
                    append(" $official ${if (official == 1) "country states" else "countries state"} an official route in.")
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_COUNTRY_LIMIT = 8
        const val TOP_LIMIT = 3
        const val GAP_LIMIT = 3
    }
}
