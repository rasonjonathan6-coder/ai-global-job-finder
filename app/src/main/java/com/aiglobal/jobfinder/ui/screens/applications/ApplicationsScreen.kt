package com.aiglobal.jobfinder.ui.screens.applications

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiglobal.jobfinder.data.model.ApplicationRecord
import com.aiglobal.jobfinder.data.model.ApplicationStatus
import com.aiglobal.jobfinder.data.submissions.OfficialSubmissionAudit
import com.aiglobal.jobfinder.ui.components.EmptyState
import com.aiglobal.jobfinder.ui.components.ErrorState
import com.aiglobal.jobfinder.ui.components.FilterChip
import com.aiglobal.jobfinder.ui.components.IconLabelRow
import com.aiglobal.jobfinder.ui.components.JobCardSkeleton
import com.aiglobal.jobfinder.ui.components.MatchChip
import com.aiglobal.jobfinder.ui.components.Pill
import com.aiglobal.jobfinder.ui.components.PrimaryButton
import com.aiglobal.jobfinder.ui.components.formatShortDate
import com.aiglobal.jobfinder.ui.components.relativeTime
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors
import com.aiglobal.jobfinder.viewmodel.ApplicationsUiState
import com.aiglobal.jobfinder.viewmodel.ApplicationsViewModel

