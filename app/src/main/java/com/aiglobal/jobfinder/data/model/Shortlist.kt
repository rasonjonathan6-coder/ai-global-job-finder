package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable

/**
 * How ready one posting is for the candidate to actually apply, once the CV match, the
 * Madagascar eligibility verdict and the user's expat priorities have all been considered.
 *
 * The bands are ordered weakest → strongest so they compare directly. They are deliberately
 * never merged into [Job.matchScore]: a role can match the CV perfectly and still be
 * impossible to apply for from Madagascar, and the shortlist has to say so.
 */
enum class ApplicationReadiness(val label: String, val shortLabel: String, val emoji: String) {
    BLOCKED("Not open to you", "Blocked", "⛔"),
    NEEDS_REVIEW("Verify before applying", "Verify", "⚠️"),
    PREPARE_FIRST("Close — close the gaps", "Prepare", "🧩"),
    READY("Ready to apply", "Ready", "✅"),
    PRIORITY("Top priority", "Priority", "🏆"),
    ;

    /** True for the bands the user should act on now, strongest first. */
    val isActionable: Boolean get() = this == READY || this == PRIORITY

    companion object {
        fun fromScore(score: Int): ApplicationReadiness = when {
            score >= 85 -> PRIORITY
            score >= 70 -> READY
            score >= 50 -> PREPARE_FIRST
            score >= 30 -> NEEDS_REVIEW
            else -> BLOCKED
        }
    }
}

/** Whether a readiness finding came from the posting itself or from the app's inference. */
enum class BlockerKind { OFFICIAL, HEURISTIC, PROFILE }

/**
 * One concrete reason a posting is not yet fully ready. Every blocker is either a fact
 * quoted from the posting ([BlockerKind.OFFICIAL]), an inference the app makes
 * ([BlockerKind.HEURISTIC]), or something about the user's own profile ([BlockerKind.PROFILE]).
 *
 * [points] is how much of the readiness score this single issue is currently withholding,
 * so the list can be read as a checklist ordered by impact.
 */
@Immutable
data class ReadinessBlocker(
    val label: String,
    val detail: String,
    val kind: BlockerKind,
    val points: Int,
) {
    val isOfficial: Boolean get() = kind == BlockerKind.OFFICIAL
    val isProfileGap: Boolean get() = kind == BlockerKind.PROFILE
}

/**
 * A posting judged fit for the expat shortlist, with the full reasoning behind its rank.
 *
 * [readinessScore] answers "can I actually apply, and how soon?", which is a different
 * question from "does my CV fit?" ([matchScore]) and from "does the employer document the
 * benefits I want?" ([expatScore]).
 */
@Immutable
data class ShortlistEntry(
    val job: Job,
    val matchScore: Int,
    val expatScore: Int,
    val readinessScore: Int,
    val readiness: ApplicationReadiness,
    val blockers: List<ReadinessBlocker>,
    val highlights: List<String>,
    val generatedAtEpochMillis: Long,
) {
    /** The employer states a route that accepts an applicant already in Madagascar. */
    val hasOfficialRoute: Boolean
        get() = job.eligibilityReasons.any { reason ->
            reason.official && OFFICIAL_ROUTE_LABELS.any { reason.label.contains(it, ignoreCase = true) }
        }

    /** What is holding this posting back, hardest-hitting first. */
    val orderedBlockers: List<ReadinessBlocker> get() = blockers.sortedByDescending { it.points }

    val blockerSummary: String
        get() = when (blockers.size) {
            0 -> "Nothing is holding this back — the route and your profile both line up."
            1 -> "1 thing to sort: ${blockers.first().label.lowercase()}."
            else -> "${blockers.size} things to sort, starting with ${orderedBlockers.first().label.lowercase()}."
        }

    companion object {
        /** Labels in [EligibilityReason] that mark a stated, official route in. */
        private val OFFICIAL_ROUTE_LABELS = listOf(
            "visa sponsorship",
            "relocation support",
            "open to international",
            "remote or worldwide",
        )
    }
}

/**
 * The whole shortlist plus the numbers that describe it. Kept as one immutable snapshot so
 * the screen renders in a single pass and the dashboard tiles can never disagree with the
 * list they sit above.
 */
@Immutable
data class ShortlistState(
    val isLoading: Boolean = true,
    val entries: List<ShortlistEntry> = emptyList(),
    val totalJobs: Int = 0,
    val isLive: Boolean = true,
    val sourceNote: String = "",
    val generatedAtEpochMillis: Long = 0L,
) {
    val priorityCount: Int get() = entries.count { it.readiness == ApplicationReadiness.PRIORITY }
    val readyCount: Int get() = entries.count { it.readiness == ApplicationReadiness.READY }
    val verifyCount: Int get() = entries.count { it.readiness == ApplicationReadiness.NEEDS_REVIEW }
    val blockedCount: Int get() = entries.count { it.readiness == ApplicationReadiness.BLOCKED }

    /** Postings with a stated official route in, the strongest signal on the screen. */
    val officialRouteCount: Int get() = entries.count { it.hasOfficialRoute }

    /** Everything the user could act on right now. */
    val actionableCount: Int get() = entries.count { it.readiness.isActionable }

    /** Profile gaps that recur across postings, so the user sees what to fix once. */
    val recurringProfileGaps: List<ReadinessBlocker>
        get() = entries.flatMap { entry -> entry.blockers.filter { it.isProfileGap } }
            .groupBy { it.label }
            .map { (_, group) -> group.maxByOrNull { it.points } ?: group.first() }
            .sortedByDescending { it.points }

    val isEmpty: Boolean get() = entries.isEmpty()
}
