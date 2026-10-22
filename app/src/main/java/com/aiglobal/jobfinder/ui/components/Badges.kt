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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
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
import com.aiglobal.jobfinder.data.model.AvailabilityResult
import com.aiglobal.jobfinder.data.model.AvailabilitySignalType
import com.aiglobal.jobfinder.data.model.AvailabilityStatus
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.InternationalFitAssessment
import com.aiglobal.jobfinder.data.model.InternationalFitBand
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.MatchTier
import com.aiglobal.jobfinder.data.model.OfferVerification
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.VerificationState
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

/** Color for an International Fit band, aligned with the existing success/info/warning scale. */
@Composable
fun internationalFitBandColor(band: InternationalFitBand): Color = when (band) {
    InternationalFitBand.EXCELLENT -> extendedColors.success
    InternationalFitBand.STRONG -> extendedColors.info
    InternationalFitBand.POSSIBLE -> extendedColors.warning
    InternationalFitBand.WEAK -> extendedColors.warning
    InternationalFitBand.INSUFFICIENT -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * International Fit, shown as its own chip. It answers a different question from the CV
 * match and the Expat Benefits Score: "is this worth my time from Madagascar, and where do
 * I stand?". The band is shown with the score, and [InternationalFitBand.INSUFFICIENT] is
 * shown as "not enough profile" rather than as a low number, so a thin profile is never
 * read as a bad match.
 */
@Composable
fun InternationalFitBadge(
    assessment: InternationalFitAssessment,
    modifier: Modifier = Modifier,
) {
    val band = assessment.band
    val content = internationalFitBandColor(band)
    val icon = when (band) {
        InternationalFitBand.EXCELLENT -> Icons.Filled.WorkspacePremium
        InternationalFitBand.STRONG -> Icons.Filled.Public
        InternationalFitBand.POSSIBLE -> Icons.Filled.HelpOutline
        InternationalFitBand.WEAK -> Icons.Filled.Schedule
        InternationalFitBand.INSUFFICIENT -> Icons.Filled.HelpOutline
    }
    val label = if (band == InternationalFitBand.INSUFFICIENT) {
        "International fit · not enough profile"
    } else {
        "International fit ${assessment.score}% · ${band.shortLabel}"
    }
    Pill(
        text = label,
        containerColor = content.copy(alpha = 0.14f),
        contentColor = content,
        leadingIcon = icon,
        modifier = modifier,
    )
}

/**
 * Color for one verification axis, on the existing success/info/warning/danger scale so
 * the verification read never introduces a new visual language. A negative state
 * (expired, inaccessible) is danger, a confirmed one is success, a possible one is info,
 * a stated refusal is warning, and "unknown" is deliberately neutral — not stated is not
 * the same as refused.
 */
@Composable
fun verificationStateColor(state: VerificationState): Color = when (state) {
    VerificationState.CONFIRMED -> extendedColors.success
    VerificationState.POSSIBLE -> extendedColors.info
    VerificationState.NOT_OFFERED -> extendedColors.warning
    VerificationState.EXPIRED, VerificationState.INACCESSIBLE -> extendedColors.danger
    VerificationState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Short label for a verification axis, kept distinct from the enum's own display label. */
private fun verificationShortLabel(state: VerificationState): String = when (state) {
    VerificationState.CONFIRMED -> "Verified"
    VerificationState.POSSIBLE -> "Possible"
    VerificationState.NOT_OFFERED -> "Not offered"
    VerificationState.EXPIRED -> "Closed"
    VerificationState.INACCESSIBLE -> "Link unusable"
    VerificationState.UNKNOWN -> "Not stated"
}

/**
 * The whole verification read as one scannable chip: whether the vacancy is confirmed open
 * and whether a visa route is documented. It answers "is this listing real and reachable?"
 * — a question distinct from the CV match, eligibility, expat and international-fit
 * numbers, so it is shown as its own chip and never blended into them.
 */
@Composable
fun OfferVerificationBadge(
    verification: OfferVerification,
    modifier: Modifier = Modifier,
) {
    val worst = listOf(verification.link, verification.activity).firstOrNull { it.isNegative }
    val content = worst?.let { verificationStateColor(it) } ?: verificationStateColor(verification.activity)
    val icon = when {
        verification.link.isNegative -> Icons.Filled.LinkOff
        verification.activity.isNegative -> Icons.Filled.EventBusy
        verification.activity == VerificationState.CONFIRMED -> Icons.Filled.Verified
        else -> Icons.AutoMirrored.Filled.HelpOutline
    }
    val label = when {
        verification.link == VerificationState.INACCESSIBLE -> "Link unusable"
        verification.activity == VerificationState.EXPIRED -> "Closed to applicants"
        verification.activity == VerificationState.CONFIRMED -> "Open · verified by source"
        else -> "Open status not stated"
    }
    val visaNote = when (verification.visa) {
        VerificationState.CONFIRMED -> " · visa verified"
        VerificationState.NOT_OFFERED -> " · no visa"
        else -> ""
    }
    Pill(
        text = label + visaNote,
        containerColor = content.copy(alpha = 0.14f),
        contentColor = content,
        leadingIcon = icon,
        modifier = modifier,
    )
}

/**
 * Colour for an availability verdict, on the shared success/info/warning/danger scale.
 * "Open" is success, "Expired" is danger, and "Unconfirmed" is deliberately neutral — the
 * absence of proof is never shown in the warning or danger colours, so a posting the app
 * could not reach is never visually accused of being closed.
 */
@Composable
fun availabilityStatusColor(status: AvailabilityStatus): Color = when (status) {
    AvailabilityStatus.OPEN -> extendedColors.success
    AvailabilityStatus.EXPIRED -> extendedColors.danger
    AvailabilityStatus.UNCONFIRMED -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * The availability verdict as one chip: "Open · verified", "Expired", or "Availability
 * unconfirmed". It is the single, unambiguous answer to "can I still apply?", kept separate
 * from the multi-axis [OfferVerificationBadge] so the two are never blended.
 *
 * Only a settled verdict gets a coloured chip. An unconfirmed read — the honest default
 * before any check, or after a network error — is rendered neutral, so it reads as "we do
 * not know yet" rather than as a rejection.
 */
@Composable
fun OfferAvailabilityBadge(
    availability: AvailabilityResult,
    modifier: Modifier = Modifier,
) {
    val content = availabilityStatusColor(availability.status)
    val icon = when (availability.status) {
        AvailabilityStatus.OPEN -> Icons.Filled.Verified
        AvailabilityStatus.EXPIRED -> Icons.Filled.EventBusy
        AvailabilityStatus.UNCONFIRMED -> Icons.AutoMirrored.Filled.HelpOutline
    }
    val label = when (availability.status) {
        AvailabilityStatus.OPEN -> when (availability.decisiveSignal) {
            AvailabilitySignalType.SOURCE_FLAG -> "Open · verified by source"
            AvailabilitySignalType.LINK_PROBE -> "Open · verified at source"
            else -> "Open · verified"
        }
        AvailabilityStatus.EXPIRED -> when (availability.decisiveSignal) {
            AvailabilitySignalType.EMPLOYER_DEADLINE -> "Expired · deadline passed"
            AvailabilitySignalType.SOURCE_FLAG -> "Expired · source reports closed"
            AvailabilitySignalType.LINK_PROBE -> "Expired · page confirms closed"
            else -> "Expired"
        }
        AvailabilityStatus.UNCONFIRMED -> "Availability unconfirmed"
    }
    Pill(
        text = label,
        containerColor = content.copy(alpha = if (availability.isUnconfirmed) 0.10f else 0.14f),
        contentColor = content,
        leadingIcon = icon,
        modifier = modifier,
    )
}

/**
 * The availability read as a detail section: the verdict, when it was checked, and each
 * reason behind it with its own source link. This is where the app shows its work, so a
 * candidate can see *why* a posting is called open, expired or unconfirmed — and can open
 * the source to check for themselves.
 */
@Composable
fun OfferAvailabilityDetail(
    availability: AvailabilityResult,
    modifier: Modifier = Modifier,
) {
    val content = availabilityStatusColor(availability.status)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when (availability.status) {
                    AvailabilityStatus.OPEN -> Icons.Filled.Verified
                    AvailabilityStatus.EXPIRED -> Icons.Filled.EventBusy
                    AvailabilityStatus.UNCONFIRMED -> Icons.AutoMirrored.Filled.HelpOutline
                },
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = when (availability.status) {
                    AvailabilityStatus.OPEN -> "Open — verified"
                    AvailabilityStatus.EXPIRED -> "Expired — confirmed closed"
                    AvailabilityStatus.UNCONFIRMED -> "Availability not confirmed"
                },
                style = MaterialTheme.typography.titleSmall,
                color = content,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            availability.lastCheckedAtEpochMillis?.let { checkedAt ->
                Text(
                    text = "Checked ${relativeCheckedLabel(checkedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (availability.evidence.isEmpty()) {
            Text(
                text = "This app has not confirmed whether the vacancy is still open. Open the posting to check.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            availability.evidence.forEach { fact ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = when (fact.type) {
                            AvailabilitySignalType.SOURCE_FLAG -> Icons.Filled.Verified
                            AvailabilitySignalType.EMPLOYER_DEADLINE -> Icons.Filled.Schedule
                            AvailabilitySignalType.LINK_PROBE -> Icons.Filled.Link
                            AvailabilitySignalType.NONE -> Icons.AutoMirrored.Filled.HelpOutline
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = fact.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** "just now", "3 h ago" or a date, for the last-checked stamp on the availability section. */
@Composable
private fun relativeCheckedLabel(epochMillis: Long): String {
    val now = remember(epochMillis) { System.currentTimeMillis() }
    val minutes = ((now - epochMillis).coerceAtLeast(0)) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} h ago"
        else -> "${minutes / (60 * 24)} d ago"
    }
}

/** Compact per-axis chip, e.g. "Visa · Verified", for the detail panel. */
@Composable
fun VerificationAxisChip(
    label: String,
    state: VerificationState,
    modifier: Modifier = Modifier,
) {
    val content = verificationStateColor(state)
    Pill(
        text = "$label · ${verificationShortLabel(state)}",
        containerColor = content.copy(alpha = 0.14f),
        contentColor = content,
        modifier = modifier,
    )
}
