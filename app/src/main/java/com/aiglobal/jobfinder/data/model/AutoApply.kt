package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Where a queued job stands in the auto-apply flow.
 *
 * The flow is deliberately "prepare, never submit": [DRAFT_READY] means the CV and cover
 * letter are ready for one-tap approval, not that anything was sent. The app never
 * submits an application on the user's behalf without that explicit tap.
 */
@Serializable
enum class AutoApplyStatus(val label: String) {
    QUEUED("Queued"),
    DRAFT_READY("Draft ready"),
    APPROVED("Approved to send"),
    SKIPPED("Skipped"),
    BLOCKED("Blocked"),
}

/** Why the engine put a job in the queue, or refused to. Always shown to the user. */
@Serializable
@Immutable
data class AutoApplyItem(
    val jobId: String,
    val jobTitle: String,
    val company: String,
    val country: String,
    val countryFlag: String,
    val matchScore: Int,
    val sourceUrl: String,
    val status: AutoApplyStatus,
    /** The generated cover letter, when one exists. */
    val coverLetterId: String? = null,
    /** Plain-language explanation of the status. */
    val reason: String,
    val preparedAtEpochMillis: Long,
)

/** Everything the auto-apply screen needs in one immutable snapshot. */
@Immutable
data class AutoApplyState(
    val enabled: Boolean = false,
    val isPreparing: Boolean = false,
    val items: List<AutoApplyItem> = emptyList(),
    val threshold: Int = 70,
) {
    val readyCount: Int get() = items.count { it.status == AutoApplyStatus.DRAFT_READY }
    val queuedCount: Int get() = items.count { it.status == AutoApplyStatus.QUEUED }
    val blockedCount: Int get() = items.count { it.status == AutoApplyStatus.BLOCKED }
    val approvedCount: Int get() = items.count { it.status == AutoApplyStatus.APPROVED }
    val activeItems: List<AutoApplyItem> get() = items.filter { it.status != AutoApplyStatus.SKIPPED }
}
