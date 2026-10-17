package com.aiglobal.jobfinder.ui.screens.ranking

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.aiglobal.jobfinder.data.model.ExpatRankedJob
import com.aiglobal.jobfinder.data.model.ExpatRankingState
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.RankingLabel
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.ui.components.EmptyState
import com.aiglobal.jobfinder.ui.components.IconChipButton
import com.aiglobal.jobfinder.ui.components.MatchChip
import com.aiglobal.jobfinder.ui.components.Pill
import com.aiglobal.jobfinder.ui.components.ProgressBar
import com.aiglobal.jobfinder.ui.components.ReadinessBadge
import com.aiglobal.jobfinder.ui.components.SecondaryButton
import com.aiglobal.jobfinder.ui.components.SectionHeader
import com.aiglobal.jobfinder.ui.components.SkeletonBox
import com.aiglobal.jobfinder.ui.components.isDarkTheme
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors
import com.aiglobal.jobfinder.viewmodel.ExpatRankingViewModel

/**
 * Stage 48: the expatriate ranking — the shortlist blended with each posting's documented
 * expatriate package, ranked strongest first.
 *
 * The screen never re-derives anything. It shows the two ingredients of every rank (how
 * ready the posting is to apply for, and how good its documented package is) beside the
 * total, and it surfaces the same cautions the engines do, so a strong number can never
 * hide an unresolved eligibility question or a scam signal.
 */
@Composable
fun ExpatRankingScreen(
    viewModel: ExpatRankingViewModel,
    onBack: () -> Unit,
    onOpenJob: (String) -> Unit = {},
    onOpenJobs: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ExpatRankingContent(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onOpenJob = onOpenJob,
        onOpenJobs = onOpenJobs,
    )
}

/** Stateless body so layout can be verified without a live feed. */
@Composable
internal fun ExpatRankingContent(
    state: ExpatRankingState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenJob: (String) -> Unit = {},
    onOpenJobs: () -> Unit = {},
) {
    val spacing = LocalSpacing.current

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        RankingHeader(onBack = onBack, onRefresh = onRefresh)

        when {
            state.isLoading && state.ranked.isEmpty() -> RankingSkeleton()
            state.ranked.isEmpty() -> EmptyState(
                emoji = "🏆",
                title = "No ranking yet",
                message = "Import and analyse your CV so the AI can rank the postings you can " +
                    "realistically apply for from Madagascar. Refresh to pull the latest.",
                action = {
                    SecondaryButton(
                        text = "Refresh",
                        onClick = onRefresh,
                        leadingIcon = Icons.Filled.Refresh,
                    )
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
                item { RankingHero(state = state, onOpenJobs = onOpenJobs) }

                item {
                    SectionHeader(
                        title = "Ranked for you",
                        subtitle = "Readiness and the documented expatriate package, combined",
                    )
                }

                itemsIndexed(state.ranked, key = { _, item -> item.job.id }) { index, item ->
                    RankedJobCard(
                        item = item,
                        index = index,
                        onOpenJob = { onOpenJob(item.job.id) },
                    )
                }

                item { RankingFootnote() }
            }
        }
    }
}

@Composable
private fun RankingHeader(onBack: () -> Unit, onRefresh: () -> Unit) {
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
                text = "Expat ranking",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Your strongest opportunities, explained",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconChipButton(
            icon = Icons.Filled.Refresh,
            onClick = onRefresh,
            contentDescription = "Refresh ranking",
        )
    }
}

