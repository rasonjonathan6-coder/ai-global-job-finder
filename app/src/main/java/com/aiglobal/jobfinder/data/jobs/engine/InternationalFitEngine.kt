package com.aiglobal.jobfinder.data.jobs.engine

import com.aiglobal.jobfinder.data.jobs.search.RoleFamily
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.EvidenceStrength
import com.aiglobal.jobfinder.data.model.ExpatBenefitsAssessment
import com.aiglobal.jobfinder.data.model.InternationalFitAssessment
import com.aiglobal.jobfinder.data.model.InternationalFitBand
import com.aiglobal.jobfinder.data.model.InternationalFitComponent
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.LanguageLevel
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.ScamRisk
import kotlin.math.roundToInt

/**
 * Scores how strong one posting is **for this candidate as an international applicant**, and
 * explains every point of it.
 *
 * This is not a replacement for the three numbers the app already keeps apart — the CV
 * [Job.matchScore], the Madagascar [MadagascarEligibility] verdict and the
 * [ExpatBenefitsAssessment] score. It is the single "is this worth my time, and where do I
 * stand?" read that folds the destination, the route in, the language and the credential facts
 * together, while leaving the documented Expat Benefits Score visible beside it so the two are
 * never conflated.
 *
 * The score is a transparent weighted sum of six components, each capped at its own ceiling:
 *
 *   route in from Madagascar   0..35  — the employer's stated route, or its absence
 *   role alignment             0..20  — the candidate's trade vs. the posting's title
 *   skills overlap             0..20  — canonical skills in common, gated by role alignment
 *   experience                 0..10  — years of experience vs. the posting's stated minimum
 *   language                   0..10  — languages the posting asks for vs. the CV's
 *   destination                0..5   — a country the candidate wants, or a worldwide remote
 *
 * Three rules keep it honest, matching the rest of the pipeline:
 *  1. Silence is neutral, not negative. A requirement the posting does not state neither earns
 *     nor loses its points, and the component is marked [EvidenceStrength.UNKNOWN] so the UI
 *     can show "not stated" rather than implying a refusal.
 *  2. Nothing is invented. Role and skills come from the existing matching output; the route,
 *     language and destination come from recorded facts on the [Job].
 *  3. A thin profile produces [InternationalFitBand.INSUFFICIENT], not a confident low number:
 *     the score is only shown when there is a real profile to score against.
 */
class InternationalFitEngine {

    fun assess(
        job: Job,
        profile: CandidateProfile,
        expat: ExpatBenefitsAssessment = ExpatBenefitsAssessment.EMPTY,
    ): InternationalFitAssessment {
        val thin = isProfileThin(profile)
        if (thin) {
            return InternationalFitAssessment(
                score = 0,
                band = InternationalFitBand.INSUFFICIENT,
                components = emptyList(),
                blockers = emptyList(),
                routeSummary = routeSummary(job),
                expatBenefitsScore = expat.score.takeIf { expat.hasEvidence },
                expatEvidenceCoverage = expat.evidenceCoverage,
                expatLowEvidence = true,
                explanation = "There is not enough on your profile to judge this posting yet. " +
                    "Import and analyse your CV so the fit reflects your real skills and experience.",
            )
        }

        val route = routeComponent(job)
        val role = roleComponent(job, profile)
        val skills = skillsComponent(job, profile)
        val experience = experienceComponent(job, profile)
        val language = languageComponent(job, profile)
        val destination = destinationComponent(job, profile)

        val components = listOf(route, role, skills, experience, language, destination)
        val score = components.sumOf { it.points }.coerceIn(0, 100)
        val band = InternationalFitBand.fromScore(score)

        return InternationalFitAssessment(
            score = score,
            band = band,
            components = components,
            blockers = blockers(job),
            routeSummary = routeSummary(job),
            expatBenefitsScore = expat.score.takeIf { expat.hasEvidence },
            expatEvidenceCoverage = expat.evidenceCoverage,
            expatLowEvidence = expat.lowEvidence,
            explanation = explain(job, band, score, components),
        )
    }

    // --------------------------------------------------------------- components

