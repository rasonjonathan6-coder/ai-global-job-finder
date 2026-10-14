package com.aiglobal.jobfinder.ui.screens.autoapply

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiglobal.jobfinder.data.model.AutoApplyItem
import com.aiglobal.jobfinder.data.model.AutoApplyStatus
import com.aiglobal.jobfinder.ui.components.AppCard
import com.aiglobal.jobfinder.ui.components.EmptyState
import com.aiglobal.jobfinder.ui.components.FilterChip
import com.aiglobal.jobfinder.ui.components.IconChipButton
import com.aiglobal.jobfinder.ui.components.Pill
import com.aiglobal.jobfinder.ui.components.PrimaryButton
import com.aiglobal.jobfinder.ui.components.SecondaryButton
import com.aiglobal.jobfinder.ui.components.SectionHeader
import com.aiglobal.jobfinder.ui.components.SkeletonBox
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors
import com.aiglobal.jobfinder.viewmodel.AutoApplyViewModel

/**
 * Safe auto-apply. The screen is explicit that the mode prepares drafts and never
 * submits: approval is always a deliberate tap, and the send happens on the posting.
 */
@Composable
fun AutoApplyScreen(
    viewModel: AutoApplyViewModel,
    onBack: () -> Unit,
    onOpenJob: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AutoApplyContent(
        state = state,
        onBack = onBack,
        onOpenJob = onOpenJob,
        onToggle = viewModel::setEnabled,
        onSetThreshold = viewModel::setThreshold,
        onPrepare = viewModel::prepare,
        onApprove = viewModel::approve,
        onSkip = viewModel::skip,
    )
}

/** Stateless body, so layout can be verified without a live pipeline. */
@Composable
internal fun AutoApplyContent(
    state: com.aiglobal.jobfinder.data.model.AutoApplyState,
    onBack: () -> Unit,
    onOpenJob: (String) -> Unit = {},
    onToggle: (Boolean) -> Unit = {},
    onSetThreshold: (Int) -> Unit = {},
    onPrepare: () -> Unit = {},
    onApprove: (String) -> Unit = {},
    onSkip: (String) -> Unit = {},
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
            IconChipButton(icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack, contentDescription = "Back")
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Safe auto-apply",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Prepare drafts, you approve every send",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            item { MasterSwitchCard(enabled = state.enabled, onToggle = onToggle) }

            item { SafetyCard() }

            if (state.enabled) {
                item { ThresholdCard(threshold = state.threshold, onSetThreshold = onSetThreshold) }

                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionHeader(
                            title = "Prepared applications",
                            subtitle = "${state.readyCount} ready · ${state.blockedCount} blocked",
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryButton(
                            text = "Prepare",
                            onClick = onPrepare,
                            leadingIcon = Icons.Filled.AutoAwesome,
                            height = 42.dp,
                        )
                    }
                }

                if (state.isPreparing && state.items.isEmpty()) {
                    item { SkeletonBox(modifier = Modifier.fillMaxWidth().height(96.dp), RoundedCornerShape(20.dp)) }
                    item { SkeletonBox(modifier = Modifier.fillMaxWidth().height(96.dp), RoundedCornerShape(20.dp)) }
                } else if (state.activeItems.isEmpty()) {
                    item {
                        EmptyState(
                            emoji = "⚡",
                            title = "Nothing to prepare",
                            message = "No posting clears your ${state.threshold}% threshold right now. Lower it or widen your target roles.",
                        )
                    }
                } else {
                    items(state.activeItems.size) { index ->
                        val item = state.activeItems[index]
                        AutoApplyCard(
                            item = item,
                            onApprove = { onApprove(item.jobId) },
                            onSkip = { onSkip(item.jobId) },
                            onOpenJob = { onOpenJob(item.jobId) },
                        )
                    }
                }
            } else {
                item {
                    EmptyState(
                        emoji = "🛡️",
                        title = "Auto-apply is off",
                        message = "Turn it on and the app prepares a tailored CV and cover letter for every job above your threshold. Nothing is sent until you approve it.",
                    )
                }
            }
        }
    }
}

@Composable
private fun MasterSwitchCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Prepare applications automatically",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Drafts only. The app never submits on your behalf.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun SafetyCard() {
    val spacing = LocalSpacing.current
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = extendedColors.successContainer,
        borderColor = extendedColors.success.copy(alpha = 0.30f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = extendedColors.success, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(spacing.xs))
            Text(
                text = "What safe auto-apply will and will not do",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(spacing.sm))
        SafetyLine(Icons.Filled.CheckCircle, extendedColors.success, "Prepares a CV and cover letter for each job above your threshold.")
        SafetyLine(Icons.Filled.CheckCircle, extendedColors.success, "Blocks anything the scam shield flags, or that is not open to Madagascar.")
        SafetyLine(Icons.Filled.Block, extendedColors.danger, "Never sends an application, email or message without your tap.")
        SafetyLine(Icons.Filled.Block, extendedColors.danger, "Never pays a fee, shares documents, or signs anything for you.")
    }
}

@Composable
private fun SafetyLine(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp).padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ThresholdCard(threshold: Int, onSetThreshold: (Int) -> Unit) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Match threshold",
            subtitle = "Only prepare jobs at or above $threshold%",
        )
        Spacer(Modifier.height(spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(50, 60, 70, 80, 90).forEach { value ->
                FilterChip(label = "$value%", selected = threshold == value, onClick = { onSetThreshold(value) })
            }
        }
    }
}

@Composable
private fun AutoApplyCard(
    item: AutoApplyItem,
    onApprove: () -> Unit,
    onSkip: () -> Unit,
    onOpenJob: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val (tone, icon, label) = when (item.status) {
        AutoApplyStatus.DRAFT_READY -> Triple(MaterialTheme.colorScheme.primary, Icons.Filled.Description, "Draft ready")
        AutoApplyStatus.APPROVED -> Triple(extendedColors.success, Icons.Filled.CheckCircle, "Approved")
        AutoApplyStatus.BLOCKED -> Triple(extendedColors.danger, Icons.Filled.Block, "Blocked")
        AutoApplyStatus.QUEUED -> Triple(extendedColors.info, Icons.Filled.Info, "Queued")
        AutoApplyStatus.SKIPPED -> Triple(MaterialTheme.colorScheme.onSurfaceVariant, Icons.Filled.SkipNext, "Skipped")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, extendedColors.cardStroke, RoundedCornerShape(20.dp))
            .padding(spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = item.countryFlag.ifBlank { "🌍" }, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.jobTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    text = "${item.company} · ${item.country}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Pill(
                text = "${item.matchScore}%",
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                contentColor = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(spacing.sm))
        Row(verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(15.dp).padding(top = 2.dp))
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = tone,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = item.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (item.status == AutoApplyStatus.DRAFT_READY || item.status == AutoApplyStatus.QUEUED) {
            Spacer(Modifier.height(spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                PrimaryButton(
                    text = "Review & approve",
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    leadingIcon = Icons.Filled.CheckCircle,
                    height = 46.dp,
                )
                SecondaryButton(
                    text = "Skip",
                    onClick = onSkip,
                    leadingIcon = Icons.Filled.SkipNext,
                    height = 46.dp,
                )
            }
        }

        if (item.status == AutoApplyStatus.APPROVED) {
            Spacer(Modifier.height(spacing.sm))
            SecondaryButton(
                text = "Open posting to send",
                onClick = onOpenJob,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.AutoMirrored.Filled.Send,
                height = 46.dp,
            )
        }
    }
}
