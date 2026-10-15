package com.aiglobal.jobfinder.data.jobs.search

import com.aiglobal.jobfinder.data.model.JobRequirement
import com.aiglobal.jobfinder.data.model.JobRequirements

/**
 * Reads the requirements a posting states about years of experience, certifications and
 * licences. It is a pure function of the text and never invents a requirement: a phrase
 * only counts when it actually appears.
 */
object RequirementAnalyzer {

    /** Certification keywords that matter for industrial maintenance roles. */
    private val certificationTerms = listOf(
        "nvq", "city & guilds", "city and guilds", "bs en", "iso 9712", "asnt", "aws certified",
        "certified welder", "welding certificate", "electrical license", "electrical licence",
        "licensed electrician", "licenced electrician", "cscs card", "compEx", "comp ex",
        "hydraulic certificate", "pneumatic certificate", "first aid certificate",
        "confined space", "working at height", "forklift licence", "forklift license",
        "rigging certificate", "rigger", "trade certificate", "trade test", "red seal",
        "vocational qualification", "technical diploma", "engineering diploma",
    )

    /** Licence/permit terms that gate a trades role. */
    private val licenceTerms = listOf(
        "driving licence", "driving license", "valid driver", "clean driving",
        "work permit", "work permit required", "right to work", "work authorisation",
        "work authorization", "cscs", "site card", "medical certificate", "medical fitness",
    )

    private val yearPattern = Regex(
        "(\\d{1,2})\\s*(?:-\\s*\\d{1,2}\\s*)?\\+?\\s*(?:years|yrs|year)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun analyze(description: String): JobRequirements {
        val lower = description.lowercase()
        // Sentences keep their original casing so the evidence reads as the employer wrote it.
        val sentences = description.split(Regex("[.\\n;!?•]+")).map { it.trim() }.filter { it.length >= 4 }

        val years = yearPattern.findAll(lower)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 1..40 }
            .minOrNull()

        val certs = certificationTerms.mapNotNull { term ->
            sentenceWith(sentences, term)?.let { JobRequirement(label = displayLabel(term), evidence = it) }
        }.distinctBy { it.label }

        val licences = licenceTerms.mapNotNull { term ->
            sentenceWith(sentences, term)?.let { JobRequirement(label = displayLabel(term), evidence = it) }
        }.distinctBy { it.label }

        return JobRequirements(minYears = years, certifications = certs, licences = licences)
    }

    private fun sentenceWith(sentences: List<String>, term: String): String? =
        sentences.firstOrNull { Phrases.contains(it.lowercase(), term) }

    private fun displayLabel(term: String): String = term.split(" ").joinToString(" ") { word ->
        if (word.length <= 3 && word !in setOf("iso", "aws", "nvq", "cscs")) word else
            word.replaceFirstChar { it.uppercase() }
    }
}
