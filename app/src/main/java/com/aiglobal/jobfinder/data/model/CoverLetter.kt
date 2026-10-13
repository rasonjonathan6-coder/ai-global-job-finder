package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** Languages the on-device cover-letter writer can produce. */
@Serializable
enum class LetterLanguage(val label: String, val code: String, val flag: String) {
    ENGLISH("English", "en", "🇬🇧"),
    FRENCH("French", "fr", "🇫🇷"),
    GERMAN("German", "de", "🇩🇪");

    companion object {
        /**
         * Picks the language an employer most likely expects. France and most of
         * francophone Africa read French; Germany, Austria and Switzerland read German.
         * Everything else defaults to English, which is the safest international choice.
         */
        fun forCountry(country: String): LetterLanguage = when (country.trim().lowercase()) {
            "france", "belgium", "luxembourg", "monaco", "madagascar", "morocco", "tunisia",
            "senegal", "ivory coast", "côte d'ivoire", "cameroon", "canada" -> FRENCH
            "germany", "austria", "switzerland", "liechtenstein" -> GERMAN
            else -> ENGLISH
        }
    }
}

/**
 * A cover letter written from the candidate's own CV and the posting. Nothing is
 * invented: [usedFacts] lists exactly what the generator had, and [missingFacts] lists
 * what it did not, so the user can see the letter is grounded rather than fabricated.
 */
@Serializable
@Immutable
data class CoverLetter(
    val id: String,
    val jobId: String,
    val language: LetterLanguage,
    val subject: String,
    val salutation: String,
    val paragraphs: List<String>,
    val closing: String,
    val signature: String,
    val usedFacts: List<String>,
    val missingFacts: List<String>,
    val generatedAtEpochMillis: Long,
    /** True once the user has edited the body by hand. */
    val edited: Boolean = false,
) {
    /** The whole letter as plain text, ready to copy or export. */
    fun asPlainText(): String = buildString {
        appendLine(subject)
        appendLine()
        appendLine(salutation)
        appendLine()
        paragraphs.forEach { paragraph ->
            appendLine(paragraph)
            appendLine()
        }
        appendLine(closing)
        append(signature)
    }

    val wordCount: Int
        get() = (paragraphs.joinToString(" ").split(Regex("\\s+")).count { it.isNotBlank() })
}
