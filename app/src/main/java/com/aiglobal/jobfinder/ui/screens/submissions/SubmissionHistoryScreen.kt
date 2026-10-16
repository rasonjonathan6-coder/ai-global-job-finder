package com.aiglobal.jobfinder.ui.screens.submissions

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiglobal.jobfinder.data.submissions.SubmissionEntry
import com.aiglobal.jobfinder.data.submissions.SubmissionOutcome
import com.aiglobal.jobfinder.ui.components.AppCard
import com.aiglobal.jobfinder.ui.components.EmptyState
import com.aiglobal.jobfinder.ui.components.FilterChip
import com.aiglobal.jobfinder.ui.components.IconChipButton
import com.aiglobal.jobfinder.ui.components.Pill
import com.aiglobal.jobfinder.ui.components.PrimaryButton
import com.aiglobal.jobfinder.ui.components.SecondaryButton
import com.aiglobal.jobfinder.ui.components.StatBlock
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors
import com.aiglobal.jobfinder.viewmodel.SubmissionHistoryViewModel

/**
 * The application registry, made legible. It answers three questions plainly: what has been
 * simulated, what was refused as a duplicate, and what is waiting for the user to confirm
 * whether it is the same opening. Nothing here ever sends anything.
 */
@Composable
fun SubmissionHistoryScreen(
    viewModel: SubmissionHistoryViewModel,
    onBack: () -> Unit,
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val sendingEnabled by viewModel.sendingEnabled.collectAsStateWithLifecycle()
    SubmissionHistoryContent(
        entries = entries,
        sendingEnabled = sendingEnabled,
        onBack = onBack,
        onSetSendingEnabled = viewModel::setSendingEnabled,
        onConfirmSame = viewModel::confirmSame,
        onConfirmDistinct = viewModel::confirmDistinct,
        onForget = viewModel::forget,
    )
}

