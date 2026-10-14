package com.aiglobal.jobfinder.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aiglobal.jobfinder.data.model.ApplicationReadiness
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.MatchTier
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.ui.theme.extendedColors

/** Color assigned to a match band. Strong matches read as green, weak ones as amber. */
@Composable
fun matchTierColor(tier: MatchTier): Color = when (tier) {
    MatchTier.EXCELLENT -> extendedColors.success
    MatchTier.STRONG -> extendedColors.info
    MatchTier.GOOD -> extendedColors.warning
    MatchTier.FAIR -> extendedColors.warning
    MatchTier.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Animated circular match gauge. The arc sweeps from zero on first composition,
 * which doubles as the "AI is evaluating" cue while the list settles.
 */
@Composable
fun MatchScoreRing(
    score: Int,
    modifier: Modifier = Modifier,
    diameter: Dp = 56.dp,
    strokeWidth: Dp = 5.dp,
    showLabel: Boolean = true,
) {
    val tier = MatchTier.fromScore(score)
    val color = matchTierColor(tier)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val progress = remember { Animatable(0f) }

    LaunchedEffect(score) {
        progress.animateTo(
            targetValue = score / 100f,
            animationSpec = tween(900, easing = FastOutSlowInEasing),
        )
    }

    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(diameter)) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * progress.value,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        if (showLabel) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$score%",
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** Inline "95% Match" chip for compact rows. */
@Composable
fun MatchChip(score: Int, modifier: Modifier = Modifier) {
    val tier = MatchTier.fromScore(score)
    val color = matchTierColor(tier)
    Pill(
        text = "$score% Match",
        containerColor = color.copy(alpha = 0.14f),
        contentColor = color,
        modifier = modifier,
    )
}

/**
 * Madagascar eligibility. This is the single most decision-relevant signal in
 * the app, so it always gets an explicit label rather than an icon alone.
 */
@Composable
fun EligibilityBadge(eligibility: MadagascarEligibility, modifier: Modifier = Modifier) {
    val (label, icon, container, content) = when (eligibility) {
        MadagascarEligibility.ELIGIBLE -> EligibilityStyle(
            "Can apply from Madagascar", Icons.Filled.CheckCircle,
            extendedColors.successContainer, extendedColors.success,
        )
        MadagascarEligibility.VISA_SPONSORED -> EligibilityStyle(
            "Visa sponsorship available", Icons.Filled.VerifiedUser,
            extendedColors.infoContainer, extendedColors.info,
        )
        MadagascarEligibility.UNCLEAR -> EligibilityStyle(
            "Eligibility unclear — verify", Icons.Filled.HelpOutline,
            extendedColors.warningContainer, extendedColors.warning,
        )
        MadagascarEligibility.RESTRICTED -> EligibilityStyle(
            "Local applicants only", Icons.Filled.ErrorOutline,
            extendedColors.dangerContainer, extendedColors.danger,
        )
    }
    Pill(text = label, containerColor = container, contentColor = content, leadingIcon = icon, modifier = modifier)
}

private data class EligibilityStyle(
    val label: String,
    val icon: ImageVector,
    val container: Color,
    val content: Color,
)

@Composable
fun VisaBadge(support: VisaSupport, modifier: Modifier = Modifier) {
    val (label, icon, content) = when (support) {
        VisaSupport.FULL_SPONSORSHIP -> Triple("Full visa sponsorship", Icons.Filled.VerifiedUser, extendedColors.success)
        VisaSupport.RELOCATION_PACKAGE -> Triple("Relocation package", Icons.Filled.Public, extendedColors.success)
        VisaSupport.SPONSORSHIP_AVAILABLE -> Triple("Sponsorship available", Icons.Filled.Shield, extendedColors.info)
        VisaSupport.NOT_MENTIONED -> Triple("Visa support not stated", Icons.Filled.HelpOutline, extendedColors.warning)
        VisaSupport.LOCAL_ONLY -> Triple("No sponsorship", Icons.Filled.ErrorOutline, extendedColors.danger)
    }
    Pill(text = label, containerColor = content.copy(alpha = 0.13f), contentColor = content, leadingIcon = icon, modifier = modifier)
}

@Composable
fun ScamRiskBadge(risk: ScamRisk, modifier: Modifier = Modifier) {
    val (label, container, content) = when (risk) {
        ScamRisk.LOW -> Triple("Verified source", extendedColors.successContainer, extendedColors.success)
        ScamRisk.MEDIUM -> Triple("Check before applying", extendedColors.warningContainer, extendedColors.warning)
        ScamRisk.HIGH -> Triple("Scam warning", extendedColors.dangerContainer, extendedColors.danger)
    }
    Pill(text = label, containerColor = container, contentColor = content, leadingIcon = Icons.Filled.Gavel, modifier = modifier)
}

/**
 * The Expat Benefits Score, shown as its own chip so it can never be read as the CV
 * match. When the posting documents too few benefits to trust the number, the chip says
 * so instead of implying the employer offers little.
 */
@Composable
fun ExpatScoreBadge(
    score: Int,
    evidenceCoverage: Int,
    lowEvidence: Boolean,
    modifier: Modifier = Modifier,
) {
    val content = when {
        lowEvidence -> extendedColors.warning
        score >= 75 -> extendedColors.success
        score >= 50 -> extendedColors.info
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = if (lowEvidence) "Expat $score% · $evidenceCoverage% documented" else "Expat benefits $score%"
    Pill(
        text = label,
        containerColor = content.copy(alpha = 0.14f),
        contentColor = content,
        leadingIcon = Icons.Filled.CardGiftcard,
        modifier = modifier,
    )
}

/**
 * Application readiness for the Stage 11 shortlist. Kept visually distinct from
 * [MatchChip] and [ExpatScoreBadge] because it answers a different question: not "does my
 * CV fit?" but "can I apply, and what is in the way?".
 */
@Composable
fun ReadinessBadge(readiness: ApplicationReadiness, modifier: Modifier = Modifier) {
    val (container, content) = when (readiness) {
        ApplicationReadiness.PRIORITY -> extendedColors.successContainer to extendedColors.success
        ApplicationReadiness.READY -> extendedColors.infoContainer to extendedColors.info
        ApplicationReadiness.PREPARE_FIRST -> extendedColors.warningContainer to extendedColors.warning
        ApplicationReadiness.NEEDS_REVIEW -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        ApplicationReadiness.BLOCKED -> extendedColors.dangerContainer to extendedColors.danger
    }
    val icon = when (readiness) {
        ApplicationReadiness.PRIORITY -> Icons.Filled.WorkspacePremium
        ApplicationReadiness.READY -> Icons.Filled.CheckCircle
        ApplicationReadiness.PREPARE_FIRST -> Icons.Filled.Build
        ApplicationReadiness.NEEDS_REVIEW -> Icons.Filled.HelpOutline
        ApplicationReadiness.BLOCKED -> Icons.Filled.ErrorOutline
    }
    Pill(text = readiness.label, containerColor = container, contentColor = content, leadingIcon = icon, modifier = modifier)
}

/** Small status chip for one benefit line in the detail sheet. */
@Composable
fun BenefitStatusChip(status: BenefitStatus, modifier: Modifier = Modifier) {
    val (container, content) = when (status) {
        BenefitStatus.CONFIRMED -> extendedColors.successContainer to extendedColors.success
        BenefitStatus.NEEDS_VERIFICATION -> extendedColors.warningContainer to extendedColors.warning
        BenefitStatus.NOT_OFFERED -> extendedColors.dangerContainer to extendedColors.danger
        BenefitStatus.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Pill(text = status.label, containerColor = container, contentColor = content, modifier = modifier)
}

/** Thin labelled progress bar used for profile completion and AI analysis. */
@Composable
fun ProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    height: Dp = 8.dp,
) {
    val animated = remember { Animatable(0f) }
    LaunchedEffect(fraction) {
        animated.animateTo(fraction.coerceIn(0f, 1f), tween(700, easing = FastOutSlowInEasing))
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(trackColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated.value)
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(color),
        )
    }
}

@Composable
fun StatBlock(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = accent,
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
fun IconLabelRow(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
        )
    }
}

/** Icon + text row used for "posted 5h ago". */
@Composable
fun FreshnessLabel(text: String, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    IconLabelRow(icon = Icons.Filled.Schedule, text = text, modifier = modifier, tint = tint)
}
