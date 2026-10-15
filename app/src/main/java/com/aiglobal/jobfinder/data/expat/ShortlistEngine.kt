package com.aiglobal.jobfinder.data.expat

import com.aiglobal.jobfinder.data.model.ApplicationReadiness
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.BlockerKind
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.EligibilityReason
import com.aiglobal.jobfinder.data.model.ExpatPreferences
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobBenefitTypes
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.RankComponent
import com.aiglobal.jobfinder.data.model.ReadinessBlocker
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.ShortlistEntry
import com.aiglobal.jobfinder.data.model.ShortlistState
import com.aiglobal.jobfinder.data.model.VisaSupport
import kotlin.math.roundToInt

/**
 * Builds the expat shortlist: the postings a candidate currently in Madagascar can
 * realistically act on, ranked by how ready they are to apply for — not by how well the
 * CV fits alone.
 *
 * The readiness score answers a question the match score cannot: "can I apply for this,
 * and what is stopping me today?". It is a transparent weighted sum of four things the
 * app already knows, so every point can be traced:
 *
 *   eligibility route  0..45  — the employer's stated route in, or its absence
 *   CV match           0..30  — the existing match score, rescaled
 *   expat benefits     0..15  — documented benefits vs. the user's priorities
 *   profile readiness  0..10  — how complete the CV-derived profile is
 *
 * Two rules keep it honest, matching the rest of the pipeline:
 *  1. Silence is never treated as a refusal. An unstated route scores low and produces a
 *     "verify" blocker, not a claim that the employer refuses international applicants.
 *  2. Every blocker names where it came from: a quoted posting fact, an app inference, or
 *     the user's own profile. The UI never has to guess which is which.
 *
 * Postings the employer restricts to local applicants are excluded outright: there is no
 * route in from Madagascar, so they do not belong on a shortlist meant to be actionable.
 */
