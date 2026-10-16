package com.aiglobal.jobfinder.ui.screens.cv

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.UploadFile
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiglobal.jobfinder.data.model.CandidateField
import com.aiglobal.jobfinder.data.model.CertificationEntry
import com.aiglobal.jobfinder.data.model.CvAiStatus
import com.aiglobal.jobfinder.data.model.CvRecord
import com.aiglobal.jobfinder.data.model.CvStatus
import com.aiglobal.jobfinder.data.model.EducationEntry
import com.aiglobal.jobfinder.data.model.LanguageSkill
import com.aiglobal.jobfinder.data.model.ProfileCompleteness
import com.aiglobal.jobfinder.ui.components.AppCard
import com.aiglobal.jobfinder.ui.components.EmptyState
import com.aiglobal.jobfinder.ui.components.ErrorState
import com.aiglobal.jobfinder.ui.components.Pill
import com.aiglobal.jobfinder.ui.components.PrimaryButton
import com.aiglobal.jobfinder.ui.components.ProgressBar
import com.aiglobal.jobfinder.ui.components.SecondaryButton
import com.aiglobal.jobfinder.ui.components.SectionHeader
import com.aiglobal.jobfinder.ui.components.SkeletonBox
import com.aiglobal.jobfinder.ui.components.formatDateTime
import com.aiglobal.jobfinder.ui.components.formatFileSize
import com.aiglobal.jobfinder.ui.components.isDarkTheme
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors
import com.aiglobal.jobfinder.viewmodel.CvMessage
import com.aiglobal.jobfinder.viewmodel.CvUiState

/**
 * CV import and AI analysis. A single scrollable column keeps the flow readable at any
 * height: hero, action card, then the analysis result sections.
 */
@Composable
fun CvScreen(
    state: CvUiState,
    onBack: () -> Unit,
    onPickFile: () -> Unit,
    onAnalyze: () -> Unit,
    onRemove: () -> Unit,
    onEditField: (CandidateField, String) -> Unit,
    onDismissMessage: () -> Unit,
) {
    val spacing = LocalSpacing.current

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = spacing.gutter,
            end = spacing.gutter,
            top = spacing.sm,
            bottom = spacing.navBarClearance,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            Column(modifier = Modifier.statusBarsPadding().padding(top = spacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { onBack() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(spacing.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AI CV Analysis",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Import your CV and let the assistant read it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (state.isLoading) {
            item { CvSkeleton() }
            return@LazyColumn
        }

        item {
            AnalysisHeroCard(
                isBusy = state.isBusy,
                busyLabel = state.busyLabel,
                isAnalyzed = state.isAnalyzed,
                isPartial = state.isPartial,
                onDevice = state.analysisStaysOnDevice,
                aiStatus = state.record?.aiStatus ?: CvAiStatus.NONE,
            )
        }

        state.message?.let { message ->
            item { MessageCard(message = message, onDismiss = onDismissMessage) }
        }

        item {
            ImportCard(
                record = state.record,
                isBusy = state.isBusy,
                onPickFile = onPickFile,
                onAnalyze = onAnalyze,
                onRemove = onRemove,
            )
        }

        val record = state.record
        when {
            record == null -> item {
                EmptyState(
                    title = "No CV imported yet",
                    message = "Add a PDF, DOC, DOCX or TXT file. Everything is analysed on your " +
                        "device and never uploaded.",
                    emoji = "📄",
                )
            }

            record.status == CvStatus.FAILED -> item {
                ErrorState(
                    title = "We could not read this CV",
                    message = record.warnings.firstOrNull()
                        ?: "Try exporting your CV as a PDF with a text layer, or paste it as a .txt file.",
                    onRetry = onPickFile,
                )
            }

            record.status == CvStatus.IMPORTED -> item {
                WaitingCard(onAnalyze = onAnalyze)
            }

            else -> {
                // Only a fragment came out: say so before showing a thin profile, so the user
                // never mistakes a partly-read document for a fully analysed one.
                if (record.status == CvStatus.PARTIAL) {
                    item { PartialReadBanner(record) }
                }
                item { SummaryCard(record, state.completeness) }
                item { AiAnalysisCard(record) }
                item { ContactCard(record, onEditField) }
                if (record.profile.skills.isNotEmpty()) item { SkillsCard(record.profile.skills) }
                if (record.profile.education.isNotEmpty()) item { EducationCard(record.profile.education) }
                if (record.profile.certifications.isNotEmpty()) item { CertificationsCard(record.profile.certifications) }
                if (record.profile.languages.isNotEmpty()) item { LanguagesCard(record.profile.languages) }
                if (record.warnings.isNotEmpty()) item { NotesCard(record.warnings) }
                if (record.extractedCharacters > 0 || record.pageCount != null) {
                    item { TechnicalDetailsCard(record) }
                }
            }
        }
    }
}

@Composable
private fun AnalysisHeroCard(
    isBusy: Boolean,
    busyLabel: String,
    isAnalyzed: Boolean,
    isPartial: Boolean,
    onDevice: Boolean,
    aiStatus: CvAiStatus,
) {
    val spacing = LocalSpacing.current
    val dark = isDarkTheme()
    val rotation by rememberInfiniteTransition(label = "cvSpin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "cvSpinValue",
    )
    val title = when {
        isBusy -> busyLabel.ifBlank { "Working…" }
        isAnalyzed -> "Analysis complete"
        isPartial -> "Analysis incomplete"
        else -> "Ready to analyse"
    }
    val aiUsed = aiStatus == CvAiStatus.SUCCESS
    val subtitle = when {
        isBusy -> "Reading your CV — this can take a moment"
        aiUsed -> "Read by the AI assistant and verified on your device"
        aiStatus.label.isNotBlank() -> aiStatus.label
        onDevice -> "Processed on your device — your CV is never uploaded"
        else -> "Analysed on your device unless you enable the AI assistant"
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
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp).rotate(if (isBusy) rotation else 0f),
                )
            }
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                )
            }
        }
        if (isBusy) {
            val progress by rememberInfiniteTransition(label = "cvProgress").animateFloat(
                initialValue = 0.08f,
                targetValue = 0.92f,
                animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
                label = "cvProgressValue",
            )
            ProgressBar(
                fraction = progress,
                color = MaterialTheme.colorScheme.secondary,
                trackColor = Color.White.copy(alpha = 0.18f),
                height = 6.dp,
            )
        }
    }
}

