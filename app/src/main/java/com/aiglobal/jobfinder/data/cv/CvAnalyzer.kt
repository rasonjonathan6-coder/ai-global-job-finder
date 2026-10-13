package com.aiglobal.jobfinder.data.cv

import com.aiglobal.jobfinder.data.model.CandidateField
import com.aiglobal.jobfinder.data.model.CandidateProfile

/** Outcome of analysing CV text: the structured profile plus how it was produced. */
data class CvAnalysisResult(
    val profile: CandidateProfile,
    val warnings: List<String> = emptyList(),
    /** 0–100, how much of the profile the analyser could actually fill. */
    val confidence: Int = 0,
    /** The snippet each field was derived from, so the user can verify it. */
    val evidence: Map<CandidateField, String> = emptyMap(),
)

/**
 * Turns raw CV text into a [CandidateProfile].
 *
 * Implementations must never fabricate a value: an absent field stays empty or null and
 * is reported as a gap instead. That contract is what makes the downstream matching
 * trustworthy, and it is asserted in the analyser tests.
 */
interface CvAnalyzer {
    /** Stable identifier, persisted so a stored result can be traced to its engine. */
    val id: String

    /** Human label shown in the analysis summary. */
    val label: String

    /** True when the text leaves the device. Drives the privacy notice in the UI. */
    val sendsDataOffDevice: Boolean

    /**
     * @param text the document text
     * @param existing the profile already on file
     * @param protectedFields fields the user edited by hand; these must survive analysis
     */
    suspend fun analyze(
        text: String,
        existing: CandidateProfile,
        protectedFields: Set<CandidateField>,
    ): CvAnalysisResult
}

/**
 * Deterministic, on-device analyser. Everything it reports is matched in the CV text by
 * the [CvLexicon]; nothing is inferred, generated or padded. No network, no telemetry.
 */
class HeuristicCvAnalyzer : CvAnalyzer {

    override val id: String = "on-device-heuristic"
    override val label: String = "On-device analysis"
    override val sendsDataOffDevice: Boolean = false

    override suspend fun analyze(
        text: String,
        existing: CandidateProfile,
        protectedFields: Set<CandidateField>,
    ): CvAnalysisResult {
        val warnings = mutableListOf<String>()
        val evidence = mutableMapOf<CandidateField, String>()

        if (text.count { !it.isWhitespace() } < CvParseResult.MIN_USABLE_CHARACTERS) {
            return CvAnalysisResult(
                profile = existing,
                warnings = listOf("There is not enough text in this document to analyse."),
                confidence = 0,
            )
        }

        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val sections = CvTextStructure.segment(lines)
        val lowerText = text.lowercase()

        val email = EMAIL.find(text)?.value
        val phone = findPhone(text)
        val name = findName(lines)
        val location = findLocation(lines, sections)
        val titles = findTitles(sections, lines)
        val years = findYearsOfExperience(text, sections)
        val skills = matchSkills(lowerText)
        val languages = findLanguages(sections, lines)
        val education = findEducation(sections)
        val certifications = findCertifications(sections, lines)
        val countries = findCountries(text)
        val salary = findSalaryExpectation(text)
        val relocation = findRelocation(lowerText)
        val visaNotes = findVisaNotes(lowerText)
        val industries = findIndustries(lowerText, skills)
        val summary = findSummary(sections, lines)

        if (email == null) warnings += "No email address was found. Add one so applications can be sent."
        if (phone == null) warnings += "No phone number was found."
        if (name == null) warnings += "The candidate name could not be identified. Check the document header."
        if (skills.isEmpty()) warnings += "No recognisable skills were found. Add a skills section to improve matching."
        if (education.isEmpty()) warnings += "No education entries were found."
        if (languages.isEmpty()) warnings += "No languages were listed. Many international roles require a language level."

        val extracted = CandidateProfile(
            fullName = name ?: "",
            email = email ?: "",
            phone = phone ?: "",
            location = location ?: "",
            currentTitle = titles.firstOrNull() ?: "",
            previousTitles = titles.drop(1),
            yearsOfExperience = years,
            skills = skills,
            industries = industries,
            education = education,
            certifications = certifications,
            languages = languages,
            preferredCountries = countries.filter { !it.equals("Madagascar", ignoreCase = true) }.take(8),
            preferredRoles = titles.take(3),
            salaryExpectationMin = salary?.first,
            salaryExpectationMax = salary?.second,
            salaryCurrency = salary?.third,
            relocationPreference = relocation ?: com.aiglobal.jobfinder.data.model.RelocationPreference.UNKNOWN,
            needsVisaSponsorship = if (visaNotes.isNotEmpty()) true else null,
            workPermitNotes = visaNotes.joinToString("; "),
            summary = summary ?: "",
        )

        name?.let { evidence[CandidateField.FULL_NAME] = it }
        email?.let { evidence[CandidateField.EMAIL] = it }
        phone?.let { evidence[CandidateField.PHONE] = it }
        location?.let { evidence[CandidateField.LOCATION] = it }
        titles.firstOrNull()?.let { evidence[CandidateField.CURRENT_TITLE] = it }
        years?.let { evidence[CandidateField.YEARS_OF_EXPERIENCE] = "$it years" }
        if (skills.isNotEmpty()) {
            evidence[CandidateField.SKILLS] = skills.take(6).joinToString(", ") +
                if (skills.size > 6) " +${skills.size - 6} more" else ""
        }
        if (education.isNotEmpty()) evidence[CandidateField.EDUCATION] = education.first().degree
        if (languages.isNotEmpty()) evidence[CandidateField.LANGUAGES] = languages.take(4).joinToString(", ") { it.displayLabel }

        val merged = CvProfileMerger.merge(
            extracted = extracted,
            existing = existing,
            protectedFields = protectedFields,
        )

        return CvAnalysisResult(
            profile = merged,
            warnings = warnings,
            confidence = computeConfidence(extracted),
            evidence = evidence,
        )
    }

