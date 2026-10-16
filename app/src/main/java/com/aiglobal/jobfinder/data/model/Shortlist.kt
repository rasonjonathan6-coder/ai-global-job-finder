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
        /** Lowest score in the top band. Shared so the dashboard and the bands cannot disagree. */
        const val PRIORITY_MIN = 85

        /** Lowest score in the ready band. */
        const val READY_MIN = 70

        fun fromScore(score: Int): ApplicationReadiness = when {
            score >= PRIORITY_MIN -> PRIORITY
            score >= READY_MIN -> READY
            score >= 50 -> PREPARE_FIRST
            score >= 30 -> NEEDS_REVIEW
            else -> BLOCKED
        }
    }
}

/** Whether a readiness finding came from the posting itself or from the app's inference. */
enum class BlockerKind { OFFICIAL, HEURISTIC, PROFILE }

/**
 * The single action that moves a posting forward, so a card can tell the user what to do
 * rather than only how it scored. Derived from the hardest-hitting blocker, never stored,
 * so it can never drift from the blockers the card shows beside it.
 */
enum class NextStepAction { APPLY, REVIEW_ELIGIBILITY, CONFIRM_BENEFITS, COMPLETE_PROFILE, TAILOR_CV, RUN_SCAM_CHECK, OPEN_JOB }

/**
 * One concrete next action for a posting. [detail] is written so it reads as advice the user
 * can act on today, and it is always honest about whose side the gap is on: a posting that
 * states nothing is "ask the employer", not "the employer refused".
 */
@Immutable
data class NextStep(
    val label: String,
    val detail: String,
    val action: NextStepAction,
)

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
 * One ingredient of the readiness score, so the rank can be opened up and read. [points]
 * is what the posting earned here and [maxPoints] the most it could earn, so the UI shows
 * "12 of 15" rather than an unexplained total.
 */
