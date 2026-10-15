package com.aiglobal.jobfinder.ui.screens.applicationdetail

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WorkHistory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiglobal.jobfinder.data.model.ApplicationEvent
import com.aiglobal.jobfinder.data.model.ApplicationEventType
import com.aiglobal.jobfinder.data.model.ApplicationRecord
import com.aiglobal.jobfinder.data.model.ApplicationStatus
import com.aiglobal.jobfinder.data.model.displayLabel
import com.aiglobal.jobfinder.data.model.isTerminal
import com.aiglobal.jobfinder.data.model.stageHint
import com.aiglobal.jobfinder.data.model.stepIndex
import com.aiglobal.jobfinder.ui.components.AppCard
import com.aiglobal.jobfinder.ui.components.EmptyState
import com.aiglobal.jobfinder.ui.components.IconChipButton
import com.aiglobal.jobfinder.ui.components.MatchChip
import com.aiglobal.jobfinder.ui.components.Pill
import com.aiglobal.jobfinder.ui.components.PrimaryButton
import com.aiglobal.jobfinder.ui.components.SectionHeader
import com.aiglobal.jobfinder.ui.components.SecondaryButton
import com.aiglobal.jobfinder.ui.components.formatDateTime
import com.aiglobal.jobfinder.ui.components.relativeTime
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors
import com.aiglobal.jobfinder.viewmodel.ApplicationDetailUiState
import com.aiglobal.jobfinder.viewmodel.ApplicationDetailViewModel

/**
 * The full picture of one application: where it stands, what to do next, the letter
 * that was written, the settlement guide for the destination, and the full history.
 */
@Composable
fun ApplicationDetailScreen(
    viewModel: ApplicationDetailViewModel,
    onBack: () -> Unit,
    onOpenCoverLetter: (String) -> Unit,
    onOpenExpat: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ApplicationDetailContent(
        state = state,
        onBack = onBack,
        onOpenCoverLetter = onOpenCoverLetter,
        onOpenExpat = onOpenExpat,
        onSetStatus = viewModel::setStatus,
        onSetFollowUpAt = viewModel::setFollowUpAt,
        onArchive = viewModel::archive,
        onDelete = viewModel::delete,
    )
}

