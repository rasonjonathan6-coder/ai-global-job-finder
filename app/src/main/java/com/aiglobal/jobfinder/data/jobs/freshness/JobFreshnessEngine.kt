package com.aiglobal.jobfinder.data.jobs.freshness

import androidx.compose.runtime.Immutable
import com.aiglobal.jobfinder.data.model.Job

/**
 * How current a posting is, derived only from evidence the source gave us.
 *
 * The distinction that matters: [Job.postedAtEpochMillis] is the employer's publication
 * date and may be absent, while [Job.retrievedAtEpochMillis] is when this app last pulled
 * the listing. A posting with no publication date is [Unknown], never "fresh" — the app
 * does not invent a date to fill the gap. [Stale] is claimed only when a real publication
 * date is older than [staleAfterDays]; a listing the source keeps returning without a date
 * is at worst [Aging], because we cannot prove it expired.
 */
enum class Freshness { FRESH, RECENT, AGING, STALE, UNKNOWN }

/** The classification plus the human sentence explaining it, so the UI never re-derives it. */
@Immutable
data class FreshnessAssessment(
    val freshness: Freshness,
    val label: String,
    val detail: String,
)

/**
 * Classifies postings by age. Pure and clock-injected so it runs on the JVM and is testable
 * without a device. It reads dates, never writes them: a missing publication date stays
 * missing and is reported as [Freshness.UNKNOWN].
 */
class JobFreshnessEngine(
    private val staleAfterDays: Int = DEFAULT_STALE_AFTER_DAYS,
) {

    fun assess(job: Job, now: Long = System.currentTimeMillis()): FreshnessAssessment {
        val posted = job.postedAtEpochMillis
            // A future date is a source clock problem, not a fresh posting; treat it as no date.
            ?.takeIf { it in 1..now }

        if (posted == null) {
            return FreshnessAssessment(
                freshness = Freshness.UNKNOWN,
                label = "Date not stated",
                detail = "The source did not provide a publication date, so this app cannot say how recent it is. " +
                    "Open the listing to check.",
            )
        }

        val ageDays = ((now - posted) / DAY_MS).toInt()
        val freshness = when {
            ageDays < 2 -> Freshness.FRESH
            ageDays < 14 -> Freshness.RECENT
            ageDays <= staleAfterDays -> Freshness.AGING
            else -> Freshness.STALE
        }

        val label = when (freshness) {
            Freshness.FRESH -> "Published recently"
            Freshness.RECENT -> "Published this fortnight"
            Freshness.AGING -> "Published a while ago"
            Freshness.STALE -> "Likely filled"
            Freshness.UNKNOWN -> "Date not stated"
        }

        val retrievedSuffix = job.retrievedAtEpochMillis?.let { " Retrieved from the source ${relativeDays(it, now)}." }.orEmpty()
        val detail = when (freshness) {
            Freshness.FRESH -> "The source published this within the last two days.$retrievedSuffix"
            Freshness.RECENT -> "The source published this within the last two weeks.$retrievedSuffix"
            Freshness.AGING -> "The source published this $ageDays days ago. Confirm it is still open before investing time.$retrievedSuffix"
            Freshness.STALE -> "The source published this $ageDays days ago, older than the $staleAfterDays-day window. It may already be filled.$retrievedSuffix"
            Freshness.UNKNOWN -> "The source did not state a publication date."
        }

        return FreshnessAssessment(freshness, label, detail)
    }

    private fun relativeDays(epochMillis: Long, now: Long): String {
        val days = ((now - epochMillis).coerceAtLeast(0) / DAY_MS).toInt()
        return when {
            days <= 0 -> "today"
            days == 1 -> "yesterday"
            else -> "$days days ago"
        }
    }

    companion object {
        private const val DAY_MS = 86_400_000L
        const val DEFAULT_STALE_AFTER_DAYS = 120
    }
}