@Composable
private fun ImportCard(
    record: CvRecord?,
    isBusy: Boolean,
    onPickFile: () -> Unit,
    onAnalyze: () -> Unit,
    onRemove: () -> Unit,
) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record?.fileName ?: "Import your CV",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (record == null) {
                            "PDF, DOC, DOCX or TXT · up to 15 MB"
                        } else {
                            "${record.fileType.label} · ${formatFileSize(record.sizeBytes)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (record != null) {
                    val (pillContainer, pillContent) = when (record.status) {
                        CvStatus.FAILED -> extendedColors.dangerContainer to extendedColors.danger
                        CvStatus.PARTIAL -> extendedColors.warningContainer to extendedColors.warning
                        else -> extendedColors.successContainer to extendedColors.success
                    }
                    Pill(
                        text = record.status.label,
                        containerColor = pillContainer,
                        contentColor = pillContent,
                    )
                }
            }

            PrimaryButton(
                text = if (record == null) "Choose a file" else "Replace CV",
                onClick = onPickFile,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.Filled.UploadFile,
            )

            if (record != null && !isBusy) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    SecondaryButton(
                        text = if (record.status == CvStatus.ANALYZED) "Re-analyse" else "Analyse CV",
                        onClick = onAnalyze,
                        modifier = Modifier.weight(1f),
                        leadingIcon = Icons.Filled.Refresh,
                    )
                    SecondaryButton(
                        text = "Remove",
                        onClick = onRemove,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun WaitingCard(onAnalyze: () -> Unit) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                text = "Your CV is ready to be analysed",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "The assistant reads your skills, experience and education, then builds a " +
                    "matching-ready profile. You can correct anything it gets wrong.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton(
                text = "Analyse my CV",
                onClick = onAnalyze,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.Filled.AutoAwesome,
            )
        }
    }
}