/** Hero summary: the headline count, then the breakdown that produced it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RankingHero(state: ExpatRankingState, onOpenJobs: () -> Unit) {
    val spacing = LocalSpacing.current
    val dark = isDarkTheme()
    val top = state.ranked.firstOrNull()
    val headline = when {
        state.actionableCount > 0 ->
            "${state.actionableCount} role${if (state.actionableCount == 1) "" else "s"} ready to act on"
        top != null -> "Building up your ranking"
        else -> "Nothing ranked yet"
    }
    val sub = if (state.totalJobs > 0) {
        "Ranked ${state.ranked.size} of ${state.totalJobs} screened postings"
    } else {
        "Refresh to rank the latest postings"
    }

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
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.WorkspacePremium,
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
            HeroStat("Open to you", state.actionableCount, Color(0xFF6EE7B7))
            HeroStat("Top score", top?.finalScore ?: 0, Color(0xFF93C5FD))
            HeroStat("Capped", state.disqualifiedCount, Color(0xFFFCA5A5))
        }

        SecondaryButton(
            text = "Browse all matches",
            onClick = onOpenJobs,
            leadingIcon = Icons.Filled.Public,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HeroStat(label: String, value: Int, accent: Color) {
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
 * One ranked posting. The rank number, the combined score and the two ingredients that
 * produced it are all visible, so the position is never a black box.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RankedJobCard(item: ExpatRankedJob, index: Int, onOpenJob: () -> Unit) {
    val spacing = LocalSpacing.current
    val job = item.job
    val visible = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible.value = true }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, tween(140), label = "rankPress")

    AnimatedVisibility(
        visible = visible.value,
        enter = fadeIn(tween(320, delayMillis = index * 55)) +
            slideInVertically(tween(380, delayMillis = index * 55)) { it / 6 },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .scale(scale)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(interactionSource = interaction, indication = null, onClick = onOpenJob)
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                RankMedal(rank = item.rank)
                Spacer(Modifier.width(spacing.sm))
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
                    Text(
                        text = "${job.countryFlag} ${job.country} · ${salaryLabel(job)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(spacing.sm))
                FinalScoreRing(score = item.finalScore, readiness = item.readiness)
            }

            if (item.labels.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    item.labels.forEach { label -> RankingLabelPill(label) }
                }
            }

            // The two ingredients, kept beside the total so the rank can always be traced.
            BreakdownRow(
                label = "Readiness",
                points = item.readinessScore,
                tone = extendedColors.info,
            )
            BreakdownRow(
                label = "Expat package",
                points = item.opportunity.score,
                tone = extendedColors.success,
            )

            if (item.reasons.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    item.reasons.take(3).forEach { reason ->
                        Row(verticalAlignment = Alignment.Top) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                            Spacer(Modifier.width(spacing.xs))
                            Text(
                                text = reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                ReadinessBadge(item.readiness)
                MatchChip(item.job.matchScore)
                Pill(
                    text = item.opportunity.band.shortLabel,
                    containerColor = extendedColors.infoContainer,
                    contentColor = extendedColors.info,
                    leadingIcon = Icons.Filled.CardGiftcard,
                )
            }

            SecondaryButton(
                text = "View job",
                onClick = onOpenJob,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                height = 46.dp,
            )
        }
    }
}

/** The 1-based rank, drawn as a medal so the order is unmistakable at a glance. */
@Composable
private fun RankMedal(rank: Int) {
    val container = when (rank) {
        1 -> extendedColors.warning.copy(alpha = 0.18f)
        2 -> extendedColors.info.copy(alpha = 0.16f)
        3 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = when (rank) {
        1 -> extendedColors.warning
        2 -> extendedColors.info
        3 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "#$rank",
            style = MaterialTheme.typography.titleSmall,
            color = content,
            fontWeight = if (rank <= 3) FontWeight.Bold else FontWeight.SemiBold,
        )
    }
}

/** Animated combined-score gauge, tinted by the readiness band so it never overstates. */
@Composable
private fun FinalScoreRing(score: Int, readiness: ApplicationReadiness) {
    val color = readinessTone(readiness)
    val progress = remember { Animatable(0f) }
    LaunchedEffect(score) {
        progress.animateTo(score / 100f, tween(800, easing = FastOutSlowInEasing))
    }
    Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { progress.value },
            modifier = Modifier.size(56.dp),
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
                text = "score",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One ingredient of the rank, shown as a labelled mini bar. */
@Composable
private fun BreakdownRow(label: String, points: Int, tone: Color) {
    val spacing = LocalSpacing.current
    val fraction = (points.toFloat() / 100f).coerceIn(0f, 1f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp),
        )
        Spacer(Modifier.width(spacing.xs))
        ProgressBar(fraction = fraction, color = tone, height = 6.dp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(spacing.xs))
        Text(
            text = "$points",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(28.dp),
        )
    }
}

/** A ranking label, coloured by whether it is a selling point or a caution. */
@Composable
private fun RankingLabelPill(label: RankingLabel) {
    val tone = if (label.positive) extendedColors.success else extendedColors.warning
    Pill(
        text = "${label.emoji} ${label.label}",
        containerColor = tone.copy(alpha = 0.14f),
        contentColor = tone,
    )
}

@Composable
private fun RankingFootnote() {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.xs, bottom = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.TrendingUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Rank combines how ready a posting is to apply for with how good its " +
                "documented expatriate package is. A blocked or disqualified posting is " +
                "capped, so a strong package can never outrank a stop sign.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RankingSkeleton() {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.padding(horizontal = spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        SkeletonBox(modifier = Modifier.fillMaxWidth().height(150.dp), shape = RoundedCornerShape(24.dp))
        repeat(3) {
            SkeletonBox(modifier = Modifier.fillMaxWidth().height(220.dp), shape = RoundedCornerShape(22.dp))
        }
    }
}

@Composable
private fun readinessTone(readiness: ApplicationReadiness): Color = when (readiness) {
    ApplicationReadiness.PRIORITY -> extendedColors.success
    ApplicationReadiness.READY -> extendedColors.info
    ApplicationReadiness.PREPARE_FIRST -> extendedColors.warning
    ApplicationReadiness.NEEDS_REVIEW -> MaterialTheme.colorScheme.onSurfaceVariant
    ApplicationReadiness.BLOCKED -> extendedColors.danger
}

private fun salaryLabel(job: Job): String {
    if (job.salaryMin <= 0) return "Salary not stated"
    val period = when (job.salaryPeriod) {
        SalaryPeriod.YEAR -> "/yr"
        SalaryPeriod.MONTH -> "/mo"
        SalaryPeriod.DAY -> "/day"
        SalaryPeriod.HOUR -> "/hr"
    }
    val amount = if (job.salaryMax > job.salaryMin) "${job.salaryMin}–${job.salaryMax}" else job.salaryMin.toString()
    return "${job.currencyCode} $amount$period"
}
