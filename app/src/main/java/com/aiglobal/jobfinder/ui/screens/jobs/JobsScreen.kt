package com.aiglobal.jobfinder.ui.screens.jobs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.HelpCenter
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiglobal.jobfinder.data.jobs.search.SearchCoverage
import com.aiglobal.jobfinder.data.model.BenefitScoreComponent
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.EligibilityReason
import com.aiglobal.jobfinder.data.model.EvidenceStrength
import com.aiglobal.jobfinder.data.model.ExpatBenefitsAssessment
import com.aiglobal.jobfinder.data.model.InternationalFitAssessment
import com.aiglobal.jobfinder.data.model.InternationalFitComponent
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobSyncState
import com.aiglobal.jobfinder.data.model.MatchReason
import com.aiglobal.jobfinder.data.model.MatchSignal
import com.aiglobal.jobfinder.data.model.SourceState
import com.aiglobal.jobfinder.ui.components.BenefitStatusChip
import com.aiglobal.jobfinder.ui.components.EmptyState
import com.aiglobal.jobfinder.ui.components.ErrorState
import com.aiglobal.jobfinder.ui.components.ExpatScoreBadge
import com.aiglobal.jobfinder.ui.components.FilterChip
import com.aiglobal.jobfinder.ui.components.IconChipButton
import com.aiglobal.jobfinder.ui.components.InternationalFitBadge
import com.aiglobal.jobfinder.ui.components.JobCard
import com.aiglobal.jobfinder.ui.components.JobListSkeleton
import com.aiglobal.jobfinder.ui.components.MatchChip
import com.aiglobal.jobfinder.ui.components.OfferVerificationBadge
import com.aiglobal.jobfinder.ui.components.Pill
import com.aiglobal.jobfinder.ui.components.PrimaryButton
import com.aiglobal.jobfinder.ui.components.relativeTime
import com.aiglobal.jobfinder.ui.components.retrievedLabel
import com.aiglobal.jobfinder.ui.components.SecondaryButton
import com.aiglobal.jobfinder.ui.components.SectionHeader
import com.aiglobal.jobfinder.ui.components.VerificationAxisChip
import com.aiglobal.jobfinder.ui.components.verificationStateColor
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors
import com.aiglobal.jobfinder.viewmodel.JobSort
import com.aiglobal.jobfinder.viewmodel.JobsUiState
import com.aiglobal.jobfinder.viewmodel.JobsViewModel

@Composable
fun JobsScreen(
    viewModel: JobsViewModel,
    onOpenAi: () -> Unit,
    onOpenJob: (String) -> Unit = {},
    onOpenLink: (String) -> Unit = {},
    /** Clears the "new postings" list once the user has acknowledged it. */
    onMarkNewReviewed: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    JobsContent(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onToggleSaved = viewModel::toggleSavedFilter,
        onToggleEligible = viewModel::toggleEligibleFilter,
        onToggleSponsored = viewModel::toggleSponsoredFilter,
        onToggleVisaDocumented = viewModel::toggleVisaDocumentedFilter,
        onToggleTradeOnly = viewModel::toggleTradeOnlyFilter,
        onToggleStrongInternationalFit = viewModel::toggleStrongInternationalFitFilter,
        onToggleVerifiedVisa = viewModel::toggleVerifiedVisaFilter,
        onToggleVerifiedAccommodation = viewModel::toggleVerifiedAccommodationFilter,
        onToggleVerifiedFlights = viewModel::toggleVerifiedFlightsFilter,
        onToggleOpenVacancy = viewModel::toggleOpenVacancyFilter,
        onToggleRecentlyVerified = viewModel::toggleRecentlyVerifiedFilter,
        onToggleNew = viewModel::toggleNewFilter,
        onCountryChange = viewModel::onCountryChange,
        onSourceChange = viewModel::onSourceChange,
        onSortChange = viewModel::onSortChange,
        onMinExpatScoreChange = viewModel::onMinExpatScoreChange,
        onClearFilters = viewModel::clearFilters,
        onRefresh = viewModel::refresh,
        onOpenAi = onOpenAi,
        onToggleSaveJob = viewModel::toggleSave,
        onOpenJob = onOpenJob,
        onOpenLink = onOpenLink,
        onMarkNewReviewed = onMarkNewReviewed,
    )
}

