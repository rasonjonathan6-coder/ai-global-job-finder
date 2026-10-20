package com.aiglobal.jobfinder.data.cv.analysis

/**
 * Splits a document into its named sections. Real CVs are inconsistent, so this is
 * deliberately forgiving: a line counts as a heading if it is short, has no sentence
 * punctuation and matches a known heading word. Text before the first heading stays
 * available as header content.
 */
object CvTextStructure {

    private const val MAX_HEADING_LENGTH = 42
    private const val MAX_HEADING_WORDS = 6

    fun segment(lines: List<String>): Map<CvLexicon.Section, List<String>> {
        val result = linkedMapOf<CvLexicon.Section, MutableList<String>>()
        var current = CvLexicon.Section.UNKNOWN

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val heading = headingFor(line)
            if (heading != null) {
                current = heading
                result.getOrPut(current) { mutableListOf() }
                continue
            }
            result.getOrPut(current) { mutableListOf() }.add(line)
        }

        return result
    }

    /** Returns the section a line announces, or null when the line is body text. */
    fun headingFor(line: String): CvLexicon.Section? {
        val trimmed = line.trim().trim(':', '•', '-', '*', '=', '_', '|', ' ').trim()
        if (trimmed.length > MAX_HEADING_LENGTH) return null
        val words = trimmed.split(Regex("\\s+"))
        if (words.size > MAX_HEADING_WORDS) return null
        if (trimmed.endsWith('.') && words.size > 2) return null
        if (trimmed.any { it.isDigit() }) return null

        // Fold accents and drop decoration so "Compétences", "COMPETENCES" and
        // "Compétences techniques" all reach the same lookup key.
        val normalised = CvTextNormalizer.fold(trimmed)
            .replace(Regex("[^a-z'\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        CvLexicon.sectionKeywords[normalised]?.let { return it }

        // Accept a single heading word, e.g. "SKILLS" or "Skills", in either language.
        return CvLexicon.sectionKeywords[normalised.takeIf { it.length >= 4 }.orEmpty()]
    }
}
