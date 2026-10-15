package com.aiglobal.jobfinder.data.cv

import androidx.compose.runtime.Immutable
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.CvRecord

/** How strongly the app can back one adaptation suggestion with something it observed. */
enum class AdaptationConfidence(val label: String) {
    DOCUMENTED("Backed by your CV"),
    INFERRED("Suggested from your CV"),
    MISSING("Needs your input"),
}

/** One concrete change to make the CV fit a target country's conventions. */
@Immutable
data class AdaptationSuggestion(
    val title: String,
    val detail: String,
    val confidence: AdaptationConfidence,
)

/** The adapted view of a CV for one destination, plus the reasoning behind each change. */
@Immutable
data class CvAdaptation(
    val country: String,
    val countryFlag: String,
    val language: String,
    val dateFormat: String,
    val pageTarget: String,
    val includesPhoto: Boolean,
    val suggestions: List<AdaptationSuggestion>,
    /**
     * True when the underlying CV has no usable content, so every suggestion is a
     * prompt to import one rather than an adaptation of real data.
     */
    val needsCv: Boolean,
) {
    val documentedCount: Int get() = suggestions.count { it.confidence == AdaptationConfidence.DOCUMENTED }
    val missingCount: Int get() = suggestions.count { it.confidence == AdaptationConfidence.MISSING }
}

/**
 * Builds a country-specific adaptation of the analysed CV.
 *
 * Every suggestion is either derived from a value actually present in the CV, or an
 * explicit prompt for something that is missing. Nothing about the candidate is invented:
 * the engine never rewrites their history, it only tells them what that country's
 * recruiters expect and what their CV currently lacks.
 */
class CvAdaptationEngine {