@Immutable
data class RankComponent(
    val label: String,
    val detail: String,
    val points: Int,
    val maxPoints: Int,
)

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
    /** The four weighted ingredients of [readinessScore], in display order. */
    val rankComponents: List<RankComponent> = emptyList(),
    /**
     * Share of the posting's listing that documented a benefit (0..100). Shown so the
     * Expat Benefits Score is never read as a verdict when the listing simply said little.
     */
    val expatEvidenceCoverage: Int = 0,
    /** Benefits the user cares about that the posting documents / that were considered. */
    val benefitsMatched: Int = 0,
    val benefitsConsidered: Int = 0,
) {
    /** The employer states a route that accepts an applicant already in Madagascar. */
    val hasOfficialRoute: Boolean
        get() = job.eligibilityReasons.any { reason ->
            reason.official && OFFICIAL_ROUTE_LABELS.any { reason.label.contains(it, ignoreCase = true) }
        }

    /** What is holding this posting back, hardest-hitting first. */
    val orderedBlockers: List<ReadinessBlocker> get() = blockers.sortedByDescending { it.points }

    /**
     * The one action that moves this posting forward, derived from the hardest-hitting
     * blocker so the advice and the score always agree. A posting with nothing in the way
     * is ready to apply for; the rest name the single biggest thing to do first.
     */
    val nextStep: NextStep
        get() = when (val hardest = orderedBlockers.firstOrNull()?.label) {
            null -> NextStep(
                label = "Ready to apply",
                detail = "The route and your profile both line up — open the posting and apply.",
                action = NextStepAction.APPLY,
            )
            "Eligibility not stated" -> NextStep(
                label = "Check eligibility first",
                detail = "The posting does not say whether it accepts applicants from Madagascar. Ask before applying.",
                action = NextStepAction.REVIEW_ELIGIBILITY,
            )
            "Restricted to local applicants" -> NextStep(
                label = "Not open to you",
                detail = "The employer restricts this role to local applicants, so there is no route in.",
                action = NextStepAction.OPEN_JOB,
            )
            "Confirm the visa terms" -> NextStep(
                label = "Confirm the visa terms",
                detail = "Sponsorship is mentioned but the conditions are not spelled out. Confirm the fees and dependants.",
                action = NextStepAction.REVIEW_ELIGIBILITY,
            )
            "Benefits not stated" -> NextStep(
                label = "Ask about the package",
                detail = "No visa, relocation or housing support is documented. That is missing information, not a refusal.",
                action = NextStepAction.CONFIRM_BENEFITS,
            )
            "Documented benefits fall short" -> NextStep(
                label = "Review the benefits",
                detail = "The documented benefits score below your priorities. Read the evidence before deciding.",
                action = NextStepAction.CONFIRM_BENEFITS,
            )
            "Scam shield flagged this posting" -> NextStep(
                label = "Run the scam check",
                detail = "Several risk signals were found. Open the scam check before sharing any details.",
                action = NextStepAction.RUN_SCAM_CHECK,
            )
            "Some details need checking" -> NextStep(
                label = "Verify the employer",
                detail = "The scam shield found minor caution signals. Check the employer independently first.",
                action = NextStepAction.RUN_SCAM_CHECK,
            )
            "No name on your profile" -> NextStep(
                label = "Add your details",
                detail = "Import and analyse your CV so applications carry your real name and details.",
                action = NextStepAction.COMPLETE_PROFILE,
            )
            else -> when {
                hardest.startsWith("CV match") -> NextStep(
                    label = "Tailor your CV",
                    detail = "Close the skill gaps this posting lists, then adapt your CV to it before applying.",
                    action = NextStepAction.TAILOR_CV,
                )
                hardest.startsWith("Profile") -> NextStep(
                    label = "Complete your profile",
                    detail = "A fuller profile scores more accurately, so this match can be trusted.",
                    action = NextStepAction.COMPLETE_PROFILE,
                )
                else -> NextStep(
                    label = "Review before applying",
                    detail = "Open the posting to see the full reasoning and what is in the way.",
                    action = NextStepAction.OPEN_JOB,
                )
            }
        }

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

    /**
     * The strongest postings first, ready to act on now. The engine already ranks entries by
     * readiness then match, so this keeps that order rather than re-sorting.
     */
    val rankedActionable: List<ShortlistEntry>
        get() = entries.filter { it.readiness.isActionable }

    /**
     * Postings worth acting on once a gap is closed: close to ready, or blocked only by
     * something the user can fix or confirm. Never includes a local-only posting, which has
     * no route in and so is not "preparation".
     */
    val needsPreparation: List<ShortlistEntry>
        get() = entries.filter { entry ->
            !entry.readiness.isActionable &&
                entry.job.eligibility != MadagascarEligibility.RESTRICTED
        }

    /** A read on how strong the shortlist itself is, so a thin list is never dressed up. */
    val quality: ShortlistQuality
        get() {
            if (entries.isEmpty()) {
                return ShortlistQuality(
                    band = ShortlistQualityBand.NONE,
                    headline = "No shortlist yet",
                    detail = "Nothing screened so far is open to applicants in Madagascar. " +
                        "Refresh to pull the latest postings.",
                    actionableCount = 0,
                    actionableShare = 0,
                    strongestReadiness = 0,
                    countriesRepresented = 0,
                    topCountry = null,
                )
            }
            val strongest = entries.maxOf { it.readinessScore }
            val actionable = actionableCount
            val share = actionable * 100 / entries.size
            val countries = entries.map { it.job.country }.distinct()
            val topCountry = entries.groupBy { it.job.country }
                .maxByOrNull { (_, group) -> group.count { it.readiness.isActionable } }
                ?.takeIf { (_, group) -> group.any { it.readiness.isActionable } }
                ?.let { (country, group) ->
                    group.first().job.countryFlag to country
                }
            val band = when {
                actionable == 0 -> ShortlistQualityBand.THIN
                actionable >= 5 && strongest >= ApplicationReadiness.PRIORITY_MIN -> ShortlistQualityBand.STRONG
                else -> ShortlistQualityBand.BUILDING
            }
            return ShortlistQuality(
                band = band,
                headline = when (band) {
                    ShortlistQualityBand.STRONG -> "$actionable postings you can act on now"
                    ShortlistQualityBand.BUILDING -> "$actionable posting${if (actionable == 1) "" else "s"} to start with"
                    else -> "Routes in are thin right now"
                },
                detail = qualityDetail(entries.size, actionable, countries.size, topCountry?.second),
                actionableCount = actionable,
                actionableShare = share,
                strongestReadiness = strongest,
                countriesRepresented = countries.size,
                topCountry = topCountry,
            )
        }

    private fun qualityDetail(
        screened: Int,
        actionable: Int,
        countries: Int,
        topCountry: String?,
    ): String {
        val base = "Screened $screened posting${if (screened == 1) "" else "s"}; " +
            "$actionable ${if (actionable == 1) "is" else "are"} open to you now"
        val places = if (countries > 0) " across $countries ${if (countries == 1) "country" else "countries"}" else ""
        val lead = if (topCountry != null) ", with $topCountry leading" else ""
        return "$base$places$lead. That is what the listings state — verify before you invest time."
    }
}

/** How strong the shortlist itself looks, so the dashboard can be honest about a thin list. */
enum class ShortlistQualityBand(val label: String) {
    NONE("No data"),
    THIN("Thin"),
    BUILDING("Building"),
    STRONG("Strong"),
}

/**
 * A short, honest read on the shortlist as a whole: how many postings are open to the user,
 * what share that is of everything screened, and where the routes in are. Every figure is
 * counted from the current snapshot, so the dashboard can never claim more than the list holds.
 */
@Immutable
data class ShortlistQuality(
    val band: ShortlistQualityBand,
    val headline: String,
    val detail: String,
    val actionableCount: Int,
    /** Actionable postings as a share (0..100) of everything on the shortlist. */
    val actionableShare: Int,
    val strongestReadiness: Int,
    val countriesRepresented: Int,
    /** The country with the most actionable postings, as flag to name, when there is one. */
    val topCountry: Pair<String, String>?,
) {
    val hasActionable: Boolean get() = actionableCount > 0
}