    // ---------------------------------------------------------------- extraction

    private fun findName(lines: List<String>): String? {
        // The name is normally one of the first lines, before any section heading.
        return lines.take(6).firstOrNull { line ->
            val words = line.split(Regex("\\s+"))
            words.size in 2..5 &&
                line.length in 5..40 &&
                !line.contains('@') &&
                !line.any { it.isDigit() } &&
                !line.contains("http", ignoreCase = true) &&
                !line.contains('|') &&
                !line.contains(':') &&
                words.all { word -> word.first().isLetter() } &&
                CvLexicon.titleMarkers.none { line.lowercase().contains(it) }
        }?.trim()
    }

    private fun findLocation(lines: List<String>, sections: Map<CvLexicon.Section, List<String>>): String? {
        // Prefer a contact section line, then the header area.
        val pool = (sections[CvLexicon.Section.CONTACT].orEmpty() + lines.take(8))
        val cityCountry = pool.firstOrNull { line ->
            CvLexicon.countries.any { country -> line.contains(country, ignoreCase = true) }
        }
        if (cityCountry != null) return cityCountry.trim().take(60)

        val commaCity = pool.firstOrNull { line ->
            line.contains(',') && line.length in 4..40 && !line.contains('@') &&
                line.split(',').size == 2 && line.all { it.isLetter() || it.isWhitespace() || it == ',' }
        }
        return commaCity?.trim()
    }

    private fun findTitles(
        sections: Map<CvLexicon.Section, List<String>>,
        lines: List<String>,
    ): List<String> {
        val found = linkedSetOf<String>()
        val candidates = sections[CvLexicon.Section.EXPERIENCE].orEmpty() + lines.take(12)
        for (line in candidates) {
            val lower = line.lowercase()
            val marker = CvLexicon.titleMarkers.firstOrNull { lower.contains(it) } ?: continue
            val cleaned = line
                .split('|', '–', '—', '·', ',')
                .firstOrNull { it.lowercase().contains(marker) }
                ?.trim()
                ?.trim('-', '•', '*', ' ')
                ?: continue
            if (cleaned.length in 3..48 && cleaned.split(Regex("\\s+")).size <= 6) {
                found += cleaned.replaceFirstChar { it.uppercase() }
            }
            if (found.size >= 5) break
        }
        return found.toList()
    }

