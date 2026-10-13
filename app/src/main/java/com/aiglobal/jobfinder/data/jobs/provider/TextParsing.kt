package com.aiglobal.jobfinder.data.jobs.provider

import com.aiglobal.jobfinder.data.model.SalaryPeriod

/** Parses the ISO-8601 timestamps the boards publish into epoch millis. */
object IsoDates {

    fun epochMillis(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return runCatching { java.time.OffsetDateTime.parse(text.trim()).toInstant().toEpochMilli() }
            .recoverCatching { java.time.LocalDateTime.parse(text.trim()).atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }
            .recoverCatching { java.time.LocalDate.parse(text.trim()).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }
            .getOrNull()
    }
}

/** Turns the HTML descriptions the boards publish into readable plain text. */
object HtmlText {

    private val blockTag = Regex("(?i)<\\s*(br|/p|/div|/li|/h[1-6]|/tr)\\s*/?>")
    private val tag = Regex("<[^>]+>")
    private val entity = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")

    /** Block-level tags become newlines so lists and paragraphs survive as structure. */
    fun toPlainText(html: String?): String {
        if (html.isNullOrBlank()) return ""
        val withBreaks = blockTag.replace(html, "\n")
        val stripped = tag.replace(withBreaks, " ")
        val decoded = decodeEntities(stripped)
        return decoded
            .lineSequence()
            .map { it.replace(Regex("[ \\t\\u00a0]+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /** First [limit] characters of plain text, cut on a word boundary. */
    fun excerpt(html: String?, limit: Int = 240): String {
        val text = toPlainText(html).replace('\n', ' ').trim()
        if (text.length <= limit) return text
        val cut = text.take(limit)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > limit / 2) cut.take(lastSpace) else cut).trimEnd() + "…"
    }

    private fun decodeEntities(input: String): String = entity.replace(input) { match ->
        when (val name = match.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos", "#39" -> "'"
            "nbsp" -> " "
            "ndash" -> "–"
            "mdash" -> "—"
            "hellip" -> "…"
            "rsquo" -> "'"
            "lsquo" -> "'"
            "ldquo" -> "\""
            "rdquo" -> "\""
            else -> if (name.startsWith("#")) decodeNumeric(name) else match.value
        }
    }

    private fun decodeNumeric(name: String): String = runCatching {
        val code = if (name.startsWith("#x", ignoreCase = true)) {
            name.substring(2).toInt(16)
        } else {
            name.substring(1).toInt()
        }
        String(Character.toChars(code))
    }.getOrDefault("")
}

/**
 * Best-effort salary parsing. Boards publish salaries as free text ("$35,3k- $52k",
 * "€55,000 - €70,000 a year", "From $60,000 per year"). Every field is optional: when
 * the shape is not recognised, null is returned and the card simply omits salary.
 */
object SalaryParser {

    private val currencySymbols = mapOf(
        '$' to "USD",
        '€' to "EUR",
        '£' to "GBP",
        '¥' to "JPY",
        '₦' to "NGN",
        '₹' to "INR",
        'R' to "ZAR",
    )

    private val codePattern = Regex("(?i)\\b(USD|EUR|GBP|CAD|AUD|NZD|CHF|SEK|NOK|DKK|PLN|CZK|AED|SAR|QAR|SGD|JPY|KRW|ZAR|KES|MAD|MUR|INR|TRY|ILS|MXN|BRL|RON|HUF|NGN)\\b")
    private val amountPattern = Regex("(\\d[\\d\\s.,]*\\d|\\d)\\s*([kK])?")

    data class Parsed(
        val min: Int?,
        val max: Int?,
        val currency: String?,
        val period: SalaryPeriod?,
    )

    fun parse(raw: String?): Parsed {
        if (raw.isNullOrBlank()) return Parsed(null, null, null, null)
        val text = raw.trim()

        val currency = codePattern.find(text)?.value?.uppercase()
            ?: currencySymbols.entries.firstOrNull { text.contains(it.key) }?.value

        val period = when {
            Regex("(?i)per\\s*year|/\\s*year|annually|annual|a\\s*year|yearly|/yr").containsMatchIn(text) -> SalaryPeriod.YEAR
            Regex("(?i)per\\s*month|/\\s*month|monthly|a\\s*month|/mo").containsMatchIn(text) -> SalaryPeriod.MONTH
            Regex("(?i)per\\s*day|/\\s*day|daily|/day").containsMatchIn(text) -> SalaryPeriod.DAY
            Regex("(?i)per\\s*hour|/\\s*hour|hourly|/hr").containsMatchIn(text) -> SalaryPeriod.HOUR
            else -> null
        }

        val amounts = amountPattern.findAll(text)
            .mapNotNull { toAmount(it.value) }
            .filter { it in 100..10_000_000 }
            .toList()

        if (amounts.isEmpty()) return Parsed(null, null, currency, period)

        val min = amounts.min()
        val max = amounts.max()
        return Parsed(
            min = min,
            max = max.takeIf { amounts.size > 1 && it != min } ?: min,
            currency = currency,
            period = period ?: inferPeriod(min, currency),
        )
    }

    /** "35,3k" and "1.2M"-style tokens are normalised to a whole number. */
    private fun toAmount(token: String): Int? {
        val cleaned = token.trim()
        val hasK = cleaned.endsWith("k", ignoreCase = true)
        val digits = cleaned.replace(Regex("[^0-9.,]"), "")
        if (digits.isEmpty()) return null
        val normalized = normalizeSeparators(digits)
        val value = normalized.toDoubleOrNull() ?: return null
        val scaled = if (hasK) value * 1000 else value
        return scaled.toInt()
    }

    /**
     * Handles both "58,000" (comma thousands) and "35,3" (comma decimal). A separator
     * followed by exactly three digits is treated as a thousands separator.
     */
    private fun normalizeSeparators(digits: String): String {
        val hasComma = digits.contains(',')
        val hasDot = digits.contains('.')
        return when {
            hasComma && hasDot -> digits.replace(",", "")
            hasComma -> {
                val parts = digits.split(',')
                if (parts.size == 2 && parts[1].length == 3) digits.replace(",", "") else digits.replace(',', '.')
            }
            else -> digits
        }
    }

    /** Falls back to a sensible period so a bare number is not read as hourly. */
    private fun inferPeriod(amount: Int?, currency: String?): SalaryPeriod? = when {
        amount == null -> null
        amount >= 12_000 -> SalaryPeriod.YEAR
        amount >= 1_500 -> SalaryPeriod.MONTH
        amount >= 60 -> SalaryPeriod.DAY
        else -> SalaryPeriod.HOUR
    }
}
