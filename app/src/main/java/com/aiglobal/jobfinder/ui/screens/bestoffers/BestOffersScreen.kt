package com.aiglobal.jobfinder.ui.screens.bestoffers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiglobal.jobfinder.data.jobs.freshness.Freshness
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.BestOfferFilter
import com.aiglobal.jobfinder.data.model.BestOfferSort
import com.aiglobal.jobfinder.data.model.EvidenceStrength
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobBenefitTypes
import com.aiglobal.jobfinder.data.model.OfferMatch
import com.aiglobal.jobfinder.data.model.OfferMatchComponent
import com.aiglobal.jobfinder.data.model.RankedOffer
import com.aiglobal.jobfinder.ui.components.BannerTone
import com.aiglobal.jobfinder.ui.components.DisclosureRow
import com.aiglobal.jobfinder.ui.components.EligibilityBadge
import com.aiglobal.jobfinder.ui.components.EmptyState
import com.aiglobal.jobfinder.ui.components.ErrorState
import com.aiglobal.jobfinder.ui.components.IconChipButton
import com.aiglobal.jobfinder.ui.components.InfoBanner
import com.aiglobal.jobfinder.ui.components.MatchScoreRing
import com.aiglobal.jobfinder.ui.components.OfferAvailabilityBadge
import com.aiglobal.jobfinder.ui.components.Pill
import com.aiglobal.jobfinder.ui.components.PrimaryButton
import com.aiglobal.jobfinder.ui.components.ProgressBar
import com.aiglobal.jobfinder.ui.components.SecondaryButton
import com.aiglobal.jobfinder.ui.components.SkeletonBox
import com.aiglobal.jobfinder.ui.components.publishedLabel
import com.aiglobal.jobfinder.ui.components.salaryLabel
import com.aiglobal.jobfinder.ui.screens.jobs.ApplyLinkSheet
import com.aiglobal.jobfinder.ui.screens.jobs.applicationUrlInspector
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors
import com.aiglobal.jobfinder.viewmodel.BestOffersUiState
import com.aiglobal.jobfinder.viewmodel.BestOffersViewModel

/**
 * Stage 80: "Mes meilleures offres" — the recently discovered postings ranked by how well
 * they fit the analysed CV.
 *
 * The screen re-derives nothing. Every number, badge and sentence comes from
 * [com.aiglobal.jobfinder.data.jobs.offer.OfferMatchEngine], so the order shown here can never
 * disagree with the compatibility the app reports elsewhere. It is a full screen with its own
 * back affordance, reached from Home without disturbing the five bottom-navigation tabs.
 */
@Composable
fun BestOffersScreen(
    viewModel: BestOffersViewModel,
    onBack: () -> Unit,
    onOpenJob: (String) -> Unit = {},
    /** Opens the CV import/management screen (SecondaryRoutes.CV). */
    onOpenCv: () -> Unit = {},
    /** Opens the AI tab (Destination.Ai). */
    onOpenAi: () -> Unit = {},
    onOpenLink: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BestOffersContent(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onOpenJob = onOpenJob,
        onOpenCv = onOpenCv,
        onOpenAi = onOpenAi,
        onOpenLink = onOpenLink,
        onSetSort = viewModel::setSort,
        onSetFilter = viewModel::setFilter,
        onAcknowledgePartial = viewModel::acknowledgePartialRefresh,
    )
}

