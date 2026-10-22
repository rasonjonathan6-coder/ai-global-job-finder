package com.aiglobal.jobfinder.data.jobs.offer

import com.aiglobal.jobfinder.data.jobs.engine.SkillLexicon
import com.aiglobal.jobfinder.data.jobs.freshness.Freshness
import com.aiglobal.jobfinder.data.jobs.freshness.FreshnessWindow
import com.aiglobal.jobfinder.data.jobs.freshness.JobFreshnessEngine
import com.aiglobal.jobfinder.data.jobs.search.RoleFamily
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.EvidenceStrength
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.OfferMatch
import com.aiglobal.jobfinder.data.model.OfferMatchBand
import com.aiglobal.jobfinder.data.model.OfferMatchComponent
import com.aiglobal.jobfinder.data.model.OfferMatchDimension
import kotlin.math.roundToInt

/**
 * The Stage 80 compatibility score: a deterministic, explainable 0..100 read of how well one
 * recently discovered posting fits the candidate's CV, weighted as the product requires.
 *
 *   CV skills & experience              35
 *   Eligibility from Madagascar         25
 *   Experience & qualifications asked   15
 *   Sector & role fit                   10
 *   Verified publication freshness      10
 *   Confirmed expatriation benefits      5
 *
 * Three rules keep it honest:
 *
 *  1. **Nothing is invented.** Every point comes from a field the app already carries — the
 *     CV profile, the eligibility verdict, the posting's own requirements and benefit
 *     evidence, the role classifier and the source's stated publication date. A missing fact
 *     is scored neutrally, never as a positive.
 *  2. **A missing date is not freshness.** Freshness points require a real publication date
 *     inside the window; an undated posting earns none and the card says so.
 *  3. **A high score cannot hide a disqualifier.** A stated local-only restriction, an expired
 *     posting, or an already-passed employer deadline caps the total and forces the LOW band.
 */
