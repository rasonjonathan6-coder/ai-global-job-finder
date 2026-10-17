package com.aiglobal.jobfinder.ui.screens.applications

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiglobal.jobfinder.data.model.ApplicationDraft
import com.aiglobal.jobfinder.data.model.DraftStatus
import com.aiglobal.jobfinder.ui.components.Pill
import com.aiglobal.jobfinder.ui.components.PrimaryButton
import com.aiglobal.jobfinder.ui.components.SecondaryButton
import com.aiglobal.jobfinder.ui.components.isDarkTheme
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.accentBrush
import com.aiglobal.jobfinder.ui.theme.extendedColors

/**
 * The approval-queue card: a prepared application the user must review before anything is
 * sent. It is deliberately explicit about two things — what will be submitted (CV summary,
 * cover letter, answers) and that nothing has been sent yet. The approve action is the only
 * way to unlock submission, and it approves the exact content shown.
 */
@Composable
fun DraftApprovalCard(
    draft: ApplicationDraft,
    onReview: () -> Unit,
    onApprove: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleConsent: (Boolean) -> Unit = {},
    onOpenOfficialForm: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    val dark = isDarkTheme()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(draft.id) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 6 },
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, extendedColors.cardStroke, RoundedCornerShape(22.dp)),
        ) {
            // Header band: an AI-prepared, needs-your-decision cue, kept calm and professional.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (dark) extendedColors.infoContainer.copy(alpha = 0.35f) else extendedColors.infoContainer)
                    .padding(horizontal = spacing.md, vertical = spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentBrush()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Spacer(Modifier.width(spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Ready for your review",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Prepared from your CV — nothing sent yet",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DraftStatusPill(draft.status)
            }

            Column(
                modifier = Modifier.padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = draft.jobTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${draft.countryFlag} ${draft.company} · ${draft.country}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Pill(
                        text = draft.channelLabel(),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        leadingIcon = Icons.Filled.Verified,
                    )
                    if (draft.coverLetterText.isNotBlank()) {
                        Pill(
                            text = "Cover letter",
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            leadingIcon = Icons.Filled.Description,
                        )
                    }
                    if (draft.answers.isNotEmpty()) {
                        Pill(
                            text = "${draft.answers.size} answer${if (draft.answers.size == 1) "" else "s"}",
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (draft.needsResubmission) {
                    DraftNotice(
                        text = draft.reason.ifBlank {
                            "Your changes need a fresh approval before this can be submitted."
                        },
                        container = extendedColors.warningContainer,
                        content = extendedColors.warning,
                    )
                }

                // Consent for the employer's own privacy policy is a mandatory part of a real
                // application, so it is collected here, next to the content it covers.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.PrivacyTip,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (draft.consentGiven) {
                            "You consented to the employer's privacy policy."
                        } else {
                            "Consent to the employer's privacy policy is required before a real submission."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.Switch(
                        checked = draft.consentGiven,
                        onCheckedChange = onToggleConsent,
                    )
                }

                var expanded by remember(draft.id) { mutableStateOf(false) }
                val chevronRotation by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = tween(180),
                    label = "chevron",
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { expanded = !expanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (expanded) "Hide what will be submitted" else "See what will be submitted",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(chevronRotation),
                    )
                }

                AnimatedVisibility(visible = expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        if (draft.cvSummary.isNotBlank()) {
                            DraftPreviewBlock(label = "CV summary", body = draft.cvSummary)
                        }
                        if (draft.coverLetterText.isNotBlank()) {
                            DraftPreviewBlock(label = "Cover letter", body = draft.coverLetterText)
                        }
                        draft.answers.forEach { answer ->
                            DraftPreviewBlock(label = answer.question, body = answer.answer)
                        }
                    }
                }

                if (draft.isApproved) {
                    DraftNotice(
                        text = "Approved. Submitting will send this exact content.",
                        container = extendedColors.successContainer,
                        content = extendedColors.success,
                    )
                } else {
                    Text(
                        text = draft.reason.ifBlank { "Review the content, then approve to unlock submitting." },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    PrimaryButton(
                        text = if (draft.needsResubmission) "Re-approve" else "Approve",
                        onClick = onApprove,
                        modifier = Modifier.weight(1f),
                        leadingIcon = Icons.Filled.CheckCircle,
                    )
                    SecondaryButton(
                        text = "Review",
                        onClick = onReview,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "Skip",
                        onClick = onSkip,
                    )
                }

                // The app cannot submit to this employer's system, so the honest next step is
                // the employer's own official form. This opens that page; it never claims a send.
                if (draft.sourceUrl.isNotBlank()) {
                    SecondaryButton(
                        text = "Open official form",
                        onClick = onOpenOfficialForm,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                    )
                }
            }
        }
    }
}

@Composable
private fun DraftPreviewBlock(label: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DraftNotice(text: String, container: Color, content: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = content,
        )
    }
}

@Composable
private fun DraftStatusPill(status: DraftStatus) {
    val (container, content) = when (status) {
        DraftStatus.NEEDS_RESUBMISSION -> extendedColors.warningContainer to extendedColors.warning
        DraftStatus.APPROVED, DraftStatus.SUBMISSION_PENDING -> extendedColors.successContainer to extendedColors.success
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Pill(text = status.label, containerColor = container, contentColor = content)
}

private fun ApplicationDraft.channelLabel(): String = when (channelKey) {
    "ATS_API" -> "Employer API"
    "EMPLOYER_PORTAL" -> "Employer portal"
    "EMAIL" -> "Email"
    else -> "Verify route"
}
