package com.aiglobal.jobfinder.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaCountries
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaCredentials
import com.aiglobal.jobfinder.data.model.BenefitPriority
import com.aiglobal.jobfinder.data.model.BenefitType
import com.aiglobal.jobfinder.data.model.ExpatPreferences
import com.aiglobal.jobfinder.data.model.JobBenefitTypes
import com.aiglobal.jobfinder.data.model.NvidiaConfig
import com.aiglobal.jobfinder.data.model.NvidiaModel
import com.aiglobal.jobfinder.data.model.NvidiaModels
import com.aiglobal.jobfinder.data.model.NvidiaResult
import com.aiglobal.jobfinder.data.model.ProviderDiagnostics
import com.aiglobal.jobfinder.data.model.ProviderStatus
import com.aiglobal.jobfinder.data.model.Skill
import com.aiglobal.jobfinder.data.model.UserProfile
import com.aiglobal.jobfinder.ui.components.AppCard
import com.aiglobal.jobfinder.ui.components.FilterChip
import com.aiglobal.jobfinder.ui.components.IconLabelRow
import com.aiglobal.jobfinder.ui.components.InitialsAvatar
import com.aiglobal.jobfinder.ui.components.Pill
import com.aiglobal.jobfinder.ui.components.PrimaryButton
import com.aiglobal.jobfinder.ui.components.ProgressBar
import com.aiglobal.jobfinder.ui.components.SectionHeader
import com.aiglobal.jobfinder.ui.components.SecondaryButton
import com.aiglobal.jobfinder.ui.components.SkeletonBox
import com.aiglobal.jobfinder.ui.components.isDarkTheme
import com.aiglobal.jobfinder.ui.screens.diagnostics.SourceStatusBadge
import com.aiglobal.jobfinder.ui.screens.diagnostics.providerDetail
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.ThemeMode
import com.aiglobal.jobfinder.ui.theme.extendedColors
import com.aiglobal.jobfinder.viewmodel.AppViewModel
import com.aiglobal.jobfinder.viewmodel.ProfileViewModel
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    appViewModel: AppViewModel,
    onOpenAi: () -> Unit,
    onOpenExpat: (String) -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val demoMode by viewModel.demoMode.collectAsStateWithLifecycle()
    val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
    val adzunaCredentials by appViewModel.adzunaCredentials.collectAsStateWithLifecycle()
    val adzunaCountries by appViewModel.adzunaCountries.collectAsStateWithLifecycle()
    val joobleApiKey by appViewModel.joobleApiKey.collectAsStateWithLifecycle()
    val nvidiaConfig by appViewModel.nvidiaConfig.collectAsStateWithLifecycle()
    val credentialsEncrypted = appViewModel.credentialsEncrypted
    val expatPreferences by appViewModel.expatPreferences.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()

    val current = profile
    if (current == null) {
        ProfileLoading()
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = LocalSpacing.current.gutter,
            end = LocalSpacing.current.gutter,
            top = LocalSpacing.current.sm,
            bottom = LocalSpacing.current.navBarClearance,
        ),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
    ) {
        item {
            Column(modifier = Modifier.statusBarsPadding().padding(top = LocalSpacing.current.sm)) {
                Text(
                    text = "Profile",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        item { ProfileHeaderCard(current) }

        item {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Profile strength",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${current.profileCompletion}%",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    ProgressBar(fraction = current.profileCompletion / 100f)
                    Text(
                        text = profileStrengthHint(current),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { CvCard(current, onOpenAi = onOpenAi) }

        item { SkillsCard(current.skills) }

        item { ExperienceCard(current) }

        if (current.targetCountries.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Settlement guides",
                    subtitle = "What each destination offers incoming workers",
                )
            }
            item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        current.targetCountries.forEach { country ->
                            SettingsRow(
                                icon = Icons.Filled.Public,
                                title = country,
                                value = "View guide",
                                onClick = { onOpenExpat(country) },
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionHeader(title = "Preferences", subtitle = "Appearance and automation")
        }

        item { ThemeCard(themeMode = themeMode, onSelect = appViewModel::setThemeMode) }

        item {
            SectionHeader(
                title = "Expat benefits",
                subtitle = "What you want a job abroad to include — this drives the Expat Benefits Score",
            )
        }

        item {
            ExpatPriorityCard(
                preferences = expatPreferences,
                onPriorityChange = appViewModel::setBenefitPriority,
                onRequireVisaChange = appViewModel::setRequireVisaSponsorship,
            )
        }

        item { AutoApplyCard(enabled = current.autoApplyEnabled, onToggle = viewModel::setAutoApply) }

        item { DemoModeCard(enabled = demoMode, onToggle = viewModel::setDemoMode) }

        item {
            SectionHeader(
                title = "AI assistant",
                subtitle = "One optional NVIDIA model, off until you turn it on",
            )
        }

        item {
            AiAssistantCard(
                config = nvidiaConfig,
                encrypted = credentialsEncrypted,
                onSaveKey = appViewModel::saveNvidiaApiKey,
                onSelectModel = appViewModel::saveNvidiaModelId,
                onConsentChange = appViewModel::setNvidiaConsent,
                onTestKey = appViewModel::testNvidiaKey,
            )
        }

        item {
            JobSourcesCard(
                credentials = adzunaCredentials,
                joobleKey = joobleApiKey,
                diagnostics = diagnostics,
                adzunaCountries = adzunaCountries,
                onSaveAdzuna = appViewModel::saveAdzunaCredentials,
                onSaveJooble = appViewModel::saveJoobleApiKey,
                onSaveAdzunaCountries = appViewModel::saveAdzunaCountries,
                onToggleSource = appViewModel::setSourceEnabled,
                onRunValidation = viewModel::runValidation,
                onOpenDiagnostics = onOpenDiagnostics,
            )
        }

        item {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsRow(Icons.Filled.Translate, "App language", "English")
                    SettingsRow(Icons.Filled.Public, "Target countries", "${current.targetCountries.size} selected")
                    SettingsRow(Icons.Filled.Badge, "Target roles", "${current.targetRoles.size} selected")
                }
            }
        }
    }
}

/**
 * Names the next concrete gap rather than asserting an invented one. The candidate is
 * targeting several countries, so the hint points at the CV sections the analyser found
 * empty — never at a certificate or reference the app has no evidence the user holds.
 */
private fun profileStrengthHint(profile: UserProfile): String {
    if (profile.profileCompletion >= 100) return "Your profile is complete."

    val gaps = buildList {
        if (profile.skills.isEmpty()) add("your skills")
        if (profile.experience.isEmpty()) add("your work experience")
        if (profile.education.isEmpty()) add("your education")
        if (!profile.cvImported) add("an imported CV")
    }
    if (gaps.isEmpty()) {
        return "Re-import your CV to refresh your profile strength."
    }
    val joined = when (gaps.size) {
        1 -> gaps.first()
        2 -> "${gaps[0]} and ${gaps[1]}"
        else -> gaps.dropLast(1).joinToString(", ") + " and " + gaps.last()
    }
    return "Add $joined to strengthen your profile."
}

@Composable
private fun ProfileHeaderCard(profile: UserProfile) {
    val spacing = LocalSpacing.current
    val dark = isDarkTheme()

    Row(
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialsAvatar(
            initials = profile.initials,
            size = 62.dp,
            gradient = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
        )
        Spacer(Modifier.width(spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.fullName.ifBlank { "Your profile" },
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = profile.headline.ifBlank { "Import a CV to build your profile" },
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            if (profile.location.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(
                        text = profile.location,
                        containerColor = Color.White.copy(alpha = 0.14f),
                        contentColor = Color.White,
                        leadingIcon = Icons.Filled.Place,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (profile.yearsOfExperience > 0) {
                    Pill(
                        text = "${profile.yearsOfExperience} yrs experience",
                        containerColor = Color.White.copy(alpha = 0.14f),
                        contentColor = Color.White,
                        leadingIcon = Icons.Filled.WorkOutline,
                    )
                } else if (!profile.cvImported) {
                    Pill(
                        text = "No CV imported",
                        containerColor = Color.White.copy(alpha = 0.14f),
                        contentColor = Color.White,
                        leadingIcon = Icons.Filled.Description,
                    )
                }
            }
        }
    }
}

@Composable
private fun CvCard(profile: UserProfile, onOpenAi: () -> Unit) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(21.dp),
                    )
                }
                Spacer(Modifier.width(spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (profile.cvImported) "CV imported" else "No CV yet",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = profile.cvFileName ?: "Import a PDF or DOCX to unlock matching",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (profile.cvImported) {
                    Pill(
                        text = "Parsed",
                        containerColor = extendedColors.successContainer,
                        contentColor = extendedColors.success,
                        leadingIcon = Icons.Filled.Check,
                    )
                }
            }
            SecondaryButton(
                text = if (profile.cvImported) "Re-import or analyse CV" else "Import CV",
                onClick = onOpenAi,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SkillsCard(skills: List<Skill>) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                text = "Skills detected",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            skills.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { skill ->
                        Pill(
                            text = skill.name,
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
private fun ExperienceCard(profile: UserProfile) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                text = "Experience",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            profile.experience.forEach { entry ->
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
                            text = entry.role,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${entry.company} · ${entry.startYear}–${entry.endYear?.toString() ?: "present"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Education",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            profile.education.forEach { entry ->
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
                        Text(
                            text = "${entry.institution} · ${entry.year}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeCard(themeMode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.SettingsBrightness,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(spacing.sm))
                Text(
                    text = "Appearance",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                ThemeOption(
                    label = "System",
                    icon = Icons.Filled.SettingsBrightness,
                    selected = themeMode == ThemeMode.System,
                    onClick = { onSelect(ThemeMode.System) },
                    modifier = Modifier.weight(1f),
                )
                ThemeOption(
                    label = "Light",
                    icon = Icons.Filled.LightMode,
                    selected = themeMode == ThemeMode.Light,
                    onClick = { onSelect(ThemeMode.Light) },
                    modifier = Modifier.weight(1f),
                )
                ThemeOption(
                    label = "Dark",
                    icon = Icons.Filled.DarkMode,
                    selected = themeMode == ThemeMode.Dark,
                    onClick = { onSelect(ThemeMode.Dark) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ThemeOption(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
        )
    }
}

@Composable
private fun ExpatPriorityCard(
    preferences: ExpatPreferences,
    onPriorityChange: (String, BenefitPriority) -> Unit,
    onRequireVisaChange: (Boolean) -> Unit,
) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                text = "How much each benefit matters to you",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Your Expat Benefits Score rewards the postings that document what you marked " +
                    "essential or important. It is always shown apart from your CV match score.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            JobBenefitTypes.all.forEach { type ->
                ExpatBenefitPriorityRow(
                    type = type,
                    priority = preferences.priorityOf(type),
                    onSelect = { onPriorityChange(type.key, it) },
                )
            }

            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Require documented visa sponsorship",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Only show postings where the employer states sponsorship",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = preferences.requireVisaSponsorship,
                    onCheckedChange = onRequireVisaChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }
    }
}

/** One benefit line with a four-step priority selector. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ExpatBenefitPriorityRow(
    type: BenefitType,
    priority: BenefitPriority,
    onSelect: (BenefitPriority) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = type.emoji, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Text(
                text = type.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            BenefitPriority.entries.forEach { option ->
                FilterChip(
                    label = option.label,
                    selected = option == priority,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun AutoApplyCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(extendedColors.warning.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = null,
                        tint = extendedColors.warning,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(Modifier.width(spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-apply mode",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (enabled) "On — drafts only, you still approve each send" else "Off — you review every application",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
            if (enabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(extendedColors.warningContainer)
                        .padding(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = "Auto-apply never submits without your confirmation. It prepares the CV and " +
                            "cover letter, then queues the application for one-tap approval.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Optional demo mode. Off by default: the live app never shows the curated sample
 * candidate or applications. Turning it on serves deterministic fixtures so the UI
 * can be explored without a network — clearly labelled, never mistaken for real data.
 */
@Composable
private fun DemoModeCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val spacing = LocalSpacing.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Science,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Demo mode",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (enabled) "On — showing sample jobs and a sample profile" else "Off — showing real job feeds",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}

/**
 * Job sources. The remote boards need no setup; Adzuna and Jooble reach ordinary
 * national vacancies (where visa-sponsored roles live) but each needs a free key.
 * Keys are stored on-device only and never leave the phone except in the request
 * to the source itself.
 */
@Composable
internal fun JobSourcesCard(
    credentials: AdzunaCredentials,
    joobleKey: String,
    diagnostics: ProviderDiagnostics = ProviderDiagnostics(),
    adzunaCountries: Set<String> = emptySet(),
    onSaveAdzuna: (String, String) -> Unit,
    onSaveJooble: (String) -> Unit,
    onSaveAdzunaCountries: (Set<String>) -> Unit = {},
    onToggleSource: (String, Boolean) -> Unit = { _, _ -> },
    onRunValidation: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    var expanded by remember { mutableStateOf(false) }
    var countriesExpanded by remember { mutableStateOf(false) }
    // Saved keys are masked and never echoed back into the fields. The field starts empty;
    // "Replace" reveals it so the old value is only overwritten by deliberate entry.
    var appId by remember(credentials.appId) { mutableStateOf("") }
    var appKey by remember(credentials.appKey) { mutableStateOf("") }
    var jooble by remember(joobleKey) { mutableStateOf("") }
    var editingAppId by remember(credentials.appId) { mutableStateOf(false) }
    var editingAppKey by remember(credentials.appKey) { mutableStateOf(false) }
    var editingJooble by remember(joobleKey) { mutableStateOf(false) }
    val adzunaSaved = credentials.isConfigured
    val joobleSaved = joobleKey.isNotBlank()

    JobSourcesCardContent(
        spacing = spacing,
        expanded = expanded,
        onExpandedChange = { expanded = it },
        countriesExpanded = countriesExpanded,
        onCountriesExpandedChange = { countriesExpanded = it },
        adzunaCountries = adzunaCountries,
        onSaveAdzunaCountries = onSaveAdzunaCountries,
        adzunaSaved = adzunaSaved,
        joobleSaved = joobleSaved,
        appId = appId,
        onAppIdChange = { appId = it },
        appKey = appKey,
        onAppKeyChange = { appKey = it },
        jooble = jooble,
        onJoobleChange = { jooble = it },
        editingAppId = editingAppId,
        onEditingAppIdChange = { editingAppId = it },
        editingAppKey = editingAppKey,
        onEditingAppKeyChange = { editingAppKey = it },
        editingJooble = editingJooble,
        onEditingJoobleChange = { editingJooble = it },
        diagnostics = diagnostics,
        onSaveAdzuna = onSaveAdzuna,
        onSaveJooble = onSaveJooble,
        onToggleSource = onToggleSource,
        onRunValidation = onRunValidation,
        onOpenDiagnostics = onOpenDiagnostics,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JobSourcesCardContent(
    spacing: com.aiglobal.jobfinder.ui.theme.Spacing,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    countriesExpanded: Boolean,
    onCountriesExpandedChange: (Boolean) -> Unit,
    adzunaCountries: Set<String>,
    onSaveAdzunaCountries: (Set<String>) -> Unit,
    adzunaSaved: Boolean,
    joobleSaved: Boolean,
    appId: String,
    onAppIdChange: (String) -> Unit,
    appKey: String,
    onAppKeyChange: (String) -> Unit,
    jooble: String,
    onJoobleChange: (String) -> Unit,
    editingAppId: Boolean,
    onEditingAppIdChange: (Boolean) -> Unit,
    editingAppKey: Boolean,
    onEditingAppKeyChange: (Boolean) -> Unit,
    editingJooble: Boolean,
    onEditingJoobleChange: (Boolean) -> Unit,
    diagnostics: ProviderDiagnostics,
    onSaveAdzuna: (String, String) -> Unit,
    onSaveJooble: (String) -> Unit,
    onToggleSource: (String, Boolean) -> Unit,
    onRunValidation: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Hub,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Job sources",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${diagnostics.successCount} of ${diagnostics.providers.size} sources answered on the last run.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            diagnostics.providers.forEach { provider ->
                DiagnosticRow(
                    name = provider.name,
                    detail = provider.message ?: providerDetail(provider),
                    status = provider.status,
                    enabled = provider.enabled,
                    onToggle = { enabled -> onToggleSource(provider.providerId, enabled) },
                )
            }

            SecondaryButton(
                text = "Run live validation",
                onClick = onRunValidation,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.Filled.PlayArrow,
            )
            SecondaryButton(
                text = "Open diagnostics",
                onClick = onOpenDiagnostics,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.Filled.MonitorHeart,
            )

            SecondaryButton(
                text = if (expanded) "Hide" else "Add optional sources",
                onClick = { onExpandedChange(!expanded) },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.Filled.Key,
            )

            if (expanded) {
                Text(
                    text = "Adzuna and Jooble are free. Create credentials at developer.adzuna.com and jooble.org/api, " +
                        "then paste them here. They are stored only on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                CredentialField(
                    label = "Adzuna app ID",
                    saved = adzunaSaved,
                    editing = editingAppId,
                    value = appId,
                    onValueChange = onAppIdChange,
                    onReplace = { onAppIdChange(""); onEditingAppIdChange(true) },
                )
                CredentialField(
                    label = "Adzuna app key",
                    saved = adzunaSaved,
                    editing = editingAppKey,
                    value = appKey,
                    onValueChange = onAppKeyChange,
                    onReplace = { onAppKeyChange(""); onEditingAppKeyChange(true) },
                    secret = true,
                )
                PrimaryButton(
                    text = "Save Adzuna credentials",
                    onClick = {
                        onSaveAdzuna(appId.trim(), appKey.trim())
                        onEditingAppIdChange(false)
                        onEditingAppKeyChange(false)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = appId.isNotBlank() && appKey.isNotBlank(),
                )

                SecondaryButton(
                    text = if (countriesExpanded) "Hide markets" else "Choose Adzuna markets",
                    onClick = { onCountriesExpandedChange(!countriesExpanded) },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = Icons.Filled.Public,
                )

                if (countriesExpanded) {
                    Text(
                        text = "Adzuna searches each market you pick. Empty means the default set " +
                            "(${AdzunaCountries.defaults.joinToString(", ") { AdzunaCountries.labelFor(it) }}).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        AdzunaCountries.supported.forEach { (code, label) ->
                            val selected = adzunaCountries.contains(code)
                            FilterChip(
                                label = label,
                                selected = selected,
                                onClick = {
                                    val next = adzunaCountries.toMutableSet()
                                    if (selected) next.remove(code) else next.add(code)
                                    onSaveAdzunaCountries(next)
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(spacing.xxs))
                CredentialField(
                    label = "Jooble API key",
                    saved = joobleSaved,
                    editing = editingJooble,
                    value = jooble,
                    onValueChange = onJoobleChange,
                    onReplace = { onJoobleChange(""); onEditingJoobleChange(true) },
                    secret = true,
                )
                PrimaryButton(
                    text = "Save Jooble key",
                    onClick = {
                        onSaveJooble(jooble.trim())
                        onEditingJoobleChange(false)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = jooble.isNotBlank(),
                )
            }
        }
    }
}

/**
 * The optional NVIDIA drafting assistant. This is the only place in the app that can send
 * data off the device, so the card states exactly what would be sent, keeps the key masked,
 * and leaves the assistant off until the user both saves a key and flips the consent switch.
 */
@Composable
internal fun AiAssistantCard(
    config: NvidiaConfig,
    encrypted: Boolean,
    onSaveKey: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onConsentChange: (Boolean) -> Unit,
    onTestKey: suspend (String) -> NvidiaResult,
) {
    val spacing = LocalSpacing.current
    var expanded by remember { mutableStateOf(false) }
    var key by remember(config.apiKey) { mutableStateOf("") }
    var editingKey by remember(config.apiKey) { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<NvidiaResult?>(null) }
    val scope = rememberCoroutineScope()
    val saved = config.isConfigured

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Cloud,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "NVIDIA AI assistant",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when {
                            !saved -> "Off — add your API key to enable drafting"
                            !config.consentGranted -> "Key saved — turn on to allow sending your CV facts"
                            else -> "On · ${NvidiaModels.labelFor(config.modelId)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Pill(
                    text = if (config.isReady) "On" else "Off",
                    containerColor = (if (config.isReady) extendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant)
                        .copy(alpha = 0.14f),
                    contentColor = if (config.isReady) extendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = "This is a real remote generative model. When on, the facts from your CV " +
                    "and the posting you select are sent to NVIDIA to explain a match or draft a " +
                    "cover letter. Your CV file, email, phone and address are never sent. " +
                    "Everything it writes is a draft you review before sending.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (saved) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (encrypted) Icons.Filled.Lock else Icons.Filled.Info,
                        contentDescription = null,
                        tint = if (encrypted) extendedColors.success else extendedColors.warning,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (encrypted) {
                            "Key stored encrypted on this device (Android Keystore)."
                        } else {
                            "Key stored on this device. Keystore encryption is unavailable here."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SecondaryButton(
                text = if (expanded) "Hide setup" else "Set up the assistant",
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.Filled.Key,
            )

            if (expanded) {
                CredentialField(
                    label = "NVIDIA API key",
                    saved = saved,
                    editing = editingKey,
                    value = key,
                    onValueChange = { key = it; testResult = null },
                    onReplace = { key = ""; editingKey = true },
                    secret = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    PrimaryButton(
                        text = if (testing) "Testing…" else "Test key",
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                testResult = onTestKey(key)
                                testing = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = key.isNotBlank() && !testing,
                        loading = testing,
                    )
                    PrimaryButton(
                        text = "Save key",
                        onClick = {
                            onSaveKey(key.trim())
                            editingKey = false
                        },
                        modifier = Modifier.weight(1f),
                        enabled = key.isNotBlank(),
                    )
                }

                testResult?.let { result ->
                    val ok = result.isSuccess
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background((if (ok) extendedColors.success else extendedColors.warning).copy(alpha = 0.12f))
                            .padding(spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Info,
                            contentDescription = null,
                            tint = if (ok) extendedColors.success else extendedColors.warning,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (ok) "NVIDIA accepted this key." else result.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Text(
                    text = "Model",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                NvidiaModels.known.forEach { model ->
                    ModelRow(
                        model = model,
                        selected = model.id == config.modelId,
                        onClick = { onSelectModel(model.id) },
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Allow sending my CV facts to NVIDIA",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Required before any request is made. You can turn this off at any time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = config.consentGranted,
                        onCheckedChange = onConsentChange,
                        enabled = saved,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelRow(model: NvidiaModel, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else extendedColors.cardStroke, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = model.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One source's real health plus its on/off switch. */
@Composable
private fun DiagnosticRow(
    name: String,
    detail: String,
    status: ProviderStatus,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SourceStatusBadge(status)
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(LocalSpacing.current.sm))
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}

/**
 * A credential field that never shows a saved value. When something is already stored it
 * shows a masked status with a "Replace" action; the field is empty until the user chooses
 * to replace it, so the stored key is not echoed back into the UI (or into a screenshot).
 */
@Composable
private fun CredentialField(
    label: String,
    saved: Boolean,
    editing: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    onReplace: () -> Unit,
    secret: Boolean = false,
) {
    val spacing = LocalSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (saved && !editing) {
                Text(
                    text = if (secret) "Saved ••••••••" else "Saved",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (saved && !editing) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onReplace),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Replace",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(spacing.xs))
                Text(
                    text = "stored only on this device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            )
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = if (onClick != null) {
            Modifier.fillMaxWidth().clickable(onClick = onClick)
        } else {
            Modifier.fillMaxWidth()
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProfileLoading() {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = spacing.gutter, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        SkeletonBox(Modifier.width(120.dp).height(24.dp))
        SkeletonBox(Modifier.fillMaxWidth().height(150.dp), RoundedCornerShape(24.dp))
        SkeletonBox(Modifier.fillMaxWidth().height(90.dp), RoundedCornerShape(22.dp))
        SkeletonBox(Modifier.fillMaxWidth().height(160.dp), RoundedCornerShape(22.dp))
    }
}
