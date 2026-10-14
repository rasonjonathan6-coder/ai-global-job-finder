package com.aiglobal.jobfinder.ui.screens.interview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiglobal.jobfinder.data.model.AnswerConfidence
import com.aiglobal.jobfinder.data.model.InterviewCategory
import com.aiglobal.jobfinder.data.model.InterviewQuestion
import com.aiglobal.jobfinder.data.model.InterviewReadiness
import com.aiglobal.jobfinder.ui.components.AppCard
import com.aiglobal.jobfinder.ui.components.EmptyState
import com.aiglobal.jobfinder.ui.components.IconChipButton
import com.aiglobal.jobfinder.ui.components.Pill
import com.aiglobal.jobfinder.ui.components.PrimaryButton
import com.aiglobal.jobfinder.ui.components.ProgressBar
import com.aiglobal.jobfinder.ui.components.SecondaryButton
import com.aiglobal.jobfinder.ui.components.SectionHeader
import com.aiglobal.jobfinder.ui.components.SkeletonBox
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors
import com.aiglobal.jobfinder.viewmodel.InterviewUiState
import com.aiglobal.jobfinder.viewmodel.InterviewViewModel

/** The interview coach for one posting: a grounded rehearsal set with local feedback. */
@Composable
fun InterviewScreen(
    viewModel: InterviewViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    InterviewContent(
        state = state,
        onBack = onBack,
        onSelectQuestion = viewModel::selectQuestion,
        onAnswerChange = viewModel::onAnswerChange,
        onConfidenceChange = viewModel::onConfidenceChange,
        onSubmit = viewModel::submitAnswer,
        onReset = viewModel::resetProgress,
    )
}

/** Stateless body, so layout can be verified without a live pipeline. */
@Composable
internal fun InterviewContent(
    state: InterviewUiState,
    onBack: () -> Unit,
    onSelectQuestion: (String) -> Unit = {},
    onAnswerChange: (String) -> Unit = {},
    onConfidenceChange: (AnswerConfidence) -> Unit = {},
    onSubmit: () -> Unit = {},
    onReset: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(
            title = state.job?.title ?: "Interview coach",
            subtitle = state.job?.company ?: "Rehearsal",
            onBack = onBack,
        )

        when {
            state.isLoading -> LoadingBody()
            state.prep == null -> EmptyState(
                emoji = "🎤",
                title = "No interview plan yet",
                message = "This posting is no longer in your feed, so the coach has nothing to rehearse.",
                modifier = Modifier.padding(top = spacing.xxl),
            )
            else -> RehearsalBody(
                state = state,
                onSelectQuestion = onSelectQuestion,
                onAnswerChange = onAnswerChange,
                onConfidenceChange = onConfidenceChange,
                onSubmit = onSubmit,
                onReset = onReset,
            )
        }
    }
}

@Composable
private fun RehearsalBody(
    state: InterviewUiState,
    onSelectQuestion: (String) -> Unit,
    onAnswerChange: (String) -> Unit,
    onConfidenceChange: (AnswerConfidence) -> Unit,
    onSubmit: () -> Unit,
    onReset: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val prep = state.prep ?: return

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
        item { ReadinessCard(readiness = state.readiness, total = prep.questionCount) }

        if (prep.focusAreas.isNotEmpty()) {
            item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(
                        title = "Focus on this",
                        subtitle = "Priorities drawn from your CV and this posting",
                    )
                    Spacer(Modifier.height(spacing.sm))
                    prep.focusAreas.forEach { area -> FocusRow(area) }
                }
            }
        }

        item { SectionHeader(title = "Rehearsal questions", subtitle = "${prep.questionCount} questions, grounded in your CV") }

        prep.grouped().forEach { (category, questions) ->
            item(key = "header-${category.name}") {
                Text(
                    text = "${category.emoji} ${category.label}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }
            questions.forEach { question ->
                item(key = question.id) {
                    QuestionCard(
                        question = question,
                        expanded = state.selectedQuestionId == question.id,
                        answered = state.attempts.any { it.questionId == question.id },
                        draft = if (state.selectedQuestionId == question.id) state.draftAnswer else "",
                        feedback = if (state.selectedQuestionId == question.id) state.feedback else null,
                        confidence = state.confidence,
                        onToggle = { onSelectQuestion(if (state.selectedQuestionId == question.id) "" else question.id) },
                        onAnswerChange = onAnswerChange,
                        onConfidenceChange = onConfidenceChange,
                        onSubmit = onSubmit,
                    )
                }
            }
        }

        if (state.attempts.isNotEmpty()) {
            item {
                SecondaryButton(
                    text = "Reset rehearsal progress",
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = Icons.Filled.Refresh,
                )
            }
        }
    }
}