@Composable
internal fun JobsContent(
    state: JobsUiState,
    onQueryChange: (String) -> Unit,
    onToggleSaved: () -> Unit,
    onToggleEligible: () -> Unit,
    onToggleSponsored: () -> Unit,
    onToggleVisaDocumented: () -> Unit = {},
    onToggleTradeOnly: () -> Unit = {},
    onToggleStrongInternationalFit: () -> Unit = {},
    onToggleVerifiedVisa: () -> Unit = {},
    onToggleVerifiedAccommodation: () -> Unit = {},
    onToggleVerifiedFlights: () -> Unit = {},
    onToggleOpenVacancy: () -> Unit = {},
    onToggleRecentlyVerified: () -> Unit = {},
    onToggleNew: () -> Unit = {},
    onCountryChange: (String?) -> Unit,
    onSourceChange: (String?) -> Unit,
    onSortChange: (JobSort) -> Unit,
    onMinExpatScoreChange: (Int) -> Unit = {},
    onClearFilters: () -> Unit,
    onRefresh: () -> Unit,
    onOpenAi: () -> Unit,
    onToggleSaveJob: (String) -> Unit,
    onOpenJob: (String) -> Unit = {},
    /** Launches the posting URL once the candidate has confirmed the destination. */
    onOpenLink: (String) -> Unit = {},
    /** Clears the "new postings" list once the user has acknowledged it. */
    onMarkNewReviewed: () -> Unit = {},
) {
    var explainJob by remember { mutableStateOf<Job?>(null) }
    var applyJob by remember { mutableStateOf<Job?>(null) }
    var showFilters by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        JobsHeader(state = state, onQueryChange = onQueryChange)
        SourceStatusStrip(
            syncState = state.syncState,
            coverage = state.coverage,
            eligibleCount = state.eligibleCount,
            totalCount = state.jobs.size,
            newCount = state.newCount,
            onRefresh = onRefresh,
            onOpenFilters = { showFilters = true },
            filtersActive = state.hasActiveFilters,
        )
        JobsFilterRow(
            state = state,
            onToggleNew = onToggleNew,
            onToggleSaved = onToggleSaved,
            onToggleEligible = onToggleEligible,
            onToggleSponsored = onToggleSponsored,
            onToggleVisaDocumented = onToggleVisaDocumented,
            onToggleTradeOnly = onToggleTradeOnly,
            onToggleStrongInternationalFit = onToggleStrongInternationalFit,
            onToggleVerifiedVisa = onToggleVerifiedVisa,
            onToggleVerifiedAccommodation = onToggleVerifiedAccommodation,
            onToggleVerifiedFlights = onToggleVerifiedFlights,
            onToggleOpenVacancy = onToggleOpenVacancy,
            onToggleRecentlyVerified = onToggleRecentlyVerified,
            onMinExpatScoreChange = onMinExpatScoreChange,
        )

        NewPostingsBanner(state = state, onMarkReviewed = onMarkNewReviewed)

        when {
            state.isLoading && state.jobs.isEmpty() -> {
                JobListSkeleton(
                    count = 3,
                    modifier = Modifier.padding(horizontal = LocalSpacing.current.gutter, vertical = 4.dp),
                )
            }
            state.jobs.isEmpty() -> {
                ErrorState(
                    title = "Could not load jobs",
                    message = "The job sources did not respond. Check your connection and retry.",
                    onRetry = onRefresh,
                )
            }
            state.visibleJobs.isEmpty() -> {
                EmptyState(
                    emoji = if (state.hasActiveFilters) "🔍" else "📭",
                    title = if (state.savedOnly) "No saved jobs yet" else "No jobs match your filters",
                    message = if (state.savedOnly) {
                        "Tap the bookmark on any job card to keep it here for later."
                    } else {
                        "Try a different keyword, or clear the filters to see all ${state.jobs.size} opportunities."
                    },
                    action = if (state.hasActiveFilters) {
                        {
                            SecondaryButton(
                                text = "Clear filters",
                                onClick = onClearFilters,
                                leadingIcon = Icons.Filled.FilterAltOff,
                            )
                        }
                    } else null,
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = LocalSpacing.current.gutter,
                        end = LocalSpacing.current.gutter,
                        top = 4.dp,
                        bottom = LocalSpacing.current.navBarClearance,
                    ),
                    verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
                ) {
                    itemsIndexed(state.visibleJobs, key = { _, job -> job.id }) { index, job ->
                        val expat = state.expatAssessment(job)
                        JobCard(
                            job = job.copy(isSaved = state.isSaved(job)),
                            onViewJob = { explainJob = job },
                            onApply = { applyJob = job },
                            onToggleSave = { onToggleSaveJob(job.id) },
                            index = index,
                            expatScore = expat.score.takeIf { expat.hasEvidence },
                            expatEvidenceCoverage = expat.evidenceCoverage,
                            expatLowEvidence = expat.hasEvidence && expat.lowEvidence,
                            internationalFit = state.internationalFitOf(job),
                            showNewBadge = state.isNew(job),
                            discoveredAtEpochMillis = state.firstSeenAt(job),
                        )
                    }
                }
            }
        }
    }

    explainJob?.let { job ->
        JobExplanationSheet(
            job = job,
            expat = state.expatAssessment(job),
            fit = state.internationalFitOf(job),
            onDismiss = { explainJob = null },
            onOpenSource = {
                explainJob = null
                applyJob = job
            },
            onWriteLetter = {
                explainJob = null
                onOpenJob(job.id)
            },
            discoveredAtEpochMillis = state.firstSeenAt(job),
        )
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

    if (showFilters) {
        JobFilterSheet(
            state = state,
            onDismiss = { showFilters = false },
            onCountryChange = onCountryChange,
            onSourceChange = onSourceChange,
            onSortChange = onSortChange,
            onClear = onClearFilters,
        )
    }
}

