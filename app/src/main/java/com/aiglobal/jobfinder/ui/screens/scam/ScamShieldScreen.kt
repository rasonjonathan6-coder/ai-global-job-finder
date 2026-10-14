package com.aiglobal.jobfinder.ui.screens.scam

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiglobal.jobfinder.data.model.ScamReport
import com.aiglobal.jobfinder.data.model.ScamSignal
import com.aiglobal.jobfinder.data.model.ScamSignalTone
import com.aiglobal.jobfinder.ui.components.AppCard
import com.aiglobal.jobfinder.ui.components.EmptyState
import com.aiglobal.jobfinder.ui.components.IconChipButton
import com.aiglobal.jobfinder.ui.components.Pill
import com.aiglobal.jobfinder.ui.components.SecondaryButton
import com.aiglobal.jobfinder.ui.components.SectionHeader
import com.aiglobal.jobfinder.ui.components.SkeletonBox
import com.aiglobal.jobfinder.ui.components.isDarkTheme
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors
import com.aiglobal.jobfinder.viewmodel.ScamShieldViewModel
import com.aiglobal.jobfinder.viewmodel.ScamUiState

/** The scam shield for one posting: every signal is quoted, so the user can judge it. */
@Composable
fun ScamShieldScreen(
    viewModel: ScamShieldViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ScamShieldContent(state = state, onBack = onBack, onRescan = viewModel::rescan)
}

/** Stateless body, so layout can be verified without a live pipeline. */
@Composable
internal fun ScamShieldContent(
    state: ScamUiState,
    onBack: () -> Unit,
    onRescan: () -> Unit = {},
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
                    text = "Scam shield",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = state.job?.title ?: "Posting check",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }

        when {
            state.isLoading -> LoadingBody()
            state.report == null -> EmptyState(
                emoji = "🛡️",
                title = "Nothing to check",
                message = state.message ?: "This posting could not be scanned.",
                modifier = Modifier.padding(top = spacing.xxl),
            )
            else -> ReportBody(report = state.report, onRescan = onRescan)
        }
    }
}

@Composable
private fun LoadingBody() {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.padding(horizontal = spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        SkeletonBox(modifier = Modifier.fillMaxWidth().height(150.dp), RoundedCornerShape(24.dp))
        repeat(3) { SkeletonBox(modifier = Modifier.fillMaxWidth().height(84.dp), RoundedCornerShape(20.dp)) }
    }
}

@Composable
private fun ReportBody(report: ScamReport, onRescan: () -> Unit) {
    val spacing = LocalSpacing.current
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
        item { VerdictCard(report) }

        if (report.dangers.isNotEmpty()) {
            item { SectionHeader(title = "Stop and read", subtitle = "These are the patterns that end in lost money") }
            items(report.dangers) { signal -> SignalCard(signal, ScamSignalTone.DANGER) }
        }

        if (report.cautions.isNotEmpty()) {
            item { SectionHeader(title = "Check before you apply", subtitle = "Not proof of a scam, but worth verifying") }
            items(report.cautions) { signal -> SignalCard(signal, ScamSignalTone.CAUTION) }
        }

        if (report.reassurances.isNotEmpty()) {
            item { SectionHeader(title = "Good signs", subtitle = "What this posting gets right") }
            items(report.reassurances) { signal -> SignalCard(signal, ScamSignalTone.REASSURANCE) }
        }

        item { RulesCard() }

        item {
            SecondaryButton(
                text = "Re-run the check",
                onClick = onRescan,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.Filled.Refresh,
            )
        }
    }
}

/** A stable list of the rules the detector applies, shown so the advice is auditable. */
@Composable
private fun RulesCard() {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = "The rules behind this check",
            subtitle = "Applied to every posting in your feed",
        )
        Spacer(Modifier.height(spacing.sm))
        RULES.forEach { rule ->
            Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp).padding(top = 2.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = rule,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VerdictCard(report: ScamReport) {
    val spacing = LocalSpacing.current
    val dark = isDarkTheme()
    val (tone, gradient) = when (report.verdict) {
        com.aiglobal.jobfinder.data.model.ScamVerdict.HIGH_RISK ->
            extendedColors.danger to listOf(Color(0xFF7A1B15), Color(0xFF3A0D0A))
        com.aiglobal.jobfinder.data.model.ScamVerdict.NEEDS_CAUTION ->
            extendedColors.warning to listOf(Color(0xFF5C3A08), Color(0xFF2A1B05))
        com.aiglobal.jobfinder.data.model.ScamVerdict.LIKELY_SAFE ->
            extendedColors.success to listOf(Color(0xFF0C4A34), Color(0xFF06251A))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(if (dark) gradient else gradient.map { it.copy(alpha = 0.96f) }))
            .padding(spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Gavel, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${report.verdict.emoji} ${report.verdict.label}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Text(
                    text = report.company.ifBlank { "Unknown employer" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Pill(
                text = "${report.safetyScore}%",
                containerColor = Color.White.copy(alpha = 0.16f),
                contentColor = Color.White,
            )
        }
        Spacer(Modifier.height(spacing.sm))
        Text(
            text = verdictAdvice(report),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.85f),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${report.dangers.size} danger · ${report.cautions.size} caution · ${report.reassurances.size} good signs",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "This is guidance, not a guarantee. You decide.",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.6f),
        )
    }
}

private fun verdictAdvice(report: ScamReport): String = when (report.verdict) {
    com.aiglobal.jobfinder.data.model.ScamVerdict.HIGH_RISK ->
        "Do not send money, documents or personal details. Real employers never ask for a fee. Verify the company independently before replying."
    com.aiglobal.jobfinder.data.model.ScamVerdict.NEEDS_CAUTION ->
        "This posting is not proven bad, but something is missing. Confirm the employer's official domain and the visa route before you invest time."
    com.aiglobal.jobfinder.data.model.ScamVerdict.LIKELY_SAFE ->
        "Nothing alarming found. Still apply through the employer's own site and keep a copy of every message."
}

@Composable
private fun SignalCard(signal: ScamSignal, tone: ScamSignalTone) {
    val spacing = LocalSpacing.current
    val color = when (tone) {
        ScamSignalTone.DANGER -> extendedColors.danger
        ScamSignalTone.CAUTION -> extendedColors.warning
        ScamSignalTone.REASSURANCE -> extendedColors.success
    }
    val icon = when (tone) {
        ScamSignalTone.DANGER -> Icons.Filled.ErrorOutline
        ScamSignalTone.CAUTION -> Icons.Filled.WarningAmber
        ScamSignalTone.REASSURANCE -> Icons.Filled.CheckCircle
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = signal.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = signal.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (signal.evidence.isNotBlank()) {
                Spacer(Modifier.height(spacing.xs))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(color.copy(alpha = 0.10f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Gavel,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "\"${signal.evidence}\"",
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val RULES = listOf(
    "A real employer never charges a registration, placement or training fee.",
    "You should never pay a third party for a work visa or permit.",
    "Payment by wire, money transfer or crypto to an employer is a scam pattern.",
    "Interviews arranged only on WhatsApp or Telegram deserve scrutiny.",
    "A company contact should use the company's own domain, not a free inbox.",
    "A posting without a named employer, a link or a description cannot be verified.",
)

/** Opens the original posting so the user can read it themselves. */
@Composable
internal fun OpenSourceRow(url: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = url,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
