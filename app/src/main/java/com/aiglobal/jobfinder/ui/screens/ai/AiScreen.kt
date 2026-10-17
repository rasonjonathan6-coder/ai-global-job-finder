package com.aiglobal.jobfinder.ui.screens.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiglobal.jobfinder.data.ai.AssistTask
import com.aiglobal.jobfinder.data.model.AiCapability
import com.aiglobal.jobfinder.data.model.AiCapabilityCatalog
import com.aiglobal.jobfinder.data.model.AiCapabilityKind
import com.aiglobal.jobfinder.ui.components.Pill
import com.aiglobal.jobfinder.ui.components.PrimaryButton
import com.aiglobal.jobfinder.ui.components.ProgressBar
import com.aiglobal.jobfinder.ui.components.SectionHeader
import com.aiglobal.jobfinder.ui.components.SecondaryButton
import com.aiglobal.jobfinder.ui.components.isDarkTheme
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors
import kotlinx.coroutines.delay

/**
 * Everything the AI workspace needs to describe the optional remote assistant honestly.
 * Defaults describe "not set up", so a caller that has not wired the ViewModel yet cannot
 * accidentally imply the model is on.
 */
data class AssistantState(
    val configured: Boolean = false,
    val consent: Boolean = false,
    val ready: Boolean = false,
    val modelLabel: String = "",
    val running: Boolean = false,
    val output: String = "",
    val outputTitle: String = "",
    val outputIsRemote: Boolean = false,
    val error: String = "",
)

/** The AI workspace. Stage 2 opens with the real CV pipeline as its first action. */
@Composable
fun AiScreen(
    onBrowseJobs: () -> Unit,
    onOpenCv: () -> Unit,
    onOpenInterview: () -> Unit = {},
    onOpenScamShield: () -> Unit = {},
    onOpenAlerts: () -> Unit = {},
    onOpenAutoApply: () -> Unit = {},
    onOpenShortlist: () -> Unit = {},
    onOpenAdaptation: () -> Unit = {},
    onOpenIntelligence: () -> Unit = {},
    onOpenRanking: () -> Unit = {},
    onOpenAssistant: () -> Unit = {},
    /** True once the CV has been analysed, so the headline card can tell the truth. */
    hasCv: Boolean = false,
    /** Skills extracted from the analysed CV, used in the analysis headline. */
    skillCount: Int = 0,
    /** How many postings currently match the profile, used in the analysis headline. */
    strongMatchCount: Int = 0,
    autoRun: Boolean = true,
    /** The optional remote assistant, described honestly by [AssistantState]. */
    assistant: AssistantState = AssistantState(),
    onRunAssistant: (AssistTask) -> Unit = {},
    onOpenAssistantSettings: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    var progress by remember { mutableFloatStateOf(if (autoRun) 0f else 1f) }
    var running by remember { androidx.compose.runtime.mutableStateOf(autoRun) }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        progress = 0f
        while (progress < 1f) {
            delay(60)
            progress = (progress + 0.018f).coerceAtMost(1f)
        }
        running = false
    }

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
                Text(
                    text = "AI Assistant",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Your CV, matched against real international openings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            AnalysisCard(
                progress = progress,
                running = running,
                hasCv = hasCv,
                skillCount = skillCount,
                strongMatchCount = strongMatchCount,
                onRun = { running = true },
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Text(
                    text = "Start here",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                CapabilityCard(
                    icon = Icons.Filled.Description,
                    title = "Import and analyse my CV",
                    body = "Read your real document, extract skills and experience, and build a " +
                        "profile you can correct. Runs on your device.",
                    accent = MaterialTheme.colorScheme.primary,
                    actionLabel = "Open CV analysis",
                    onClick = onOpenCv,
                )
            }
        }

        item {
            AssistantPanel(
                state = assistant,
                onRun = onRunAssistant,
                onOpenSettings = onOpenAssistantSettings,
            )
        }

        item {
            AiTransparencyCard()
        }

        item {
            SectionHeader(
                title = "What the AI does for you",
                subtitle = "Every capability below is wired into the job pipeline",
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                CapabilityCard(
                    icon = Icons.Filled.Description,
                    title = "CV analysis",
                    body = if (hasCv) {
                        "Your CV is analysed. Re-open it any time to correct a skill or add experience."
                    } else {
                        "Reads your CV, extracts skills and experience, then scores every posting against it."
                    },
                    accent = MaterialTheme.colorScheme.primary,
                    actionLabel = if (hasCv) "Open my CV" else "Import my CV",
                    onClick = onOpenCv,
                )
                CapabilityCard(
                    icon = Icons.Filled.Public,
                    title = "Expat shortlist",
                    body = "Ranks the postings you can actually apply for from Madagascar, and says what is in the way.",
                    accent = extendedColors.success,
                    actionLabel = "Open shortlist",
                    onClick = onOpenShortlist,
                )
                CapabilityCard(
                    icon = Icons.Filled.Insights,
                    title = "Expat intelligence",
                    body = "Maps which countries are open to you, what keeps blocking you, and the roles to act on first.",
                    accent = extendedColors.info,
                    actionLabel = "Open intelligence",
                    onClick = onOpenIntelligence,
                )
                CapabilityCard(
                    icon = Icons.Filled.WorkspacePremium,
                    title = "Expat ranking",
                    body = "Blends how ready each posting is to apply for with how good its documented package is — and explains every place.",
                    accent = MaterialTheme.colorScheme.secondary,
                    actionLabel = "Open ranking",
                    onClick = onOpenRanking,
                )
                CapabilityCard(
                    icon = Icons.Filled.AssignmentTurnedIn,
                    title = "Application assistant",
                    body = "Builds a step-by-step plan for one posting: route in, CV tailoring, documents and next action.",
                    accent = MaterialTheme.colorScheme.primary,
                    actionLabel = "Open assistant",
                    onClick = onOpenAssistant,
                )
                CapabilityCard(
                    icon = Icons.Filled.Translate,
                    title = "CV adaptation",
                    body = "Rewrites your CV for each country's format — Canadian, German and Gulf conventions.",
                    accent = MaterialTheme.colorScheme.secondary,
                    actionLabel = "Adapt my CV",
                    onClick = onOpenAdaptation,
                )
                CapabilityCard(
                    icon = Icons.Filled.Gavel,
                    title = "Scam shield",
                    body = "Flags upfront fees, unregistered employers and unrealistic salary claims.",
                    accent = extendedColors.danger,
                    actionLabel = "Check a posting",
                    onClick = onOpenScamShield,
                )
                CapabilityCard(
                    icon = Icons.Filled.Mic,
                    title = "Interview coach",
                    body = "Rehearses visa interviews and technical rounds with instant feedback.",
                    accent = extendedColors.warning,
                    actionLabel = "Start rehearsing",
                    onClick = onOpenInterview,
                )
                CapabilityCard(
                    icon = Icons.Filled.NotificationsActive,
                    title = "Job alerts",
                    body = "Saved searches that tell you the moment a matching posting appears.",
                    accent = extendedColors.info,
                    actionLabel = "Manage alerts",
                    onClick = onOpenAlerts,
                )
                CapabilityCard(
                    icon = Icons.Filled.RocketLaunch,
                    title = "Safe auto-apply",
                    body = "Prepares a tailored draft for every strong match, and never sends it for you.",
                    accent = MaterialTheme.colorScheme.secondary,
                    actionLabel = "Open auto-apply",
                    onClick = onOpenAutoApply,
                )
            }
        }

        item {
            PrimaryButton(
                text = "Browse matched jobs",
                onClick = onBrowseJobs,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.Filled.AutoAwesome,
            )
        }
    }
}

