package com.aiglobal.jobfinder.data.cv

/**
 * Splits a document into its named sections. Real CVs are inconsistent, so this is
 * deliberately forgiving: a line counts as a heading if it is short, has no sentence
 * punctuation and matches a known heading word. Text before the first heading stays
 * available as header content.
 */
internal object CvTextStructure {

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
        val trimmed = line.trim().trim(':', '•', '-', '*', '=').trim()
        if (trimmed.length > MAX_HEADING_LENGTH) return null
        val words = trimmed.split(Regex("\\s+"))
        if (words.size > MAX_HEADING_WORDS) return null
        if (trimmed.endsWith('.') && words.size > 2) return null
        if (trimmed.any { it.isDigit() }) return null

        val normalised = trimmed.lowercase()
            .replace(Regex("[^a-zà-ÿ'\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        CvLexicon.sectionKeywords[normalised]?.let { return it }

        // Accept a heading word that appears as the whole line, e.g. "SKILLS" or "Skills".
        return CvLexicon.sectionKeywords.entries
            .firstOrNull { (key, _) -> key == normalised && key.length >= 4 }
            ?.value
    }
}