/** Stateless body, so layout can be verified without a live registry. */
@Composable
internal fun SubmissionHistoryContent(
    entries: List<SubmissionEntry>,
    sendingEnabled: Boolean,
    onBack: () -> Unit,
    onSetSendingEnabled: (Boolean) -> Unit = {},
    onConfirmSame: (String, String) -> Unit = { _, _ -> },
    onConfirmDistinct: (String) -> Unit = {},
    onForget: (String) -> Unit = {},
) {
    val spacing = LocalSpacing.current
    var filter by remember { mutableStateOf(SubmissionFilter.All) }

    val sorted = entries.sortedByDescending { it.updatedAtEpochMillis }
    val pendingCount = sorted.count { it.awaitsVerification }
    val duplicateCount = sorted.count { it.outcome == SubmissionOutcome.BLOCKED_DUPLICATE }
    val simulatedCount = sorted.count { it.outcome == SubmissionOutcome.DRY_RUN }
    val visible = sorted.filter { filter.matches(it) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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
                    text = "Submission history",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Every attempt, simulated and blocked — nothing is ever sent",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = spacing.gutter,
                end = spacing.gutter,
                top = spacing.xxs,
                bottom = spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item { SummaryCard(simulated = simulatedCount, duplicates = duplicateCount, pending = pendingCount) }

            item { SendingGateCard(sendingEnabled = sendingEnabled, onSetSendingEnabled = onSetSendingEnabled) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    FilterChip(
                        label = "All",
                        selected = filter == SubmissionFilter.All,
                        onClick = { filter = SubmissionFilter.All },
                    )
                    FilterChip(
                        label = "Simulated",
                        selected = filter == SubmissionFilter.Simulated,
                        onClick = { filter = SubmissionFilter.Simulated },
                    )
                    FilterChip(
                        label = "Duplicates",
                        selected = filter == SubmissionFilter.Duplicates,
                        onClick = { filter = SubmissionFilter.Duplicates },
                    )
                    FilterChip(
                        label = "Verify",
                        selected = filter == SubmissionFilter.NeedsVerification,
                        onClick = { filter = SubmissionFilter.NeedsVerification },
                    )
                }
            }

            if (visible.isEmpty()) {
                item {
                    EmptyState(
                        emoji = "🗂️",
                        title = if (entries.isEmpty()) "No applications yet" else "Nothing in this filter",
                        message = if (entries.isEmpty()) {
                            "When you prepare and approve a draft, it is recorded here. Simulated attempts, blocked duplicates and postings needing your verification all show up in this list."
                        } else {
                            "No entries match the selected filter. Try another filter to see the rest of your history."
                        },
                    )
                }
            } else {
                items(visible, key = { it.key }) { entry ->
                    SubmissionCard(
                        entry = entry,
                        onConfirmSame = onConfirmSame,
                        onConfirmDistinct = onConfirmDistinct,
                        onForget = { onForget(entry.key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(simulated: Int, duplicates: Int, pending: Int) {
    val spacing = LocalSpacing.current
    AppCard {
        Text(
            text = "Registry summary",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(spacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatBlock(
                value = simulated.toString(),
                label = "Simulated",
                accent = MaterialTheme.colorScheme.primary,
            )
            StatBlock(
                value = duplicates.toString(),
                label = "Duplicates blocked",
                accent = extendedColors.warning,
            )
            StatBlock(
                value = pending.toString(),
                label = "Need verification",
                accent = extendedColors.info,
            )
        }
    }
}

@Composable
private fun SendingGateCard(sendingEnabled: Boolean, onSetSendingEnabled: (Boolean) -> Unit) {
    val spacing = LocalSpacing.current
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = extendedColors.info,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(spacing.xs))
            Text(
                text = "Real sending is locked",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = sendingEnabled, onCheckedChange = onSetSendingEnabled)
        }
        Spacer(Modifier.height(spacing.xs))
        Text(
            text = "Every attempt stays a simulation. Even with this switch on, no application can leave the app: no channel is verified for automatic sending yet, and the app will never ask for your email password.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SubmissionCard(
    entry: SubmissionEntry,
    onConfirmSame: (String, String) -> Unit,
    onConfirmDistinct: (String) -> Unit,
    onForget: () -> Unit,
) {
    val spacing = LocalSpacing.current
    AppCard {
        Row(verticalAlignment = Alignment.Top) {
            OutcomeIcon(entry.outcome)
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.jobTitle.ifBlank { "Untitled role" },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOf(entry.company, entry.country).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(spacing.xs))
            OutcomePill(entry.outcome)
        }

        Spacer(Modifier.height(spacing.sm))
        Text(
            text = entry.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (entry.seenSources.isNotEmpty()) {
            Spacer(Modifier.height(spacing.xs))
            Text(
                text = "Seen on ${entry.seenSources.joinToString(", ")} · ${entry.channel.label}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(spacing.sm))
        if (entry.awaitsVerification) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                PrimaryButton(
                    text = "Same posting",
                    onClick = { onConfirmSame(entry.key, entry.pendingReview) },
                    modifier = Modifier.weight(1f),
                    leadingIcon = Icons.Filled.CheckCircle,
                    height = 44.dp,
                )
                SecondaryButton(
                    text = "Different",
                    onClick = { onConfirmDistinct(entry.key) },
                    modifier = Modifier.weight(1f),
                    height = 44.dp,
                )
            }
        } else {
            SecondaryButton(
                text = "Remove from history",
                onClick = onForget,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.Filled.DeleteOutline,
                height = 44.dp,
            )
        }
    }
}

@Composable
private fun OutcomeIcon(outcome: SubmissionOutcome) {
    val (icon, tint) = outcomeVisuals(outcome)
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(color = tint.copy(alpha = 0.14f), shape = MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun OutcomePill(outcome: SubmissionOutcome) {
    val (_, tint) = outcomeVisuals(outcome)
    Pill(
        text = outcome.label,
        containerColor = tint.copy(alpha = 0.14f),
        contentColor = tint,
    )
}

@Composable
private fun outcomeVisuals(outcome: SubmissionOutcome): Pair<ImageVector, Color> = when (outcome) {
    SubmissionOutcome.SENT -> Icons.Filled.CheckCircle to extendedColors.success
    SubmissionOutcome.DRY_RUN -> Icons.Filled.PlayCircleOutline to MaterialTheme.colorScheme.primary
    SubmissionOutcome.BLOCKED_DUPLICATE,
    SubmissionOutcome.BLOCKED_CONCURRENT,
    SubmissionOutcome.BLOCKED_NOT_ENABLED,
    SubmissionOutcome.BLOCKED_INCOMPATIBLE,
    -> Icons.Filled.Block to extendedColors.warning
    SubmissionOutcome.NEEDS_VERIFICATION -> Icons.Filled.HourglassTop to extendedColors.info
}

private enum class SubmissionFilter {
    All,
    Simulated,
    Duplicates,
    NeedsVerification;

    fun matches(entry: SubmissionEntry): Boolean = when (this) {
        All -> true
        Simulated -> entry.outcome == SubmissionOutcome.DRY_RUN
        Duplicates -> entry.outcome == SubmissionOutcome.BLOCKED_DUPLICATE ||
            entry.outcome == SubmissionOutcome.BLOCKED_CONCURRENT
        NeedsVerification -> entry.awaitsVerification
    }
}