class ShortlistEngine(
    private val expatEngine: ExpatBenefitsEngine = ExpatBenefitsEngine(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun build(
        jobs: List<Job>,
        profile: CandidateProfile,
        preferences: ExpatPreferences,
        profileCompletenessPercent: Int,
        salaryEurPerYear: (Job) -> Int?,
        limit: Int = DEFAULT_LIMIT,
    ): ShortlistState {
        val entries = jobs
            .filter { it.eligibility != MadagascarEligibility.RESTRICTED }
            .map { job ->
                assess(
                    job = job,
                    profile = profile,
                    preferences = preferences,
                    profileCompletenessPercent = profileCompletenessPercent,
                    salaryEurPerYear = salaryEurPerYear(job),
                )
            }
            .sortedWith(
                compareByDescending<ShortlistEntry> { it.readinessScore }
                    .thenByDescending { it.matchScore },
            )
            .take(limit)

        return ShortlistState(
            isLoading = false,
            entries = entries,
            totalJobs = jobs.size,
            generatedAtEpochMillis = clock(),
        )
    }

    /** Scores one posting. Public so it can be exercised directly in tests. */
    fun assess(
        job: Job,
        profile: CandidateProfile,
        preferences: ExpatPreferences,
        profileCompletenessPercent: Int,
        salaryEurPerYear: Int?,
    ): ShortlistEntry {
        val assessment = expatEngine.assess(
            benefits = job.benefitEvidence,
            preferences = preferences,
            salaryEurPerYear = salaryEurPerYear,
        )
        val expatHasEvidence = assessment.hasEvidence
        val expatScore = if (expatHasEvidence) assessment.score else 0

        val eligibilityPoints = eligibilityPoints(job)
        val matchPoints = (job.matchScore.coerceIn(0, 100) * MATCH_WEIGHT) / 100
        val expatPoints = (expatScore.coerceIn(0, 100) * EXPAT_WEIGHT) / 100
        val profilePoints = (profileCompletenessPercent.coerceIn(0, 100) * PROFILE_WEIGHT) / 100

        val score = (eligibilityPoints + matchPoints + expatPoints + profilePoints).coerceIn(0, 100)

        val documentedBenefits = assessment.components.count { it.status == BenefitStatus.CONFIRMED }
        val consideredBenefits = assessment.components.count { it.priority.weight > 0 }

        return ShortlistEntry(
            job = job,
            matchScore = job.matchScore,
            expatScore = expatScore,
            readinessScore = score,
            readiness = ApplicationReadiness.fromScore(score),
            blockers = buildBlockers(
                job = job,
                profile = profile,
                profileCompletenessPercent = profileCompletenessPercent,
                eligibilityPoints = eligibilityPoints,
                matchPoints = matchPoints,
                expatHasEvidence = expatHasEvidence,
                expatScore = expatScore,
            ),
            highlights = buildHighlights(job, expatHasEvidence, documentedBenefits),
            generatedAtEpochMillis = clock(),
            rankComponents = buildRankComponents(
                job = job,
                eligibilityPoints = eligibilityPoints,
                matchPoints = matchPoints,
                expatPoints = expatPoints,
                expatScore = expatScore,
                expatHasEvidence = expatHasEvidence,
                profilePoints = profilePoints,
                profileCompletenessPercent = profileCompletenessPercent,
            ),
            expatEvidenceCoverage = assessment.evidenceCoverage,
            benefitsMatched = documentedBenefits,
            benefitsConsidered = consideredBenefits,
        )
    }

    /**
     * The four weighted ingredients of the readiness score, so the number on the card can be
     * traced to "45 route + 27 CV + 6 benefits + 8 profile". Each component says what the
     * posting earned and what it could have earned, using the same constants as [assess].
     */
    private fun buildRankComponents(
        job: Job,
        eligibilityPoints: Int,
        matchPoints: Int,
        expatPoints: Int,
        expatScore: Int,
        expatHasEvidence: Boolean,
        profilePoints: Int,
        profileCompletenessPercent: Int,
    ): List<RankComponent> = listOf(
        RankComponent(
            label = "Route in from Madagascar",
            detail = when (job.eligibility) {
                MadagascarEligibility.VISA_SPONSORED ->
                    "The employer states a route in (visa sponsorship or relocation)."
                MadagascarEligibility.ELIGIBLE ->
                    "The posting is open to applicants from Madagascar."
                MadagascarEligibility.UNCLEAR ->
                    "The posting does not state whether it accepts applicants from Madagascar."
                MadagascarEligibility.RESTRICTED ->
                    "The posting is limited to local applicants; there is no route in."
            },
            points = eligibilityPoints,
            maxPoints = ELIGIBILITY_MAX,
        ),
        RankComponent(
            label = "CV match",
            detail = "Your CV matches ${job.matchScore}% of this posting.",
            points = matchPoints,
            maxPoints = MATCH_WEIGHT,
        ),
        RankComponent(
            label = "Documented benefits",
            detail = if (expatHasEvidence) {
                "Benefits you care about score $expatScore% on what the posting documents."
            } else {
                "The posting does not document the benefits you care about."
            },
            points = expatPoints,
            maxPoints = EXPAT_WEIGHT,
        ),
        RankComponent(
            label = "Profile completeness",
            detail = "Your profile is $profileCompletenessPercent% complete, so the CV match is " +
                if (profileCompletenessPercent >= PROFILE_TARGET) "fully trusted." else "only partly trusted.",
            points = profilePoints,
            maxPoints = PROFILE_WEIGHT,
        ),
    )

    private fun eligibilityPoints(job: Job): Int = when {
        job.eligibility == MadagascarEligibility.VISA_SPONSORED -> 45
        job.eligibility == MadagascarEligibility.ELIGIBLE && hasOfficialRoute(job) -> 45
        job.eligibility == MadagascarEligibility.ELIGIBLE -> 38
        job.eligibility == MadagascarEligibility.UNCLEAR -> 18
        else -> 0
    }

    private fun hasOfficialRoute(job: Job): Boolean =
        job.eligibilityReasons.any { reason ->
            reason.official && ROUTE_LABELS.any { reason.label.contains(it, ignoreCase = true) }
        }

    private fun buildBlockers(
        job: Job,
        profile: CandidateProfile,
        profileCompletenessPercent: Int,
        eligibilityPoints: Int,
        matchPoints: Int,
        expatHasEvidence: Boolean,
        expatScore: Int,
    ): List<ReadinessBlocker> {
        val blockers = mutableListOf<ReadinessBlocker>()

        when (job.eligibility) {
            MadagascarEligibility.UNCLEAR -> blockers += ReadinessBlocker(
                label = "Eligibility not stated",
                detail = "The posting does not say whether it accepts applicants from Madagascar. " +
                    "Ask the employer before you invest time in the application.",
                kind = BlockerKind.HEURISTIC,
                points = ELIGIBILITY_MAX - eligibilityPoints,
            )
            MadagascarEligibility.RESTRICTED -> blockers += ReadinessBlocker(
                label = "Restricted to local applicants",
                detail = "The posting states a local-only requirement, so there is no route in from Madagascar.",
                kind = BlockerKind.OFFICIAL,
                points = ELIGIBILITY_MAX,
            )
            else -> Unit
        }

        // A stated visa route that the employer has not pinned to a benefit sentence is
        // worth confirming, even when the verdict is VISA_SPONSORED.
        if (job.eligibility == MadagascarEligibility.VISA_SPONSORED && !documentsVisaSponsorship(job)) {
            blockers += ReadinessBlocker(
                label = "Confirm the visa terms",
                detail = "The posting mentions sponsorship but does not spell out the conditions. " +
                    "Confirm who pays the fees and whether dependants are covered.",
                kind = BlockerKind.HEURISTIC,
                points = 8,
            )
        }

        if (job.matchScore < STRONG_MATCH) {
            val gap = STRONG_MATCH - job.matchScore
            val missing = job.missingSkills.take(3)
            blockers += ReadinessBlocker(
                label = "CV match below $STRONG_MATCH%",
                detail = if (missing.isNotEmpty()) {
                    "Your CV is missing ${job.missingSkills.size} skill(s) this posting lists: " +
                        missing.joinToString(", ") + "."
                } else {
                    "This posting fits your CV less closely than your strongest matches."
                },
                kind = BlockerKind.PROFILE,
                points = (gap * MATCH_WEIGHT) / 100,
            )
        }

        if (!expatHasEvidence) {
            blockers += ReadinessBlocker(
                label = "Benefits not stated",
                detail = "The posting does not document visa, relocation or housing support. " +
                    "That is missing information, not a refusal — ask the employer.",
                kind = BlockerKind.HEURISTIC,
                points = EXPAT_WEIGHT,
            )
        } else if (expatScore < BENEFIT_FLOOR) {
            blockers += ReadinessBlocker(
                label = "Documented benefits fall short",
                detail = "The benefits the employer documents score below your priorities. " +
                    "Review the evidence before deciding.",
                kind = BlockerKind.HEURISTIC,
                points = ((BENEFIT_FLOOR - expatScore) * EXPAT_WEIGHT) / 100,
            )
        }

        if (profileCompletenessPercent < PROFILE_TARGET) {
            blockers += ReadinessBlocker(
                label = "Profile ${profileCompletenessPercent}% complete",
                detail = "A fuller profile scores more accurately. Add the missing CV sections " +
                    "so this match can be trusted.",
                kind = BlockerKind.PROFILE,
                points = ((PROFILE_TARGET - profileCompletenessPercent) * PROFILE_WEIGHT) / 100,
            )
        }

        if (job.scamRisk == ScamRisk.HIGH) {
            blockers += ReadinessBlocker(
                label = "Scam shield flagged this posting",
                detail = "Several risk signals were found. Open the scam check before you share any details.",
                kind = BlockerKind.HEURISTIC,
                points = 20,
            )
        } else if (job.scamRisk == ScamRisk.MEDIUM) {
            blockers += ReadinessBlocker(
                label = "Some details need checking",
                detail = "The scam shield found minor caution signals. Verify the employer independently.",
                kind = BlockerKind.HEURISTIC,
                points = 8,
            )
        }

        if (profile.fullName.isBlank()) {
            blockers += ReadinessBlocker(
                label = "No name on your profile",
                detail = "Import and analyse your CV so applications carry your real details.",
                kind = BlockerKind.PROFILE,
                points = 6,
            )
        }

        return blockers.filter { it.points > 0 }
    }

    private fun documentsVisaSponsorship(job: Job): Boolean =
        job.benefitEvidence.any {
            it.type.key == JobBenefitTypes.VISA_SPONSORSHIP.key && it.status == BenefitStatus.CONFIRMED
        }

    private fun buildHighlights(job: Job, expatHasEvidence: Boolean, confirmedBenefits: Int): List<String> {
        val highlights = mutableListOf<String>()

        job.eligibilityReasons.filter { it.official }.forEach { reason ->
            when {
                reason.label.contains("visa sponsorship", ignoreCase = true) ->
                    highlights += "Employer states visa sponsorship"
                reason.label.contains("open to international", ignoreCase = true) ->
                    highlights += "Open to international applicants"
                reason.label.contains("relocation", ignoreCase = true) ->
                    highlights += "Relocation support mentioned"
                reason.label.contains("remote or worldwide", ignoreCase = true) ->
                    highlights += "Listed as remote or worldwide"
            }
        }

        if (job.matchScore >= STRONG_MATCH) {
            highlights += "Strong CV match (${job.matchScore}%)"
        }
        if (expatHasEvidence && confirmedBenefits > 0) {
            highlights += "Documents $confirmedBenefits benefit${if (confirmedBenefits == 1) "" else "s"} you care about"
        }
        if (job.visaSupport == VisaSupport.FULL_SPONSORSHIP) {
            highlights += "Full sponsorship advertised"
        }
        if (job.isNew) {
            highlights += "Freshly posted"
        }
        return highlights.distinct()
    }

    private companion object {
        const val ELIGIBILITY_MAX = 45
        const val MATCH_WEIGHT = 30
        const val EXPAT_WEIGHT = 15
        const val PROFILE_WEIGHT = 10
        const val STRONG_MATCH = 70
        const val BENEFIT_FLOOR = 50
        const val PROFILE_TARGET = 80
        const val DEFAULT_LIMIT = 40

        val ROUTE_LABELS = listOf(
            "visa sponsorship",
            "relocation",
            "open to international",
            "remote or worldwide",
        )
    }
}
