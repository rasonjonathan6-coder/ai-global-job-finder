package com.aiglobal.jobfinder.data.jobs.engine

import androidx.compose.runtime.Immutable
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.MatchReason
import com.aiglobal.jobfinder.data.model.MatchSignal
import com.aiglobal.jobfinder.data.model.MatchWeights
import kotlin.math.roundToInt

/** The full result of scoring one posting against the candidate profile. */
@Immutable
data class MatchAssessment(
    val score: Int,
    val reasons: List<MatchReason>,
    val matchedSkills: List<String>,
    val missingSkills: List<String>,
    val requiredYears: Int?,
    val requiredLanguages: List<String>,
)

/**
 * Scores a posting against the candidate profile.
 *
 * The score is the sum of six weighted signals, each capped at its own weight. Every
 * signal also produces a [MatchReason] explaining how many points it contributed, so
 * the number shown on the card can always be opened up and read. When the profile is
 * empty the score is low by construction rather than fabricated.
 */
class MatchingEngine(private val weights: MatchWeights = MatchWeights()) {

    fun assess(job: MatchInput, profile: CandidateProfile): MatchAssessment {
        val reasons = mutableListOf<MatchReason>()

        val candidateSkills = profile.skills.map { SkillLexicon.canonicalize(it) }.toSet()
        val requiredSkills = SkillLexicon.detect(
            buildString {
                append(job.title).append(' ')
                append(job.tags.joinToString(" ")).append(' ')
                append(job.description)
            },
        )

        val matched = requiredSkills.intersect(candidateSkills).toList().sorted()
        val missing = requiredSkills.subtract(candidateSkills).toList().sorted()

        var score = 0

        // ---- Role / title alignment
        // Computed before skills because it gates how much a skill overlap is worth: a
        // single familiar tool in an otherwise unrelated posting is not a skill match.
        val titleRatio = titleSimilarity(profile, job.title)
        val titlePoints = (weights.title * titleRatio).roundToInt()
        score += titlePoints
        reasons += MatchReason(
            label = "Role",
            detail = when {
                titleRatio >= 0.75f -> "Your current and previous titles closely match \"${job.title}\"."
                titleRatio >= 0.35f -> "Your background overlaps partly with \"${job.title}\"."
                else -> "Your recorded titles do not closely match \"${job.title}\"."
            },
            signal = when {
                titleRatio >= 0.5f -> MatchSignal.POSITIVE
                titleRatio >= 0.25f -> MatchSignal.NEUTRAL
                else -> MatchSignal.NEGATIVE
            },
            weight = titlePoints,
        )

        // ---- Skills
        val rawSkillRatio = if (requiredSkills.isEmpty()) {
            // Nothing comparable detected: award a neutral share rather than a perfect one.
            0.45f
        } else {
            matched.size.toFloat() / requiredSkills.size
        }
        // A skill overlap only counts when the posting is for a role the candidate does.
        // Without this gate, one incidentally-detected tool ("CMMS" in a marketing job)
        // awarded the full skill weight and pushed an unrelated posting to a high score.
        val skillGate = when {
            requiredSkills.isEmpty() || titleRatio >= 0.35f -> 1f
            titleRatio >= 0.2f -> 0.5f
            else -> 0.2f
        }
        val skillPoints = (weights.skills * rawSkillRatio * skillGate).roundToInt()
        score += skillPoints
        reasons += MatchReason(
            label = "Skills",
            detail = when {
                requiredSkills.isEmpty() && candidateSkills.isEmpty() ->
                    "No comparable skills found in the posting or your profile yet."
                requiredSkills.isEmpty() ->
                    "The posting does not name specific skills, so this part is scored neutrally."
                matched.isEmpty() ->
                    "None of the ${requiredSkills.size} skills named in the posting appear in your profile."
                skillGate < 1f ->
                    "The posting names a skill you have, but it is not a role you do; the overlap counts for little."
                missing.isEmpty() ->
                    "You match all ${requiredSkills.size} skills named in the posting."
                else ->
                    "You match ${matched.size} of ${requiredSkills.size} skills named in the posting."
            },
            signal = when {
                requiredSkills.isEmpty() -> MatchSignal.NEUTRAL
                matched.isEmpty() || skillGate < 1f -> MatchSignal.NEGATIVE
                else -> MatchSignal.POSITIVE
            },
            weight = skillPoints,
        )

        // ---- Experience
        val requiredYears = requiredYears(job.description)
        val candidateYears = profile.yearsOfExperience ?: 0
        val experienceRatio = when {
            requiredYears == null -> 0.7f
            candidateYears >= requiredYears -> 1f
            candidateYears + 1 >= requiredYears -> 0.7f
            requiredYears == 0 -> 1f
            else -> (candidateYears.toFloat() / requiredYears).coerceIn(0.15f, 0.6f)
        }
        val experiencePoints = (weights.experience * experienceRatio).roundToInt()
        score += experiencePoints
        reasons += MatchReason(
            label = "Experience",
            detail = when {
                profile.yearsOfExperience == null -> "Your profile does not state your years of experience yet."
                requiredYears == null -> "The posting does not state a required number of years."
                candidateYears >= requiredYears -> "Your $candidateYears years cover the ${requiredYears}+ years requested."
                else -> "The posting asks for ${requiredYears}+ years; your profile shows $candidateYears."
            },
            signal = when {
                requiredYears == null -> MatchSignal.NEUTRAL
                candidateYears >= requiredYears -> MatchSignal.POSITIVE
                else -> MatchSignal.NEGATIVE
            },
            weight = experiencePoints,
        )

        // ---- Location preference
        val preferred = profile.preferredCountries.map { it.lowercase().trim() }
        val locationRatio = when {
            preferred.isEmpty() -> 0.6f
            preferred.any { it == job.country.lowercase() } -> 1f
            job.country.equals("Worldwide", ignoreCase = true) -> 0.8f
            else -> 0.3f
        }
        val locationPoints = (weights.location * locationRatio).roundToInt()
        score += locationPoints
        reasons += MatchReason(
            label = "Location",
            detail = when {
                preferred.isEmpty() -> "You have not chosen preferred countries, so location is scored neutrally."
                preferred.any { it == job.country.lowercase() } -> "${job.country} is one of your preferred countries."
                job.country.equals("Worldwide", ignoreCase = true) -> "This is a worldwide remote role, which fits any location."
                else -> "${job.country} is not in your preferred countries."
            },
            signal = when {
                preferred.isEmpty() -> MatchSignal.NEUTRAL
                locationRatio >= 0.8f -> MatchSignal.POSITIVE
                else -> MatchSignal.NEGATIVE
            },
            weight = locationPoints,
        )

        // ---- Language
        val requiredLanguages = languagesIn(job.description + " " + job.tags.joinToString(" "))
        val candidateLanguages = profile.languages.map { it.name.lowercase() }.toSet()
        val languageRatio = when {
            requiredLanguages.isEmpty() -> 0.6f
            else -> {
                val have = requiredLanguages.count { req -> candidateLanguages.any { it.contains(req.lowercase()) } }
                have.toFloat() / requiredLanguages.size
            }
        }
        val languagePoints = (weights.language * languageRatio).roundToInt()
        score += languagePoints
        reasons += MatchReason(
            label = "Language",
            detail = when {
                requiredLanguages.isEmpty() -> "The posting does not name a required language."
                candidateLanguages.isEmpty() -> "The posting asks for ${requiredLanguages.joinToString(", ")}; your profile lists no languages."
                languageRatio >= 1f -> "You list ${requiredLanguages.joinToString(", ")}, which the posting asks for."
                else -> "The posting asks for ${requiredLanguages.joinToString(", ")}; check the level required."
            },
            signal = when {
                requiredLanguages.isEmpty() -> MatchSignal.NEUTRAL
                languageRatio >= 1f -> MatchSignal.POSITIVE
                else -> MatchSignal.NEGATIVE
            },
            weight = languagePoints,
        )

        // ---- Recency
        val recencyRatio = when (val days = job.postedDaysAgo) {
            null -> 0.6f
            in 0..7 -> 1f
            in 8..30 -> 0.6f
            else -> if (days > 30) 0.3f else 0.6f
        }
        val recencyPoints = (weights.recency * recencyRatio).roundToInt()
        score += recencyPoints
        reasons += MatchReason(
            label = "Freshness",
            detail = when (val days = job.postedDaysAgo) {
                null -> "The source did not publish a posting date."
                in 0..7 -> "Posted in the last week."
                in 8..30 -> "Posted within the last month."
                else -> "Posted over a month ago; the role may be filled."
            },
            signal = when {
                job.postedDaysAgo == null -> MatchSignal.NEUTRAL
                job.postedDaysAgo!! <= 7 -> MatchSignal.POSITIVE
                else -> MatchSignal.NEUTRAL
            },
            weight = recencyPoints,
        )

        val finalScore = score.coerceIn(5, 99)
        return MatchAssessment(
            score = finalScore,
            reasons = reasons,
            matchedSkills = matched,
            missingSkills = missing,
            requiredYears = requiredYears,
            requiredLanguages = requiredLanguages,
        )
    }

