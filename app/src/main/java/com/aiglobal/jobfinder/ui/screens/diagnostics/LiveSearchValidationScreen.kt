package com.aiglobal.jobfinder.ui.screens.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiglobal.jobfinder.data.model.ProviderDiagnostic
import com.aiglobal.jobfinder.data.model.ProviderStatus
import com.aiglobal.jobfinder.ui.components.AppCard
import com.aiglobal.jobfinder.ui.components.EmptyState
import com.aiglobal.jobfinder.ui.components.IconChipButton
import com.aiglobal.jobfinder.ui.components.Pill
import com.aiglobal.jobfinder.ui.components.PrimaryButton
import com.aiglobal.jobfinder.ui.components.SectionHeader
import com.aiglobal.jobfinder.ui.components.clickableNoRipple
import com.aiglobal.jobfinder.ui.components.relativeTime
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors
import com.aiglobal.jobfinder.viewmodel.SourceDiagnosticsUiState
import com.aiglobal.jobfinder.viewmodel.SourceDiagnosticsViewModel

/** Live-search validation: what each source actually did, and a way to re-run it. */
@Composable
fun LiveSearchValidationScreen(
    viewModel: SourceDiagnosticsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LiveSearchValidationContent(
        state = state,
        onBack = onBack,
        onRun = viewModel::runValidation,
        onDismissNote = viewModel::dismissNote,
    )
}

/** Stateless body, so the screen can be verified without a live repository. */
@Composable
internal fun LiveSearchValidationContent(
    state: SourceDiagnosticsUiState,
    onBack: () -> Unit,
    onRun: () -> Unit = {},
    onToggleSource: (String, Boolean) -> Unit = { _, _ -> },
    onOpenDocumentation: (String) -> Unit = {},
    onDismissNote: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    val diagnostics = state.diagnostics

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
                    text = "Live search validation",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "What each source actually returned",
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
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (!diagnostics.networkAvailable) {
                item { OfflineBanner() }
            }
            if (diagnostics.showingCachedOnly) {
                item { CachedOnlyBanner() }
            }

            item { RunSummaryCard(state = state, onRun = onRun, onDismissNote = onDismissNote) }

            item {
                SectionHeader(
                    title = "Sources",
                    subtitle = "${diagnostics.successCount} answered · ${diagnostics.failureCount} failed",
                )
            }

            if (diagnostics.providers.isEmpty()) {
                item {
                    EmptyState(
                        emoji = "🔌",
                        title = "No sources configured",
                        message = "Job sources are added by the app; none are registered right now.",
                    )
                }
            } else {
                items(diagnostics.providers, key = { it.providerId }) { provider ->
                    SourceDiagnosticCard(
                        provider = provider,
                        onToggle = { enabled -> onToggleSource(provider.providerId, enabled) },
                        onOpenDocumentation = { onOpenDocumentation(provider.documentationUrl) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RunSummaryCard(state: SourceDiagnosticsUiState, onRun: () -> Unit, onDismissNote: () -> Unit) {
    val spacing = LocalSpacing.current
    val diagnostics = state.diagnostics
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.VerifiedUser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(spacing.xs))
                Text(
                    text = "Last run",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = diagnostics.lastAttemptAtEpochMillis?.let { relativeTime(it) } ?: "Not run yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                SummaryStat("Answered", diagnostics.successCount, extendedColors.success, Modifier.weight(1f))
                SummaryStat("Failed", diagnostics.failureCount, extendedColors.danger, Modifier.weight(1f))
                SummaryStat("Usable jobs", diagnostics.totalValidJobs, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            }

            if (diagnostics.totalDuplicatesRemoved > 0 || diagnostics.totalRejected > 0) {
                Text(
                    text = "${diagnostics.totalDuplicatesRemoved} duplicate(s) removed · " +
                        "${diagnostics.totalRejected} record(s) did not pass validation",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = state.note != null) {
                state.note?.let { note ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(extendedColors.infoContainer)
                            .clickableNoRipple(onDismissNote)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            PrimaryButton(
                text = if (state.isRunning) "Validating…" else "Run validation now",
                onClick = onRun,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isRunning,
                leadingIcon = Icons.Filled.PlayArrow,
            )
            Text(
                text = "Running validation re-reads every enabled source. A source that fails never clears the postings already on screen.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceDiagnosticCard(
    provider: ProviderDiagnostic,
    onToggle: (Boolean) -> Unit,
    onOpenDocumentation: () -> Unit,
) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (provider.lastAttemptAtEpochMillis != null) {
                        Text(
                            text = "Checked ${relativeTime(provider.lastAttemptAtEpochMillis)}" +
                                (provider.lastDurationMillis?.let { " · ${it} ms" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = "Not checked yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SourceStatusBadge(provider.status)
            }

            Text(
                text = provider.message ?: providerDetail(provider),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                if (provider.status.isSuccess) {
                    Pill(
                        text = "${provider.validJobCount} usable",
                        containerColor = extendedColors.successContainer,
                        contentColor = extendedColors.onSuccessContainer,
                    )
                }
                if (provider.duplicateRemovedCount > 0) {
                    Pill(
                        text = "${provider.duplicateRemovedCount} duplicate",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (provider.malformedRejectedCount > 0) {
                    Pill(
                        text = "${provider.malformedRejectedCount} rejected",
                        containerColor = extendedColors.warningContainer,
                        contentColor = extendedColors.warning,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (provider.enabled) "Source enabled" else "Source disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = provider.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
                )
            }

            if (provider.documentationUrl.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickableNoRipple(onOpenDocumentation)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "Source documentation",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** The status chip for a source, reused by the Profile diagnostics section. */
@Composable
internal fun SourceStatusBadge(status: ProviderStatus) {
    val (icon, color, container) = statusStyle(status)
    Pill(text = status.label, containerColor = container, contentColor = color, leadingIcon = icon)
}

@Composable
private fun statusStyle(status: ProviderStatus): Triple<ImageVector, Color, Color> = when {
    status.isSuccess -> Triple(Icons.Filled.TaskAlt, extendedColors.success, extendedColors.successContainer)
    status.isFailure -> Triple(Icons.Filled.ErrorOutline, extendedColors.danger, extendedColors.dangerContainer)
    status == ProviderStatus.WAITING_FOR_NETWORK ->
        Triple(Icons.Filled.CloudOff, extendedColors.warning, extendedColors.warningContainer)
    status == ProviderStatus.IN_PROGRESS ->
        Triple(Icons.Filled.HourglassEmpty, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceVariant)
    else ->
        Triple(Icons.Filled.WarningAmber, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
}

/** A source's own explanation if it gave one, otherwise the reason for its current state. */
internal fun providerDetail(provider: ProviderDiagnostic): String = when {
    provider.needsSetup -> "This source needs a key before it can be searched."
    provider.status.isSuccess -> "This source answered normally."
    provider.status.isDormant -> "No request was made to this source on the last run."
    else -> "The last attempt to reach this source did not succeed."
}

@Composable
private fun OfflineBanner() {
    BannerRow(
        icon = Icons.Filled.CloudOff,
        text = "You are offline. No requests were sent, and the postings on screen are the last saved ones.",
        container = extendedColors.warningContainer,
        tint = extendedColors.warning,
    )
}

@Composable
private fun CachedOnlyBanner() {
    BannerRow(
        icon = Icons.Filled.ErrorOutline,
        text = "No source answered on the last run. Showing the last good results until a source responds.",
        container = extendedColors.dangerContainer,
        tint = extendedColors.danger,
    )
}

@Composable
private fun BannerRow(
    icon: ImageVector,
    text: String,
    container: Color,
    tint: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
