package com.aiglobal.jobfinder.ui.components

import com.aiglobal.jobfinder.data.geo.CurrencyConverter
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val numberFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)
private val dateFormat = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/** Compact salary range, e.g. "CAD 58k – 74k / year". */
fun formatSalary(min: Int, max: Int, currency: String, period: SalaryPeriod): String {
    val suffix = periodSuffix(period)
    return "$currency ${compact(min)} – ${compact(max)} $suffix"
}

private fun periodSuffix(period: SalaryPeriod): String = when (period) {
    SalaryPeriod.YEAR -> "/yr"
    SalaryPeriod.MONTH -> "/mo"
    SalaryPeriod.DAY -> "/day"
    SalaryPeriod.HOUR -> "/hr"
}

/** Placeholder used when the source did not publish a salary. */
const val SALARY_NOT_STATED = "Salary not stated"

/** Placeholder used when the source gave a unit that cannot be right for the amount. */
const val SALARY_TO_VERIFY = "Salary to verify"

/** Salary label that never invents a number. */
fun formatSalaryOrUnknown(min: Int, max: Int, currency: String, period: SalaryPeriod): String =
    if (min <= 0 || currency.isBlank()) SALARY_NOT_STATED else formatSalary(min, max, currency, period)

/**
 * The salary line for a posting, honouring the parser's honesty flags. A figure whose source
 * unit is impossible ("USD 106k–112k/hr") reads "Salary to verify" rather than a number that
 * would be off by orders of magnitude; a missing figure reads "Salary not stated".
 */
fun salaryLabel(job: com.aiglobal.jobfinder.data.model.Job): String = when {
    job.salaryUnitUncertain -> SALARY_TO_VERIFY
    job.salaryMin <= 0 || job.currencyCode.isBlank() -> SALARY_NOT_STATED
    else -> formatSalary(job.salaryMin, job.salaryMax, job.currencyCode, job.salaryPeriod)
}

/**
 * The same salary in the candidate's reference currency (EUR), with the yearly basis
 * made explicit. Returns null when the source currency is unknown, when the period was
 * only inferred, or when the source's unit is untrustworthy — in those cases a conversion
 * would present a guess as a fact, so the caller omits the line instead.
 */
fun formatSalaryInEur(min: Int, max: Int, currency: String, period: SalaryPeriod): String? {
    val eurMin = CurrencyConverter.toEurPerYear(min, currency, period) ?: return null
    val eurMax = CurrencyConverter.toEurPerYear(max, currency, period) ?: return null
    return "≈ €${compact(eurMin)} – €${compact(eurMax)} per year"
}

/** Direction of the exchange-rate caveat, so the UI can phrase it honestly. */
fun eurRateNote(currency: String): String? =
    if (currency.isBlank()) null
    else "Converted at a fixed rate from ${currency.uppercase()}; check the live rate before you decide."

private fun compact(value: Int): String =
    if (value >= 10_000) "${numberFormat.format(value / 1000)}k" else numberFormat.format(value)

/** Human "posted" label: "5h ago", "3d ago". */
fun relativeTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val diff = (now - epochMillis).coerceAtLeast(0)
    val minutes = diff / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> "${days / 7}w ago"
    }
}

/**
 * Freshness label for a posting whose source may not have stated a date. Never invents one:
 * a missing date reads "Date not stated" rather than a fabricated "just now".
 */
fun postedAgeLabel(postedAtEpochMillis: Long?, now: Long = System.currentTimeMillis()): String =
    postedAtEpochMillis?.let { relativeTime(it, now) } ?: "Date not stated"

/**
 * The publication date, prefixed so it reads as the employer's own date and cannot be
 * mistaken for when this app fetched or discovered the posting. Those are two different
 * facts, and the card shows both when it has them.
 */
fun publishedLabel(postedAtEpochMillis: Long?, now: Long = System.currentTimeMillis()): String =
    postedAtEpochMillis?.let { "Published ${relativeTime(it, now)}" } ?: "Date not stated"

/**
 * Retrieval label: when this app last pulled the posting from its source. Kept distinct from
 * the publication date so a listing the source keeps returning is never shown as if it were
 * freshly published — the two timestamps answer different questions.
 */
fun retrievedLabel(retrievedAtEpochMillis: Long?, now: Long = System.currentTimeMillis()): String =
    retrievedAtEpochMillis?.let { "Fetched ${relativeTime(it, now)}" } ?: "Not fetched yet"

fun formatShortDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(dateFormat)

/** Human file size, e.g. "248 KB", "1.4 MB". */
fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

fun formatDateTime(epochMillis: Long): String =
    "${formatShortDate(epochMillis)} · ${
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    }"