/** Stateless body, so layout can be verified without a live pipeline. */
@Composable
internal fun ApplicationDetailContent(
    state: ApplicationDetailUiState,
    onBack: () -> Unit,
    onOpenCoverLetter: (String) -> Unit,
    onOpenExpat: (String) -> Unit,
    onSetStatus: (ApplicationStatus) -> Unit,
    onSetFollowUpAt: (Long?) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val spacing = LocalSpacing.current
    // Recording an application as sent is the one status change that must not be a single
    // stray tap: it is the app's record that a real submission happened. Every other step is
    // a plain local move, so only APPLIED is gated behind a confirmation.
    var confirmApplied by remember { mutableStateOf(false) }
    val requestStatus: (ApplicationStatus) -> Unit = { status ->
        if (status == ApplicationStatus.APPLIED) confirmApplied = true else onSetStatus(status)
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        DetailTopBar(title = state.record?.jobTitle ?: "Application", onBack = onBack)

        val record = state.record
        if (record == null) {
            EmptyState(
                emoji = "🗂️",
                title = if (state.isLoading) "Loading application" else "Application not found",
                message = if (state.isLoading) {
                    "Fetching the latest state of this application."
                } else {
                    "It may have been deleted from your pipeline."
                },
            )
            return@Column
        }

        if (confirmApplied) {
            AppliedConfirmationDialog(
                record = record,
                onConfirm = {
                    confirmApplied = false
                    onSetStatus(ApplicationStatus.APPLIED)
                },
                onDismiss = { confirmApplied = false },
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = spacing.gutter,
                end = spacing.gutter,
                top = spacing.sm,
                bottom = spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item { SummaryCard(record = record, nextAction = state.nextAction) }
            item { StageStepper(record = record, onSelect = requestStatus) }
            item { NextStepsCard(record = record, onSetFollowUp = onSetFollowUpAt) }
            item { LetterCard(record = record, onOpen = { onOpenCoverLetter(record.jobId) }) }
            item { ExpatCard(country = record.country, onOpen = { onOpenExpat(record.country) }) }
            item { TimelineCard(events = record.timeline) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    SecondaryButton(
                        text = "Archive",
                        onClick = onArchive,
                        modifier = Modifier.weight(1f),
                        enabled = !record.status.isTerminal,
                    )
                    SecondaryButton(
                        text = "Delete",
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * Confirms that the user really did submit the application on the employer's own page
 * before the app records it as sent. Nothing is transmitted here — the app only writes
 * down what the user tells it happened.
 */
@Composable
private fun AppliedConfirmationDialog(
    record: ApplicationRecord,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.Send,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("Mark as applied?") },
        text = {
            Text(
                "This records that you sent your application for \"${record.jobTitle}\" at " +
                    "${record.company}. It does not send anything for you. Only confirm after you " +
                    "have actually submitted it on the employer's page.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Yes, I applied")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not yet")
            }
        },
    )
}

@Composable
private fun DetailTopBar(title: String, onBack: () -> Unit) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = spacing.gutter, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconChipButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            onClick = onBack,
            contentDescription = "Back",
        )
        Spacer(Modifier.width(spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Application",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SummaryCard(record: ApplicationRecord, nextAction: String) {
    val spacing = LocalSpacing.current
    val accent = Color(record.companyAccent)

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = record.company.take(2).uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.jobTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
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

        Spacer(Modifier.height(spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(
                text = record.status.displayLabel,
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                contentColor = MaterialTheme.colorScheme.primary,
            )
            if (record.hasCoverLetter) {
                Pill(
                    text = "Cover letter ready",
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    leadingIcon = Icons.Filled.Description,
                )
            }
        }

        if (nextAction.isNotBlank()) {
            Spacer(Modifier.height(spacing.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(extendedColors.infoContainer)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    tint = extendedColors.info,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = nextAction,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Horizontal stepper through the happy path. Tapping a stage moves the application. */
@Composable
private fun StageStepper(record: ApplicationRecord, onSelect: (ApplicationStatus) -> Unit) {
    val spacing = LocalSpacing.current
    val steps = com.aiglobal.jobfinder.data.model.ApplicationPipeline.steps
    val currentIndex = record.status.stepIndex

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Progress",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(spacing.sm))
        steps.forEachIndexed { index, step ->
            val reached = currentIndex >= index && currentIndex >= 0
            val isCurrent = record.status == step
            StageRow(
                step = step,
                index = index + 1,
                reached = reached,
                isCurrent = isCurrent,
                onClick = { onSelect(step) },
            )
            if (index != steps.lastIndex) {
                Box(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .width(2.dp)
                        .height(14.dp)
                        .background(
                            if (reached) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        ),
                )
            }
        }
        if (record.status.isTerminal) {
            Spacer(Modifier.height(spacing.xs))
            Pill(
                text = record.status.displayLabel,
                containerColor = extendedColors.dangerContainer,
                contentColor = extendedColors.danger,
            )
        }
    }
}

@Composable
private fun StageRow(
    step: ApplicationStatus,
    index: Int,
    reached: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    if (reached) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (reached) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Text(
                    text = "$index",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.displayLabel,
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (isCurrent) {
                Text(
                    text = step.stageHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NextStepsCard(record: ApplicationRecord, onSetFollowUp: (Long?) -> Unit) {
    val spacing = LocalSpacing.current
    val now = System.currentTimeMillis()
    AppCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = "Reminders", subtitle = "Nudges you set yourself")
        Spacer(Modifier.height(spacing.sm))
        ReminderRow(
            icon = Icons.Filled.Schedule,
            label = "Follow-up",
            value = record.followUpAtEpochMillis?.let { formatDateTime(it) } ?: "Not set",
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            SecondaryButton(
                text = "In 3 days",
                onClick = { onSetFollowUp(now + 3L * DAY) },
                modifier = Modifier.weight(1f),
                height = 42.dp,
            )
            SecondaryButton(
                text = "In 1 week",
                onClick = { onSetFollowUp(now + 7L * DAY) },
                modifier = Modifier.weight(1f),
                height = 42.dp,
            )
            if (record.followUpAtEpochMillis != null) {
                SecondaryButton(
                    text = "Clear",
                    onClick = { onSetFollowUp(null) },
                    modifier = Modifier.weight(1f),
                    height = 42.dp,
                )
            }
        }
    }
}

@Composable
private fun ReminderRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = extendedColors.info, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun LetterCard(record: ApplicationRecord, onOpen: () -> Unit) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Cover letter",
            subtitle = if (record.hasCoverLetter) "Written from your CV — review before sending" else "Not generated yet",
        )
        Spacer(Modifier.height(spacing.sm))
        if (record.hasCoverLetter) {
            PrimaryButton(
                text = "Open and edit letter",
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.Filled.Description,
            )
        } else {
            PrimaryButton(
                text = "Generate a cover letter",
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.Filled.Description,
            )
        }
    }
}

@Composable
private fun ExpatCard(country: String, onOpen: () -> Unit) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Settlement in $country",
            subtitle = "Visa route, relocation and family rules",
        )
        Spacer(Modifier.height(spacing.sm))
        SecondaryButton(
            text = "View settlement guide",
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = Icons.Filled.Public,
        )
    }
}

@Composable
private fun TimelineCard(events: List<ApplicationEvent>) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = "History", subtitle = "Everything that happened, newest first")
        Spacer(Modifier.height(spacing.sm))
        if (events.isEmpty()) {
            Text(
                text = "No activity recorded yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            events.sortedByDescending { it.atEpochMillis }.forEachIndexed { index, event ->
                TimelineRow(event = event)
                if (index != events.lastIndex) {
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(event: ApplicationEvent) {
    val (icon, tint) = event.style(event.label)
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (event.detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = event.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = relativeTime(event.atEpochMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ApplicationEvent.style(label: String): Pair<ImageVector, Color> {
    val isFollowUp = label.contains("Follow-up", ignoreCase = true)
    val isInterview = label.contains("Interview", ignoreCase = true)
    return when {
        type == ApplicationEventType.REMINDER && isFollowUp ->
            Icons.Filled.Schedule to extendedColors.warning
        type == ApplicationEventType.REMINDER && isInterview ->
            Icons.Filled.Event to MaterialTheme.colorScheme.primary
        type == ApplicationEventType.REMINDER ->
            Icons.Filled.Event to extendedColors.warning
        type == ApplicationEventType.CREATED ->
            Icons.Filled.WorkHistory to MaterialTheme.colorScheme.primary
        type == ApplicationEventType.STATUS_CHANGE ->
            Icons.Filled.Check to extendedColors.info
        type == ApplicationEventType.NOTE ->
            Icons.Filled.Notes to MaterialTheme.colorScheme.onSurfaceVariant
        else ->
            Icons.Filled.Description to extendedColors.success
    }
}

private const val DAY = 24L * 60 * 60 * 1000
