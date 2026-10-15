package com.aiglobal.jobfinder.ui.screens.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiglobal.jobfinder.data.model.ApplicationPlan
import com.aiglobal.jobfinder.data.model.CvTailoring
import com.aiglobal.jobfinder.data.model.DocumentRequirement
import com.aiglobal.jobfinder.data.model.PlanStep
import com.aiglobal.jobfinder.data.model.PlanStepStatus
import com.aiglobal.jobfinder.data.model.ScamVerdict
import com.aiglobal.jobfinder.ui.components.AppCard
import com.aiglobal.jobfinder.ui.components.EmptyState
import com.aiglobal.jobfinder.ui.components.IconChipButton
import com.aiglobal.jobfinder.ui.components.Pill
import com.aiglobal.jobfinder.ui.components.PrimaryButton
import com.aiglobal.jobfinder.ui.components.ProgressBar
import com.aiglobal.jobfinder.ui.components.ReadinessBadge
import com.aiglobal.jobfinder.ui.components.SecondaryButton
import com.aiglobal.jobfinder.ui.components.SectionHeader
import com.aiglobal.jobfinder.ui.components.SkeletonBox
import com.aiglobal.jobfinder.ui.components.isDarkTheme
import com.aiglobal.jobfinder.ui.screens.jobs.ApplyLinkSheet
import com.aiglobal.jobfinder.ui.screens.jobs.applicationUrlInspector
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors
import com.aiglobal.jobfinder.viewmodel.ApplicationPlanUiState
import com.aiglobal.jobfinder.viewmodel.ApplicationPlanViewModel

/**
 * Stage 21: the per-posting Application Assistant. One screen that answers "what do I do to
 * apply for this job, and am I ready?" by unifying the route in from Madagascar, the CV
 * tailoring, the cover letter, the scam check and the interview rehearsal into one ordered,
 * evidence-backed plan.
 */
@Composable
fun ApplicationAssistantScreen(
    viewModel: ApplicationPlanViewModel,
    onBack: () -> Unit,
    onOpenCoverLetter: (String) -> Unit = {},
    onOpenInterview: (String) -> Unit = {},
    onOpenScam: (String) -> Unit = {},
    onOpenExpat: (String) -> Unit = {},
    onOpenLink: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ApplicationAssistantContent(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onOpenCoverLetter = onOpenCoverLetter,
        onOpenInterview = onOpenInterview,
        onOpenScam = onOpenScam,
        onOpenExpat = onOpenExpat,
        onOpenLink = onOpenLink,
    )
}

/** Stateless body so the layout can be verified without a live feed. */
@Composable
internal fun ApplicationAssistantContent(
    state: ApplicationPlanUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenCoverLetter: (String) -> Unit = {},
    onOpenInterview: (String) -> Unit = {},
    onOpenScam: (String) -> Unit = {},
    onOpenExpat: (String) -> Unit = {},
    onOpenLink: (String) -> Unit = {},
) {
    val spacing = LocalSpacing.current
    var showApplySheet by remember { mutableStateOf(false) }
    val plan = state.plan
    val job = state.job

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AssistantHeader(onBack = onBack, onRefresh = onRefresh)

        when {
            state.isLoading && plan == null -> AssistantSkeleton()
            plan == null -> EmptyState(
                emoji = "🧭",
                title = "Posting unavailable",
                message = state.message
                    ?: "This posting is no longer in your feed. Refresh the Jobs tab to reload it.",
                action = {
                    SecondaryButton(text = "Refresh", onClick = onRefresh, leadingIcon = Icons.Filled.Refresh)
                },
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = spacing.gutter,
                    end = spacing.gutter,
                    top = spacing.xs,
                    bottom = spacing.navBarClearance,
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                item { HeroCard(plan = plan) }
                item { NextActionCard(plan = plan) }
                item {
                    SectionHeader(
                        title = "Your application checklist",
                        subtitle = "${plan.doneCount} of ${plan.totalCount} steps done",
                    )
                }
                itemsIndexed(plan.steps, key = { _, step -> step.kind.name }) { index, step ->
                    StepCard(step = step, index = index)
                }
                item { TailoringCard(tailoring = plan.tailoring) }
                item { DocumentsCard(documents = plan.documents) }
                item { RouteCard(plan = plan) }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        PrimaryButton(
                            text = if (plan.hasCoverLetter) "Open the cover letter" else "Write a cover letter",
                            onClick = { onOpenCoverLetter(plan.jobId) },
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = Icons.Filled.Description,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                            SecondaryButton(
                                text = "Interview coach",
                                onClick = { onOpenInterview(plan.jobId) },
                                modifier = Modifier.weight(1f),
                                leadingIcon = Icons.Filled.Mic,
                            )
                            SecondaryButton(
                                text = "Scam check",
                                onClick = { onOpenScam(plan.jobId) },
                                modifier = Modifier.weight(1f),
                                leadingIcon = Icons.Filled.Gavel,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                            SecondaryButton(
                                text = "Country guide",
                                onClick = { onOpenExpat(plan.country) },
                                modifier = Modifier.weight(1f),
                                leadingIcon = Icons.Filled.Public,
                            )
                            SecondaryButton(
                                text = "Apply",
                                onClick = { showApplySheet = true },
                                modifier = Modifier.weight(1f),
                                leadingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showApplySheet && job != null) {
        ApplyLinkSheet(
            job = job,
            inspection = applicationUrlInspector.inspect(job),
            onDismiss = { showApplySheet = false },
            onOpen = { url ->
                showApplySheet = false
                onOpenLink(url)
            },
        )
    }
}

@Composable
private fun AssistantHeader(onBack: () -> Unit, onRefresh: () -> Unit) {
    val spacing = LocalSpacing.current
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
                text = "Application assistant",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Your route in and your next steps",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconChipButton(
            icon = Icons.Filled.Refresh,
            onClick = onRefresh,
            contentDescription = "Refresh plan",
        )
    }
}

@Composable
private fun HeroCard(plan: ApplicationPlan) {
    val spacing = LocalSpacing.current
    val dark = isDarkTheme()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    if (dark) listOf(Color(0xFF152C5C), Color(0xFF0A1730))
                    else listOf(Color(0xFF15356F), Color(0xFF0A1D40)),
                ),
            )
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = plan.countryFlag, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = plan.jobTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${plan.company} · ${plan.country}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = "${plan.readinessScore}%",
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Column {
                Text(
                    text = "readiness to apply",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.75f),
                )
                Text(
                    text = "${plan.readiness.label} · ${plan.matchScore}% CV match",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
        }

        ProgressBar(
            fraction = plan.progressPercent / 100f,
            color = MaterialTheme.colorScheme.secondary,
            trackColor = Color.White.copy(alpha = 0.18f),
            height = 8.dp,
        )
    }
}

