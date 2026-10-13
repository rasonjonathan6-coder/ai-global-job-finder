package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * The order a healthy application moves through. [ApplicationStatus.REJECTED] and
 * [ApplicationStatus.CLOSED] are terminal and sit outside this path, so the progress
 * stepper never implies a rejection is "further along" than an interview.
 */
object ApplicationPipeline {
    val steps: List<ApplicationStatus> = listOf(
        ApplicationStatus.SAVED,
        ApplicationStatus.PREPARING,
        ApplicationStatus.APPLIED,
        ApplicationStatus.INTERVIEW,
        ApplicationStatus.OFFER,
    )
}

/** Label shown on cards, chips and the stepper. */
val ApplicationStatus.displayLabel: String
    get() = when (this) {
        ApplicationStatus.SAVED -> "Saved"
        ApplicationStatus.PREPARING -> "Preparing"
        ApplicationStatus.APPLIED -> "Applied"
        ApplicationStatus.INTERVIEW -> "Interview"
        ApplicationStatus.OFFER -> "Offer"
        ApplicationStatus.REJECTED -> "Not selected"
        ApplicationStatus.CLOSED -> "Archived"
    }

/** Short label for dense rows such as the pipeline summary. */
val ApplicationStatus.shortLabel: String
    get() = when (this) {
        ApplicationStatus.SAVED -> "Saved"
        ApplicationStatus.PREPARING -> "Preparing"
        ApplicationStatus.APPLIED -> "Applied"
        ApplicationStatus.INTERVIEW -> "Interview"
        ApplicationStatus.OFFER -> "Offer"
        ApplicationStatus.REJECTED -> "Rejected"
        ApplicationStatus.CLOSED -> "Archived"
    }

/** One-line description of what the stage means, so the user knows what to do next. */
val ApplicationStatus.stageHint: String
    get() = when (this) {
        ApplicationStatus.SAVED -> "You bookmarked this role but have not started yet."
        ApplicationStatus.PREPARING -> "Tailor your CV and cover letter before sending."
        ApplicationStatus.APPLIED -> "Sent. Give the employer 1–2 weeks before following up."
        ApplicationStatus.INTERVIEW -> "Prepare for the round and confirm the time zone."
        ApplicationStatus.OFFER -> "Review the contract, visa route and start date."
        ApplicationStatus.REJECTED -> "Not selected this time. Keep the record for your history."
        ApplicationStatus.CLOSED -> "Archived. It no longer appears in your active pipeline."
    }

/** True when the application is no longer moving forward. */
val ApplicationStatus.isTerminal: Boolean
    get() = this == ApplicationStatus.REJECTED || this == ApplicationStatus.CLOSED

/** Position on the happy path, or -1 for a terminal state. */
val ApplicationStatus.stepIndex: Int
    get() = ApplicationPipeline.steps.indexOf(this)

/** What produced a timeline entry. Drives the icon and the colour on the timeline. */
@Serializable
enum class ApplicationEventType { CREATED, STATUS_CHANGE, NOTE, COVER_LETTER, REMINDER }

/**
 * One entry on an application's timeline. The timeline is append-only: every status
 * change, note, generated letter and reminder lands here, so the history is never lost
 * even when the current status changes.
 */
@Immutable
@Serializable
data class ApplicationEvent(
    val id: String,
    val type: ApplicationEventType,
    val label: String,
    val detail: String = "",
    val atEpochMillis: Long,
)

/** The kind of nudge a reminder represents. */
enum class ReminderKind(val label: String) {
    FOLLOW_UP("Follow up"),
    INTERVIEW("Interview"),
}

/**
 * A dated nudge derived from an application. Reminders are computed from the records
 * rather than stored separately, so editing an application can never leave a stale
 * reminder behind.
 */
@Immutable
data class ApplicationReminder(
    val applicationId: String,
    val jobTitle: String,
    val company: String,
    val countryFlag: String,
    val kind: ReminderKind,
    val atEpochMillis: Long,
    /** True once the moment has arrived or passed. */
    val due: Boolean,
    val detail: String,
)
