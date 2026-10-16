package com.aiglobal.jobfinder.data.cv

import java.text.Normalizer

/**
 * Normalises raw extracted CV text before anything is matched against it.
 *
 * Text pulled out of a real PDF is messy: PDFBox may split a line across many text
 * fragments, break a word with a soft hyphen, insert stray spaces inside words
 * ("M aintenance"), and render accented letters as bare Latin-1 bytes (a CV saying
 * "Mécanicien" can arrive as "MÃ©canicien"). None of that should decide whether a field is
 * found, so the cleanup happens once, up front, and every extractor sees the same shape.
 *
 * [fold] strips accents for language-tolerant matching. It is only ever used for
 * comparison — the value shown to the user is the original, unaccented-but-unchanged text.
 */
internal object CvTextNormalizer {

    private val SOFT_HYPHEN_AT_LINE_END = Regex("([A-Za-zÀ-ÿ])-\\s*\\n\\s*([A-Za-zÀ-ÿ])")
    private val MULTI_SPACE = Regex("[ \\t\\u00A0]{2,}")

    /**
     * Repairs encoding and layout damage that PDF extraction introduces, without changing
     * the words themselves.
     */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return raw

        var text = raw
            .replace('\r', '\n')
            .replace('\u0000', ' ')

        // A PDF page break must not glue the last word of one page to the first of the next.
        text = text.replace(Regex("[\\u000C\\u2028\\u2029]"), "\n")

        // Word-reference separators: keep them as spaces, not for the reader to clean up.
        text = text.replace(Regex("[\\u00AD\\u200B]"), "") // soft hyphen, zero-width space

        // "Mainte-\n nance" -> "Maintenance" (hyphenated word split across two lines).
        text = SOFT_HYPHEN_AT_LINE_END.replace(text) { "${it.groupValues[1]}${it.groupValues[2]}" }

        // Their bullets and box-drawing marks become plain dashes or disappear.
        text = text.replace(Regex("[\\u2022\\u25CF\\u25AA\\u25E6\\u2023\\u2043\\u2219]"), "-")

        // Unify the dash family so downstream regexes only deal with the ASCII hyphen.
        text = text.replace(Regex("[\\u2013\\u2014\\u2212\\u2010\\u2011]"), "-")

        // Repair mojibake only when it is unambiguous: UTF-8 bytes read back as Latin-1.
        text = repairMojibake(text)

        // Collapse runs of spaces but keep single newlines so section splitting still works.
        text = text.lines().joinToString("\n") { iso -> iso.replace(MULTI_SPACE, " ").trimEnd() }

        return text.trim()
    }

    /**
     * True when [text] looks like UTF-8 that was decoded as Latin-1/Windows-1252. This is
     * the classic way "Mécanicien" reaches us as "MÃ©canicien".
     */
    fun looksLikeMojibake(text: String): Boolean =
        text.contains("Ã") || text.contains("Â") || text.contains("â€")

    private fun repairMojibake(text: String): String {
        if (!looksLikeMojibake(text)) return text
        // Re-read the mojibake as Latin-1 bytes, then decode those bytes as UTF-8. When the
        // round-trip produces fewer replacement characters than we started with, it worked.
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        val reDecoded = String(bytes, Charsets.UTF_8)
        val before = text.count { it == '\uFFFD' } + countSuspiciousSequences(text)
        val after = reDecoded.count { it == '\uFFFD' } + countSuspiciousSequences(reDecoded)
        return if (after < before) reDecoded else text
    }

    private fun countSuspiciousSequences(text: String): Int =
        Regex("Ã.|Â.|â€").findAll(text).count()

    /** Accent-folded, lowercased form used only for keyword matching. */
    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{Mn}+"), "") // drop combining accents
            .replace('œ', 'o')
            .replace('æ', 'a')
            .lowercase()
    }

    /** True when a line is a bullet item rather than a heading or a plain sentence. */
    fun isBullet(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("-") || trimmed.startsWith("*") || trimmed.startsWith("•") ||
            trimmed.startsWith("▪") || trimmed.startsWith("○") || trimmed.startsWith("‣")
    }
}