/** Stateless body so every state can be rendered and verified without a live feed. */
@Composable
internal fun BestOffersContent(
    state: BestOffersUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenJob: (String) -> Unit = {},
    /** Opens the CV import/management screen (SecondaryRoutes.CV). */
    onOpenCv: () -> Unit = {},
    /** Opens the AI tab (Destination.Ai). */
    onOpenAi: () -> Unit = {},
    onOpenLink: (String) -> Unit = {},
    onSetSort: (BestOfferSort) -> Unit = {},
    onSetFilter: (BestOfferFilter) -> Unit = {},
    onAcknowledgePartial: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    var applyJob by remember { mutableStateOf<Job?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BestOffersHeader(
            onBack = onBack,
            onRefresh = onRefresh,
            isRefreshing = state.isRefreshing,
            showRefresh = state.cvAnalysed,
        )

        when {
            // No CV imported yet: the screen cannot rank anything, so it opens the CV screen
            // where the file is actually imported — not the Jobs tab, which cannot import.
            !state.hasCv -> EmptyState(
                emoji = "📄",
                title = "Import your CV to begin",
                message = "The AI ranks offers against your skills, experience and education. " +
                    "Import your CV, then come back to see your best-matched offers abroad.",
                action = {
                    PrimaryButton(
                        text = "Import your CV",
                        onClick = onOpenCv,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )

            // CV imported but not analysed yet: the analysis runs from the AI tab, so open it.
            !state.cvAnalysed -> EmptyState(
                emoji = "🧠",
                title = "Analyse your CV",
                message = "Your CV is imported but not analysed yet. Run the analysis so the AI " +
                    "can match it against the postings it discovers.",
                action = {
                    PrimaryButton(
                        text = "Open the AI tab",
                        onClick = onOpenAi,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )

            state.isLoading && !state.hasOffers -> BestOffersSkeleton()

            // Every source failed: an outage, not a genuine "no offers".
            state.allSourcesFailed && !state.hasOffers -> ErrorState(
                title = "We could not reach the job sources",
                message = "No source answered, so there is nothing to rank right now. Check your " +
                    "connection and try again.",
                onRetry = onRefresh,
            )

            state.totalCompatible == 0 -> EmptyState(
                emoji = "🔍",
                title = "No compatible offers yet",
                message = "None of the discovered postings match your CV closely enough. Refresh " +
                    "to look for new ones, or widen your CV with the skills you have.",
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
                item {
                    BestOffersHero(
                        profileName = state.profileName,
                        totalCompatible = state.totalCompatible,
                        shown = state.offers.size,
                        lastSyncedAtEpochMillis = state.lastSyncedAtEpochMillis,
                    )
                }

                // One honest line about how the last read went: a partial failure, a total
                // outage with a kept list, or an offline launch. A clean success shows nothing.
                val notice = state.refreshNotice
                if (notice != null) {
                    item {
                        InfoBanner(
                            icon = if (state.refreshNoticeIsWarning) {
                                Icons.Filled.CloudOff
                            } else {
                                Icons.Filled.Verified
                            },
                            text = notice,
                            tone = if (state.refreshNoticeIsWarning) BannerTone.Warning else BannerTone.Info,
                            trailing = if (state.partialRefresh && !state.partialAcknowledged) {
                                {
                                    TextButton(onClick = onAcknowledgePartial) { Text("Got it") }
                                }
                            } else {
                                null
                            },
                        )
                    }
                }

                item {
                    BestOffersControls(
                        sort = state.sort,
                        filter = state.filter,
                        onSetSort = onSetSort,
                        onSetFilter = onSetFilter,
                    )
                }

                if (state.offers.isEmpty()) {
                    item {
                        EmptyState(
                            emoji = "🧭",
                            title = "No offer matches this filter",
                            message = "Compatible offers exist, but none pass the filter you chose. " +
                                "Try \"All compatible\" to see them.",
                            action = {
                                SecondaryButton(
                                    text = "Clear filter",
                                    onClick = { onSetFilter(BestOfferFilter.ALL) },
                                )
                            },
                        )
                    }
                } else {
                    itemsIndexed(state.offers, key = { _, offer -> offer.job.id }) { index, offer ->
                        BestOfferCard(
                            offer = offer,
                            index = index,
                            onViewJob = { onOpenJob(offer.job.id) },
                            onApply = { applyJob = offer.job },
                        )
                    }
                    item { BestOffersFootnote() }
                }
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
private fun BestOffersHeader(
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    isRefreshing: Boolean,
    showRefresh: Boolean,
) {
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
                text = "Mes meilleures offres",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Récentes et classées selon votre CV",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showRefresh) {
            IconChipButton(
                icon = Icons.Filled.Refresh,
                onClick = { if (!isRefreshing) onRefresh() },
                contentDescription = "Refresh",
            )
        }
    }
}

@Composable
private fun BestOffersHero(
    profileName: String,
    totalCompatible: Int,
    shown: Int,
    lastSyncedAtEpochMillis: Long?,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, extendedColors.cardStroke, RoundedCornerShape(24.dp))
            .padding(spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)),
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
                    text = if (profileName.isNotBlank()) {
                        "Matched to ${profileName.substringBefore(" ")}'s CV"
                    } else {
                        "Matched to your CV"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "$totalCompatible compatible offer${if (totalCompatible == 1) "" else "s"} found" +
                        if (shown < totalCompatible) " · showing $shown" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (lastSyncedAtEpochMillis != null) {
            Spacer(Modifier.height(spacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Last updated ${publishedLabel(lastSyncedAtEpochMillis).removePrefix("Published ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BestOffersControls(
    sort: BestOfferSort,
    filter: BestOfferFilter,
    onSetSort: (BestOfferSort) -> Unit,
    onSetFilter: (BestOfferFilter) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            items(BestOfferSort.entries) { option ->
                SelectChip(
                    label = option.label,
                    selected = option == sort,
                    onClick = { onSetSort(option) },
                )
            }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            items(BestOfferFilter.entries) { option ->
                SelectChip(
                    label = option.label,
                    selected = option == filter,
                    onClick = { onSetFilter(option) },
                )
            }
        }
    }
}

@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(120), label = "selectChip")
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(
                width = if (selected) 0.dp else 1.dp,
                color = if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(50),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BestOfferCard(
    offer: RankedOffer,
    index: Int,
    onViewJob: () -> Unit,
    onApply: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val job = offer.job
    val match = offer.match
    var expanded by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(320, delayMillis = index * 55)) +
            slideInVertically(tween(380, delayMillis = index * 55)) { it / 8 },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, extendedColors.cardStroke, RoundedCornerShape(22.dp))
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MatchScoreRing(score = match.score, diameter = 54.dp)
                Spacer(Modifier.width(spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = job.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = job.company,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(
                            text = listOf(job.countryFlag, job.country.ifBlank { "—" }, job.city)
                                .filter { it.isNotBlank() }
                                .joinToString(" "),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                        Pill(
                            text = match.band.shortLabel,
                            containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                            contentColor = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }

            // Wraps rather than clips: eligibility + freshness + a settled availability verdict
            // can exceed one line on a narrow screen, and FlowRow lets them reflow instead of
            // pushing a badge off the card edge.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                EligibilityBadge(eligibility = job.eligibility)
                FreshnessPill(match = match)
                // The best-offers list is the one place a candidate decides what to act on, so
                // the "can I still apply?" verdict belongs here next to eligibility. An
                // unconfirmed read is left off: it adds no signal and would only clutter the row.
                if (!job.availability.isUnconfirmed) {
                    OfferAvailabilityBadge(availability = job.availability)
                }
            }

            val benefitPills = benefitPills(job)
            if (job.salaryMin > 0 || benefitPills.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (job.salaryMin > 0) {
                        Pill(
                            text = "💰 ${salaryLabel(job)}",
                            containerColor = extendedColors.successContainer,
                            contentColor = extendedColors.success,
                        )
                    }
                    benefitPills.forEach { (emoji, label) ->
                        Pill(
                            text = "$emoji $label",
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            if (job.matchedSkills.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Matches your CV",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        job.matchedSkills.take(6).forEach { skill ->
                            Pill(
                                text = skill,
                                containerColor = extendedColors.successContainer,
                                contentColor = extendedColors.success,
                            )
                        }
                    }
                }
            }

            val missing = missingRequirements(match)
            if (missing.isNotEmpty()) {
                InfoBanner(
                    icon = Icons.Filled.Schedule,
                    text = "Worth checking: ${missing.joinToString("; ")}.",
                    tone = BannerTone.Warning,
                )
            }

            if (match.hardStops.isNotEmpty()) {
                InfoBanner(
                    icon = Icons.Filled.Public,
                    text = match.hardStops.first(),
                    tone = BannerTone.Danger,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            DisclosureRow(
                expanded = expanded,
                labelExpanded = "Hide why this score",
                labelCollapsed = "Why this score?",
                onClick = { expanded = !expanded },
            )
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    match.rankedComponents.forEach { component ->
                        ScoreComponentRow(component)
                    }
                    match.reasons.take(3).forEach { reason ->
                        Text(
                            text = "• $reason",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                SecondaryButton(
                    text = "View job",
                    onClick = onViewJob,
                    leadingIcon = Icons.Filled.Public,
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = "Apply",
                    onClick = onApply,
                    leadingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ScoreComponentRow(component: OfferMatchComponent) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = component.dimension.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${component.points}/${component.maxPoints}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
        ProgressBar(
            fraction = component.earnedShare,
            height = 5.dp,
            color = if (component.strength == EvidenceStrength.UNKNOWN) {
                MaterialTheme.colorScheme.outline
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}

@Composable
private fun FreshnessPill(match: OfferMatch) {
    val (text, tone) = when {
        !match.hasVerifiedDate -> "Date unknown" to BannerTone.Warning
        match.freshness == Freshness.FRESH -> "Published recently" to BannerTone.Success
        match.freshness == Freshness.RECENT -> "Published this fortnight" to BannerTone.Success
        match.freshness == Freshness.AGING -> "Published a while ago" to BannerTone.Warning
        else -> "Likely filled" to BannerTone.Danger
    }
    val container = when (tone) {
        BannerTone.Success -> extendedColors.successContainer
        BannerTone.Warning -> extendedColors.warningContainer
        BannerTone.Danger -> extendedColors.dangerContainer
        BannerTone.Info -> extendedColors.infoContainer
    }
    val content = when (tone) {
        BannerTone.Success -> extendedColors.success
        BannerTone.Warning -> extendedColors.warning
        BannerTone.Danger -> extendedColors.danger
        BannerTone.Info -> extendedColors.info
    }
    Pill(text = text, containerColor = container, contentColor = content)
}

private fun benefitPills(job: Job): List<Pair<String, String>> {
    val confirmedKeys = job.benefitEvidence
        .filter { it.status == BenefitStatus.CONFIRMED }
        .map { it.type.key }
        .toSet()
    return JobBenefitTypes.all
        .filter { it.key in confirmedKeys }
        .map { it.emoji to it.shortLabel }
        .take(3)
}

private fun missingRequirements(match: OfferMatch): List<String> = buildList {
    match.qualificationGaps.filter { it.met == false }.forEach { add(it.label) }
    if (match.missingSkills.isNotEmpty()) {
        add("skills not shown in your CV: ${match.missingSkills.take(4).joinToString(", ")}")
    }
}.distinct()

@Composable
private fun BestOffersSkeleton() {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        SkeletonBox(modifier = Modifier.fillMaxWidth().height(84.dp), shape = RoundedCornerShape(24.dp))
        SkeletonBox(modifier = Modifier.fillMaxWidth().height(36.dp), shape = RoundedCornerShape(50))
        repeat(3) {
            SkeletonBox(modifier = Modifier.fillMaxWidth().height(210.dp), shape = RoundedCornerShape(22.dp))
        }
    }
}

@Composable
private fun BestOffersFootnote() {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Verified,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Scores come from your CV and the postings' own text. Dates are the source's, never guessed.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
