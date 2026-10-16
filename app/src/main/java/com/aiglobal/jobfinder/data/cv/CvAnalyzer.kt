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
        // Matching runs on an accent-folded copy; values shown to the user keep their accents.
        val foldedText = CvTextNormalizer.fold(text)

        val email = EMAIL.find(text)?.value
        val phone = findPhone(text)
        val name = findName(lines, sections)
        val location = findLocation(lines, sections)
        val titles = findTitles(sections, lines)
        val years = findYearsOfExperience(text, sections)
        val skills = matchSkills(foldedText)
        val languages = findLanguages(sections, lines)
        val education = findEducation(sections)
        val certifications = findCertifications(sections, lines)
        val countries = findCountries(foldedText)
        val salary = findSalaryExpectation(text)
        val relocation = findRelocation(foldedText)
        val visaNotes = findVisaNotes(foldedText)
        val industries = findIndustries(foldedText, skills)
        val summary = findSummary(sections, lines)

        warnings += sectionWarnings(sections, skills, education, languages)

        if (email == null) warnings += "No email address was found. Add one so applications can be sent."
        if (phone == null) warnings += "No phone number was found."
        if (name == null) warnings += "The candidate name could not be identified. Check the document header."

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

    /**
     * Finds the candidate name. The header area is searched first, then a Contact section,
     * because many CVs put the name under "Contact" rather than at the very top.
     *
     * A line only qualifies when its shape really looks like a person's name: no digits,
     * no e-mail, no web address, not a heading, not a job title, and every word starting
     * with a letter. Nothing is invented — if no line qualifies, the field stays empty.
     */
    private fun findName(
        lines: List<String>,
        sections: Map<CvLexicon.Section, List<String>>,
    ): String? {
        val header = lines.take(8)
        val contact = sections[CvLexicon.Section.CONTACT].orEmpty().take(4)
        return (header + contact).firstOrNull { isNameLike(it) }?.trim()
    }

    private fun isNameLike(line: String): Boolean {
        val trimmed = line.trim()
        if (CvTextStructure.headingFor(trimmed) != null) return false
        if (trimmed.any { it.isDigit() }) return false
        if (trimmed.contains('@') || trimmed.contains("http", ignoreCase = true)) return false
        if (trimmed.contains('|') || trimmed.contains(':') || trimmed.contains(',')) return false
        if (CvTextNormalizer.isBullet(trimmed)) return false

        val words = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size !in 2..5) return false
        if (trimmed.length !in 5..44) return false
        // Every word must start with a letter; this removes "Ref: 2024", "Tél:", etc.
        if (!words.all { it.first().isLetter() }) return false
        // A job-title line is a headline, not a name.
        val folded = CvTextNormalizer.fold(trimmed)
        if (CvLexicon.titleMarkers.any { folded.contains(it) }) return false
        if (CvLexicon.sectionKeywords.containsKey(folded)) return false
        // A name has at least two capitalised words and no all-caps sentence words.
        val capitalised = words.count { it.first().isUpperCase() }
        return capitalised >= 2
    }

    /**
     * Warnings that distinguish a genuinely absent section from one that is present but
     * could not be read. The user sees "we could not read" only when the document actually
     * contained a matching section, which tells them it is worth correcting by hand.
     */
    private fun sectionWarnings(
        sections: Map<CvLexicon.Section, List<String>>,
        skills: List<String>,
        education: List<Any>,
        languages: List<Any>,
    ): List<String> {
        val out = mutableListOf<String>()

        // A section exists when a heading introduces content that follows it. A lone heading
        // with nothing under it is treated as absent, so the message stays truthful.
        fun linesUnder(section: CvLexicon.Section): List<String> {
            val body = sections[section].orEmpty().filter { it.isNotBlank() }
            val headings = body.count { CvTextStructure.headingFor(it) != null }
            return if (body.size > headings) body else emptyList()
        }

        if (skills.isEmpty()) {
            out += if (linesUnder(CvLexicon.Section.SKILLS).isNotEmpty()) {
                "A skills section was found but no known skills were recognised in it. Add yours by hand."
            } else {
                "No skills section was found. Add your skills to improve matching."
            }
        }
        if (education.isEmpty()) {
            out += if (linesUnder(CvLexicon.Section.EDUCATION).isNotEmpty()) {
                "A training section was found but no degree or diploma could be read from it."
            } else {
                "No education or training section was found."
            }
        }
        if (languages.isEmpty()) {
            out += if (linesUnder(CvLexicon.Section.LANGUAGES).isNotEmpty()) {
                "A languages section was found but no known language was recognised in it."
            } else {
                "No languages were listed. Many international roles require a language level."
            }
        }
        return out
    }

    private fun findLocation(lines: List<String>, sections: Map<CvLexicon.Section, List<String>>): String? {
        // Prefer a contact section line, then the header area.
        val pool = (sections[CvLexicon.Section.CONTACT].orEmpty() + lines.take(10))

        // 1. A line naming a known country or major city, matched on folded text so an
        //    accented spelling behaves like the plain one.
        val knownPlace = pool.firstOrNull { line ->
            val folded = CvTextNormalizer.fold(line)
            CvLexicon.foldedPlaces.any { place ->
                Regex("\\b" + Regex.escape(place) + "\\b").containsMatchIn(folded)
            }
        }
        if (knownPlace != null) return knownPlace.trim().take(60)

        // 2. A "City, Country"-shaped line: two comma-separated parts, letters only.
        val commaCity = pool.firstOrNull { line ->
            line.contains(',') && line.length in 4..40 && !line.contains('@') &&
                line.split(',').size == 2 &&
                line.all { it.isLetter() || it.isWhitespace() || it == ',' || it == '-' || it == '.' || it == '\'' }
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
            val folded = CvTextNormalizer.fold(line)
            val marker = CvLexicon.titleMarkers.firstOrNull { folded.contains(it) } ?: continue
            val cleaned = line
                .split('|', '–', '—', '·', ',', '/')
                .firstOrNull { CvTextNormalizer.fold(it).contains(marker) }
                ?.trim()
                ?.trim('-', '•', '*', ' ', ':')
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

    private fun matchSkills(foldedText: String): List<String> {
        val matches = mutableListOf<Pair<String, Int>>()
        for (definition in CvLexicon.skills) {
            var best = 0
            for (alias in definition.aliases) {
                val occurrences = Regex("\\b" + Regex.escape(CvTextNormalizer.fold(alias)) + "\\b")
                    .findAll(foldedText).count()
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
        val seen = mutableSetOf<String>()
        for (line in pool) {
            // Fold the line so "Anglais", "francais" and "Espanol" all match their alias.
            val foldedLine = CvTextNormalizer.fold(line)
            for ((canonical, aliases) in CvLexicon.languageAliases) {
                if (seen.contains(canonical)) continue
                val hit = aliases.firstOrNull { alias ->
                    Regex("\\b" + Regex.escape(CvTextNormalizer.fold(alias)) + "\\b")
                        .containsMatchIn(foldedLine)
                } ?: continue

                // The level word sits right after the language name ("English - fluent").
                val levelRegex = Regex(
                    Regex.escape(CvTextNormalizer.fold(hit)) +
                        "(?:[\\s:-]+([a-z]{1,12}\\d?|native|fluent|beginner|intermediate|advanced|basic|courant|moyen|debutant|bilingue|natif|avance|maternelle))?",
                )
                val token = levelRegex.find(foldedLine)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                val level = com.aiglobal.jobfinder.data.model.LanguageLevel.fromToken(token)
                out += com.aiglobal.jobfinder.data.model.LanguageSkill(
                    name = canonical,
                    level = level,
                    evidence = line.trim().take(80),
                )
                seen += canonical
            }
        }
        return out
    }

    private fun findEducation(sections: Map<CvLexicon.Section, List<String>>): List<com.aiglobal.jobfinder.data.model.EducationEntry> {
        val pool = sections[CvLexicon.Section.EDUCATION].orEmpty()
        if (pool.isEmpty()) return emptyList()
        val out = mutableListOf<com.aiglobal.jobfinder.data.model.EducationEntry>()
        val lines = pool.map { it.trim() }.filter { it.isNotEmpty() }

        var i = 0
        while (i < lines.size && out.size < 6) {
            val line = lines[i]
            // Degree markers are matched on folded text, so "Ingenieur" and "Ingénieur" agree.
            val folded = CvTextNormalizer.fold(line)
            val hasDegree = CvLexicon.degreeMarkers.any { folded.contains(it) }
            if (!hasDegree) {
                i++
                continue
            }

            val next = lines.getOrNull(i + 1)
            val foldedNext = next?.let { CvTextNormalizer.fold(it) }

            // The institution is usually the next line, or the same line after a separator.
            val sameLineInstitution = CvLexicon.institutionMarkers.any { folded.contains(it) }
            val nextIsInstitution = foldedNext != null &&
                CvLexicon.institutionMarkers.any { foldedNext.contains(it) } &&
                CvLexicon.degreeMarkers.none { foldedNext.contains(it) }

            // Split on the separators a CV uses to put degree and school on one line.
            val parts = line.split('|', ';', ',', '—', '·').map { it.trim() }.filter { it.isNotEmpty() }
            val degree = parts
                .firstOrNull { part ->
                    val f = CvTextNormalizer.fold(part)
                    CvLexicon.degreeMarkers.any { f.contains(it) } &&
                        CvLexicon.institutionMarkers.none { f.contains(it) }
                }
                ?: parts.firstOrNull()
                ?: line

            val institution = when {
                nextIsInstitution -> next!!.trim()
                parts.size >= 2 -> parts.drop(1)
                    .firstOrNull { part ->
                        CvLexicon.institutionMarkers.any { CvTextNormalizer.fold(part).contains(it) }
                    }
                    .orEmpty()
                sameLineInstitution -> ""
                else -> ""
            }

            val year = YEAR.find(line)?.value?.toIntOrNull()
                ?: next?.let { YEAR.find(it)?.value?.toIntOrNull() }

            out += com.aiglobal.jobfinder.data.model.EducationEntry(
                degree = degree.replace(YEAR, "").trim(' ', '-', '•', ',').take(90),
                institution = institution.replace(YEAR, "").trim(' ', '-', '•', ',').take(90),
                country = "",
                year = year ?: 0,
            )
            // Skip the institution line we just consumed.
            i += if (nextIsInstitution) 2 else 1
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
        // [text] is the accent-folded, lowercased document, so the country names are folded too.
        val found = linkedMapOf<String, Int>()
        for (country in CvLexicon.countries) {
            val folded = CvTextNormalizer.fold(country)
            val count = Regex("\\b" + Regex.escape(folded) + "\\b").findAll(text).count()
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