    /** The employer's stated route in, or the honest absence of one. */
    private fun routeComponent(job: Job): InternationalFitComponent {
        val label = "Route from Madagascar"
        return when (job.eligibility) {
            MadagascarEligibility.ELIGIBLE -> InternationalFitComponent(
                label = label,
                detail = "The posting states it accepts applicants like you, or is a worldwide remote role.",
                strength = EvidenceStrength.DOCUMENTED,
                points = 35,
                maxPoints = 35,
            )
            MadagascarEligibility.VISA_SPONSORED -> InternationalFitComponent(
                label = label,
                detail = "The employer states visa sponsorship or relocation support. Confirm the conditions before applying.",
                strength = EvidenceStrength.DOCUMENTED,
                points = 30,
                maxPoints = 35,
            )
            MadagascarEligibility.UNCLEAR -> InternationalFitComponent(
                label = label,
                detail = "The posting does not say whether it accepts applicants from Madagascar. That is missing information, not a refusal.",
                strength = EvidenceStrength.UNKNOWN,
                points = 10,
                maxPoints = 35,
            )
            MadagascarEligibility.RESTRICTED -> InternationalFitComponent(
                label = label,
                detail = "The employer restricts this role to local applicants, so there is no route in from Madagascar.",
                strength = EvidenceStrength.REFUSED,
                points = 0,
                maxPoints = 35,
            )
        }
    }

    /** The candidate's trade vs. the posting's title, from the existing role classifier. */
    private fun roleComponent(job: Job, profile: CandidateProfile): InternationalFitComponent {
        val label = "Role alignment"
        return when (job.roleFamily) {
            RoleFamily.TARGET -> InternationalFitComponent(
                label = label,
                detail = job.matchedTargetRole?.let { "\"${job.title}\" is one of your target roles ($it)." }
                    ?: "\"${job.title}\" is one of your target roles.",
                strength = EvidenceStrength.DOCUMENTED,
                points = 20,
                maxPoints = 20,
            )
            RoleFamily.MAINTENANCE_FAMILY -> InternationalFitComponent(
                label = label,
                detail = "\"${job.title}\" is in your wider trade, though not one of your named target roles.",
                strength = EvidenceStrength.INFERRED,
                points = 12,
                maxPoints = 20,
            )
            RoleFamily.UNRELATED -> InternationalFitComponent(
                label = label,
                detail = "\"${job.title}\" is a different profession from your trade, so this is not your role.",
                strength = EvidenceStrength.INFERRED,
                points = 2,
                maxPoints = 20,
            )
            RoleFamily.UNKNOWN -> InternationalFitComponent(
                label = label,
                detail = "There was not enough of a title to judge whether this is your trade.",
                strength = EvidenceStrength.UNKNOWN,
                points = 8,
                maxPoints = 20,
            )
        }
    }

    /** Canonical skills in common, gated by role alignment exactly as the match score is. */
    private fun skillsComponent(job: Job, profile: CandidateProfile): InternationalFitComponent {
        val label = "Skills"
        val candidate = profile.skills.map { SkillLexicon.canonicalize(it) }.toSet()
        val named = (job.matchedSkills + job.missingSkills).map { SkillLexicon.canonicalize(it) }.toSet()
        val matched = named.intersect(candidate).size
        val total = named.size

        if (candidate.isEmpty()) {
            return InternationalFitComponent(
                label = label,
                detail = "Your profile lists no skills yet, so there is nothing to compare.",
                strength = EvidenceStrength.UNKNOWN,
                points = 0,
                maxPoints = 20,
            )
        }
        if (total == 0) {
            return InternationalFitComponent(
                label = label,
                detail = "The posting does not name specific skills, so this part is scored neutrally.",
                strength = EvidenceStrength.UNKNOWN,
                points = 10,
                maxPoints = 20,
            )
        }

        val ratio = matched.toFloat() / total
        // A skill overlap only counts when the posting is for a role the candidate does, the
        // same gate the match score uses, so an incidental tool is not read as a skill fit.
        val gate = when {
            job.roleFamily == RoleFamily.TARGET -> 1f
            job.roleFamily == RoleFamily.MAINTENANCE_FAMILY -> 0.85f
            job.roleFamily == RoleFamily.UNKNOWN -> 0.5f
            else -> 0.25f
        }
        val points = (20 * ratio * gate).roundToInt().coerceIn(0, 20)
        return InternationalFitComponent(
            label = label,
            detail = if (job.roleFamily == RoleFamily.UNRELATED && matched > 0) {
                "You share $matched of the ${total} skills named, but this is not a role you do; the overlap counts for little."
            } else {
                "You match $matched of the ${total} skills named in the posting."
            },
            strength = if (matched == 0) EvidenceStrength.UNKNOWN else EvidenceStrength.DOCUMENTED,
            points = points,
            maxPoints = 20,
        )
    }