    private fun findYearsOfExperience(
        text: String,
        sections: Map<CvLexicon.Section, List<String>>,
    ): Int? {
        // An explicit statement wins.
        val explicit = listOf(
            Regex("(\\d{1,2})\\s*\\+?\\s*(?:years?|yrs?)\\s*(?:of\\s*)?(?:professional\\s*)?experience", RegexOption.IGNORE_CASE),
            Regex("experience\\s*(?:of|:)?\\s*(\\d{1,2})\\s*(?:years?|yrs?)", RegexOption.IGNORE_CASE),
            Regex("(\\d{1,2})\\s*(?:ans?)\\s*d[’']?\\s*expérience", RegexOption.IGNORE_CASE),
            Regex("plus de\\s*(\\d{1,2})\\s*ans", RegexOption.IGNORE_CASE),
        )
        for (regex in explicit) {
            val match = regex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (match != null && match in 1..60) return match
        }

        // Otherwise derive from the date ranges in the experience section.
        val experienceText = sections[CvLexicon.Section.EXPERIENCE].orEmpty().joinToString("\n")
        if (experienceText.isBlank()) return null

        var earliest: Int? = null
        var latest: Int? = null
        for (range in DATE_RANGE.findAll(experienceText)) {
            val start = range.groupValues[1].toIntOrNull() ?: continue
            val endToken = range.groupValues[2]
            val end = if (endToken.contains("present", ignoreCase = true) ||
                endToken.contains("current", ignoreCase = true) ||
                endToken.contains("now", ignoreCase = true) ||
                endToken.contains("actuel", ignoreCase = true)
            ) {
                CURRENT_YEAR
            } else {
                endToken.toIntOrNull() ?: CURRENT_YEAR
            }
            if (start in 1950..CURRENT_YEAR) {
                if (earliest == null || start < earliest!!) earliest = start
                if (latest == null || end > latest!!) latest = end
            }
        }
        val span = if (earliest != null && latest != null) latest!! - earliest!! else null
        return span?.takeIf { it in 1..60 }
    }

    private fun matchSkills(lowerText: String): List<String> {
        val matches = mutableListOf<Pair<String, Int>>()
        for (definition in CvLexicon.skills) {
            var best = 0
            for (alias in definition.aliases) {
                val occurrences = Regex("\\b" + Regex.escape(alias) + "\\b").findAll(lowerText).count()
                if (occurrences > best) best = occurrences
            }
            if (best > 0) matches += definition.canonical to best
        }
        // Most-mentioned skills first, then alphabetical for stable output.
        return matches.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .map { it.first }
            .distinct()
    }

    private fun findLanguages(
        sections: Map<CvLexicon.Section, List<String>>,
        lines: List<String>,
    ): List<com.aiglobal.jobfinder.data.model.LanguageSkill> {
        val pool = sections[CvLexicon.Section.LANGUAGES].orEmpty().ifEmpty { lines }
        val out = mutableListOf<com.aiglobal.jobfinder.data.model.LanguageSkill>()
        for (line in pool) {
            for (language in CvLexicon.languages) {
                val regex = Regex("\\b" + Regex.escape(language) + "\\b(?:[\\s:–—\\-()]+([A-Za-z]{1,3}\\d?|native|fluent|beginner|intermediate|advanced|basic|courant|moyen|débutant))?", RegexOption.IGNORE_CASE)
                val match = regex.find(line) ?: continue
                val token = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
                val level = com.aiglobal.jobfinder.data.model.LanguageLevel.fromToken(token)
                out += com.aiglobal.jobfinder.data.model.LanguageSkill(
                    name = language,
                    level = level,
                    evidence = line.trim().take(80),
                )
            }
        }
        return out.distinctBy { it.name.lowercase() }
    }

    private fun findEducation(sections: Map<CvLexicon.Section, List<String>>): List<com.aiglobal.jobfinder.data.model.EducationEntry> {
        val pool = sections[CvLexicon.Section.EDUCATION].orEmpty()
        if (pool.isEmpty()) return emptyList()
        val out = mutableListOf<com.aiglobal.jobfinder.data.model.EducationEntry>()
        val lines = pool.map { it.trim() }.filter { it.isNotEmpty() }

        var i = 0
        while (i < lines.size && out.size < 6) {
            val line = lines[i]
            val lower = line.lowercase()
            val hasDegree = CvLexicon.degreeMarkers.any { lower.contains(it) }
            if (!hasDegree) {
                i++
                continue
            }
            // The institution is usually the next line, or the same line after a separator.
            val sameLineInstitution = CvLexicon.institutionMarkers.firstOrNull { lower.contains(it) }
            val next = lines.getOrNull(i + 1)
            val institution = when {
                sameLineInstitution != null -> line
                next != null && CvLexicon.institutionMarkers.any { next.lowercase().contains(it) } -> next
                else -> ""
            }
            val year = YEAR.find(line)?.value?.toIntOrNull()
                ?: next?.let { YEAR.find(it)?.value?.toIntOrNull() }

            val degree = line.split('|', '–', '—', ',')
                .map { it.trim() }
                .firstOrNull { part -> CvLexicon.degreeMarkers.any { part.lowercase().contains(it) } }
                ?.replace(YEAR, "")
                ?.trim(' ', '-', '•')
                ?: line.trim()

            out += com.aiglobal.jobfinder.data.model.EducationEntry(
                degree = degree.take(90),
                institution = if (institution == line) "" else institution.trim().take(90),
                country = "",
                year = year ?: 0,
            )
            i++
        }
        return out
    }

