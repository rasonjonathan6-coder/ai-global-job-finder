package com.aiglobal.jobfinder.ui.screens.ai

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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Translate
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
import com.aiglobal.jobfinder.ui.components.PrimaryButton
import com.aiglobal.jobfinder.ui.components.ProgressBar
import com.aiglobal.jobfinder.ui.components.SectionHeader
import com.aiglobal.jobfinder.ui.components.SecondaryButton
import com.aiglobal.jobfinder.ui.components.isDarkTheme
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors
import kotlinx.coroutines.delay

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
    autoRun: Boolean = true,
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

        item { AnalysisCard(progress = progress, running = running, onRun = { running = true }) }

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
                    body = "Reads your CV, extracts skills and experience, then scores every posting against it.",
                    accent = MaterialTheme.colorScheme.primary,
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
                    icon = Icons.Filled.Translate,
                    title = "CV adaptation",
                    body = "Rewrites your CV for each country's format — Canadian, German and Gulf conventions.",
                    accent = MaterialTheme.colorScheme.secondary,
                )
                CapabilityCard(
                    icon = Icons.Filled.RocketLaunch,
                    title = "Cover letters",
                    body = "Generates a tailored letter per role, in the language the employer expects.",
                    accent = extendedColors.info,
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
                    icon = Icons.Filled.School,
                    title = "Skills gap plan",
                    body = "Tells you exactly which certificate unlocks the next salary band.",
                    accent = extendedColors.success,
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
private fun AnalysisCard(progress: Float, running: Boolean, onRun: () -> Unit) {
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
                    text = if (running) "Matching against 1,240 live listings" else "8 strong matches found",
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
            AnalysisStep("CV parsed — 12 skills extracted", progress > 0.15f)
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