    /** Years of experience vs. the posting's stated minimum. Silence is neutral. */
    private fun experienceComponent(job: Job, profile: CandidateProfile): InternationalFitComponent {
        val label = "Experience"
        val required = job.requirements.minYears
        val years = profile.yearsOfExperience
        return when {
            years == null -> InternationalFitComponent(
                label = label,
                detail = "Your profile does not state your years of experience yet.",
                strength = EvidenceStrength.UNKNOWN,
                points = 0,
                maxPoints = 10,
            )
            required == null -> InternationalFitComponent(
                label = label,
                detail = "The posting does not state a required number of years.",
                strength = EvidenceStrength.UNKNOWN,
                points = 7,
                maxPoints = 10,
            )
            years >= required -> InternationalFitComponent(
                label = label,
                detail = "Your $years years cover the ${required}+ years requested.",
                strength = EvidenceStrength.DOCUMENTED,
                points = 10,
                maxPoints = 10,
            )
            years + 1 >= required -> InternationalFitComponent(
                label = label,
                detail = "The posting asks for ${required}+ years; you are close with $years.",
                strength = EvidenceStrength.INFERRED,
                points = 7,
                maxPoints = 10,
            )
            else -> InternationalFitComponent(
                label = label,
                detail = "The posting asks for ${required}+ years; your profile shows $years.",
                strength = EvidenceStrength.INFERRED,
                points = (10f * years / required).roundToInt().coerceIn(1, 6),
                maxPoints = 10,
            )
        }
    }

    /** Languages the posting asks for vs. the CV's, with the honest "cannot tell" case. */
    private fun languageComponent(job: Job, profile: CandidateProfile): InternationalFitComponent {
        val label = "Language"
        val required = job.languages
        val known = profile.languages.map { it.name.lowercase() }
        if (required.isEmpty()) {
            return InternationalFitComponent(
                label = label,
                detail = "The posting does not name a required language.",
                strength = EvidenceStrength.UNKNOWN,
                points = 6,
                maxPoints = 10,
            )
        }
        if (known.isEmpty()) {
            return InternationalFitComponent(
                label = label,
                detail = "The posting asks for ${required.joinToString(", ")}; your profile lists no languages.",
                strength = EvidenceStrength.UNKNOWN,
                points = 0,
                maxPoints = 10,
            )
        }
        val have = required.count { req -> known.any { it.contains(req.lowercase()) } }
        val points = (10f * have / required.size).roundToInt().coerceIn(0, 10)
        val fluent = required.filter { req -> profile.languages.any { it.name.equals(req, true) && isFluent(it.level) } }
        return InternationalFitComponent(
            label = label,
            detail = when {
                have == required.size && fluent.isNotEmpty() ->
                    "You list ${fluent.joinToString(", ")}, which the posting asks for."
                have == required.size ->
                    "You list ${required.joinToString(", ")}, which the posting asks for; check the level required."
                else ->
                    "The posting asks for ${required.joinToString(", ")}; check the level required."
            },
            strength = if (have == required.size && fluent.isNotEmpty()) EvidenceStrength.DOCUMENTED
            else EvidenceStrength.INFERRED,
            points = points,
            maxPoints = 10,
        )
    }

