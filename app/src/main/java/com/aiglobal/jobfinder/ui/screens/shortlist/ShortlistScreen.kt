package com.aiglobal.jobfinder.ui.screens.shortlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiglobal.jobfinder.data.model.ApplicationReadiness
import com.aiglobal.jobfinder.data.model.BlockerKind
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.RankComponent
import com.aiglobal.jobfinder.data.model.ReadinessBlocker
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ShortlistEntry
import com.aiglobal.jobfinder.data.model.ShortlistState
import com.aiglobal.jobfinder.ui.components.AppCard
import com.aiglobal.jobfinder.ui.components.EmptyState
import com.aiglobal.jobfinder.ui.components.ExpatScoreBadge
import com.aiglobal.jobfinder.ui.components.IconChipButton
import com.aiglobal.jobfinder.ui.components.MatchChip
import com.aiglobal.jobfinder.ui.components.Pill
import com.aiglobal.jobfinder.ui.components.PrimaryButton
import com.aiglobal.jobfinder.ui.components.ReadinessBadge
import com.aiglobal.jobfinder.ui.components.SecondaryButton
import com.aiglobal.jobfinder.ui.components.SectionHeader
import com.aiglobal.jobfinder.ui.components.SkeletonBox
import com.aiglobal.jobfinder.ui.components.isDarkTheme
import com.aiglobal.jobfinder.ui.screens.jobs.ApplyLinkSheet
import com.aiglobal.jobfinder.ui.screens.jobs.applicationUrlInspector
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors
import com.aiglobal.jobfinder.viewmodel.ShortlistViewModel

/**
 * Stage 11: the expat shortlist — the postings a candidate in Madagascar can realistically
 * apply for, ranked by how ready they are to act on rather than by CV fit alone.
 */
@Composable
fun ShortlistScreen(
    viewModel: ShortlistViewModel,
    onBack: () -> Unit,
    onOpenJob: (String) -> Unit = {},
    onOpenJobs: () -> Unit = {},
    onOpenLink: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ShortlistContent(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onOpenJob = onOpenJob,
        onOpenJobs = onOpenJobs,
        onOpenLink = onOpenLink,
    )
}

/** Stateless body so layout can be verified without a live feed. */
@Composable
internal fun ShortlistContent(
    state: ShortlistState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenJob: (String) -> Unit = {},
    onOpenJobs: () -> Unit = {},
    onOpenLink: (String) -> Unit = {},
) {
    val spacing = LocalSpacing.current
    var explainEntry by remember { mutableStateOf<ShortlistEntry?>(null) }
    var applyJob by remember { mutableStateOf<Job?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ShortlistHeader(state = state, onBack = onBack, onRefresh = onRefresh)

        when {
            state.isLoading && state.isEmpty -> ShortlistSkeleton()
            state.isEmpty -> EmptyState(
                emoji = "🧭",
                title = "No shortlist yet",
                message = "Nothing in your feed is open to applicants in Madagascar right now. " +
                    "Refresh to pull the latest postings, or widen your filters.",
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
                item { ShortlistSummary(state = state, onOpenJobs = onOpenJobs) }

                if (state.recurringProfileGaps.isNotEmpty()) {
                    item { ProfileGapCard(gaps = state.recurringProfileGaps) }
                }

                item {
                    SectionHeader(
                        title = "Ready to act on",
                        subtitle = "${state.actionableCount} of ${state.entries.size} postings are open to you now",
                    )
                }

                itemsIndexed(state.entries, key = { _, entry -> entry.job.id }) { index, entry ->
                    ShortlistEntryCard(
                        entry = entry,
                        index = index,
                        onOpenJob = { onOpenJob(entry.job.id) },
                        onApply = { applyJob = entry.job },
                        onExplain = { explainEntry = entry },
                    )
                }

                item { SourceFootnote(note = state.sourceNote) }
            }
        }
    }

    explainEntry?.let { entry ->
        ReadinessExplanationSheet(entry = entry, onDismiss = { explainEntry = null })
    }

    applyJob?.let { job ->
        ApplyLinkSheet(
            job = job,
            inspection = applicationUrlInspector.inspect(job),
            onDismiss = { applyJob = null },
            onOpen = onOpenLink,
        )
    }
}

@Composable
private fun ShortlistHeader(state: ShortlistState, onBack: () -> Unit, onRefresh: () -> Unit) {
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
                text = "Expat shortlist",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Roles open to applicants in Madagascar",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconChipButton(
            icon = Icons.Filled.Refresh,
            onClick = onRefresh,
            contentDescription = "Refresh shortlist",
        )
    }
}