@Composable
private fun SummaryCard(record: CvRecord, completeness: ProfileCompleteness) {
    val spacing = LocalSpacing.current
    val profile = record.profile
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = profile.fullName.ifBlank { "Candidate profile" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${completeness.percent}%",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (profile.currentTitle.isNotBlank()) {
                Text(
                    text = profile.currentTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ProgressBar(fraction = completeness.percent / 100f)

            if (completeness.missing.isNotEmpty()) {
                Text(
                    text = "Still missing: " + completeness.missing.take(4).joinToString(", ") { it.label },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = extendedColors.success,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Your profile is complete and ready for matching.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            record.engineLabel?.let { engine ->
                Text(
                    text = "Analysed ${record.analyzedAtEpochMillis?.let { formatDateTime(it) } ?: "just now"} · $engine",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AiAnalysisCard(record: CvRecord) {
    val status = record.aiStatus
    // Only two of the three-empty states are actionable from here: the assistant is off, or
    // no key is saved. A transient failure is informational.
    val (accent, container, icon) = when (status) {
        CvAiStatus.SUCCESS -> Triple(
            extendedColors.success,
            extendedColors.successContainer,
            Icons.Filled.AutoAwesome,
        )
        CvAiStatus.CONSENT_REQUIRED, CvAiStatus.NOT_CONFIGURED -> Triple(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            Icons.Filled.AutoAwesome,
        )
        else -> Triple(
            extendedColors.warning,
            extendedColors.warningContainer,
            Icons.Filled.ErrorOutline,
        )
    }
    val title = when (status) {
        CvAiStatus.SUCCESS -> "Read with the AI assistant"
        CvAiStatus.CONSENT_REQUIRED -> "The AI assistant is off"
        CvAiStatus.NOT_CONFIGURED -> "Add your NVIDIA key to use AI"
        CvAiStatus.FAILED -> "The AI could not be reached"
        CvAiStatus.NONE -> "Analysed on your device"
    }
    val body = when (status) {
        CvAiStatus.SUCCESS ->
            "Your CV was sent to the AI model only because you turned the assistant on. " +
                "The fields below are the model's reading, checked against an on-device pass. " +
                "Correct anything that is wrong — your edits always win."
        CvAiStatus.CONSENT_REQUIRED ->
            "This profile was built entirely on your device. Turn on the AI assistant in " +
                "Profile → AI assistant to read your CV with the model instead."
        CvAiStatus.NOT_CONFIGURED ->
            "This profile was built entirely on your device. Save an NVIDIA API key in " +
                "Profile → AI assistant to enable AI reading."
        CvAiStatus.FAILED ->
            "The model did not return a usable answer, so the on-device analysis is shown. " +
                "Check your connection and try again."
        CvAiStatus.NONE ->
            "Everything was processed on your device; your CV was never uploaded."
    }

    AppCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = container,
        borderColor = accent.copy(alpha = 0.3f),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ContactCard(record: CvRecord, onEditField: (CandidateField, String) -> Unit) {
    val profile = record.profile
    val rows = listOfNotNull(
        profile.fullName.takeIf { it.isNotBlank() }?.let { CandidateField.FULL_NAME to it },
        profile.email.takeIf { it.isNotBlank() }?.let { CandidateField.EMAIL to it },
        profile.phone.takeIf { it.isNotBlank() }?.let { CandidateField.PHONE to it },
        profile.location.takeIf { it.isNotBlank() }?.let { CandidateField.LOCATION to it },
        profile.currentTitle.takeIf { it.isNotBlank() }?.let { CandidateField.CURRENT_TITLE to it },
    )
    if (rows.isEmpty()) return

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Extracted details",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            rows.forEach { (field, value) ->
                ExtractedRow(
                    field = field,
                    value = value,
                    isUserEdited = record.userEditedFields.contains(field),
                    onEdit = onEditField,
                )
            }
        }
    }
}

@Composable
private fun SkillsCard(skills: List<String>) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            SectionHeader(
                title = "Skills detected",
                subtitle = "${skills.size} skills matched against our job vocabulary",
            )
            skills.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { skill ->
                        Pill(
                            text = skill,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun EducationCard(education: List<EducationEntry>) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                text = "Education",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            education.forEach { entry ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Filled.School,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(spacing.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.degree,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val detail = buildList {
                            if (entry.institution.isNotBlank()) add(entry.institution)
                            if (entry.year > 0) add(entry.year.toString())
                        }.joinToString(" · ")
                        if (detail.isNotBlank()) {
                            Text(
                                text = detail,
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

@Composable
private fun CertificationsCard(certifications: List<CertificationEntry>) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                text = "Certifications",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            certifications.forEach { entry ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .padding(top = 5.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.width(spacing.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (entry.year != null) {
                            Text(
                                text = entry.year.toString(),
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

@Composable
private fun LanguagesCard(languages: List<LanguageSkill>) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Translate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(spacing.sm))
                Text(
                    text = "Languages",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            languages.forEach { language ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = language.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Pill(
                        text = language.level?.label ?: "Level not stated",
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun NotesCard(warnings: List<String>) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = extendedColors.warning,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Worth checking",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            warnings.forEach { warning ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(extendedColors.warning),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Shown when the document yielded some text but not enough for a full analysis. It states
 * the honest reason first, so a thin profile is never read as a complete one.
 */
@Composable
private fun PartialReadBanner(record: CvRecord) {
    val spacing = LocalSpacing.current
    val characters = record.extractedCharacters
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = extendedColors.warningContainer,
        borderColor = extendedColors.warning.copy(alpha = 0.3f),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = extendedColors.warning,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Only part of this CV could be read",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = buildString {
                    append("$characters characters were recovered")
                    record.pageCount?.let { append(" from $it page${if (it == 1) "" else "s"}") }
                    append(". The missing parts are probably an image, so they cannot be read ")
                    append("as text. Add the fields by hand, or import a version with a ")
                    append("selectable text layer.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Page and character counts, so the user can see exactly how much was extracted. */
@Composable
private fun TechnicalDetailsCard(record: CvRecord) {
    val spacing = LocalSpacing.current
    val rows = buildList {
        record.pageCount?.let { add("Pages" to it.toString()) }
        add("Characters extracted" to record.extractedCharacters.toString())
        add("Format" to record.fileType.label)
        add("File size" to formatFileSize(record.sizeBytes))
        record.engineLabel?.let { add("Engine" to it) }
    }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Technical details",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            rows.forEach { (label, value) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageCard(message: CvMessage, onDismiss: () -> Unit) {
    val accent = if (message.isError) extendedColors.danger else extendedColors.success
    val container = if (message.isError) extendedColors.dangerContainer else extendedColors.successContainer
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = container,
        borderColor = accent.copy(alpha = 0.3f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (message.isError) Icons.Filled.ErrorOutline else Icons.Filled.Check,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Dismiss",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onDismiss() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * One extracted value with a visible provenance marker. The chip says whether the value
 * came from the CV or from a manual correction, and Edit flips it to a user value.
 */
@Composable
private fun ExtractedRow(
    field: CandidateField,
    value: String,
    isUserEdited: Boolean,
    onEdit: (CandidateField, String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(value) { mutableStateOf(value) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = field.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        if (editing) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.Check,
                contentDescription = "Save",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable {
                        onEdit(field, draft.trim())
                        editing = false
                    }
                    .padding(3.dp),
            )
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            ProvenanceChip(isUserEdited = isUserEdited)
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Edit",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { editing = true }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun ProvenanceChip(isUserEdited: Boolean) {
    val label = if (isUserEdited) "Your edit" else "From CV"
    val container = if (isUserEdited) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (isUserEdited) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Pill(text = label, containerColor = container, contentColor = content)
}

@Composable
private fun CvSkeleton() {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        SkeletonBox(Modifier.fillMaxWidth().height(120.dp), RoundedCornerShape(24.dp))
        SkeletonBox(Modifier.fillMaxWidth().height(130.dp), RoundedCornerShape(22.dp))
        SkeletonBox(Modifier.fillMaxWidth().height(180.dp), RoundedCornerShape(22.dp))
    }
}