    /** Fraction of the posting's title words that appear in the candidate's titles. */
    private fun titleSimilarity(profile: CandidateProfile, jobTitle: String): Float {
        val candidateTokens = buildList {
            profile.currentTitle?.let { add(it) }
            addAll(profile.previousTitles)
            addAll(profile.preferredRoles)
        }.flatMap { tokens(it) }.toSet()
        val jobTokens = tokens(jobTitle)
        if (jobTokens.isEmpty() || candidateTokens.isEmpty()) return 0f
        val overlap = jobTokens.count { it in candidateTokens }
        return (overlap.toFloat() / jobTokens.size).coerceIn(0f, 1f)
    }

    private val stopWords = setOf(
        "and", "or", "the", "a", "an", "of", "for", "with", "to", "in", "at", "on",
        "senior", "junior", "lead", "staff", "m", "f", "d", "h", "w",
    )

    private fun tokens(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9+#]+"))
            .map { it.trim() }
            .filter { it.length >= 2 && it !in stopWords }

    /** Smallest "N years" requirement stated in the description. */
    private fun requiredYears(description: String): Int? =
        yearPattern.findAll(description.lowercase())
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 1..40 }
            .minOrNull()

    private fun languagesIn(text: String): List<String> {
        val lower = text.lowercase()
        return languageNames.filter { lower.contains(it.lowercase()) }
    }

    private companion object {
        val yearPattern = Regex("(\\d{1,2})\\s*\\+?\\s*(?:to\\s*\\d{1,2}\\s*)?(?:years|yrs|year)")
        val languageNames = listOf(
            "English", "French", "German", "Spanish", "Portuguese", "Dutch",
            "Italian", "Arabic", "Mandarin", "Russian", "Swedish", "Norwegian",
        )
    }
}

/** Everything the matching engine may read from a posting. */
@Immutable
data class MatchInput(
    val title: String,
    val country: String,
    val description: String,
    val tags: List<String> = emptyList(),
    /** Days since the posting was published, or null when the source omitted the date. */
    val postedDaysAgo: Int? = null,
)