@Composable
private fun JobsHeader(state: JobsUiState, onQueryChange: (String) -> Unit) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = spacing.gutter, vertical = spacing.sm),
    ) {
        Text(
            text = "Find jobs",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(2.dp))
        // Three distinct counts, never blended: how many postings are loaded, how many pass the
        // current filters, and how many are open to a Madagascar-based applicant. The old single
        // line called every loaded posting "open to international applicants", which overstated it.
        Text(
            text = buildString {
                append("${state.visibleJobs.size} of ${state.jobs.size} postings shown")
                if (state.hasActiveFilters) append(" (filters active)")
                append(" · ${state.eligibleCount} open to Madagascar")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
        Spacer(Modifier.height(spacing.md))
        SearchField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = "Search role, company or country",
        )
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .androidClickable { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Clear search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * The incremental-discovery banner. It appears only when the ledger has genuinely new postings,
 * names how many were found and when, offers a one-tap filter to them, and a way to acknowledge
 * them so the badge clears. A failed or empty read shows nothing here rather than an empty box.
 */
@Composable
private fun NewPostingsBanner(state: JobsUiState, onMarkReviewed: () -> Unit) {
    if (state.newCount == 0) return
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.gutter)
            .padding(bottom = spacing.sm)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), RoundedCornerShape(16.dp))
            .padding(horizontal = spacing.sm, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Icon(
            imageVector = Icons.Filled.NewReleases,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = if (state.newOnly) {
                "Showing ${state.newCount} new ${if (state.newCount == 1) "posting" else "postings"} since your last review."
            } else {
                "${state.newCount} new ${if (state.newCount == 1) "posting" else "postings"} found since your last review."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (state.newOnly) {
            Text(
                text = "Mark reviewed",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onMarkReviewed)
                    .padding(horizontal = spacing.xs, vertical = spacing.xxs),
            )
        }
    }
}

@Composable
private fun JobsFilterRow(
    state: JobsUiState,
    onToggleNew: () -> Unit,
    onToggleSaved: () -> Unit,
    onToggleEligible: () -> Unit,
    onToggleSponsored: () -> Unit,
    onToggleVisaDocumented: () -> Unit,
    onToggleTradeOnly: () -> Unit,
    onToggleStrongInternationalFit: () -> Unit,
    onToggleVerifiedVisa: () -> Unit,
    onToggleVerifiedAccommodation: () -> Unit,
    onToggleVerifiedFlights: () -> Unit,
    onToggleOpenVacancy: () -> Unit,
    onToggleRecentlyVerified: () -> Unit,
    onMinExpatScoreChange: (Int) -> Unit,
) {
    val spacing = LocalSpacing.current
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = spacing.sm),
        contentPadding = PaddingValues(horizontal = spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.newCount > 0) {
            item {
                FilterChip(
                    label = "New (${state.newCount})",
                    selected = state.newOnly,
                    onClick = onToggleNew,
                    leadingIcon = Icons.Filled.NewReleases,
                )
            }
        }
        item {
            FilterChip(
                label = "My trade only",
                selected = state.tradeOnly,
                onClick = onToggleTradeOnly,
                leadingIcon = Icons.Filled.Construction,
            )
        }
        item {
            FilterChip(
                label = "Open now",
                selected = state.openVacancyOnly,
                onClick = onToggleOpenVacancy,
                leadingIcon = Icons.Filled.Verified,
            )
        }
        item {
            FilterChip(
                label = "Visa verified",
                selected = state.verifiedVisaOnly,
                onClick = onToggleVerifiedVisa,
                leadingIcon = Icons.Filled.VerifiedUser,
            )
        }
        item {
            FilterChip(
                label = "Housing verified",
                selected = state.verifiedAccommodationOnly,
                onClick = onToggleVerifiedAccommodation,
                leadingIcon = Icons.Filled.CardGiftcard,
            )
        }
        item {
            FilterChip(
                label = "Flights verified",
                selected = state.verifiedFlightsOnly,
                onClick = onToggleVerifiedFlights,
                leadingIcon = Icons.Filled.CardGiftcard,
            )
        }
        item {
            FilterChip(
                label = "Recently checked",
                selected = state.recentlyVerifiedOnly,
                onClick = onToggleRecentlyVerified,
                leadingIcon = Icons.Filled.Refresh,
            )
        }
        item {
            FilterChip(
                label = "Strong fit",
                selected = state.strongInternationalFitOnly,
                onClick = onToggleStrongInternationalFit,
                leadingIcon = Icons.Filled.Public,
            )
        }
        item {
            FilterChip(
                label = "Madagascar eligible",
                selected = state.eligibleOnly,
                onClick = onToggleEligible,
                leadingIcon = Icons.Filled.Public,
            )
        }
        item {
            FilterChip(
                label = "Visa sponsored",
                selected = state.sponsoredOnly,
                onClick = onToggleSponsored,
                leadingIcon = Icons.Filled.VerifiedUser,
            )
        }
        item {
            FilterChip(
                label = "Visa documented",
                selected = state.visaDocumentedOnly,
                onClick = onToggleVisaDocumented,
                leadingIcon = Icons.Filled.Description,
            )
        }
        item {
            FilterChip(
                label = "Benefits 60%+",
                selected = state.minExpatScore >= 60,
                onClick = { onMinExpatScoreChange(if (state.minExpatScore >= 60) 0 else 60) },
                leadingIcon = Icons.Filled.CardGiftcard,
            )
        }
        item {
            FilterChip(
                label = "Saved",
                selected = state.savedOnly,
                onClick = onToggleSaved,
                leadingIcon = Icons.Filled.Bookmark,
            )
        }
    }
}