@Composable
fun ApplicationsScreen(
    viewModel: ApplicationsViewModel,
    onBrowseJobs: () -> Unit,
    onOpenApplication: (String) -> Unit = {},
    onReviewDraft: (String) -> Unit = {},
    onOpenLink: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ApplicationsHeader(
            state = state,
            onSelectStatus = viewModel::selectStatus,
        )

        SubmissionChannelBanner(
            modifier = Modifier.padding(horizontal = LocalSpacing.current.gutter),
        )

        when {
            state.isLoading && state.applications.isEmpty() && state.draftsAwaitingApproval.isEmpty() -> {
                Column(
                    modifier = Modifier.padding(horizontal = LocalSpacing.current.gutter),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    repeat(3) { JobCardSkeleton() }
                }
            }
            state.applications.isEmpty() && state.draftsAwaitingApproval.isEmpty() -> {
                ErrorState(
                    title = "Nothing to show",
                    message = "Your applications did not load. Try again in a moment.",
                    onRetry = viewModel::refresh,
                )
            }
            else -> {
                val visible = state.applications
                    .filter { state.selectedStatus == null || it.status == state.selectedStatus }
                    .sortedByDescending { it.updatedAtEpochMillis }

                val showApprovalQueue = state.draftsAwaitingApproval.isNotEmpty()

                if (visible.isEmpty() && !showApprovalQueue) {
                    EmptyState(
                        emoji = "📂",
                        title = "No applications in this stage",
                        message = "Pick another filter to see the rest of your pipeline.",
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = LocalSpacing.current.gutter,
                            end = LocalSpacing.current.gutter,
                            top = 4.dp,
                            bottom = LocalSpacing.current.navBarClearance,
                        ),
                        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
                    ) {
                        if (showApprovalQueue) {
                            item(key = "approval-queue-header") {
                                ApprovalQueueHeader(count = state.draftsAwaitingApproval.size)
                            }
                            items(state.draftsAwaitingApproval, key = { "draft-${it.id}" }) { draft ->
                                DraftApprovalCard(
                                    draft = draft,
                                    onReview = { onReviewDraft(draft.id) },
                                    onApprove = { viewModel.approveDraft(draft.id) },
                                    onSkip = { viewModel.skipDraft(draft.id) },
                                    onToggleConsent = { given -> viewModel.setDraftConsent(draft.id, given) },
                                    onOpenOfficialForm = { onOpenLink(draft.sourceUrl) },
                                )
                            }
                            item(key = "approval-queue-divider") {
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                        items(visible, key = { it.id }) { record ->
                            ApplicationCard(
                                record = record,
                                onClick = { onOpenApplication(record.id) },
                            )
                        }
                        item {
                            Spacer(Modifier.height(4.dp))
                            PrimaryButton(
                                text = "Find more opportunities",
                                onClick = onBrowseJobs,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApplicationsHeader(
    state: ApplicationsUiState,
    onSelectStatus: (ApplicationStatus?) -> Unit,
) {
    val spacing = LocalSpacing.current
    val active = state.applications.count {
        it.status == ApplicationStatus.APPLIED || it.status == ApplicationStatus.INTERVIEW
    }
    val interviews = state.applications.count { it.status == ApplicationStatus.INTERVIEW }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = spacing.gutter, vertical = spacing.sm),
    ) {
        Text(
            text = "Applications",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "$active in progress · $interviews interview${if (interviews == 1) "" else "s"} scheduled",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(spacing.md))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            item {
                FilterChip(
                    label = "All",
                    selected = state.selectedStatus == null,
                    onClick = { onSelectStatus(null) },
                )
            }
            items(ApplicationStatus.entries.toList()) { status ->
                FilterChip(
                    label = status.displayLabel(),
                    selected = state.selectedStatus == status,
                    onClick = { onSelectStatus(status) },
                )
            }
        }
    }
}

/**
 * The honest channel banner: it states, once, that no verified submission route exists, so
 * the app prepares applications and the user submits them on the employer's own form. It is
 * driven by the same audit the rest of the app reads, so it can never claim a capability the
 * audit does not grant.
 */
@Composable
private fun SubmissionChannelBanner(modifier: Modifier = Modifier) {
    val spacing = LocalSpacing.current
    val available = OfficialSubmissionAudit.realSubmissionAvailable
    val container = if (available) extendedColors.successContainer else extendedColors.infoContainer
    val content = if (available) extendedColors.success else extendedColors.info
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .padding(horizontal = spacing.sm, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (available) Icons.Filled.Verified else Icons.Filled.Info,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (available) {
                "A verified submission route is available."
            } else {
                "No verified submission route yet — nothing is sent automatically. Open the official form to apply."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Section label above the approval queue, so the queue reads as a distinct, actionable block. */
@Composable
private fun ApprovalQueueHeader(count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Waiting for your approval",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "$count prepared application${if (count == 1) "" else "s"} · nothing is sent until you approve",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ApplicationCard(record: ApplicationRecord, onClick: () -> Unit) {
    val spacing = LocalSpacing.current
    val accent = Color(record.companyAccent)
    val statusStyle = record.status.style()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, extendedColors.cardStroke, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = record.company.take(2).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.jobTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${record.countryFlag} ${record.company} · ${record.country}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(spacing.xs))
            MatchChip(record.matchScore)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(
                text = statusStyle.label,
                containerColor = statusStyle.container,
                contentColor = statusStyle.content,
            )
            Spacer(Modifier.width(spacing.xs))
            if (record.hasCoverLetter) {
                Pill(
                    text = "Cover letter ready",
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    leadingIcon = Icons.Filled.Description,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                record.nextStepLabel?.let { step ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CalendarMonth,
                            contentDescription = null,
                            tint = statusStyle.content,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = step,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        record.nextStepAtEpochMillis?.let { at ->
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "· ${formatShortDate(at)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                IconLabelRow(
                    icon = Icons.Filled.CalendarMonth,
                    text = record.appliedAtEpochMillis?.let { "Applied ${relativeTime(it)}" }
                        ?: "Not submitted yet",
                )
            }
        }

        if (record.notes.isNotBlank()) {
            Text(
                text = record.notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun ApplicationStatus.displayLabel(): String = when (this) {
    ApplicationStatus.SAVED -> "Saved"
    ApplicationStatus.PREPARING -> "Preparing"
    ApplicationStatus.APPLIED -> "Applied"
    ApplicationStatus.INTERVIEW -> "Interview"
    ApplicationStatus.OFFER -> "Offer"
    ApplicationStatus.REJECTED -> "Closed"
    ApplicationStatus.CLOSED -> "Archived"
}

private data class StatusStyle(val label: String, val container: Color, val content: Color)

@Composable
private fun ApplicationStatus.style(): StatusStyle = when (this) {
    ApplicationStatus.SAVED -> StatusStyle("Saved", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    ApplicationStatus.PREPARING -> StatusStyle("Preparing", extendedColors.infoContainer, extendedColors.info)
    ApplicationStatus.APPLIED -> StatusStyle("Applied", extendedColors.infoContainer, extendedColors.info)
    ApplicationStatus.INTERVIEW -> StatusStyle("Interview", extendedColors.successContainer, extendedColors.success)
    ApplicationStatus.OFFER -> StatusStyle("Offer", extendedColors.successContainer, extendedColors.success)
    ApplicationStatus.REJECTED -> StatusStyle("Not selected", extendedColors.dangerContainer, extendedColors.danger)
    ApplicationStatus.CLOSED -> StatusStyle("Archived", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
}
