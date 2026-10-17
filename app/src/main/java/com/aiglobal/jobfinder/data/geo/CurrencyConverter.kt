package com.aiglobal.jobfinder.data.geo

import com.aiglobal.jobfinder.data.model.SalaryPeriod

/**
 * Converts advertised salaries into a single currency so they can be compared across
 * sources. Rates are a coarse, hand-maintained table: the app only needs the right
 * order of magnitude to sort and to flag "above/below your expectation", never to
 * quote an exact figure. When a rate is unknown the original amount is returned and
 * the caller keeps showing the original currency.
 */
object CurrencyConverter {

    private const val EUR = "EUR"

    /** Units of EUR per one unit of the key currency. */
    private val toEur: Map<String, Double> = mapOf(
        "EUR" to 1.0,
        "USD" to 0.92,
        "CAD" to 0.68,
        "GBP" to 1.17,
        "AUD" to 0.61,
        "NZD" to 0.56,
        "CHF" to 1.05,
        "SEK" to 0.088,
        "NOK" to 0.086,
        "DKK" to 0.134,
        "PLN" to 0.23,
        "CZK" to 0.040,
        "RON" to 0.20,
        "HUF" to 0.0026,
        "AED" to 0.25,
        "SAR" to 0.245,
        "QAR" to 0.252,
        "KWD" to 3.0,
        "OMR" to 2.39,
        "BHD" to 2.44,
        "SGD" to 0.69,
        "JPY" to 0.0061,
        "KRW" to 0.00068,
        "MYR" to 0.20,
        "ZAR" to 0.050,
        "KES" to 0.0071,
        "MAD" to 0.092,
        "MUR" to 0.020,
        "INR" to 0.011,
        "TRY" to 0.027,
        "ILS" to 0.25,
        "MXN" to 0.048,
        "BRL" to 0.17,
    )

    /** True when a rate exists and conversion is meaningful. */
    fun canConvert(currency: String): Boolean = toEur.containsKey(currency.uppercase())

    /**
     * Converts an amount per [period] into EUR per year. Returns null when the
     * currency is unknown, so the caller can fall back to displaying the raw value.
     */
    fun toEurPerYear(amount: Int, currency: String, period: SalaryPeriod): Int? {
        val rate = toEur[currency.uppercase()] ?: return null
        val multiplier = when (period) {
            SalaryPeriod.YEAR -> 1.0
            SalaryPeriod.MONTH -> 12.0
            SalaryPeriod.DAY -> 220.0
            SalaryPeriod.HOUR -> 1760.0
        }
        return (amount * rate * multiplier).toInt()
    }

    /** Currency used for every normalised comparison. */
    val baseCurrency: String get() = EUR

    /**
     * Where the rates come from and how fresh they are. They are a coarse, hand-maintained
     * table shipped with the app, so the figures are only good enough to sort and to flag
     * "above/below expectation" — never to quote. Both are surfaced in the UI beside any
     * converted salary so the estimate is never mistaken for a live quote.
     */
    val rateSource: String = "On-device reference table (approximate)"

    /** Epoch millis the table was last reviewed. */
    val rateAsOfEpochMillis: Long = RATE_AS_OF_EPOCH_MILLIS

    /** Human date for the rate table, shown next to a converted figure. */
    val rateAsOfLabel: String = "2024-06"

    private const val RATE_AS_OF_EPOCH_MILLIS = 1717200000000L // 2024-06-01
}
