package com.aiglobal.jobfinder.ui.screens.expat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiglobal.jobfinder.data.model.BenefitItem
import com.aiglobal.jobfinder.data.model.ExpatGuide
import com.aiglobal.jobfinder.ui.components.AppCard
import com.aiglobal.jobfinder.ui.components.EmptyState
import com.aiglobal.jobfinder.ui.components.IconChipButton
import com.aiglobal.jobfinder.ui.components.SectionHeader
import com.aiglobal.jobfinder.ui.components.SkeletonBox
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors
import com.aiglobal.jobfinder.viewmodel.ExpatGuideViewModel
import com.aiglobal.jobfinder.viewmodel.ExpatUiState

/**
 * The settlement guide for a destination. Every benefit cites an official source so the
 * user can verify it before relying on it.
 */
@Composable
fun ExpatScreen(
    viewModel: ExpatGuideViewModel,
    country: String,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(country) { viewModel.load(country) }
    ExpatContent(state = state, country = country, onBack = onBack)
}

/** Stateless body, so layout can be verified without a live pipeline. */
@Composable
internal fun ExpatContent(
    state: ExpatUiState,
    country: String,
    onBack: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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
                    text = "Settlement guide",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = country,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        val guide = state.guide
        if (guide == null) {
            if (state.isLoading) {
                Column(
                    modifier = Modifier.padding(horizontal = spacing.gutter),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    repeat(3) {
                        SkeletonBox(modifier = Modifier.fillMaxWidth().height(120.dp))
                    }
                }
            } else {
                EmptyState(
                    emoji = "🌍",
                    title = "No guide for $country yet",
                    message = "We only publish guides backed by official sources. Check back as coverage grows.",
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = spacing.gutter,
                end = spacing.gutter,
                top = spacing.xxs,
                bottom = spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item { SummaryCard(guide) }
            guide.groupedBenefits.forEach { (category, items) ->
                item(key = category.name) {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        SectionHeader(title = "${category.emoji} ${category.label}")
                        items.forEach { benefit -> BenefitCard(benefit) }
                    }
                }
            }
            if (guide.practicalNotes.isNotEmpty()) {
                item {
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = "Practical notes", subtitle = "Things people get wrong")
                        Spacer(Modifier.height(spacing.sm))
                        guide.practicalNotes.forEach { note ->
                            Row(
                                modifier = Modifier.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Icon(
                                    Icons.Filled.Info,
                                    contentDescription = null,
                                    tint = extendedColors.info,
                                    modifier = Modifier.size(15.dp).padding(top = 2.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(guide: ExpatGuide) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = guide.countryFlag, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(LocalSpacing.current.sm))
            Text(
                text = guide.country,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(LocalSpacing.current.xs))
        Text(
            text = guide.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BenefitCard(benefit: BenefitItem) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, extendedColors.cardStroke, RoundedCornerShape(18.dp))
            .padding(spacing.md),
    ) {
        Text(
            text = benefit.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = benefit.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(spacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Verified,
                contentDescription = null,
                tint = extendedColors.success,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Source: ${benefit.sourceName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
