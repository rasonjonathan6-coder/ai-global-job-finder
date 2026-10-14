package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** How often a saved search should look for new postings. */
@Serializable
enum class AlertFrequency(val label: String) {
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly"),
}

/** Which saved postings an alert is allowed to surface. */
@Serializable
enum class AlertScope(val label: String) {
    ANY_MATCH("Any new match"),
    STRONG_ONLY("Strong matches only"),
    VISA_FRIENDLY("Visa-sponsoring only"),
}

/**
 * A saved search. Alerts are derived by re-scoring the live feed against this rule,
 * so a new posting is caught the moment it appears rather than on a stored snapshot.
 */
@Serializable
@Immutable
data class JobAlertRule(
    val id: String,
    val name: String,
    /** Case-insensitive keywords matched against the title, tags and summary. */
    val keywords: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val frequency: AlertFrequency = AlertFrequency.DAILY,
    val scope: AlertScope = AlertScope.ANY_MATCH,
    val minMatchScore: Int = 60,
    val createdAtEpochMillis: Long,
    val enabled: Boolean = true,
)

/** A posting an alert rule caught, kept until the user dismisses or acts on it. */
@Serializable
@Immutable
data class JobAlert(
    val ruleId: String,
    val ruleName: String,
    val jobId: String,
    val jobTitle: String,
    val company: String,
    val country: String,
    val countryFlag: String,
    val matchScore: Int,
    val isVisaFriendly: Boolean,
    val detectedAtEpochMillis: Long,
    /** True once the user has opened it, so the unread badge is honest. */
    val read: Boolean = false,
)

/** Everything the alerts screen needs in one immutable snapshot. */
@Immutable
data class JobAlertsState(
    val rules: List<JobAlertRule> = emptyList(),
    val alerts: List<JobAlert> = emptyList(),
    val isRefreshing: Boolean = false,
) {
    val unreadCount: Int get() = alerts.count { !it.read }
    val activeRuleCount: Int get() = rules.count { it.enabled }
}