/** Hero summary: the one number that matters, then the breakdown behind it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShortlistSummary(state: ShortlistState, onOpenJobs: () -> Unit) {
    val spacing = LocalSpacing.current
    val dark = isDarkTheme()
    val headline = when {
        state.priorityCount > 0 -> "${state.priorityCount} top-priority role${if (state.priorityCount == 1) "" else "s"} for you"
        state.readyCount > 0 -> "${state.readyCount} role${if (state.readyCount == 1) "" else "s"} ready to apply for"
        else -> "Build up your shortlist"
    }
    val sub = if (state.totalJobs > 0) {
        "Screened ${state.totalJobs} postings · ${state.officialRouteCount} with a stated route in"
    } else {
        "Refresh to screen the latest postings"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    if (dark) listOf(Color(0xFF152C5C), Color(0xFF0A1730))
                    else listOf(Color(0xFF15356F), Color(0xFF0A1D40)),
                )
            )
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                )
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            SummaryStat("Priority", state.priorityCount, Color(0xFF6EE7B7))
            SummaryStat("Ready", state.readyCount, Color(0xFF93C5FD))
            SummaryStat("Verify", state.verifyCount, Color(0xFFFCD34D))
            SummaryStat("Official route", state.officialRouteCount, Color.White)
        }

        SecondaryButton(
            text = "Browse all matches",
            onClick = onOpenJobs,
            leadingIcon = Icons.Filled.Tune,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SummaryStat(label: String, value: Int, accent: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = accent,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.82f),
        )
    }
}

/**
 * The gaps that recur across postings. Fixing one of these improves every match at once,
 * so it is worth surfacing above the list.
 */
@Composable
private fun ProfileGapCard(gaps: List<ReadinessBlocker>) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(extendedColors.warning.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = extendedColors.warning,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Fix once, help every match",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "These gaps hold back several of your shortlisted roles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(spacing.sm))
        gaps.take(3).forEach { gap ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = extendedColors.warning,
                    modifier = Modifier.size(15.dp).padding(top = 2.dp),
                )
                Spacer(Modifier.width(spacing.xs))
                Text(
                    text = gap.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShortlistEntryCard(
    entry: ShortlistEntry,
    index: Int,
    onOpenJob: () -> Unit,
    onApply: () -> Unit,
    onExplain: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val job = entry.job
    val visible = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible.value = true }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, tween(140), label = "entryPress")

    AnimatedVisibility(
        visible = visible.value,
        enter = fadeIn(tween(320, delayMillis = index * 60)) +
            slideInVertically(tween(380, delayMillis = index * 60)) { it / 6 },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .scale(scale)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(interactionSource = interaction, indication = null, onClick = onExplain)
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = job.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = job.company,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${job.countryFlag} ${job.country}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(spacing.xs))
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(spacing.xs))
                        Text(
                            text = salaryLabel(job),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
                Spacer(Modifier.width(spacing.sm))
                ReadinessScoreRing(score = entry.readinessScore, readiness = entry.readiness)
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                ReadinessBadge(entry.readiness)
                MatchChip(entry.matchScore)
                if (entry.expatScore > 0) {
                    ExpatScoreBadge(
                        score = entry.expatScore,
                        evidenceCoverage = entry.expatEvidenceCoverage,
                        lowEvidence = entry.expatEvidenceCoverage < 40,
                    )
                }
                if (entry.hasOfficialRoute) {
                    Pill(
                        text = "Stated route in",
                        containerColor = extendedColors.successContainer,
                        contentColor = extendedColors.success,
                        leadingIcon = Icons.Filled.Shield,
                    )
                }
            }

            entry.highlights.take(3).forEach { highlight ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = extendedColors.success,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = highlight,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (entry.blockers.isEmpty()) Icons.Filled.Check else Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = if (entry.blockers.isEmpty()) extendedColors.success else extendedColors.warning,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = entry.blockerSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                SecondaryButton(
                    text = "View job",
                    onClick = onOpenJob,
                    modifier = Modifier.weight(1f),
                    height = 46.dp,
                )
                PrimaryButton(
                    text = "Apply",
                    onClick = onApply,
                    modifier = Modifier.weight(1f),
                    leadingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                    height = 46.dp,
                )
            }
        }
    }
}

