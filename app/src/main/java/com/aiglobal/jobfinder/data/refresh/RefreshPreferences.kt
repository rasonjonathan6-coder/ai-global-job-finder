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
) {
    val interval: RefreshInterval get() = RefreshInterval.fromHours(intervalHours)
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
