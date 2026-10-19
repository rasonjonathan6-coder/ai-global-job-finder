package com.aiglobal.jobfinder.ui.screens.refresh

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiglobal.jobfinder.data.discovery.RefreshHistory
import com.aiglobal.jobfinder.data.discovery.RefreshOutcome
import com.aiglobal.jobfinder.data.discovery.RefreshRunRecord
import com.aiglobal.jobfinder.data.discovery.RefreshTrigger
import com.aiglobal.jobfinder.ui.components.AppCard
import com.aiglobal.jobfinder.ui.components.EmptyState
import com.aiglobal.jobfinder.ui.components.IconChipButton
import com.aiglobal.jobfinder.ui.components.Pill
import com.aiglobal.jobfinder.ui.components.StatBlock
import com.aiglobal.jobfinder.ui.components.relativeTime
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors
import com.aiglobal.jobfinder.viewmodel.RefreshHistoryViewModel

/**
 * The refresh history, made legible. It answers what the automatic and manual searches have
 * actually done: when each ran, what triggered it, how it ended, and which sources answered.
 * It reports the run that happened rather than the run that was requested, so a failed or
 * skipped search is visible instead of silent.
 */
@Composable
fun RefreshHistoryScreen(
    viewModel: RefreshHistoryViewModel,
    onBack: () -> Unit,
) {
    val history by viewModel.history.collectAsStateWithLifecycle(initialValue = RefreshHistory())
    RefreshHistoryContent(history = history, onBack = onBack)
}

/** Stateless body, so layout can be verified without a live store. */
@Composable
internal fun RefreshHistoryContent(
    history: RefreshHistory,
    onBack: () -> Unit,
) {
    val spacing = LocalSpacing.current

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
                    text = "Refresh history",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Every search the app has run, manual and automatic",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (history.runs.isEmpty()) {
            EmptyState(
                title = "No searches yet",
                message = "When the app refreshes the job sources — automatically or when you tap " +
                    "Refresh now — each run will appear here.",
                emoji = "🛰️",
            )
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = spacing.gutter,
                end = spacing.gutter,
                top = spacing.sm,
                bottom = spacing.navBarClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item { HistoryOverview(history) }
            items(history.runs, key = { it.id }) { run -> RunCard(run) }
        }
    }
}

/** A compact overview: how many runs, how many succeeded, and how many failed outright. */
@Composable
private fun HistoryOverview(history: RefreshHistory) {
    val spacing = LocalSpacing.current
    val succeeded = history.runs.count { it.succeeded }
    val failed = history.runs.count { it.outcome == RefreshOutcome.FAILED }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            StatBlock(value = history.runs.size.toString(), label = "Runs", modifier = Modifier.weight(1f))
            StatBlock(value = succeeded.toString(), label = "Succeeded", modifier = Modifier.weight(1f))
            StatBlock(value = failed.toString(), label = "Failed", modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun RunCard(run: RefreshRunRecord) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(38.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = run.outcome.icon(),
                        contentDescription = null,
                        tint = run.outcome.tint(),
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = run.outcome.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = relativeTime(run.endedAtEpochMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Pill(
                    text = run.trigger.label,
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    leadingIcon = if (run.trigger == RefreshTrigger.MANUAL) {
                        Icons.Filled.TouchApp
                    } else {
                        Icons.Filled.Schedule
                    },
                )
            }

            run.reason?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (run.succeeded) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        extendedColors.warning
                    },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                MetaItem(Icons.Filled.Timer, "${run.durationMillis / 1000}s")
                MetaItem(
                    Icons.Filled.CheckCircle,
                    "${run.successCount} source${if (run.successCount == 1) "" else "s"} ok",
                )
                if (run.failureCount > 0) {
                    MetaItem(Icons.Filled.CloudOff, "${run.failureCount} failed")
                }
            }

            if (run.newCount != null || run.updatedCount != null || run.duplicateCount != null) {
                Text(
                    text = measuredCounts(run),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val failing = run.providers.filter { it.failed }
            if (failing.isNotEmpty()) {
                Text(
                    text = "Could not reach: " + failing.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = extendedColors.warning,
                )
            }
        }
    }
}

@Composable
private fun MetaItem(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** The measured counts, phrased so a zero is only shown when it was genuinely measured. */
private fun measuredCounts(run: RefreshRunRecord): String = buildString {
    val new = run.newCount
    val updated = run.updatedCount
    if (new != null) {
        append("$new new")
    }
    if (updated != null) {
        if (isNotEmpty()) append(" · ")
        append("$updated updated")
    }
    val duplicates = run.duplicateCount
    if (duplicates != null && duplicates > 0) {
        if (isNotEmpty()) append(" · ")
        append("$duplicates duplicate${if (duplicates == 1) "" else "s"} removed")
    }
    if (isEmpty()) append("No change measured")
}

private fun RefreshOutcome.icon(): ImageVector = when (this) {
    RefreshOutcome.SUCCEEDED -> Icons.Filled.CheckCircle
    RefreshOutcome.PARTIAL -> Icons.Filled.CheckCircle
    RefreshOutcome.FAILED -> Icons.Filled.ErrorOutline
    RefreshOutcome.RATE_LIMITED -> Icons.Filled.HourglassEmpty
    RefreshOutcome.OFFLINE -> Icons.Filled.CloudOff
    RefreshOutcome.NOT_ATTEMPTED -> Icons.Filled.HourglassEmpty
}

@Composable
private fun RefreshOutcome.tint(): Color = when (this) {
    RefreshOutcome.SUCCEEDED, RefreshOutcome.PARTIAL -> extendedColors.success
    RefreshOutcome.RATE_LIMITED, RefreshOutcome.NOT_ATTEMPTED -> extendedColors.warning
    RefreshOutcome.FAILED, RefreshOutcome.OFFLINE -> MaterialTheme.colorScheme.error
}