    private fun findCertifications(
        sections: Map<CvLexicon.Section, List<String>>,
        lines: List<String>,
    ): List<com.aiglobal.jobfinder.data.model.CertificationEntry> {
        val pool = sections[CvLexicon.Section.CERTIFICATIONS].orEmpty()
            .ifEmpty { lines.filter { line -> CvLexicon.certificationMarkers.any { line.lowercase().contains(it) } } }
        return pool.mapNotNull { line ->
            val lower = line.lowercase()
            val marker = CvLexicon.certificationMarkers.firstOrNull { lower.contains(it) } ?: return@mapNotNull null
            if (line.length > 120) return@mapNotNull null
            val year = YEAR.find(line)?.value?.toIntOrNull()
            com.aiglobal.jobfinder.data.model.CertificationEntry(
                name = line.trim().trim('-', '•', '*', ' ').take(90),
                issuer = if (line.lowercase().contains(marker) && marker.length > 4) "" else "",
                year = year,
            )
        }.distinctBy { it.name.lowercase() }.take(10)
    }

    private fun findCountries(text: String): List<String> {
        val found = linkedMapOf<String, Int>()
        for (country in CvLexicon.countries) {
            val count = Regex("\\b" + Regex.escape(country) + "\\b").findAll(text).count()
            if (count > 0) found[country] = count
        }
        return found.entries.sortedByDescending { it.value }.map { it.key }
    }

    private fun findSalaryExpectation(text: String): Triple<Int, Int, String>? {
        val currencyPattern = "(EUR|USD|CAD|GBP|CHF|MGA|€|\\$|£)"
        val range = Regex("$currencyPattern\\s*([\\d.,]{2,9})\\s*(?:-|–|to)\\s*$currencyPattern?\\s*([\\d.,]{2,9})", RegexOption.IGNORE_CASE)
            .find(text)
        if (range != null) {
            val currency = normalizeCurrency(range.groupValues[1])
            val min = parseAmount(range.groupValues[2]) ?: return null
            val max = parseAmount(range.groupValues[4].ifBlank { range.groupValues[5] }) ?: min
            if (min in 100..10_000_000 && max in 100..10_000_000) return Triple(min, max, currency)
        }
        val single = Regex("$currencyPattern\\s*([\\d.,]{3,9})", RegexOption.IGNORE_CASE).find(text)
        if (single != null) {
            val amount = parseAmount(single.groupValues[2]) ?: return null
            if (amount in 100..10_000_000) return Triple(amount, amount, normalizeCurrency(single.groupValues[1]))
        }
        return null
    }

    private fun parseAmount(raw: String): Int? {
        val cleaned = raw.replace(Regex("[^\\d]"), "")
        if (cleaned.isEmpty()) return null
        return cleaned.toIntOrNull()
    }

    private fun normalizeCurrency(token: String): String = when (token.uppercase()) {
        "€" -> "EUR"
        "$" -> "USD"
        "£" -> "GBP"
        else -> token.uppercase()
    }

