package com.aiglobal.jobfinder.data.jobs.freshness

/**
 * How far back the Jobs screen looks when it narrows by publication date. The three windows
 * the product offers are the last week, fortnight and month; [THIRTY_DAYS] is the default.
 *
 * The window is a *view*, not a claim: a posting kept because it falls inside it is never
 * asserted to be open — only that its stated publication date is recent enough to be worth
 * showing. A posting with no date, or a date the source clock placed in the future, has no
 * place on this axis and is not counted as recent by any window (see [JobFreshnessEngine]).
 */
enum class FreshnessWindow(val days: Int, val label: String) {
    SEVEN_DAYS(7, "7 derniers jours"),
    FOURTEEN_DAYS(14, "14 derniers jours"),
    THIRTY_DAYS(30, "30 derniers jours");

    /**
     * True when [postedAtEpochMillis] states a real publication date inside this window.
     *
     * The same rules the [JobFreshnessEngine] applies hold here: a missing date, a zero
     * date or a date the source placed in the future is *not* recent, so it never passes.
     * Nothing is invented to fill the gap — an undated posting is left for the caller to
     * route to a clearly-signalled separate group rather than counted as fresh.
     */
    fun includes(postedAtEpochMillis: Long?, now: Long): Boolean {
        if (postedAtEpochMillis == null) return false
        if (postedAtEpochMillis !in 1..now) return false
        return (now - postedAtEpochMillis) / DAY_MS <= days
    }

    companion object {
        /** The window selected when the user has expressed no preference. */
        val DEFAULT = THIRTY_DAYS

        private const val DAY_MS = 86_400_000L
    }
}