@Composable
private fun TopBar(title: String, subtitle: String, onBack: () -> Unit) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = spacing.gutter, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconChipButton(icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack, contentDescription = "Back")
        Spacer(Modifier.width(spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Interview coach",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LoadingBody() {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.padding(horizontal = spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        SkeletonBox(modifier = Modifier.fillMaxWidth().height(110.dp), RoundedCornerShape(22.dp))
        repeat(3) { SkeletonBox(modifier = Modifier.fillMaxWidth().height(88.dp), RoundedCornerShape(20.dp)) }
    }
}

@Composable
private fun ReadinessCard(readiness: InterviewReadiness?, total: Int) {
    val spacing = LocalSpacing.current
    val percent = readiness?.percent ?: 0
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = readiness?.label ?: "Not started",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${readiness?.attempted ?: 0} of $total rehearsed · ${readiness?.confident ?: 0} marked confident",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(spacing.sm))
        ProgressBar(fraction = percent / 100f, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun FocusRow(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.Lightbulb,
            contentDescription = null,
            tint = extendedColors.warning,
            modifier = Modifier.size(15.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QuestionCard(
    question: InterviewQuestion,
    expanded: Boolean,
    answered: Boolean,
    draft: String,
    feedback: com.aiglobal.jobfinder.data.model.AnswerFeedback?,
    confidence: AnswerConfidence,
    onToggle: () -> Unit,
    onAnswerChange: (String) -> Unit,
    onConfidenceChange: (AnswerConfidence) -> Unit,
    onSubmit: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, extendedColors.cardStroke, RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            .padding(spacing.md),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = question.prompt,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (answered) {
                Spacer(Modifier.width(spacing.xs))
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Rehearsed",
                    tint = extendedColors.success,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = question.whyItMatters,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(220)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            Column(modifier = Modifier.padding(top = spacing.sm)) {
                if (question.grounding.isNotEmpty()) {
                    Text(
                        text = "From your CV",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    question.grounding.forEach { fact ->
                        Text(
                            text = "• $fact",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(spacing.xs))
                }
                if (question.missingFacts.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Filled.WarningAmber,
                            contentDescription = null,
                            tint = extendedColors.warning,
                            modifier = Modifier.size(15.dp).padding(top = 2.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            question.missingFacts.forEach { fact ->
                                Text(
                                    text = fact,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = extendedColors.warning,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(spacing.xs))
                }

                Text(
                    text = "How to answer",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                question.structure.forEachIndexed { index, step ->
                    Text(
                        text = "${index + 1}. $step",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                Spacer(Modifier.height(spacing.sm))
                AnswerField(
                    value = draft,
                    onValueChange = onAnswerChange,
                )
                Spacer(Modifier.height(spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AnswerConfidence.entries.forEach { option ->
                        com.aiglobal.jobfinder.ui.components.FilterChip(
                            label = option.label,
                            selected = confidence == option,
                            onClick = { onConfidenceChange(option) },
                        )
                    }
                }
                Spacer(Modifier.height(spacing.xs))
                PrimaryButton(
                    text = "Get feedback",
                    onClick = onSubmit,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = Icons.Filled.AutoAwesome,
                )

                AnimatedVisibility(visible = feedback != null) {
                    if (feedback != null) {
                        Spacer(Modifier.height(spacing.sm))
                        FeedbackBlock(feedback)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnswerField(value: String, onValueChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    androidx.compose.foundation.text.BasicTextField(
        value = text,
        onValueChange = {
            text = it
            onValueChange(it)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(12.dp),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            Box {
                if (text.isBlank()) {
                    Text(
                        text = "Write your answer here, then get instant feedback…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            }
        },
    )
}

@Composable
private fun FeedbackBlock(feedback: com.aiglobal.jobfinder.data.model.AnswerFeedback) {
    val spacing = LocalSpacing.current
    val tone = when {
        feedback.score >= 75 -> extendedColors.success
        feedback.score >= 45 -> extendedColors.warning
        else -> extendedColors.danger
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tone.copy(alpha = 0.10f))
            .border(1.dp, tone.copy(alpha = 0.30f), RoundedCornerShape(16.dp))
            .padding(spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(
                text = "${feedback.score}/100",
                containerColor = tone.copy(alpha = 0.18f),
                contentColor = tone,
            )
            Spacer(Modifier.width(spacing.xs))
            Text(
                text = feedback.headline,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (feedback.strengths.isNotEmpty()) {
            Spacer(Modifier.height(spacing.xs))
            feedback.strengths.forEach { line ->
                Text(
                    text = "✓ $line",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (feedback.improvements.isNotEmpty()) {
            Spacer(Modifier.height(spacing.xs))
            feedback.improvements.forEach { line ->
                Text(
                    text = "→ $line",
                    style = MaterialTheme.typography.bodySmall,
                    color = tone,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