    private fun findRelocation(lowerText: String): com.aiglobal.jobfinder.data.model.RelocationPreference? {
        val noRelocation = lowerText.contains("no relocation") || lowerText.contains("not willing to relocate")
        if (noRelocation) return com.aiglobal.jobfinder.data.model.RelocationPreference.NO_RELOCATION
        if (lowerText.contains("remote only")) return com.aiglobal.jobfinder.data.model.RelocationPreference.REMOTE_ONLY
        if (lowerText.contains("willing to relocate") || lowerText.contains("ready to relocate") ||
            lowerText.contains("open to relocation") || lowerText.contains("mobilité") ||
            lowerText.contains("disponible pour expatriation")
        ) {
            return com.aiglobal.jobfinder.data.model.RelocationPreference.PREFERS_RELOCATION
        }
        if (lowerText.contains("relocation") || lowerText.contains("relocate")) {
            return com.aiglobal.jobfinder.data.model.RelocationPreference.OPEN_TO_RELOCATION
        }
        return null
    }

    private fun findVisaNotes(lowerText: String): List<String> =
        CvLexicon.visaPhrases.filter { lowerText.contains(it.first) }.map { it.second }.distinct()

    private fun findIndustries(lowerText: String, skills: List<String>): List<String> {
        val mapping = mapOf(
            "Manufacturing" to listOf("production line", "manufacturing", "factory", "usine", "assembly"),
            "Industrial maintenance" to listOf("maintenance", "machinery", "conveyor", "machine"),
            "Construction" to listOf("construction", "bâtiment", "chantier", "civil engineering"),
            "Logistics & transport" to listOf("logistics", "warehouse", "supply chain", "transport", "shipping"),
            "Healthcare" to listOf("hospital", "clinic", "patient", "nursing", "healthcare"),
            "Hospitality & tourism" to listOf("hotel", "restaurant", "tourism", "hospitality"),
            "Agriculture" to listOf("agriculture", "farming", "plantation", "sugar", "livestock"),
            "Energy & utilities" to listOf("energy", "power plant", "electricity", "solar", "water treatment"),
            "Mining" to listOf("mining", "mine", "quarry", "extraction"),
            "Information technology" to listOf("software", "it support", "developer", "network", "database"),
            "Education" to listOf("teaching", "school", "university", "training"),
            "Retail & sales" to listOf("retail", "sales", "shop", "store", "commerce"),
            "Automotive" to listOf("automotive", "vehicle", "car", "engine repair"),
        )
        return mapping.filter { (_, keywords) -> keywords.any { lowerText.contains(it) } }
            .keys
            .take(4)
            .toList()
    }

    private fun findSummary(
        sections: Map<CvLexicon.Section, List<String>>,
        lines: List<String>,
    ): String? {
        val pool = sections[CvLexicon.Section.SUMMARY].orEmpty()
        val source = if (pool.isNotEmpty()) {
            pool.joinToString(" ")
        } else {
            // Fall back to the text directly under the name, which is often the headline.
            lines.dropWhile { !it.contains(',') }.drop(1).take(4).joinToString(" ")
        }
        val cleaned = source.replace(Regex("\\s+"), " ").trim()
        return cleaned.take(600).takeIf { it.length >= 30 }
    }

    private fun computeConfidence(profile: CandidateProfile): Int {
        var score = 0
        if (profile.fullName.isNotBlank()) score += 15
        if (profile.email.isNotBlank()) score += 15
        if (profile.phone.isNotBlank()) score += 8
        if (profile.location.isNotBlank()) score += 7
        if (profile.currentTitle.isNotBlank()) score += 15
        if (profile.yearsOfExperience != null) score += 12
        score += (profile.skills.size.coerceAtMost(8) * 2) // up to 16
        if (profile.education.isNotEmpty()) score += 8
        if (profile.languages.isNotEmpty()) score += 4
        return score.coerceIn(0, 100)
    }

    private companion object {
        val EMAIL = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
        val YEAR = Regex("\\b(19|20)\\d{2}\\b")
        val DATE_RANGE = Regex("\\b(19|20\\d{2})\\s*(?:-|–|—|to|à|a)\\s*(present|current|now|actuel|(?:19|20)\\d{2})\\b", RegexOption.IGNORE_CASE)
        val PHONE = Regex("(\\+?\\d[\\d\\s().\\-]{6,18}\\d)")
        const val CURRENT_YEAR = 2026

        /**
         * Finds a phone number, rejecting anything whose digit count is outside the
         * E.164-plausible range so dates and reference numbers are not mistaken for it.
         */
        fun findPhone(text: String): String? {
            for (match in PHONE.findAll(text)) {
                val value = match.value.trim()
                val digits = value.count { it.isDigit() }
                if (digits in 7..15) return value.replace(Regex("\\s+"), " ")
            }
            return null
        }
    }
}