@Composable
private fun AnalysisCard(
    progress: Float,
    running: Boolean,
    hasCv: Boolean,
    skillCount: Int,
    strongMatchCount: Int,
    onRun: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val dark = isDarkTheme()
    val shape = RoundedCornerShape(24.dp)
    val rotation by rememberInfiniteTransition(label = "aiSpin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "aiSpinValue",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    if (dark) listOf(Color(0xFF152C5C), Color(0xFF0A1730))
                    else listOf(Color(0xFF15356F), Color(0xFF0A1D40)),
                )
            )
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
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
                    modifier = Modifier.size(24.dp).rotate(rotation),
                )
            }
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (running) "Analysing your profile" else "Analysis complete",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Text(
                    text = when {
                        running -> "Scoring your profile against the live feed"
                        !hasCv -> "Import your CV to start matching"
                        strongMatchCount > 0 -> "$strongMatchCount strong match${if (strongMatchCount == 1) "" else "es"} found"
                        else -> "No strong matches yet — pull to refresh"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                )
            }
        }

        ProgressBar(
            fraction = progress,
            color = MaterialTheme.colorScheme.secondary,
            trackColor = Color.White.copy(alpha = 0.18f),
            height = 6.dp,
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AnalysisStep(
                if (skillCount > 0) "CV parsed — $skillCount skills extracted" else "CV parsed",
                progress > 0.15f,
            )
            AnalysisStep("Skills mapped to international standards", progress > 0.4f)
            AnalysisStep("Visa eligibility checked per country", progress > 0.65f)
            AnalysisStep("Scam risk screened on every listing", progress > 0.85f)
            AnalysisStep("Matches ranked by fit", progress >= 1f)
        }

        if (!running) {
            SecondaryButton(
                text = "Run analysis again",
                onClick = onRun,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AnalysisStep(label: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val alpha by animateFloatAsState(if (done) 1f else 0.45f, tween(400), label = "stepAlpha")
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(
                    if (done) MaterialTheme.colorScheme.secondary.copy(alpha = alpha)
                    else Color.White.copy(alpha = 0.16f)
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = Color(0xFF04231F),
                    modifier = Modifier.size(10.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = if (done) 0.95f else 0.5f),
        )
    }
}

@Composable
private fun AssistantPanel(
    state: AssistantState,
    onRun: (AssistTask) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val shape = RoundedCornerShape(20.dp)
    val accent = MaterialTheme.colorScheme.secondary

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
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Cloud,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "NVIDIA drafting assistant",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when {
                        !state.configured -> "Optional — add your NVIDIA API key to switch it on"
                        !state.consent -> "Key saved — turn it on to allow sending your CV facts"
                        else -> "Ready · ${state.modelLabel.ifBlank { "model selected" }}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Pill(
                text = if (state.ready) "On" else "Off",
                containerColor = (if (state.ready) extendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant)
                    .copy(alpha = 0.14f),
                contentColor = if (state.ready) extendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = "When on, only the facts from your CV and this posting are sent to NVIDIA to " +
                "explain a match or draft text. Your CV file, email and phone are never sent. " +
                "Every draft is yours to edit before you send it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!state.ready) {
            SecondaryButton(
                text = if (state.configured) "Turn on in settings" else "Set up the assistant",
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.Filled.Settings,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                AssistantTaskButton(
                    label = "Explain match",
                    task = AssistTask.EXPLAIN_MATCH,
                    enabled = !state.running,
                    onRun = onRun,
                    modifier = Modifier.weight(1f),
                )
                AssistantTaskButton(
                    label = "Cover letter",
                    task = AssistTask.COVER_LETTER,
                    enabled = !state.running,
                    onRun = onRun,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        AnimatedVisibility(visible = state.running) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressBar(
                    fraction = 0.5f,
                    color = accent,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    height = 6.dp,
                )
            }
        }

        if (state.error.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(extendedColors.warning.copy(alpha = 0.12f))
                    .padding(spacing.sm),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = null,
                    tint = extendedColors.warning,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        if (state.output.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, extendedColors.cardStroke, RoundedCornerShape(14.dp))
                    .padding(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (state.outputIsRemote) Icons.Filled.Cloud else Icons.Filled.Lock,
                        contentDescription = null,
                        tint = if (state.outputIsRemote) accent else extendedColors.info,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = state.outputTitle.ifBlank { "Assistant output" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Pill(
                    text = if (state.outputIsRemote) "Drafted by NVIDIA" else "On-device explanation",
                    containerColor = (if (state.outputIsRemote) accent else extendedColors.info).copy(alpha = 0.14f),
                    contentColor = if (state.outputIsRemote) accent else extendedColors.info,
                )
                Text(
                    text = state.output,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AssistantTaskButton(
    label: String,
    task: AssistTask,
    enabled: Boolean,
    onRun: (AssistTask) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = { onRun(task) },
            )
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AiTransparencyCard() {
    val spacing = LocalSpacing.current
    var expanded by remember { androidx.compose.runtime.mutableStateOf(false) }
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
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(extendedColors.info.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.VerifiedUser,
                    contentDescription = null,
                    tint = extendedColors.info,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "How this app's AI works",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Rules and parsers on-device, plus one optional NVIDIA model",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            text = AiCapabilityCatalog.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Spacer(Modifier.height(2.dp))
                AiCapabilityCatalog.capabilities.forEach { capability ->
                    AiCapabilityRow(capability)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { expanded = !expanded },
                )
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "Hide the full breakdown" else "See the full breakdown",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun AiCapabilityRow(capability: AiCapability) {
    val spacing = LocalSpacing.current
    val accent = when (capability.kind) {
        AiCapabilityKind.REMOTE_MODEL -> extendedColors.danger
        AiCapabilityKind.ON_DEVICE_MODEL -> MaterialTheme.colorScheme.primary
        else -> extendedColors.info
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = capability.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Pill(
                text = capability.kind.label,
                containerColor = accent.copy(alpha = 0.14f),
                contentColor = accent,
            )
        }
        Text(
            text = capability.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (capability.location == com.aiglobal.jobfinder.data.model.AiProcessingLocation.REMOTE) {
                    Icons.Filled.Cloud
                } else {
                    Icons.Filled.Smartphone
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = capability.location.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = capability.note,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        )
        Spacer(Modifier.height(spacing.xs))
    }
}

@Composable
private fun CapabilityCard(
    icon: ImageVector,
    title: String,
    body: String,
    accent: Color,
    actionLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, extendedColors.cardStroke, RoundedCornerShape(20.dp))
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .padding(spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
