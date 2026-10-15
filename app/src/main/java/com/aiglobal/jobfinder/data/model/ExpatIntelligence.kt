package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable

/**
 * How one destination country looks across the whole feed: how many postings are actually
 * open to an applicant in Madagascar, the strongest readiness found there, and whether any
 * employer states an official route in. It is a summary of the postings the app has, not a
 * claim about the country itself.
 */
@Immutable
data class CountryOpportunity(
    val country: String,
    val countryFlag: String,
    /** Postings in this country that are open to applicants from Madagascar. */
    val actionableCount: Int,
    /** Total postings seen in this country, including ones with no route in. */
    val totalCount: Int,
    /** Highest readiness score found among this country's actionable postings. */
    val bestReadiness: Int,
    /** True when at least one posting states an official route in (visa or relocation). */
    val hasOfficialRoute: Boolean,
) {
    val openShare: Int get() = if (totalCount == 0) 0 else (actionableCount * 100 / totalCount)
}

/**
 * A whole-feed digest for the expat job seeker: where the routes in actually are, what keeps
 * recurring, and what to do first. Every figure is counted from the postings currently held,
 * so it changes as the feed refreshes rather than being a fixed editorial claim.
 */
@Immutable
data class ExpatIntelligence(
    val totalJobs: Int,
    val actionableCount: Int,
    val priorityCount: Int,
    val officialRouteCount: Int,
    val countries: List<CountryOpportunity>,
    /** Profile gaps that recur across many postings, so the user sees what to fix once. */
    val recurringProfileGaps: List<ReadinessBlocker>,
    /** The strongest postings to act on first, by readiness. */
    val topOpportunities: List<ShortlistEntry>,
    /** One honest sentence on where the best odds currently are. */
    val summary: String,
    val generatedAtEpochMillis: Long,
) {
    val hasRoutes: Boolean get() = actionableCount > 0
    val topCountry: CountryOpportunity? get() = countries.firstOrNull()
    val isEmpty: Boolean get() = totalJobs == 0
}
