package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable

/**
 * The labels a ranked posting can carry. Each one is only emitted when the evidence behind
 * it exists — a "visa sponsorship" label needs the employer's stated sponsorship, a
 * "salary disclosed" label needs a real figure. [positive] drives the colour in the UI so a
 * caution never looks like a selling point.
 */
enum class RankingLabel(val label: String, val emoji: String, val positive: Boolean) {
    TOP_PRIORITY("Top priority", "🏆", true),
    READY_TO_APPLY("Ready to apply", "✅", true),
    VISA_SPONSORSHIP("Visa sponsorship", "🛂", true),
    RELOCATION_CONFIRMED("Relocation support", "📦", true),
    ACCOMMODATION_CONFIRMED("Accommodation", "🏠", true),
    FLIGHTS_CONFIRMED("Return flights", "✈️", true),
    SALARY_DISCLOSED("Salary disclosed", "💰", true),
    STRONG_PACKAGE("Strong package", "⭐", true),
    VERIFY_ELIGIBILITY("Verify eligibility", "⚠️", false),
    BENEFITS_NOT_DISCLOSED("Benefits not stated", "❔", false),
    LINK_TO_CHECK("Check the link", "🔗", false),
    SCAM_CAUTION("Scam caution", "🛡️", false),
    NOT_OPEN_TO_YOU("Not open to you", "⛔", false),
}

/**
 * One posting placed in the explainable expatriate ranking.
 *
 * [finalScore] folds together how ready the posting is to apply for ([readinessScore], from
 * the shortlist) and how good its documented expatriate package is ([opportunity]). The two
 * ingredients stay on the record beside the total, so the rank can always be traced. A
 * disqualified posting — ineligible, scam-flagged or with a dead link — is capped and can
 * never sit at the top, however attractive its package looks.
 */
@Immutable
data class ExpatRankedJob(
    val job: Job,
    /** 1-based position in the ranking, strongest first. */
    val rank: Int,
    val finalScore: Int,
    val readinessScore: Int,
    val readiness: ApplicationReadiness,
    val opportunity: ExpatOpportunity,
    val labels: List<RankingLabel>,
    /** Short, evidence-backed reasons for the rank, strongest first. */
    val reasons: List<String>,
)

/** The whole ranked feed plus the counts the UI summarises, as one immutable snapshot. */
@Immutable
data class ExpatRankingState(
    val isLoading: Boolean = true,
    val ranked: List<ExpatRankedJob> = emptyList(),
    val totalJobs: Int = 0,
    val generatedAtEpochMillis: Long = 0L,
) {
    val actionableCount: Int get() = ranked.count { it.readiness.isActionable }
    val disqualifiedCount: Int get() = ranked.count { it.opportunity.disqualified }
    val topOpportunities: List<ExpatRankedJob> get() = ranked.take(3)

    companion object {
        val EMPTY = ExpatRankingState()
    }
}
