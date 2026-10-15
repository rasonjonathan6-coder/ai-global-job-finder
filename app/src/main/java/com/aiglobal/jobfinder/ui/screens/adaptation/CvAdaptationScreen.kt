package com.aiglobal.jobfinder.ui.screens.adaptation

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiglobal.jobfinder.data.cv.AdaptationConfidence
import com.aiglobal.jobfinder.data.cv.AdaptationSuggestion
import com.aiglobal.jobfinder.data.cv.CvAdaptation
import com.aiglobal.jobfinder.ui.components.EmptyState
import com.aiglobal.jobfinder.ui.components.IconChipButton
import com.aiglobal.jobfinder.ui.components.PrimaryButton
import com.aiglobal.jobfinder.ui.components.SectionHeader
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors
import com.aiglobal.jobfinder.viewmodel.CvAdaptationViewModel

/** Adapts the analysed CV to a chosen destination's conventions, with full reasoning. */
@Composable
fun CvAdaptationScreen(
    viewModel: CvAdaptationViewModel,
    onBack: () -> Unit,
    onOpenCv: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CvAdaptationContent(
        adaptation = state.adaptation,
        onBack = onBack,
        onOpenCv = onOpenCv,
    )
}

@Composable
internal fun CvAdaptationContent(
    adaptation: CvAdaptation,
    onBack: () -> Unit,
    onOpenCv: () -> Unit,
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
                    text = "CV adaptation",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (adaptation.country.isBlank()) {
                        "Target country"
                    } else {
                        "${adaptation.countryFlag} ${adaptation.country}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (adaptation.needsCv) {
            EmptyState(
                emoji = "📄",
                title = "No CV to adapt yet",
                message = "Import and analyse your CV first. The adaptation is built from your real " +
                    "skills, experience and languages — never invented.",
                action = {
                    PrimaryButton(
                        text = "Import my CV",
                        onClick = onOpenCv,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                modifier = Modifier.padding(top = spacing.xl),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = spacing.gutter,
                    end = spacing.gutter,
                    top = spacing.xxs,
                    bottom = spacing.navBarClearance,
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                item { ConventionCard(adaptation) }
                item {
                    SectionHeader(
                        title = "What to change",
                        subtitle = "${adaptation.documentedCount} grounded in your CV · " +
                            "${adaptation.missingCount} need your input",
                    )
                }
                items(adaptation.suggestions.size) { index ->
                    SuggestionCard(adaptation.suggestions[index])
                }
            }
        }
    }
}

@Composable
private fun ConventionCard(adaptation: CvAdaptation) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
            .border(1.dp, extendedColors.cardStroke, RoundedCornerShape(20.dp))
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        RoundedCornerShape(13.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "What ${adaptation.country} expects",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Working language: ${adaptation.language}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        RuleRow("Dates", adaptation.dateFormat)
        RuleRow("Length", adaptation.pageTarget)
        RuleRow(
            "Photo",
            if (adaptation.includesPhoto) "Conventional — include one if it flatters you"
            else "Normally omitted",
        )
    }
}

@Composable
private fun RuleRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SuggestionCard(suggestion: AdaptationSuggestion) {
    val spacing = LocalSpacing.current
    val (icon, tint) = when (suggestion.confidence) {
        AdaptationConfidence.DOCUMENTED -> Icons.Filled.CheckCircle to extendedColors.success
        AdaptationConfidence.INFERRED -> Icons.Filled.Lightbulb to extendedColors.info
        AdaptationConfidence.MISSING -> Icons.Filled.PriorityHigh to extendedColors.warning
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = suggestion.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = suggestion.confidence.label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
            )
        }
        Text(
            text = suggestion.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
