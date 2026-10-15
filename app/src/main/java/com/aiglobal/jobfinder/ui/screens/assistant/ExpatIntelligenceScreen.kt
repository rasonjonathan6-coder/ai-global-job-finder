package com.aiglobal.jobfinder.ui.screens.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
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
import com.aiglobal.jobfinder.data.model.CountryOpportunity
import com.aiglobal.jobfinder.data.model.ExpatIntelligence
import com.aiglobal.jobfinder.data.model.ReadinessBlocker
import com.aiglobal.jobfinder.data.model.ShortlistEntry
import com.aiglobal.jobfinder.ui.components.AppCard
import com.aiglobal.jobfinder.ui.components.EmptyState
import com.aiglobal.jobfinder.ui.components.IconChipButton
import com.aiglobal.jobfinder.ui.components.Pill
import com.aiglobal.jobfinder.ui.components.ProgressBar
import com.aiglobal.jobfinder.ui.components.ReadinessBadge
import com.aiglobal.jobfinder.ui.components.SecondaryButton
import com.aiglobal.jobfinder.ui.components.SectionHeader
import com.aiglobal.jobfinder.ui.components.SkeletonBox
import com.aiglobal.jobfinder.ui.components.isDarkTheme
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors
import com.aiglobal.jobfinder.viewmodel.ExpatIntelligenceUiState
import com.aiglobal.jobfinder.viewmodel.ExpatIntelligenceViewModel

/**
 * Stage 21: the whole-feed Expat Intelligence digest. Where the routes in from Madagascar
 * actually are, what keeps recurring across postings, and the few roles worth acting on
 * first — every figure counted from the postings currently held.
 */
@Composable
fun ExpatIntelligenceScreen(
    viewModel: ExpatIntelligenceViewModel,
    onBack: () -> Unit,
    onOpenJob: (String) -> Unit = {},
    onOpenJobs: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ExpatIntelligenceContent(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onOpenJob = onOpenJob,
        onOpenJobs = onOpenJobs,
    )
}

/** Stateless body so the layout can be verified without a live feed. */
@Composable
internal fun ExpatIntelligenceContent(
    state: ExpatIntelligenceUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenJob: (String) -> Unit = {},
    onOpenJobs: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    val intelligence = state.intelligence

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        IntelligenceHeader(onBack = onBack, onRefresh = onRefresh)

        when {
            state.isLoading && intelligence == null -> IntelligenceSkeleton()
            intelligence == null || intelligence.isEmpty -> EmptyState(
                emoji = "🌍",
                title = "No intelligence yet",
                message = "Refresh the feed and the app will map which countries are open to " +
                    "applicants in Madagascar, and what keeps blocking you.",
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
                item { IntelligenceHero(intelligence = intelligence) }

                if (intelligence.countries.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "Where the routes are",
                            subtitle = "${intelligence.actionableCount} open postings across " +
                                "${intelligence.countries.size} " +
                                (if (intelligence.countries.size == 1) "country" else "countries"),
                        )
                    }
                    itemsIndexed(
                        intelligence.countries,
                        key = { _, country -> country.country },
                    ) { index, country ->
                        CountryCard(country = country, index = index)
                    }
                }

                if (intelligence.recurringProfileGaps.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "What keeps blocking you",
                            subtitle = "Fix these once and every application gets easier",
                        )
                    }
                    item { GapsCard(gaps = intelligence.recurringProfileGaps) }
                }

                if (intelligence.topOpportunities.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "Strongest opportunities",
                            subtitle = "Ranked by how ready you are to act, not CV fit alone",
                        )
                    }
                    itemsIndexed(
                        intelligence.topOpportunities,
                        key = { _, entry -> entry.job.id },
                    ) { index, entry ->
                        OpportunityCard(
                            entry = entry,
                            index = index,
                            onOpenJob = { onOpenJob(entry.job.id) },
                        )
                    }
                }

                item {
                    SecondaryButton(
                        text = "Browse all matched jobs",
                        onClick = onOpenJobs,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = Icons.Filled.Public,
                    )
                }
            }
        }
    }
}

@Composable
private fun IntelligenceHeader(onBack: () -> Unit, onRefresh: () -> Unit) {
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
                text = "Expat intelligence",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Where you can apply from Madagascar",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconChipButton(
            icon = Icons.Filled.Refresh,
            onClick = onRefresh,
            contentDescription = "Refresh intelligence",
        )
    }
}

@Composable
private fun IntelligenceHero(intelligence: ExpatIntelligence) {
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
            Text(
                text = intelligence.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            HeroStat("Open to you", intelligence.actionableCount, Color(0xFF6EE7B7))
            HeroStat("Priority", intelligence.priorityCount, Color(0xFF93C5FD))
            HeroStat("Official route", intelligence.officialRouteCount, Color(0xFFFCD34D))
        }
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

@Composable
private fun CountryCard(country: CountryOpportunity, index: Int) {
    val spacing = LocalSpacing.current
    val visible = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible.value = true }

    AnimatedVisibility(
        visible = visible.value,
        enter = fadeIn(tween(300, delayMillis = index * 40)) +
            slideInVertically(tween(340, delayMillis = index * 40)) { it / 8 },
    ) {
        AppCard(contentPadding = spacing.md) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = country.countryFlag, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(spacing.xs))
                    Text(
                        text = country.country,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${country.actionableCount} open",
                        style = MaterialTheme.typography.labelMedium,
                        color = extendedColors.success,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                ProgressBar(
                    fraction = country.openShare / 100f,
                    color = extendedColors.success,
                    height = 6.dp,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xxs),
                ) {
                    Text(
                        text = "${country.openShare}% of ${country.totalCount} postings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (country.hasOfficialRoute) {
                        Spacer(Modifier.width(spacing.xxs))
                        Pill(
                            text = "Official route",
                            containerColor = extendedColors.infoContainer,
                            contentColor = extendedColors.info,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GapsCard(gaps: List<ReadinessBlocker>) {
    val spacing = LocalSpacing.current
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            gaps.forEach { gap ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .padding(top = 5.dp)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                if (gap.isOfficial) extendedColors.warning
                                else MaterialTheme.colorScheme.primary,
                            ),
                    )
                    Spacer(Modifier.width(spacing.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = gap.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = gap.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (gap.points > 0) {
                        Text(
                            text = "-${gap.points}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OpportunityCard(entry: ShortlistEntry, index: Int, onOpenJob: () -> Unit) {
    val spacing = LocalSpacing.current
    val job = entry.job
    val visible = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible.value = true }
    val interaction = remember { MutableInteractionSource() }

    AnimatedVisibility(
        visible = visible.value,
        enter = fadeIn(tween(300, delayMillis = index * 40)) +
            slideInVertically(tween(340, delayMillis = index * 40)) { it / 8 },
    ) {
        AppCard(
            contentPadding = spacing.md,
            modifier = Modifier.clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onOpenJob,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = job.countryFlag, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = job.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${job.company} · ${job.country}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(spacing.xs))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${entry.readinessScore}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "ready",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(spacing.xs))
            ReadinessBadge(readiness = entry.readiness)
        }
    }
}

@Composable
private fun IntelligenceSkeleton() {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = spacing.gutter, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        SkeletonBox(modifier = Modifier.fillMaxWidth().height(140.dp), shape = RoundedCornerShape(24.dp))
        SkeletonBox(modifier = Modifier.fillMaxWidth().height(96.dp), shape = RoundedCornerShape(22.dp))
        SkeletonBox(modifier = Modifier.fillMaxWidth().height(96.dp), shape = RoundedCornerShape(22.dp))
    }
}
