package com.aiglobal.jobfinder.ui.screens.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.SourceState
import com.aiglobal.jobfinder.ui.components.ErrorState
import com.aiglobal.jobfinder.ui.components.EmptyState
import com.aiglobal.jobfinder.ui.components.JobCard
import com.aiglobal.jobfinder.ui.components.JobCardCompact
import com.aiglobal.jobfinder.ui.components.JobListSkeleton
import com.aiglobal.jobfinder.ui.components.MatchScoreRing
import com.aiglobal.jobfinder.ui.components.PrimaryButton
import com.aiglobal.jobfinder.ui.components.ProgressBar
import com.aiglobal.jobfinder.ui.components.relativeTime
import com.aiglobal.jobfinder.ui.components.SecondaryButton
import com.aiglobal.jobfinder.ui.components.SectionHeader
import com.aiglobal.jobfinder.ui.components.SkeletonBox
import com.aiglobal.jobfinder.ui.components.StatCardSkeleton
import com.aiglobal.jobfinder.ui.components.isDarkTheme
import com.aiglobal.jobfinder.ui.screens.jobs.ApplyLinkSheet
import com.aiglobal.jobfinder.ui.screens.jobs.applicationUrlInspector
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors
import com.aiglobal.jobfinder.viewmodel.HomeUiState
import com.aiglobal.jobfinder.viewmodel.HomeViewModel

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenJobs: () -> Unit,
    onOpenJob: (String) -> Unit,
    onOpenLink: (String) -> Unit = {},
    onOpenShortlist: () -> Unit = {},
    onOpenRanking: () -> Unit = {},
    onToggleSaveJob: (String) -> Unit = {},
    onOpenAlerts: () -> Unit = {},
    /** Opens the Jobs tab focused on the postings first seen since the last review. */
    onOpenNewPostings: (List<String>) -> Unit = {},
    /** Acknowledges the new postings so the discovery badge clears. */
    onMarkNewReviewed: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // First launch with no CV and no completed sync: nothing has failed yet, the user
    // simply has not imported a CV. Any real source error falls through to the error state.
    val isFirstRun = state.profile?.cvImported != true &&
        state.syncState.sources.none { it.state == SourceState.SUCCESS || it.state == SourceState.ERROR }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (state.isLoading && state.bestMatches.isEmpty()) {
            HomeLoadingContent()
        } else if (state.bestMatches.isEmpty() && isFirstRun) {
            EmptyState(
                title = "Let's find your opportunities",
                message = "Import your CV so the AI can rank roles abroad that accept applicants from Madagascar.",
                emoji = "🚀",
                action = {
                    PrimaryButton(
                        text = "Import your CV",
                        onClick = onOpenJobs,
                        leadingIcon = Icons.Filled.Description,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )
        } else if (state.bestMatches.isEmpty()) {
            ErrorState(
                title = "No opportunities loaded",
                message = "We could not reach the job sources. Check your connection and try again.",
                onRetry = { viewModel.refresh() },
            )
        } else {
            HomeContent(
                state = state,
                onOpenJobs = onOpenJobs,
                onOpenJob = onOpenJob,
                onOpenLink = onOpenLink,
                onOpenShortlist = onOpenShortlist,
                onOpenRanking = onOpenRanking,
                onToggleSaveJob = onToggleSaveJob,
                onOpenAlerts = onOpenAlerts,
                onOpenNewPostings = onOpenNewPostings,
                onMarkNewReviewed = onMarkNewReviewed,
            )
        }
    }
}

