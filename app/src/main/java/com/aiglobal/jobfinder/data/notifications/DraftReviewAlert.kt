package com.aiglobal.jobfinder.data.notifications

import androidx.compose.runtime.Immutable
import com.aiglobal.jobfinder.data.model.ApplicationDraft

/**
 * A notification telling the user an application has been prepared and is waiting for their
 * approval. Kept as a pure value so the wording rules are unit-testable without Android.
 */
@Immutable
data class DraftReviewAlert(
    val draftId: String,
    val title: String,
    val body: String,
)

/**
 * Decides which prepared drafts deserve a "ready for review" notification.
 *
 * The rule is deliberately narrow. A notification is only posted when a draft is *newly*
 * awaiting the user's approval and the user has not already been told about that exact
 * draft. A draft the user has already seen, approved or submitted never fires again, and the
 * body never claims anything was sent — it states plainly that the user must review and
 * approve before anything happens.
 */
object DraftReviewAlertPolicy {

    /**
     * @param drafts every prepared draft
     * @param alreadyNotified draft ids the user has already been notified about
     * @return the alerts to post now, newest draft first, never repeating an id
     */
    fun select(
        drafts: List<ApplicationDraft>,
        alreadyNotified: Set<String>,
        maxPerRun: Int = 2,
    ): List<DraftReviewAlert> = drafts
        .asSequence()
        .filter { it.status.awaitsUser }
        .filter { it.id !in alreadyNotified }
        .sortedByDescending { it.updatedAtEpochMillis }
        .take(maxPerRun)
        .map(::toAlert)
        .toList()

    private fun toAlert(draft: ApplicationDraft): DraftReviewAlert {
        val where = if (draft.country.isBlank()) "" else " · ${draft.countryFlag} ${draft.country}".trimEnd()
        val lead = when {
            draft.needsResubmission -> "Your changes need a fresh approval"
            draft.coverLetterText.isNotBlank() -> "A cover letter and CV summary are ready"
            else -> "Your CV summary is ready"
        }
        return DraftReviewAlert(
            draftId = draft.id,
            title = "Review before sending: ${draft.jobTitle}",
            body = "$lead for ${draft.company}$where.\n" +
                "Nothing has been sent — open it to review and approve, or skip it.",
        )
    }
}
