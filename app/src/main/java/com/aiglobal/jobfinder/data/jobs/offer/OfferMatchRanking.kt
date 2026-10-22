package com.aiglobal.jobfinder.data.jobs.offer

import com.aiglobal.jobfinder.data.jobs.freshness.Freshness
import com.aiglobal.jobfinder.data.model.BestOfferFilter
import com.aiglobal.jobfinder.data.model.BestOfferSort
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.RankedOffer
import com.aiglobal.jobfinder.data.submissions.CanonicalUrl

/**
 * Turns the live feed into the ranked list the "My best offers" screen shows.
 *
 * The pipeline is: deduplicate, score every posting with [OfferMatchEngine], drop the
 * postings the active filter excludes, sort, then number the survivors. It never fetches
 * anything and never invents a posting, a date or a score; it only orders what the app
 * already discovered.
 *
 * Two ordering rules are worth naming:
 *
 *  - **A disqualifier sinks.** A posting with a hard stop (local-only, expired, passed
 *    deadline) is placed below every posting that does not have one, whatever its raw score,
 *    so a high compatibility number can never float it to the top.
 *  - **Verified freshness is the tie-breaker, not the headline.** Within the same score, a
 *    posting whose source stated a recent date sorts above one whose date is unknown, but an
 *    undated posting is never pushed ahead of a higher-scoring dated one.
 */
class OfferMatchRanking(
    private val engine: OfferMatchEngine = OfferMatchEngine(),
) {

    fun rank(
        jobs: List<Job>,
        profile: CandidateProfile,
        now: Long = System.currentTimeMillis(),
        sort: BestOfferSort = BestOfferSort.COMPATIBILITY,
        filter: BestOfferFilter = BestOfferFilter.ALL,
        limit: Int = DEFAULT_LIMIT,
    ): List<RankedOffer> {
        val ranked = dedupe(jobs)
            .map { job -> RankedOffer(job = job, rank = 0, match = engine.assess(job, profile, now), availability = job.availability) }
            .filter { filter.matches(it, now) }
            .sortedWith(comparator(sort))
            .take(limit)
            .mapIndexed { index, offer -> offer.copy(rank = index + 1) }
        return ranked
    }

    /** The number of postings that pass the filter, before the display limit is applied. */
    fun count(
        jobs: List<Job>,
        profile: CandidateProfile,
        now: Long = System.currentTimeMillis(),
        filter: BestOfferFilter = BestOfferFilter.ALL,
    ): Int = dedupe(jobs)
        .map { job -> RankedOffer(job = job, rank = 0, match = engine.assess(job, profile, now), availability = job.availability) }
        .count { filter.matches(it, now) }

    private fun comparator(sort: BestOfferSort): Comparator<RankedOffer> = when (sort) {
        BestOfferSort.COMPATIBILITY -> compareByDescending<RankedOffer> { !it.match.disqualified }
            .thenByDescending { it.match.score }
            .thenByDescending { it.match.hasVerifiedDate }
            .thenByDescending { freshnessRank(it.match.freshness) }
            .thenBy { it.job.id }
        BestOfferSort.FRESHNESS -> compareByDescending<RankedOffer> { !it.match.disqualified }
            .thenByDescending { it.job.postedAtEpochMillis ?: Long.MIN_VALUE }
            .thenByDescending { it.match.score }
            .thenBy { it.job.id }
    }

    private fun freshnessRank(freshness: Freshness): Int = when (freshness) {
        Freshness.FRESH -> 4
        Freshness.RECENT -> 3
        Freshness.AGING -> 2
        Freshness.STALE -> 1
        Freshness.UNKNOWN -> 0
    }

    /**
     * Collapses the same vacancy listed by more than one source. The canonical application URL
     * is the identity; when two postings share one, the one with the more verified date wins,
     * and a tie is broken by the higher compatibility score, then by id so the order is stable.
     */
    private fun dedupe(jobs: List<Job>): List<Job> {
        val byKey = LinkedHashMap<String, Job>()
        jobs.forEach { job ->
            val key = dedupeKey(job)
            val existing = byKey[key]
            byKey[key] = if (existing == null || preferredOver(job, existing)) job else existing
        }
        return byKey.values.toList()
    }

    private fun dedupeKey(job: Job): String {
        val canonical = CanonicalUrl.of(job.sourceUrl)
        if (canonical.isNotBlank()) return canonical
        return "${job.providerId}|${job.title.lowercase().trim()}|${job.company.lowercase().trim()}|${job.country.lowercase().trim()}"
    }

    private fun preferredOver(candidate: Job, existing: Job): Boolean {
        val candidateDated = candidate.postedAtEpochMillis != null
        val existingDated = existing.postedAtEpochMillis != null
        if (candidateDated != existingDated) return candidateDated
        if (candidate.matchScore != existing.matchScore) return candidate.matchScore > existing.matchScore
        return candidate.id < existing.id
    }

    companion object {
        /** How many ranked offers the screen shows by default. */
        const val DEFAULT_LIMIT = 50
    }
}