@Composable
internal fun HomeContent(
    state: HomeUiState,
    onOpenJobs: () -> Unit,
    onOpenJob: (String) -> Unit,
    onOpenLink: (String) -> Unit = {},
    onOpenShortlist: () -> Unit = {},
    onOpenRanking: () -> Unit = {},
    onToggleSaveJob: (String) -> Unit = {},
    onOpenAlerts: () -> Unit = {},
    /** Opens the Jobs tab focused on the postings first seen since the last review. */
    onOpenNewPostings: (List<String>) -> Unit = {},
    /** Acknowledges the new postings so the discovery badge clears. */
    onMarkNewReviewed: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    val profile = state.profile
    var applyJob by remember { mutableStateOf<Job?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = spacing.navBarClearance),
    ) {
        item {
            HomeTopBar(
                hasNotifications = state.stats.applications > 0 || state.stats.interviews > 0,
                onOpenAlerts = onOpenAlerts,
            )
        }

        item {
            Box(modifier = Modifier.padding(horizontal = spacing.gutter)) {
                GreetingHero(
                    name = profile?.fullName?.substringBefore(" ")?.trim().orEmpty(),
                    profileCompletion = profile?.profileCompletion ?: 0,
                    headline = profile?.headline.orEmpty(),
                    strongMatches = state.stats.matchingJobs,
                    onAnalyseCv = onOpenJobs,
                )
            }
        }

        item {
            Spacer(Modifier.height(spacing.lg))
            StatsRow(state = state)
        }

        if (state.discoveryBaselineEstablished) {
            item {
                Spacer(Modifier.height(spacing.lg))
                Box(modifier = Modifier.padding(horizontal = spacing.gutter)) {
                    NewPostingsHomeCard(
                        state = state,
                        onReview = { onOpenNewPostings(state.newOpportunities.map { it.id }) },
                        onMarkReviewed = onMarkNewReviewed,
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(spacing.lg))
            Column(
                modifier = Modifier.padding(horizontal = spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                EntryBannerCard(
                    icon = Icons.Filled.Public,
                    tint = extendedColors.success,
                    title = "Expat shortlist",
                    body = "Roles you can actually apply for from Madagascar, ranked and explained.",
                    onClick = onOpenShortlist,
                )
                EntryBannerCard(
                    icon = Icons.Filled.WorkspacePremium,
                    tint = MaterialTheme.colorScheme.secondary,
                    title = "Expat ranking",
                    body = "Your strongest opportunities, with readiness and package blended into one place.",
                    onClick = onOpenRanking,
                )
            }
        }

        item {
            Spacer(Modifier.height(spacing.xl))
            Box(modifier = Modifier.padding(horizontal = spacing.gutter)) {
                SectionHeader(
                    title = "Your best opportunities",
                    subtitle = "Ranked by how well your CV fits",
                    actionLabel = "See all",
                    onActionClick = onOpenJobs,
                )
            }
        }

        item {
            Spacer(Modifier.height(spacing.sm))
            Column(
                modifier = Modifier.padding(horizontal = spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                state.bestMatches.take(3).forEachIndexed { index, job ->
                    val expat = state.expatAssessments[job.id]
                    JobCard(
                        job = job,
                        onViewJob = { onOpenJob(job.id) },
                        onApply = { applyJob = job },
                        onToggleSave = { onToggleSaveJob(job.id) },
                        index = index,
                        expatScore = state.expatScore(job),
                        expatEvidenceCoverage = expat?.evidenceCoverage ?: 0,
                        expatLowEvidence = expat?.hasEvidence == true && expat.lowEvidence,
                        showNewBadge = state.newOpportunities.any { it.id == job.id },
                    )
                }
            }
        }

        if (state.newOpportunities.isNotEmpty()) {
            item {
                Spacer(Modifier.height(spacing.xl))
                Box(modifier = Modifier.padding(horizontal = spacing.gutter)) {
                    SectionHeader(
                        title = "New since your last review",
                        subtitle = "${state.newOpportunities.size} found by the incremental search",
                        actionLabel = "Review",
                        onActionClick = { onOpenNewPostings(state.newOpportunities.map { it.id }) },
                    )
                }
            }
            item {
                Spacer(Modifier.height(spacing.sm))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = spacing.gutter),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    items(state.newOpportunities, key = { it.id }) { job ->
                        JobCardCompact(job = job, onClick = { onOpenJob(job.id) })
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(spacing.xl))
            Box(modifier = Modifier.padding(horizontal = spacing.gutter)) {
                AiInsightCard(
                    scannedCount = state.totalJobs,
                    eligibleCount = state.bestMatches.count { it.applyableFromMadagascar },
                )
            }
        }
    }

    applyJob?.let { job ->
        ApplyLinkSheet(
            job = job,
            inspection = applicationUrlInspector.inspect(job),
            onDismiss = { applyJob = null },
            onOpen = { url ->
                applyJob = null
                onOpenLink(url)
            },
        )
    }
}

@Composable
private fun HomeTopBar(hasNotifications: Boolean, onOpenAlerts: () -> Unit) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = spacing.gutter, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "AI Global Job Finder",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Jobs you can actually apply for",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, extendedColors.cardStroke, CircleShape)
                    .clickable(onClick = onOpenAlerts),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Notifications,
                    contentDescription = "Notifications",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (hasNotifications) {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp, end = 8.dp)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(extendedColors.danger),
                )
            }
        }
    }
}

