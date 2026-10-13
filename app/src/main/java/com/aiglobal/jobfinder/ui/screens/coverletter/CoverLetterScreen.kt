package com.aiglobal.jobfinder.ui.screens.coverletter

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiglobal.jobfinder.data.model.CoverLetter
import com.aiglobal.jobfinder.data.model.LetterLanguage
import com.aiglobal.jobfinder.ui.components.AppCard
import com.aiglobal.jobfinder.ui.components.EmptyState
import com.aiglobal.jobfinder.ui.components.IconChipButton
import com.aiglobal.jobfinder.ui.components.Pill
import com.aiglobal.jobfinder.ui.components.PrimaryButton
import com.aiglobal.jobfinder.ui.components.SecondaryButton
import com.aiglobal.jobfinder.ui.components.SectionHeader
import com.aiglobal.jobfinder.ui.components.relativeTime
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors
import com.aiglobal.jobfinder.viewmodel.CoverLetterUiState
import com.aiglobal.jobfinder.viewmodel.CoverLetterViewModel

/**
 * The cover-letter workspace. The letter is written from the candidate's own CV, and
 * the panel below it shows exactly which facts were used so nothing looks invented.
 */
@Composable
fun CoverLetterScreen(
    viewModel: CoverLetterViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CoverLetterContent(
        state = state,
        onBack = onBack,
        onSelectLanguage = viewModel::selectLanguage,
        onGenerate = viewModel::generate,
    )
}

/** Stateless body, so layout can be verified without a live pipeline. */
@Composable
internal fun CoverLetterContent(
    state: CoverLetterUiState,
    onBack: () -> Unit,
    onSelectLanguage: (LetterLanguage) -> Unit,
    onGenerate: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val clipboard = LocalClipboardManager.current

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
                    text = "Cover letter",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = state.job?.title ?: "Tailored letter",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
            state.message?.let { message ->
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(extendedColors.warningContainer)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.WarningAmber,
                            contentDescription = null,
                            tint = extendedColors.warning,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            item { LanguageCard(selected = state.language, onSelect = onSelectLanguage) }

            item {
                PrimaryButton(
                    text = if (state.letter == null) "Generate cover letter" else "Generate again",
                    onClick = onGenerate,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = Icons.Filled.AutoAwesome,
                    loading = state.isGenerating,
                    enabled = state.job != null,
                )
            }

            val letter = state.letter
            if (letter != null) {
                item { LetterPreviewCard(letter) }
                item {
                    SecondaryButton(
                        text = "Copy to clipboard",
                        onClick = { clipboard.setText(AnnotatedString(letter.asPlainText())) },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = Icons.Filled.ContentCopy,
                    )
                }
                item { GroundingCard(letter) }
            } else if (!state.isGenerating) {
                item {
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        EmptyState(
                            emoji = "✍️",
                            title = "No letter yet",
                            message = if (state.hasCv) {
                                "Generate a letter tailored to this role and language."
                            } else {
                                "Import and analyse your CV first, so the letter can use your real details."
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageCard(selected: LetterLanguage, onSelect: (LetterLanguage) -> Unit) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Language",
            subtitle = "Picked for the employer's market — change it if you prefer",
        )
        Spacer(Modifier.height(spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            LetterLanguage.entries.forEach { language ->
                LanguageChip(
                    language = language,
                    selected = language == selected,
                    onClick = { onSelect(language) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LanguageChip(
    language: LetterLanguage,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else extendedColors.cardStroke,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = language.flag, style = MaterialTheme.typography.titleMedium)
        Text(
            text = language.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LetterPreviewCard(letter: CoverLetter) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = letter.language.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${letter.wordCount} words · generated ${relativeTime(letter.generatedAtEpochMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (letter.edited) {
                Pill(
                    text = "Edited",
                    containerColor = extendedColors.infoContainer,
                    contentColor = extendedColors.info,
                )
            }
        }

        Spacer(Modifier.height(spacing.md))
        Text(
            text = letter.subject,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            text = letter.salutation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        letter.paragraphs.forEach { paragraph ->
            Spacer(Modifier.height(spacing.xs))
            Text(
                text = paragraph,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(spacing.sm))
        Text(
            text = letter.closing,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = letter.signature,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun GroundingCard(letter: CoverLetter) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Built from your CV",
            subtitle = "Facts this letter used, and what it could not find",
        )
        Spacer(Modifier.height(spacing.sm))
        if (letter.usedFacts.isNotEmpty()) {
            letter.usedFacts.forEach { fact -> FactRow(fact, positive = true) }
        }
        if (letter.missingFacts.isNotEmpty()) {
            Spacer(Modifier.height(spacing.xs))
            Text(
                text = "Missing — add these to your CV to strengthen the letter",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            letter.missingFacts.forEach { fact -> FactRow(fact, positive = false) }
        }
    }
}

@Composable
private fun FactRow(fact: String, positive: Boolean) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (positive) Icons.Filled.FactCheck else Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = if (positive) extendedColors.success else extendedColors.warning,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = fact,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