@Composable
private fun NextActionCard(plan: ApplicationPlan) {
    val spacing = LocalSpacing.current
    val blocked = plan.blockers.isNotEmpty()
    val accent = if (blocked) extendedColors.danger else MaterialTheme.colorScheme.primary
    AppCard {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (blocked) Icons.Filled.WarningAmber else Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(spacing.sm))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (blocked) "Clear this first" else "Next best action",
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = plan.nextAction,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun StepCard(step: PlanStep, index: Int) {
    val spacing = LocalSpacing.current
    val visible = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible.value = true }

    val accent = when (step.status) {
        PlanStepStatus.DONE -> extendedColors.success
        PlanStepStatus.BLOCKED -> extendedColors.danger
        PlanStepStatus.TO_DO -> MaterialTheme.colorScheme.primary
        PlanStepStatus.NOT_NEEDED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    AnimatedVisibility(
        visible = visible.value,
        enter = fadeIn(tween(300, delayMillis = index * 40)) +
            slideInVertically(tween(340, delayMillis = index * 40)) { it / 8 },
    ) {
        AppCard(contentPadding = spacing.md) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (step.isDone) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(17.dp),
                        )
                    } else {
                        Text(text = step.kind.emoji, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.width(spacing.sm))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = step.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (step.weight > 0) {
                            Text(
                                text = "-${step.weight} pts",
                                style = MaterialTheme.typography.labelSmall,
                                color = accent,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Text(
                        text = step.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (step.action.isNotBlank()) {
                        Text(
                            text = step.action,
                            style = MaterialTheme.typography.bodySmall,
                            color = accent,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TailoringCard(tailoring: CvTailoring) {
    val spacing = LocalSpacing.current
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(spacing.xs))
                Text(
                    text = "CV tailoring for this posting",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = tailoring.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (tailoring.leadSkills.isNotEmpty()) {
                Text(
                    text = "Lead with these",
                    style = MaterialTheme.typography.labelMedium,
                    color = extendedColors.success,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xxs),
                    verticalArrangement = Arrangement.spacedBy(spacing.xxs),
                ) {
                    tailoring.leadSkills.forEach { highlight ->
                        Pill(
                            text = highlight.skill,
                            containerColor = extendedColors.successContainer,
                            contentColor = extendedColors.onSuccessContainer,
                        )
                    }
                }
            }

            if (tailoring.addressSkills.isNotEmpty()) {
                Text(
                    text = "Address or confirm these",
                    style = MaterialTheme.typography.labelMedium,
                    color = extendedColors.warning,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xxs),
                    verticalArrangement = Arrangement.spacedBy(spacing.xxs),
                ) {
                    tailoring.addressSkills.forEach { skill ->
                        Pill(
                            text = skill,
                            containerColor = extendedColors.warningContainer,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentsCard(documents: List<DocumentRequirement>) {
    val spacing = LocalSpacing.current
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                text = "Documents to have ready",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            documents.forEach { document ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .padding(top = 5.dp)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                if (document.required) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                            ),
                    )
                    Spacer(Modifier.width(spacing.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = document.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = document.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteCard(plan: ApplicationPlan) {
    val spacing = LocalSpacing.current
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(spacing.xs))
                Text(
                    text = "Route in from Madagascar",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                ReadinessBadge(readiness = plan.readiness)
            }
            Text(
                text = plan.routeSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            plan.scamVerdict?.let { verdict -> ScamLine(verdict = verdict) }
        }
    }
}

@Composable
private fun ScamLine(verdict: ScamVerdict) {
    val accent = when (verdict) {
        ScamVerdict.LIKELY_SAFE -> extendedColors.success
        ScamVerdict.NEEDS_CAUTION -> extendedColors.warning
        ScamVerdict.HIGH_RISK -> extendedColors.danger
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = verdict.emoji, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Scam shield: ${verdict.label}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun AssistantSkeleton() {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = spacing.gutter, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        SkeletonBox(modifier = Modifier.fillMaxWidth().height(150.dp), shape = RoundedCornerShape(24.dp))
        SkeletonBox(modifier = Modifier.fillMaxWidth().height(84.dp), shape = RoundedCornerShape(22.dp))
        SkeletonBox(modifier = Modifier.fillMaxWidth().height(72.dp), shape = RoundedCornerShape(22.dp))
        SkeletonBox(modifier = Modifier.fillMaxWidth().height(72.dp), shape = RoundedCornerShape(22.dp))
    }
}
