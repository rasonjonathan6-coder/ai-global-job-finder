package com.aiglobal.jobfinder.ui.screens.jobs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiglobal.jobfinder.data.apply.ApplicationDestination
import com.aiglobal.jobfinder.data.apply.ApplicationLinkInspection
import com.aiglobal.jobfinder.data.apply.ApplicationUrlInspector
import com.aiglobal.jobfinder.data.jobs.freshness.JobFreshnessEngine
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.ui.components.PrimaryButton
import com.aiglobal.jobfinder.ui.components.SecondaryButton
import com.aiglobal.jobfinder.ui.components.SectionHeader
import com.aiglobal.jobfinder.ui.components.postedAgeLabel
import com.aiglobal.jobfinder.ui.components.retrievedLabel
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors

/**
 * Confirms where an "Apply" tap will actually go before the browser opens. The candidate
 * is deciding whether to trust a link with a real application and their time, so the app
 * states the host, what kind of site it is, and any concern it found — rather than
 * silently launching a URL.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ApplyLinkSheet(
    job: Job,
    inspection: ApplicationLinkInspection,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        ApplyLinkSheetBody(job = job, inspection = inspection, onDismiss = onDismiss, onOpen = onOpen)
    }
}

/**
 * The sheet's contents, kept free of the `ModalBottomSheet` host so it can be rendered and
 * verified on its own.
 */
@Composable
internal fun ApplyLinkSheetBody(
    job: Job,
    inspection: ApplicationLinkInspection,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.gutter)
            .padding(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = job.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = job.company.ifBlank { "Unknown employer" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionHeader(
            title = "Where this opens",
            subtitle = "Check the destination before you share any personal details",
        )

        DestinationRow(inspection)

        if (inspection.warnings.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                inspection.warnings.forEach { warning -> WarningRow(warning) }
            }
        }

        if (inspection.host != null) {
            Text(
                text = inspection.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val freshness = remember(job) { jobFreshnessEngine.assess(job) }
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Text(
                text = "Freshness and source",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${freshness.label} — ${freshness.detail}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Published: ${postedAgeLabel(job.postedAtEpochMillis)}  ·  ${retrievedLabel(job.retrievedAtEpochMillis)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Listed by ${job.source.ifBlank { "an unknown source" }}. A working link does not by itself confirm the vacancy is still open.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(spacing.xxs))
        PrimaryButton(
            text = if (inspection.canOpen) "Open posting" else "No link to open",
            onClick = { onOpen(inspection.url) },
            modifier = Modifier.fillMaxWidth(),
            enabled = inspection.canOpen,
            leadingIcon = Icons.AutoMirrored.Filled.OpenInNew,
        )
        SecondaryButton(
            text = "Cancel",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Never pay a fee to apply. Legitimate employers do not ask for payment.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DestinationRow(inspection: ApplicationLinkInspection) {
    val (icon, tint, container) = destinationStyle(inspection.destination)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = inspection.destination.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = inspection.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WarningRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(extendedColors.warningContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
    ) {
        Icon(
            Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = extendedColors.warning,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun destinationStyle(destination: ApplicationDestination): Triple<ImageVector, androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> =
    when (destination) {
        ApplicationDestination.EMPLOYER_SITE ->
            Triple(Icons.Filled.Business, extendedColors.success, extendedColors.successContainer)
        ApplicationDestination.GOVERNMENT_PORTAL ->
            Triple(Icons.Filled.AccountBalance, extendedColors.info, extendedColors.infoContainer)
        ApplicationDestination.JOB_BOARD ->
            Triple(Icons.Filled.WorkOutline, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceVariant)
        ApplicationDestination.UNKNOWN_HOST ->
            Triple(Icons.Filled.Public, extendedColors.warning, extendedColors.warningContainer)
        ApplicationDestination.INVALID ->
            Triple(Icons.Filled.LinkOff, extendedColors.danger, extendedColors.dangerContainer)
    }

/** Shared inspector, so the sheet and any caller agree on how a link is judged. */
internal val applicationUrlInspector = ApplicationUrlInspector()

/** Shared freshness classifier, so the sheet and any caller agree on how old a posting is. */
internal val jobFreshnessEngine = JobFreshnessEngine()