/**
 * Gradient hero. The greeting is the first thing the user reads, so it sits on
 * the highest-contrast surface in the app.
 */
@Composable
private fun GreetingHero(
    name: String,
    profileCompletion: Int,
    headline: String,
    strongMatches: Int,
    onAnalyseCv: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val shape = RoundedCornerShape(26.dp)
    val dark = isDarkTheme()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    if (dark) listOf(Color(0xFF152C5C), Color(0xFF0A1730))
                    else listOf(Color(0xFF14336B), Color(0xFF0A1D40)),
                )
            ),
    ) {
        // Soft accent glow in the top-right corner: reads as "AI" without being loud.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(190.dp)
                .background(
                    Brush.radialGradient(
                        listOf(MaterialTheme.colorScheme.secondary.copy(alpha = 0.30f), Color.Transparent),
                    )
                ),
        )
        Column(modifier = Modifier.padding(spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "AI MATCHING ACTIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.92f),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(spacing.md))
            Text(
                text = if (name.isBlank()) "Welcome back 👋" else "Hello, $name 👋",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (headline.isNotBlank()) "$headline · Madagascar" else "Madagascar",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(spacing.md))
            Text(
                text = if (strongMatches > 0) {
                    "$strongMatches opportunities match your profile and accept international applicants."
                } else {
                    "Import your CV so the AI can rank opportunities for you."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.86f),
            )

            Spacer(Modifier.height(spacing.lg))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(
                    text = "Refresh matches",
                    onClick = onAnalyseCv,
                    leadingIcon = Icons.Filled.AutoAwesome,
                    height = 46.dp,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(spacing.md))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center) {
                        ProgressRing(progress = profileCompletion / 100f)
                        Text(
                            text = "$profileCompletion%",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Profile",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressRing(progress: Float, size: Int = 46) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(900), label = "profileRing")
    // Read theme colors outside the draw lambda: DrawScope is not a composable scope.
    val trackColor = Color.White.copy(alpha = 0.22f)
    val progressColor = MaterialTheme.colorScheme.secondary
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size.dp)) {
        val stroke = 4.dp.toPx()
        val inset = stroke / 2f
        val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(
            color = trackColor,
            startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = arcSize,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
        drawArc(
            color = progressColor,
            startAngle = -90f, sweepAngle = 360f * animated, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = arcSize,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}

/** Four headline numbers. Kept as one card so the row never looks cramped. */
@Composable
private fun StatsRow(state: HomeUiState) {
    val spacing = LocalSpacing.current
    val stats = state.stats

    Box(modifier = Modifier.padding(horizontal = spacing.gutter)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, extendedColors.cardStroke, RoundedCornerShape(20.dp))
                .padding(vertical = spacing.md, horizontal = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatTile(
                icon = Icons.Filled.Work,
                value = "${stats.matchingJobs}",
                label = "Matching",
                accent = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            StatDivider()
            StatTile(
                icon = Icons.Filled.Bolt,
                value = "${state.newPostingCount}",
                label = "New",
                accent = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
            StatDivider()
            StatTile(
                icon = Icons.Filled.Description,
                value = "${stats.applications}",
                label = "Applied",
                accent = extendedColors.info,
                modifier = Modifier.weight(1f),
            )
            StatDivider()
            StatTile(
                icon = Icons.Filled.Badge,
                value = "${stats.interviews}",
                label = "Interviews",
                accent = extendedColors.warning,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(34.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun StatTile(
    icon: ImageVector,
    value: String,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The Home summary of incremental discovery. It states plainly when the last search ran and
 * succeeded, how many postings are new since the last review, and which sources are failing.
 * When nothing is new it still reports the last run, so "nothing new" reads as a result rather
 * than as silence.
 */
@Composable
private fun NewPostingsHomeCard(
    state: HomeUiState,
    onReview: () -> Unit,
    onMarkReviewed: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val summary = state.discoverySummary
    val newCount = state.newOpportunities.size
    val shape = RoundedCornerShape(20.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, extendedColors.cardStroke, shape)
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (newCount > 0) "$newCount new since your last review" else "No new postings yet",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = discoveryRunLabel(summary.lastSuccessAtEpochMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (summary.isRefreshing) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (summary.hasFailures) {
            Text(
                text = "${summary.failures.size} source${if (summary.failures.size == 1) "" else "s"} " +
                    "could not be reached. Results may be incomplete.",
                style = MaterialTheme.typography.bodySmall,
                color = extendedColors.warning,
            )
        }

        if (newCount > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                PrimaryButton(
                    text = "Review new",
                    onClick = onReview,
                    modifier = Modifier.weight(1f),
                    height = 42.dp,
                )
                SecondaryButton(
                    text = "Mark reviewed",
                    onClick = onMarkReviewed,
                    modifier = Modifier.weight(1f),
                    height = 42.dp,
                )
            }
        }
    }
}

/** "Last search succeeded 5m ago", or an honest prompt when none has succeeded yet. */
private fun discoveryRunLabel(lastSuccessAtEpochMillis: Long?): String =
    if (lastSuccessAtEpochMillis == null) "No successful search yet"
    else "Last search succeeded ${relativeTime(lastSuccessAtEpochMillis)}"

/** Bottom banner explaining what the AI is doing in the background. */
@Composable
private fun AiInsightCard(scannedCount: Int, eligibleCount: Int) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(extendedColors.infoContainer)
            .padding(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.TrendingUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "AI scanned $scannedCount listings in your last refresh",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (eligibleCount > 0) {
                    "$eligibleCount are open to applicants based in Madagascar."
                } else {
                    "Filtered to roles that sponsor visas or accept applicants from Madagascar."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Home entry point into the expat surfaces (shortlist, ranking). Kept as a slim banner so it
 * invites a tap without competing with the job cards below it.
 */
@Composable
private fun EntryBannerCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, tween(140), label = "entryBannerPress")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.width(spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(spacing.xs))
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun HomeLoadingContent() {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = spacing.gutter, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkeletonBox(Modifier.size(38.dp), RoundedCornerShape(12.dp))
            Spacer(Modifier.width(spacing.sm))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SkeletonBox(Modifier.width(150.dp).height(14.dp))
                SkeletonBox(Modifier.width(110.dp).height(10.dp))
            }
        }
        SkeletonBox(Modifier.fillMaxWidth().height(220.dp), RoundedCornerShape(26.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            repeat(4) { StatCardSkeleton(Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(spacing.xs))
        JobListSkeleton(count = 2)
    }
}