    /** A country the candidate wants, a worldwide remote, or a destination they did not choose. */
    private fun destinationComponent(job: Job, profile: CandidateProfile): InternationalFitComponent {
        val label = "Destination"
        val preferred = profile.preferredCountries.map { it.lowercase().trim() }
        return when {
            preferred.isEmpty() -> InternationalFitComponent(
                label = label,
                detail = "You have not chosen preferred countries, so the destination is scored neutrally.",
                strength = EvidenceStrength.UNKNOWN,
                points = 3,
                maxPoints = 5,
            )
            preferred.any { it == job.country.lowercase() } -> InternationalFitComponent(
                label = label,
                detail = "${job.country} is one of your preferred countries.",
                strength = EvidenceStrength.DOCUMENTED,
                points = 5,
                maxPoints = 5,
            )
            job.country.equals("Worldwide", ignoreCase = true) -> InternationalFitComponent(
                label = label,
                detail = "This is a worldwide remote role, which fits any location.",
                strength = EvidenceStrength.DOCUMENTED,
                points = 4,
                maxPoints = 5,
            )
            else -> InternationalFitComponent(
                label = label,
                detail = "${job.country} is not one of your preferred countries.",
                strength = EvidenceStrength.INFERRED,
                points = 1,
                maxPoints = 5,
            )
        }
    }

    // --------------------------------------------------------------- helpers

    /**
     * A profile with neither a title nor any skills cannot support a fit judgement. The engine
     * returns [InternationalFitBand.INSUFFICIENT] rather than a number built on nothing.
     */
    private fun isProfileThin(profile: CandidateProfile): Boolean =
        profile.currentTitle.isBlank() && profile.previousTitles.isEmpty() &&
            profile.skills.isEmpty() && profile.yearsOfExperience == null

    private fun isFluent(level: LanguageLevel?): Boolean =
        level == LanguageLevel.FLUENT || level == LanguageLevel.NATIVE || level == LanguageLevel.ADVANCED

    /** The facts from the posting that stand in the way, official ones first. */
    private fun blockers(job: Job): List<String> {
        val blockers = mutableListOf<String>()
        if (job.eligibility == MadagascarEligibility.RESTRICTED) {
            blockers += "The employer restricts this role to local applicants."
        } else if (job.eligibility == MadagascarEligibility.UNCLEAR) {
            blockers += "The posting does not document a visa route or sponsorship; confirm it before applying."
        }
        if (job.scamRisk != ScamRisk.LOW) {
            blockers += "The scam shield flagged signals to check before you share any details."
        }
        return blockers
    }

    /** One honest sentence about the route in, or its absence. */
    private fun routeSummary(job: Job): String = when (job.eligibility) {
        MadagascarEligibility.ELIGIBLE ->
            "There is a route in: the posting is open to applicants like you."
        MadagascarEligibility.VISA_SPONSORED ->
            "The employer states sponsorship or relocation. Confirm the conditions with them."
        MadagascarEligibility.UNCLEAR ->
            "No route in is stated. Ask the employer before you invest time."
        MadagascarEligibility.RESTRICTED ->
            "There is no route in from Madagascar for this posting."
    }

    private fun explain(
        job: Job,
        band: InternationalFitBand,
        score: Int,
        components: List<InternationalFitComponent>,
    ): String {
        val weakest = components.minByOrNull { it.earnedShare }?.takeIf { it.earnedShare < 0.5f }
        val base = when (band) {
            InternationalFitBand.EXCELLENT -> "This is one of your strongest international fits at $score%."
            InternationalFitBand.STRONG -> "This is a strong international fit for you at $score%."
            InternationalFitBand.POSSIBLE -> "This is worth a look at $score%."
            InternationalFitBand.WEAK -> "This is a stretch for you at $score%."
            InternationalFitBand.INSUFFICIENT -> "There is not enough on your profile to judge this posting yet."
        }
        val gap = weakest?.let { " The weakest part is ${it.label.lowercase()}: ${it.detail}" } ?: ""
        val expat = if (job.benefitEvidence.isNotEmpty()) {
            " Benefits are scored separately, from what the posting documents."
        } else {
            ""
        }
        return base + gap + expat
    }
}
