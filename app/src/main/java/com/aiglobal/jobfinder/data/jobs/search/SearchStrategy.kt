package com.aiglobal.jobfinder.data.jobs.search

import androidx.compose.runtime.Immutable
import com.aiglobal.jobfinder.data.jobs.provider.JobProvider
import com.aiglobal.jobfinder.data.model.ProviderDiagnostic

/**
 * How one source is searched. [serverSideSearch] is true only when the source's API really
 * narrows the feed by the keyword, so the app can be honest that the other sources return a
 * broad sample that is filtered locally.
 */
@Immutable
data class ProviderSearchPlan(
    val providerId: String,
    val providerName: String,
    val keywordSets: List<List<String>>,
    val serverSideSearch: Boolean,
) {
    /** True when the source is fetched once and filtered locally against the target roles. */
    val broadSample: Boolean get() = !serverSideSearch
}

/** One row of the coverage report: what a source did for this search. */
@Immutable
data class SearchCoverageSource(
    val providerId: String,
    val providerName: String,
    val targeted: Boolean,
    /** Postings this source returned that matched a target role. */
    val relevantCount: Int,
    /** Postings this source returned in total. */
    val totalCount: Int,
)

/**
 * An honest account of the search that was just run: which sources can narrow by keyword,
 * which only return a broad sample, and how many of the postings actually matched a target
 * role. The UI reads this so it never presents a broad remote feed as a targeted search.
 */
@Immutable
data class SearchCoverage(
    val sources: List<SearchCoverageSource> = emptyList(),
    val totalRelevant: Int = 0,
    val totalRetrieved: Int = 0,
) {
    val targetedSourceCount: Int get() = sources.count { it.targeted }
    val broadSampleSourceCount: Int get() = sources.count { !it.targeted }

    /** True when at least one source could actually narrow the search by keyword. */
    val hasTargetedSource: Boolean get() = targetedSourceCount > 0

    /** True when at least one relevant posting was found. */
    val hasRelevantResults: Boolean get() = totalRelevant > 0

    /**
     * A short, plain statement of what this search was. It says "broad sample" rather than
     * "search" when no source supports keyword narrowing, so the user is not misled.
     */
    val summary: String
        get() = when {
            totalRetrieved == 0 -> "No postings were retrieved."
            targetedSourceCount == 0 && totalRelevant == 0 ->
                "Broad sample from ${sources.size} sources; none matched your target roles."
            targetedSourceCount == 0 ->
                "Broad sample from ${sources.size} sources; $totalRelevant matched your target roles."
            broadSampleSourceCount == 0 ->
                "Targeted search across $targetedSourceCount sources; $totalRelevant relevant."
            else ->
                "Targeted search on $targetedSourceCount source(s), broad sample from " +
                    "$broadSampleSourceCount; $totalRelevant relevant."
        }
}

/**
 * Turns the candidate's target roles and each provider's real capabilities into a concrete
 * fetch plan. A provider that can filter server-side is asked for the target roles; one
 * that cannot is fetched once and its results are filtered locally by [RoleRelevance].
 */
object SearchStrategy {

    /** The keyword plan for a provider, or a single empty set when it cannot search. */
    fun planFor(provider: JobProvider, profileTitles: List<String>): ProviderSearchPlan {
        val caps = provider.capabilities
        val keywordSets = if (caps.serverSideKeyword) {
            TargetSearch.keywordPlan(profileTitles).map { listOf(it) }
        } else {
            listOf(emptyList())
        }
        return ProviderSearchPlan(
            providerId = provider.id,
            providerName = provider.name,
            keywordSets = keywordSets,
            serverSideSearch = caps.serverSideKeyword,
        )
    }

    /**
     * Builds the coverage report from the diagnostics of the last refresh plus, per source,
     * how many of the returned postings matched a target role.
     */
    fun coverageFrom(
        diagnostics: List<ProviderDiagnostic>,
        relevantByProvider: Map<String, Int>,
        totalByProvider: Map<String, Int>,
    ): SearchCoverage {
        val rows = diagnostics.map { diag ->
            SearchCoverageSource(
                providerId = diag.providerId,
                providerName = diag.name,
                targeted = diag.serverSideSearch,
                relevantCount = relevantByProvider[diag.providerId] ?: 0,
                totalCount = totalByProvider[diag.providerId] ?: diag.validJobCount,
            )
        }
        return SearchCoverage(
            sources = rows,
            totalRelevant = rows.sumOf { it.relevantCount },
            totalRetrieved = rows.sumOf { it.totalCount },
        )
    }
}
