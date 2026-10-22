package com.aiglobal.jobfinder.ui.screens.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiglobal.jobfinder.data.jobs.provider.ProviderAudit
import com.aiglobal.jobfinder.data.model.ProviderDiagnostic
import com.aiglobal.jobfinder.data.model.ProviderStatus
import com.aiglobal.jobfinder.data.submissions.OfficialSubmissionAudit
import com.aiglobal.jobfinder.data.submissions.SubmissionPrerequisite
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

            item { SubmissionPrerequisitesCard() }

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
                        audit = state.auditFor(provider.providerId),
                        onToggle = { enabled -> onToggleSource(provider.providerId, enabled) },
                        onOpenDocumentation = { onOpenDocumentation(provider.documentationUrl) },
                    )
                }
            }
        }
    }
}

/**
 * Stage 49 — the prerequisites for a real, API-driven submission, listed honestly. Every row
 * comes from [OfficialSubmissionAudit], which is derived from the provider's own documentation,
 * so this panel explains *why* the app cannot send instead of leaving the user to guess.
 */
@Composable
private fun SubmissionPrerequisitesCard() {
    val spacing = LocalSpacing.current
    val available = OfficialSubmissionAudit.realSubmissionAvailable
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (available) Icons.Filled.Verified else Icons.Filled.Lock,
                    contentDescription = null,
                    tint = if (available) extendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(spacing.xs))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Real application submission",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (available) "Available" else "Not available yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (available) extendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Pill(
                    text = "${OfficialSubmissionAudit.SMART_RECRUITERS_PREREQUISITES.count { it.met }}/" +
                        "${OfficialSubmissionAudit.SMART_RECRUITERS_PREREQUISITES.size} met",
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = OfficialSubmissionAudit.statusSummary(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OfficialSubmissionAudit.SMART_RECRUITERS_PREREQUISITES.forEach { prerequisite ->
                PrerequisiteRow(prerequisite)
            }

            Text(
                text = "Source: SmartRecruiters Application API documentation — " +
                    OfficialSubmissionAudit.SMART_RECRUITERS_DOCS,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PrerequisiteRow(prerequisite: SubmissionPrerequisite) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (prerequisite.met) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (prerequisite.met) extendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(spacing.xs))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = prerequisite.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = prerequisite.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!prerequisite.met) {
                Text(
                    text = "To enable: ${prerequisite.howToSatisfy}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
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
    audit: ProviderAudit?,
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

            // A failure does not throw away the last good read, so say so plainly. Without
            // this the counts on the card (from the older successful attempt) would look like
            // they came from the attempt that just failed.
            if (provider.status.isFailure && provider.lastSuccessAtEpochMillis != null) {
                Text(
                    text = "Kept from the last good read ${relativeTime(provider.lastSuccessAtEpochMillis)}. " +
                        "This failure did not empty the list.",
                    style = MaterialTheme.typography.labelSmall,
                    color = extendedColors.warning,
                )
            }

            if (audit != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    // The kind of source this is, stated from the source's own definition —
                    // so a fixture or a mock is never read as a live feed.
                    Pill(
                        text = "${audit.dataSource.label} · ${audit.kind.name.lowercase().replace('_', ' ')}",
                        containerColor = if (audit.isLive) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (audit.isLive) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Pill(
                        text = audit.reachabilityLabel,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (audit.capabilities.serverSideKeyword) {
                        Pill(
                            text = "Keyword search",
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!audit.isLive) {
                    Text(
                        text = "This source serves bundled sample data, not a live feed.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (audit.botGated) {
                    Text(
                        text = "This host is known to challenge automated requests; a failure here is the gate, not an empty feed.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // The employer portals this source actually reads, by their published names.
                // Shown so the curated employers are visible in the app rather than only the
                // aggregating source. Collapsed by default to keep the card scannable; the
                // count is always stated, and the names are one tap away. The list is derived
                // from the same curated lists the feed walks, so it can never name a portal the
                // source does not query.
                if (audit.portals.isNotEmpty()) {
                    var portalsExpanded by rememberSaveable(audit.id) { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { portalsExpanded = !portalsExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        Icon(
                            imageVector = if (portalsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "${audit.portals.size} employer " +
                                if (audit.portals.size == 1) "portal" else "portals",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (portalsExpanded) {
                        Text(
                            text = audit.portals.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

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
                if (!provider.realSubmissionSupported) {
                    Pill(
                        text = "No auto-send",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!provider.realSubmissionSupported) {
                Text(
                    text = "Applications from this source are prepared and simulated — the app never sends them for you.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