class OfferMatchEngine(
    private val freshnessEngine: JobFreshnessEngine = JobFreshnessEngine(),
) {

    fun assess(job: Job, profile: CandidateProfile, now: Long = System.currentTimeMillis()): OfferMatch {
        val freshness = freshnessEngine.assess(job, now).freshness

        val components = listOf(
            cvSkillsAndExperience(job, profile),
            madagascarEligibility(job),
            requiredExperienceAndQualifications(job),
            sectorAndRoleFit(job),
            verifiedFreshness(job, freshness, now),
            confirmedBenefits(job),
        )

        val raw = components.sumOf { it.points }.coerceIn(0, 100)
        val hardStops = hardStops(job, now)
        val disqualified = hardStops.isNotEmpty()
        val score = if (disqualified) minOf(raw, DISQUALIFIED_CAP) else raw
        val band = if (disqualified) OfferMatchBand.LOW else OfferMatchBand.fromScore(score)

        return OfferMatch(
            score = score,
            band = band,
            components = components,
            matchedSkills = job.matchedSkills,
            missingSkills = job.missingSkills,
            qualificationGaps = job.qualificationGaps,
            reasons = reasons(components, job, profile, hardStops, freshness),
            hardStops = hardStops,
            disqualified = disqualified,
            eligibility = job.eligibility,
            freshness = freshness,
            hasVerifiedDate = job.postedAtEpochMillis != null && job.postedAtEpochMillis in 1..now,
        )
    }

    // ------------------------------------------------------------------ dimensions

    /**
     * 35 points: how much of the posting's own skill list the CV actually covers. When the
     * posting names no comparable skill the dimension is scored at a neutral share, never at
     * full marks — the app does not reward a posting for saying nothing.
     */
    private fun cvSkillsAndExperience(job: Job, profile: CandidateProfile): OfferMatchComponent {
        val requiredSkills = SkillLexicon.detect(
            buildString {
                append(job.title).append(' ')
                append(job.matchedSkills.joinToString(" ")).append(' ')
                append(job.missingSkills.joinToString(" ")).append(' ')
                append(job.summary)
            },
        )

        // A CV that lists no skills covers none of them. The posting's own matched/missing
        // split is only trusted once the profile actually carries skills to match against, so
        // an empty profile can never inherit a pre-computed match.
        val candidateHasSkills = profile.skills.isNotEmpty()
        val matchedCount = if (candidateHasSkills) job.matchedSkills.size else 0

        val ratio = when {
            requiredSkills.isEmpty() -> NEUTRAL_SKILL_RATIO
            !candidateHasSkills -> 0f
            else -> matchedCount.toFloat() / requiredSkills.size
        }.coerceIn(0f, 1f)

        val points = (OfferMatchDimension.CV_SKILLS_AND_EXPERIENCE.weight * ratio).roundToInt()
        val summary = when {
            requiredSkills.isEmpty() ->
                "The posting does not name specific skills, so this part is scored neutrally."
            !candidateHasSkills ->
                "Your CV lists no skills yet, so none of the ${requiredSkills.size} skills the posting names can be credited."
            matchedCount == 0 ->
                "None of the ${requiredSkills.size} skills the posting names appear in your CV."
            job.missingSkills.isEmpty() ->
                "Your CV covers all ${requiredSkills.size} skills the posting names."
            else ->
                "Your CV covers $matchedCount of ${requiredSkills.size} skills the posting names."
        }
        val strength = when {
            requiredSkills.isEmpty() -> EvidenceStrength.UNKNOWN
            matchedCount == 0 -> EvidenceStrength.INFERRED
            job.missingSkills.isEmpty() -> EvidenceStrength.DOCUMENTED
            else -> EvidenceStrength.INFERRED
        }
        return OfferMatchComponent(OfferMatchDimension.CV_SKILLS_AND_EXPERIENCE, points, 35, summary, strength)
    }

    /**
     * 25 points: can the candidate realistically apply from Madagascar? The four eligibility
     * states map to four distinct shares, and a stated local-only restriction is a hard stop
     * handled separately, so an "unclear" posting still scores as a genuine question rather
     * than as a rejection.
     */
    private fun madagascarEligibility(job: Job): OfferMatchComponent {
        val (ratio, summary, strength) = when (job.eligibility) {
            MadagascarEligibility.ELIGIBLE -> Triple(
                1f,
                "The posting states it is open to applicants from Madagascar, or lists the role as remote/worldwide.",
                EvidenceStrength.DOCUMENTED,
            )
            MadagascarEligibility.VISA_SPONSORED -> Triple(
                0.85f,
                "The employer states visa sponsorship or relocation support, which is a route in from Madagascar.",
                EvidenceStrength.DOCUMENTED,
            )
            MadagascarEligibility.UNCLEAR -> Triple(
                0.4f,
                "The posting does not state whether it accepts applicants from Madagascar. Confirm before you rely on it.",
                EvidenceStrength.UNKNOWN,
            )
            MadagascarEligibility.RESTRICTED -> Triple(
                0f,
                "The posting states it is restricted to local applicants, so eligibility from Madagascar is not stated.",
                EvidenceStrength.REFUSED,
            )
        }
        val points = (OfferMatchDimension.MADAGASCAR_ELIGIBILITY.weight * ratio).roundToInt()
        return OfferMatchComponent(OfferMatchDimension.MADAGASCAR_ELIGIBILITY, points, 25, summary, strength)
    }

    /**
     * 15 points: does the CV meet the years and the certifications/licences the posting asks
     * for? A requirement the app cannot check ([com.aiglobal.jobfinder.data.model.QualificationGap.met]
     * == null) is treated as unknown, not as a miss, so the score never punishes a CV for
     * being terse.
     */
    private fun requiredExperienceAndQualifications(job: Job): OfferMatchComponent {
        val gaps = job.qualificationGaps
        val met = gaps.count { it.met == true }
        val unmet = gaps.count { it.met == false }
        val unknown = gaps.count { it.met == null }

        val ratio = when {
            gaps.isEmpty() -> NEUTRAL_REQUIREMENT_RATIO
            else -> ((met + unknown * 0.6f) / gaps.size).coerceIn(0f, 1f)
        }
        val points = (OfferMatchDimension.REQUIRED_EXPERIENCE_AND_QUALIFICATIONS.weight * ratio).roundToInt()

        val summary = when {
            gaps.isEmpty() ->
                "The posting states no specific years or qualifications, so this part is scored neutrally."
            unmet > 0 ->
                "Your CV does not yet show $unmet of the ${gaps.size} requirements the posting lists."
            unknown > 0 && met > 0 ->
                "Your CV meets $met of the ${gaps.size} requirements; $unknown cannot be confirmed from your CV."
            unknown > 0 ->
                "The posting lists $unknown requirements your CV does not mention, so they cannot be confirmed."
            else ->
                "Your CV meets all ${gaps.size} requirements the posting lists."
        }
        val strength = when {
            gaps.isEmpty() -> EvidenceStrength.UNKNOWN
            unmet > 0 -> EvidenceStrength.INFERRED
            unknown > 0 -> EvidenceStrength.NEEDS_VERIFICATION
            else -> EvidenceStrength.DOCUMENTED
        }
        return OfferMatchComponent(
            OfferMatchDimension.REQUIRED_EXPERIENCE_AND_QUALIFICATIONS,
            points,
            15,
            summary,
            strength,
        )
    }

    /**
     * 10 points: is the title a role the candidate actually does? The role classifier is
     * title-led, so a lone "technician" in an unrelated posting does not score here.
     */
    private fun sectorAndRoleFit(job: Job): OfferMatchComponent {
        val (ratio, summary, strength) = when (job.roleFamily) {
            RoleFamily.TARGET -> Triple(
                1f,
                job.matchedTargetRole?.let { "The title matches your target role \"$it\"." }
                    ?: "The title matches one of your target roles.",
                EvidenceStrength.DOCUMENTED,
            )
            RoleFamily.MAINTENANCE_FAMILY -> Triple(
                0.7f,
                "The title is in your wider maintenance/trade family, even if not a canonical target.",
                EvidenceStrength.INFERRED,
            )
            RoleFamily.UNRELATED -> Triple(
                0f,
                "The title names a different profession from your target roles.",
                EvidenceStrength.INFERRED,
            )
            RoleFamily.UNKNOWN -> Triple(
                0.4f,
                "There is not enough in the title to judge whether it is your trade.",
                EvidenceStrength.UNKNOWN,
            )
        }
        val points = (OfferMatchDimension.SECTOR_AND_ROLE_FIT.weight * ratio).roundToInt()
        return OfferMatchComponent(OfferMatchDimension.SECTOR_AND_ROLE_FIT, points, 10, summary, strength)
    }

    /**
     * 10 points: only a *verified* publication date inside the window earns these. An undated
     * posting earns zero and the summary says so rather than implying freshness.
     */
    private fun verifiedFreshness(job: Job, freshness: Freshness, now: Long): OfferMatchComponent {
        val ratio = when {
            !FreshnessWindow.DEFAULT.includes(job.postedAtEpochMillis, now) -> 0f
            freshness == Freshness.FRESH -> 1f
            freshness == Freshness.RECENT -> 0.8f
            freshness == Freshness.AGING -> 0.4f
            else -> 0f
        }
        val summary = when {
            job.postedAtEpochMillis == null || job.postedAtEpochMillis !in 1..now ->
                "The source did not state a publication date, so freshness cannot be credited."
            ratio >= 1f -> "The source published this within the last two days."
            ratio >= 0.8f -> "The source published this within the last two weeks."
            ratio > 0f -> "The source published this a while ago; confirm it is still open."
            else -> "The source published this more than ${FreshnessWindow.DEFAULT.days} days ago."
        }
        val points = (OfferMatchDimension.VERIFIED_FRESHNESS.weight * ratio).roundToInt()
        val strength = if (ratio > 0f) EvidenceStrength.DOCUMENTED else EvidenceStrength.UNKNOWN
        return OfferMatchComponent(OfferMatchDimension.VERIFIED_FRESHNESS, points, 10, summary, strength)
    }

    /**
     * 5 points: confirmed expatriation benefits only. A benefit that is merely mentioned, or
     * not stated at all, earns nothing — silence is never dressed up as an advantage.
     */
    private fun confirmedBenefits(job: Job): OfferMatchComponent {
        val confirmed = job.benefitEvidence.count { it.status == BenefitStatus.CONFIRMED }
        val ratio = (confirmed.toFloat() / BENEFIT_FULL_MARKS).coerceIn(0f, 1f)
        val points = (OfferMatchDimension.CONFIRMED_EXPAT_BENEFITS.weight * ratio).roundToInt()
        val summary = when {
            confirmed == 0 -> "The posting states no expatriation benefits, so none are credited."
            confirmed >= BENEFIT_FULL_MARKS -> "The posting documents several expatriation benefits."
            else -> "The posting documents $confirmed expatriation benefit${if (confirmed == 1) "" else "s"}."
        }
        val strength = if (confirmed == 0) EvidenceStrength.UNKNOWN else EvidenceStrength.DOCUMENTED
        return OfferMatchComponent(OfferMatchDimension.CONFIRMED_EXPAT_BENEFITS, points, 5, summary, strength)
    }

    // ------------------------------------------------------------------ hard stops

    /**
     * The conditions that cap the score and force the LOW band. Each is a positive fact the
     * app can name, never an absence: a stated local-only restriction, an expired posting, or
     * an employer deadline that has already passed.
     */
    private fun hardStops(job: Job, now: Long): List<String> = buildList {
        if (job.eligibility == MadagascarEligibility.RESTRICTED) {
            add("The posting is restricted to local applicants, so you cannot apply from Madagascar.")
        }
        if (job.availability.isExpired) {
            add("The posting is marked expired, so it is not open to new applicants.")
        }
        val deadline = job.expiresAtEpochMillis
        if (deadline != null && deadline in 1..now) {
            add("The application deadline stated by the employer has already passed.")
        }
    }

    // ------------------------------------------------------------------ reasons

    /** The plain-language reasons behind the score, strongest dimension first. */
    private fun reasons(
        components: List<OfferMatchComponent>,
        job: Job,
        profile: CandidateProfile,
        hardStops: List<String>,
        freshness: Freshness,
    ): List<String> = buildList {
        hardStops.forEach { add(it) }

        components.sortedByDescending { it.points }.forEach { add(it.summary) }

        if (job.missingSkills.isNotEmpty()) {
            add("Skills the posting names that your CV does not show: ${job.missingSkills.joinToString(", ")}.")
        }
        if (profile.skills.isEmpty()) {
            add("Your CV lists no skills yet, so the skills part of the score is limited.")
        }
        if (freshness == Freshness.UNKNOWN) {
            add("Publication date unknown: the app did not credit any freshness points.")
        }
        if (job.benefitEvidence.isEmpty()) {
            add("The posting does not state expatriation benefits; none were assumed.")
        }
    }.distinct()

    companion object {
        /** The cap applied when a hard stop fires, so a disqualifier can never be outvoted. */
        const val DISQUALIFIED_CAP = 30

        /** Neutral share when the posting names no comparable skills. */
        private const val NEUTRAL_SKILL_RATIO = 0.45f

        /** Neutral share when the posting lists no checkable requirements. */
        private const val NEUTRAL_REQUIREMENT_RATIO = 0.6f

        /** How many confirmed benefits earn full marks in the 5-point dimension. */
        private const val BENEFIT_FULL_MARKS = 4
    }
}