/** Compact gauge for the readiness score, tinted by band. */
@Composable
private fun ReadinessScoreRing(score: Int, readiness: ApplicationReadiness) {
    val color = when (readiness) {
        ApplicationReadiness.PRIORITY -> extendedColors.success
        ApplicationReadiness.READY -> extendedColors.info
        ApplicationReadiness.PREPARE_FIRST -> extendedColors.warning
        ApplicationReadiness.NEEDS_REVIEW -> MaterialTheme.colorScheme.onSurfaceVariant
        ApplicationReadiness.BLOCKED -> extendedColors.danger
    }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(score) { progress.animateTo(score / 100f, tween(700)) }
    Box(modifier = Modifier.size(54.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { progress.value },
            modifier = Modifier.size(54.dp),
            color = color,
            trackColor = color.copy(alpha = 0.16f),
            strokeWidth = 5.dp,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$score",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "ready",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadinessExplanationSheet(entry: ShortlistEntry, onDismiss: () -> Unit) {
    val spacing = LocalSpacing.current
    val job = entry.job
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.gutter)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = job.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${job.company} · ${job.countryFlag} ${job.country}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                ReadinessBadge(entry.readiness)
                MatchChip(entry.matchScore)
            }

            Text(
                text = "Why this rank",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = spacing.xs),
            )

            if (entry.highlights.isEmpty()) {
                Text(
                    text = "Nothing in the posting stood out as a strong positive signal yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                entry.highlights.forEach { highlight ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = extendedColors.success,
                            modifier = Modifier.size(16.dp).padding(top = 2.dp),
                        )
                        Spacer(Modifier.width(spacing.xs))
                        Text(
                            text = highlight,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            if (entry.rankComponents.isNotEmpty()) {
                Text(
                    text = "How this ranks",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = spacing.xs),
                )
                Text(
                    text = "Readiness ${entry.readinessScore}% is these four ingredients added up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry.rankComponents.forEach { component -> RankComponentRow(component) }
            }

            if (entry.blockers.isNotEmpty()) {
                Text(
                    text = "What is in the way",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = spacing.xs),
                )
                entry.orderedBlockers.forEach { blocker -> BlockerRow(blocker) }
            }

            Text(
                text = "Readiness blends the employer's stated route in, your CV match, the " +
                    "documented benefits and how complete your profile is. It is a guide, not a decision.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs),
            )

            PrimaryButton(
                text = "Open job",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.Filled.Public,
            )
        }
    }
}

@Composable
private fun BlockerRow(blocker: ReadinessBlocker) {
    val spacing = LocalSpacing.current
    val (tone, kindLabel) = when (blocker.kind) {
        BlockerKind.OFFICIAL -> extendedColors.danger to "From the posting"
        BlockerKind.HEURISTIC -> extendedColors.warning to "App assessment"
        BlockerKind.PROFILE -> extendedColors.info to "Your profile"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tone.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = tone,
                modifier = Modifier.size(15.dp),
            )
        }
        Spacer(Modifier.width(spacing.xs))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = blocker.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = blocker.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = kindLabel,
                style = MaterialTheme.typography.labelSmall,
                color = tone,
            )
        }
    }
}

@Composable
private fun RankComponentRow(component: RankComponent) {
    val spacing = LocalSpacing.current
    val earnedShare = if (component.maxPoints == 0) 0f else component.points.toFloat() / component.maxPoints
    val tone = when {
        earnedShare >= 0.75f -> extendedColors.success
        earnedShare >= 0.4f -> extendedColors.info
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Pill(
            text = "${component.points}/${component.maxPoints}",
            containerColor = tone.copy(alpha = 0.14f),
            contentColor = tone,
        )
        Spacer(Modifier.width(spacing.xs))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = component.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = component.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceFootnote(note: String) {
    if (note.isBlank()) return
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.xs, bottom = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Public,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = note,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShortlistSkeleton() {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.padding(horizontal = spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        SkeletonBox(modifier = Modifier.fillMaxWidth().height(150.dp), shape = RoundedCornerShape(24.dp))
        repeat(3) {
            SkeletonBox(modifier = Modifier.fillMaxWidth().height(196.dp), shape = RoundedCornerShape(22.dp))
        }
    }
}

private fun salaryLabel(job: Job): String {
    if (job.salaryMin <= 0) return "Salary not stated"
    val period = when (job.salaryPeriod) {
        SalaryPeriod.YEAR -> "/yr"
        SalaryPeriod.MONTH -> "/mo"
        SalaryPeriod.DAY -> "/day"
        SalaryPeriod.HOUR -> "/hr"
    }
    val amount = if (job.salaryMax > job.salaryMin) {
        "${job.salaryMin}–${job.salaryMax}"
    } else {
        job.salaryMin.toString()
    }
    return "${job.currencyCode} $amount$period"
}