    fun adapt(record: CvRecord?, country: String): CvAdaptation {
        val profile = record?.profile ?: CandidateProfile()
        val convention = conventionFor(country)
        val needsCv = profile.fullName.isBlank() && profile.skills.isEmpty()

        return CvAdaptation(
            country = country,
            countryFlag = flagFor(country),
            language = convention.language,
            dateFormat = convention.dateFormat,
            pageTarget = convention.pageTarget,
            includesPhoto = convention.includesPhoto,
            needsCv = needsCv,
            suggestions = buildList {
                add(
                    AdaptationSuggestion(
                        title = "Contact block",
                        detail = if (profile.fullName.isNotBlank() && profile.email.isNotBlank()) {
                            "Keep your name, email and phone at the top. Add your location as " +
                                "\"${profile.location.ifBlank { "your city" }}, Madagascar\" so the recruiter " +
                                "sees your base before the first screen."
                        } else {
                            "Add your full name and a working email at the very top — ${convention.language} " +
                                "recruiters read this block first."
                        },
                        confidence = if (profile.fullName.isNotBlank() && profile.email.isNotBlank()) {
                            AdaptationConfidence.DOCUMENTED
                        } else {
                            AdaptationConfidence.MISSING
                        },
                    ),
                )

                add(
                    AdaptationSuggestion(
                        title = "Headline and summary",
                        detail = if (profile.currentTitle.isNotBlank()) {
                            "Lead with \"${profile.currentTitle}\" and rewrite the opening line for " +
                                "$country, naming the years of experience (${profile.yearsOfExperience ?: "—"}) " +
                                "and the industries you know."
                        } else {
                            "Add a one-line headline naming the role you are targeting, then a three-line " +
                                "summary. $country recruiters scan this before the skills list."
                        },
                        confidence = if (profile.currentTitle.isNotBlank()) {
                            AdaptationConfidence.DOCUMENTED
                        } else {
                            AdaptationConfidence.MISSING
                        },
                    ),
                )

                add(
                    AdaptationSuggestion(
                        title = "Skills framing",
                        detail = if (profile.skills.isNotEmpty()) {
                            "Regroup your ${profile.skills.size} skills under the terms $country employers " +
                                "use, technical first, and put the ${profile.skills.take(3).joinToString(", ")} " +
                                "at the top where they are scanned."
                        } else {
                            "List your technical skills as short, single keywords. Our matcher reads them " +
                                "to rank you against real postings."
                        },
                        confidence = if (profile.skills.isNotEmpty()) {
                            AdaptationConfidence.DOCUMENTED
                        } else {
                            AdaptationConfidence.MISSING
                        },
                    ),
                )

                add(
                    AdaptationSuggestion(
                        title = "Languages",
                        detail = if (profile.languages.isNotEmpty()) {
                            "State each language with a level (${
                                profile.languages.joinToString(", ") { it.displayLabel }
                            }). Make the working language of $country, ${convention.language}, explicit."
                        } else {
                            "Add your languages with a level. A stated level is what lets an employer " +
                                "believe you can work in ${convention.language}."
                        },
                        confidence = if (profile.languages.isNotEmpty()) {
                            AdaptationConfidence.DOCUMENTED
                        } else {
                            AdaptationConfidence.MISSING
                        },
                    ),
                )

                add(
                    AdaptationSuggestion(
                        title = "Education and certifications",
                        detail = if (profile.education.isNotEmpty() || profile.certifications.isNotEmpty()) {
                            "Keep the ${profile.education.size} qualification(s) and " +
                                "${profile.certifications.size} certification(s), newest first. Add the " +
                                "issuing country next to each so a foreign recruiter can read the level."
                        } else {
                            "Add your highest qualification and any certificates. Foreign recruiters often " +
                                "cannot judge a local diploma without the level stated."
                        },
                        confidence = if (profile.education.isNotEmpty() || profile.certifications.isNotEmpty()) {
                            AdaptationConfidence.DOCUMENTED
                        } else {
                            AdaptationConfidence.MISSING
                        },
                    ),
                )

                add(
                    AdaptationSuggestion(
                        title = "Format and layout",
                        detail = "Use ${convention.dateFormat} dates, ${convention.pageTarget} and " +
                            "${if (convention.includesPhoto) "include a professional photo only where the destination permits it" else "leave out the photo — $country CVs normally do not include one"}.",
                        confidence = AdaptationConfidence.INFERRED,
                    ),
                )
            },
        )
    }

    private data class Convention(
        val language: String,
        val dateFormat: String,
        val pageTarget: String,
        val includesPhoto: Boolean,
    )

    private fun conventionFor(country: String): Convention = when (country.lowercase()) {
        "germany", "austria", "switzerland" ->
            Convention("German", "day.month.year (e.g. 12.03.2025)", "a two-page maximum, tabular format", true)
        "canada", "united states", "usa", "united kingdom", "uk", "ireland", "australia", "new zealand" ->
            Convention("English", "month name + year (e.g. March 2025)", "a two-page maximum, no photo", false)
        "united arab emirates", "uae", "saudi arabia", "qatar", "kuwait", "oman", "bahrain" ->
            Convention("English", "month name + year", "two to three pages, include visa status and nationality", true)
        "france", "belgium", "luxembourg" ->
            Convention("French", "day/month/year", "a single page, no photo", true)
        "japan", "south korea" ->
            Convention("English or the local language", "year/month", "a two-page maximum, photo is conventional", true)
        else -> Convention("English", "month name + year", "a two-page maximum", false)
    }

    private fun flagFor(country: String): String = when (country.lowercase()) {
        "canada" -> "🇨🇦"
        "germany" -> "🇩🇪"
        "france" -> "🇫🇷"
        "united kingdom", "uk" -> "🇬🇧"
        "united states", "usa" -> "🇺🇸"
        "australia" -> "🇦🇺"
        "new zealand" -> "🇳🇿"
        "united arab emirates", "uae" -> "🇦🇪"
        "saudi arabia" -> "🇸🇦"
        "qatar" -> "🇶🇦"
        "netherlands" -> "🇳🇱"
        "belgium" -> "🇧🇪"
        "switzerland" -> "🇨🇭"
        "austria" -> "🇦🇹"
        "ireland" -> "🇮🇪"
        "japan" -> "🇯🇵"
        "south korea" -> "🇰🇷"
        else -> "🌍"
    }
}