/** Ripple-free click target for the inline clear button. */
@Composable
private fun Modifier.androidClickable(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/**
 * Live sync strip. Shows which sources answered, how many postings are eligible, and
 * the way into the full filter sheet. A failed source is stated plainly rather than
 * hidden, because "0 jobs" and "source down" are very different situations.
 */
@Composable
private fun SourceStatusStrip(
    syncState: JobSyncState,
    coverage: SearchCoverage,
    eligibleCount: Int,
    totalCount: Int,
    newCount: Int = 0,
    onRefresh: () -> Unit,
    onOpenFilters: () -> Unit,
    filtersActive: Boolean,
) {
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.gutter, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            if (syncState.isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Checking job sources…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Icon(
                    imageVector = if (syncState.errorCount > 0) Icons.Filled.CloudOff else Icons.Filled.CloudDone,
                    contentDescription = null,
                    tint = if (syncState.errorCount > 0) extendedColors.warning else extendedColors.success,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = sourceSummary(syncState, totalCount, eligibleCount) +
                        if (newCount > 0) " · $newCount new" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconChipButton(
                icon = Icons.Filled.Tune,
                onClick = onOpenFilters,
                contentDescription = "Filters",
                tint = if (filtersActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconChipButton(
                icon = Icons.Filled.Refresh,
                onClick = onRefresh,
                contentDescription = "Refresh jobs",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!syncState.isRefreshing && coverage.totalRetrieved > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.gutter),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Icon(
                    imageVector = if (coverage.hasTargetedSource) Icons.Filled.Search else Icons.Filled.Public,
                    contentDescription = null,
                    tint = if (coverage.hasTargetedSource) extendedColors.info else extendedColors.warning,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = coverage.summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun sourceSummary(syncState: JobSyncState, total: Int, eligible: Int): String {
    val ok = syncState.sources.filter { it.state == SourceState.SUCCESS }.map { it.name }
    val failed = syncState.sources.filter { it.state == SourceState.ERROR }.map { it.name }
    return buildString {
        append("$total live postings · $eligible open to Madagascar")
        if (ok.isNotEmpty()) append(" · from ${ok.joinToString(", ")}")
        if (failed.isNotEmpty()) append(" · ${failed.joinToString(", ")} unavailable")
        syncState.lastSyncedAtEpochMillis?.let { append(" · last search ${relativeTime(it)}") }
    }
}

/** Bottom sheet listing every point of the match score and the eligibility verdict. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobExplanationSheet(
    job: Job,
    expat: ExpatBenefitsAssessment,
    fit: InternationalFitAssessment,
    onDismiss: () -> Unit,
    onOpenSource: () -> Unit,
    onWriteLetter: () -> Unit,
    /** When the app first saw this posting, or null when no first sighting is recorded. */
    discoveredAtEpochMillis: Long? = null,
) {
    val spacing = LocalSpacing.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.gutter)
                .padding(bottom = spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = job.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${job.company} · ${locationLabelForSheet(job)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                MatchChip(job.matchScore)
                Pill(
                    text = job.source.ifBlank { "Unknown source" },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            InternationalFitSection(fit)

            OfferVerificationSection(job = job)

            if (discoveredAtEpochMillis != null) {
                DiscoveryProvenanceSection(
                    discoveredAtEpochMillis = discoveredAtEpochMillis,
                    job = job,
                )
            }

            SectionHeader(
                title = "Why this match score",
                subtitle = "Every point of the ${job.matchScore}% comes from one of these signals",
            )
            job.matchReasons.forEach { reason -> MatchReasonRow(reason) }

            SectionHeader(
                title = "Can I apply from Madagascar?",
                subtitle = "Official statements from the posting come first",
            )
            job.eligibilityReasons.forEach { reason -> EligibilityReasonRow(reason) }

            EvidenceSection(job = job)

            RequirementsSection(job = job)

            ExpatBenefitsSection(expat)

            Spacer(Modifier.height(spacing.xs))
            PrimaryButton(
                text = "Write a cover letter",
                onClick = onWriteLetter,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.Filled.AutoAwesome,
            )
            SecondaryButton(
                text = "Open official posting",
                onClick = onOpenSource,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.AutoMirrored.Filled.OpenInNew,
            )
            Text(
                text = "Always confirm the details on the employer's own page before you apply.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The incremental-discovery provenance block: when this app first saw the posting, which source
 * supplied it, when it was last fetched, and a way to open the employer's own page. Each line is
 * a recorded fact, so the user can trace a "new" find back to its origin instead of trusting it.
 */
@Composable
private fun DiscoveryProvenanceSection(discoveredAtEpochMillis: Long, job: Job) {
    val spacing = LocalSpacing.current
    SectionHeader(
        title = "How this was found",
        subtitle = "Discovered by the incremental search, from a source you can check",
    )
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        DiscoveryFactRow(
            icon = Icons.Filled.NewReleases,
            label = "First seen",
            value = relativeTime(discoveredAtEpochMillis),
        )
        DiscoveryFactRow(
            icon = Icons.Filled.Public,
            label = "Source",
            value = job.source.ifBlank { "Unknown source" },
        )
        DiscoveryFactRow(
            icon = Icons.Filled.Refresh,
            label = "Last checked",
            value = retrievedLabel(job.retrievedAtEpochMillis),
        )
        if (job.sourceUrl.isNotBlank()) {
            DiscoveryFactRow(
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                label = "Official posting",
                value = job.sourceUrl,
            )
        }
    }
}

@Composable
private fun DiscoveryFactRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The verification read as its own section: is the vacancy still taking applicants, has the
 * employer's stated deadline passed, does the link work, and are the visa / relocation claims
 * documented. Each row is backed by a recorded fact, so a claim can always be traced back to
 * the employer's own words.
 */
@Composable
private fun OfferVerificationSection(job: Job) {
    val spacing = LocalSpacing.current
    val verification = job.verification

    SectionHeader(
        title = "Verified before you apply",
        subtitle = "What the app could confirm about this posting, and what it could not",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        OfferVerificationBadge(verification = verification)
        if (verification.freshness == com.aiglobal.jobfinder.data.model.VerificationState.CONFIRMED) {
            VerificationAxisChip("Freshness", verification.freshness)
        }
    }

    verification.facts.forEach { fact ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Icon(
                imageVector = when (fact.strength) {
                    EvidenceStrength.DOCUMENTED -> Icons.Filled.Verified
                    EvidenceStrength.REFUSED -> Icons.Filled.Block
                    EvidenceStrength.NEEDS_VERIFICATION -> Icons.Filled.HelpCenter
                    EvidenceStrength.INFERRED -> Icons.Filled.Insights
                    EvidenceStrength.UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline
                },
                contentDescription = null,
                tint = verificationStateColor(fact.strength.toVerificationState()),
                modifier = Modifier.size(16.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fact.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = fact.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Maps an evidence strength onto the shared verification colour scale. */
private fun EvidenceStrength.toVerificationState(): com.aiglobal.jobfinder.data.model.VerificationState =
    when (this) {
        EvidenceStrength.DOCUMENTED -> com.aiglobal.jobfinder.data.model.VerificationState.CONFIRMED
        EvidenceStrength.REFUSED -> com.aiglobal.jobfinder.data.model.VerificationState.NOT_OFFERED
        EvidenceStrength.NEEDS_VERIFICATION -> com.aiglobal.jobfinder.data.model.VerificationState.POSSIBLE
        EvidenceStrength.INFERRED -> com.aiglobal.jobfinder.data.model.VerificationState.POSSIBLE
        EvidenceStrength.UNKNOWN -> com.aiglobal.jobfinder.data.model.VerificationState.UNKNOWN
    }

/**
 * The International Fit block for the detail sheet. It shows the one number that folds the
 * destination, the route in from Madagascar, the language and the credential facts together,
 * lists every component behind it, then the facts that stand in the way. The documented Expat
 * Benefits Score stays a separate number beside it, never blended in.
 */
@Composable
private fun InternationalFitSection(fit: InternationalFitAssessment) {
    val spacing = LocalSpacing.current
    SectionHeader(
        title = "Your international fit",
        subtitle = "Your trade, skills, route and destination, scored together from the posting",
    )

    if (fit.isInsufficient) {
        Text(
            text = fit.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        InternationalFitBadge(assessment = fit)
        fit.expatBenefitsScore?.let { score ->
            ExpatScoreBadge(
                score = score,
                evidenceCoverage = fit.expatEvidenceCoverage,
                lowEvidence = fit.expatLowEvidence,
            )
        }
    }

    Text(
        text = fit.routeSummary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
    )

    fit.components.forEach { component -> InternationalFitComponentRow(component) }

    if (fit.blockers.isNotEmpty()) {
        SectionHeader(
            title = "Before you apply",
            subtitle = "Facts from the posting that stand in the way",
        )
        fit.blockers.forEach { blocker ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Icon(
                    imageVector = Icons.Filled.PriorityHigh,
                    contentDescription = null,
                    tint = extendedColors.warning,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = blocker,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Text(
        text = fit.explanation,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One ingredient of the International Fit Score: what it earned, and how it was read. */
@Composable
private fun InternationalFitComponentRow(component: InternationalFitComponent) {
    val color = when (component.strength) {
        EvidenceStrength.DOCUMENTED -> extendedColors.success
        EvidenceStrength.REFUSED -> extendedColors.danger
        EvidenceStrength.INFERRED -> extendedColors.info
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
    ) {
        Pill(
            text = "${component.points}/${component.maxPoints}",
            containerColor = color.copy(alpha = 0.14f),
            contentColor = color,
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = component.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Pill(
                    text = component.strength.label,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = component.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MatchReasonRow(reason: MatchReason) {
    val color = when (reason.signal) {
        MatchSignal.POSITIVE -> extendedColors.success
        MatchSignal.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        MatchSignal.NEGATIVE -> extendedColors.warning
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
    ) {
        Pill(
            text = "${reason.weight} pts",
            containerColor = color.copy(alpha = 0.14f),
            contentColor = color,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = reason.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = reason.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EligibilityReasonRow(reason: EligibilityReason) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
    ) {
        Icon(
            imageVector = if (reason.official) Icons.Filled.Verified else Icons.AutoMirrored.Filled.HelpOutline,
            contentDescription = null,
            tint = if (reason.official) extendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (reason.official) reason.label else "${reason.label} (not confirmed)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = reason.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The typed evidence list: every claim the app makes about this posting, each with its
 * strength and, when it is documented, the employer's own sentence and a link back.
 * Unknown items are shown rather than hidden so the user sees what the posting does not say.
 */
@Composable
private fun EvidenceSection(job: Job) {
    if (job.evidence.isEmpty()) return
    val spacing = LocalSpacing.current
    SectionHeader(
        title = "Evidence",
        subtitle = "What the posting states, what is only inferred, and what it leaves out",
    )
    job.evidence.forEach { item ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Icon(
                imageVector = when (item.strength) {
                    EvidenceStrength.DOCUMENTED -> Icons.Filled.Verified
                    EvidenceStrength.REFUSED -> Icons.Filled.Block
                    EvidenceStrength.INFERRED -> Icons.Filled.Insights
                    EvidenceStrength.NEEDS_VERIFICATION -> Icons.AutoMirrored.Filled.HelpOutline
                    EvidenceStrength.UNKNOWN -> Icons.Filled.HelpCenter
                },
                contentDescription = null,
                tint = when (item.strength) {
                    EvidenceStrength.DOCUMENTED -> extendedColors.success
                    EvidenceStrength.REFUSED -> extendedColors.danger
                    EvidenceStrength.INFERRED -> extendedColors.info
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(16.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Pill(
                        text = item.strength.label,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The posting's stated requirements paired with whether the CV appears to meet them.
 * A "not confirmed" state is honest about the limit of what the app can read from a CV;
 * it never claims the candidate is qualified or unqualified when it cannot tell.
 */
@Composable
private fun RequirementsSection(job: Job) {
    val requirements = job.requirements
    val gaps = job.qualificationGaps
    if (requirements.isEmpty && gaps.isEmpty()) return
    val spacing = LocalSpacing.current

    SectionHeader(
        title = "What this role asks for",
        subtitle = "Read from the posting and checked against your CV where possible",
    )
    requirements.minYears?.let { years ->
        Text(
            text = "About $years+ years of experience requested.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    (requirements.certifications + requirements.licences).forEach { requirement ->
        Column {
            Text(
                text = requirement.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = requirement.evidence,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    gaps.forEach { gap ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Icon(
                imageVector = when (gap.met) {
                    true -> Icons.Filled.CheckCircle
                    false -> Icons.Filled.Cancel
                    null -> Icons.AutoMirrored.Filled.HelpOutline
                },
                contentDescription = null,
                tint = when (gap.met) {
                    true -> extendedColors.success
                    false -> extendedColors.warning
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(16.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = gap.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = when (gap.met) {
                        true -> "Your CV appears to cover this."
                        false -> "Your CV does not show this yet."
                        null -> "The app cannot confirm this from your CV — check it yourself."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Expat benefits block for the detail sheet. It states the Expat Benefits Score as its
 * own number (never merged with the CV match), then lists every benefit the user cares
 * about with the status and the exact wording it came from. Benefits the posting is
 * silent about are shown as "Not stated", never as a refusal.
 */
@Composable
private fun ExpatBenefitsSection(expat: ExpatBenefitsAssessment) {
    val spacing = LocalSpacing.current
    SectionHeader(
        title = "Expat benefits",
        subtitle = "Scored separately from your CV match, from what the posting documents",
    )

    if (!expat.hasEvidence) {
        Text(
            text = "This posting does not describe its expat benefits, so there is nothing to score yet. " +
                "Check the employer's own page before you rely on any package.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        ExpatScoreBadge(
            score = expat.score,
            evidenceCoverage = expat.evidenceCoverage,
            lowEvidence = expat.lowEvidence,
        )
        Pill(
            text = "${expat.evidenceCoverage}% documented",
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        text = expat.explanation,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (expat.unmetEssentials.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Icon(
                Icons.Filled.PriorityHigh,
                contentDescription = null,
                tint = extendedColors.warning,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "You marked ${expat.unmetEssentials.joinToString(", ") { it.shortLabel }} as essential, " +
                    "but this posting does not document ${if (expat.unmetEssentials.size == 1) "it" else "them"}. " +
                    "Ask the employer before you count on it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    expat.components.forEach { component -> BenefitEvidenceRow(component) }
}

@Composable
private fun BenefitEvidenceRow(component: BenefitScoreComponent) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(text = component.type.emoji, style = MaterialTheme.typography.bodyMedium)
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = component.type.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                BenefitStatusChip(component.status)
            }
            if (component.status == BenefitStatus.CONFIRMED ||
                component.status == BenefitStatus.NEEDS_VERIFICATION
            ) {
                Text(
                    text = "“${component.evidence}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (component.priority.weight > 0) {
                Text(
                    text = "You rated this ${component.priority.label.lowercase()} · " +
                        "${component.earnedPoints}/${component.potentialPoints} pts",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Full filter sheet: country, source and sort order for the loaded postings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobFilterSheet(
    state: JobsUiState,
    onDismiss: () -> Unit,
    onCountryChange: (String?) -> Unit,
    onSourceChange: (String?) -> Unit,
    onSortChange: (JobSort) -> Unit,
    onClear: () -> Unit,
) {
    val spacing = LocalSpacing.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.gutter)
                .padding(bottom = spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = "Filter opportunities",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Filters apply to the ${state.jobs.size} postings already loaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader(title = "Sort by")
            FlowChips {
                JobSort.entries.forEach { sort ->
                    FilterChip(
                        label = sort.label,
                        selected = state.sort == sort,
                        onClick = { onSortChange(sort) },
                    )
                }
            }

            SectionHeader(title = "Source")
            FlowChips {
                FilterChip(
                    label = "All sources",
                    selected = state.source == null,
                    onClick = { onSourceChange(null) },
                )
                state.sources.forEach { (id, name) ->
                    FilterChip(
                        label = name,
                        selected = state.source == id,
                        onClick = { onSourceChange(id) },
                    )
                }
            }

            SectionHeader(
                title = "Country",
                subtitle = "Countries present in the current results",
            )
            FlowChips {
                FilterChip(
                    label = "Any country",
                    selected = state.country == null,
                    onClick = { onCountryChange(null) },
                )
                state.countries.forEach { country ->
                    FilterChip(
                        label = country,
                        selected = state.country == country,
                        onClick = { onCountryChange(country) },
                    )
                }
            }

            Spacer(Modifier.height(spacing.xs))
            SecondaryButton(
                text = "Clear all filters",
                onClick = onClear,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.Filled.FilterAltOff,
            )
        }
    }
}

/** Simple wrapping row for chips; avoids a lazy list inside a bottom sheet. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowChips(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
    ) {
        content()
    }
}

private fun locationLabelForSheet(job: Job): String =
    if (job.city.isBlank() || job.city.equals(job.country, ignoreCase = true)) job.country
    else "${job.city}, ${job.country}"

