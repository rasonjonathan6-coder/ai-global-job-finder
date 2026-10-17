package com.aiglobal.jobfinder.data.refresh

import androidx.compose.runtime.Immutable

/**
 * The user's automatic-refresh settings.
 *
 * Android may defer background work, so the interval is a *request* rather than a promise:
 * the UI says "about every 6 hours" and never "at 08:00". [RefreshPreferences.enabled] is
 * the single switch that decides whether a periodic worker exists at all.
 */
@Immutable
data class RefreshPreferences(
    val enabled: Boolean = true,
    val intervalHours: Int = RefreshInterval.TWICE_DAILY.hours,
    /**
     * The minimum CV match score a newly discovered posting must reach before it may raise a
     * notification. A conservative default keeps a fresh install quiet until the user opts
     * in, and the value is the user's to move.
     */
    val alertMinMatchScore: Int = AlertThreshold.STRONG.score,
) {
    val interval: RefreshInterval get() = RefreshInterval.fromHours(intervalHours)
    val alertThreshold: AlertThreshold get() = AlertThreshold.fromScore(alertMinMatchScore)
}

/**
 * The relevance bar for a new-match alert. The scores line up with the match tiers the rest
 * of the app already uses, so "Strong" here means the same thing it means on a job card.
 * The wording states plainly what each level will and will not interrupt the user for.
 */
enum class AlertThreshold(val score: Int, val label: String, val description: String) {
    EXCELLENT(90, "Excellent only", "Only the very closest matches"),
    STRONG(75, "Strong and up", "Strong matches — a good default"),
    GOOD(60, "Good and up", "More alerts, including decent matches"),
    FAIR(45, "Fair and up", "Many alerts, including loose matches");

    companion object {
        fun fromScore(score: Int): AlertThreshold =
            entries.firstOrNull { it.score == score } ?: STRONG
    }
}

/** The intervals a user may choose. All are far above WorkManager's 15-minute floor. */
enum class RefreshInterval(val hours: Int, val label: String, val description: String) {
    EVERY_6_HOURS(6, "Every 6 hours", "Freshest feed, more background requests"),
    TWICE_DAILY(12, "Twice a day", "A good balance for a personal search"),
    DAILY(24, "Daily", "Light on battery and data"),
    WEEKLY(168, "Weekly", "Minimal background activity");

    companion object {
        fun fromHours(hours: Int): RefreshInterval =
            entries.firstOrNull { it.hours == hours } ?: TWICE_DAILY
    }
}
