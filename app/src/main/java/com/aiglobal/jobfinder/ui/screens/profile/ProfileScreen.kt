package com.aiglobal.jobfinder.ui.screens.profile

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiglobal.jobfinder.data.model.Skill
import com.aiglobal.jobfinder.data.model.UserProfile
import com.aiglobal.jobfinder.ui.components.AppCard
import com.aiglobal.jobfinder.ui.components.IconLabelRow
import com.aiglobal.jobfinder.ui.components.InitialsAvatar
import com.aiglobal.jobfinder.ui.components.Pill
import com.aiglobal.jobfinder.ui.components.ProgressBar
import com.aiglobal.jobfinder.ui.components.SectionHeader
import com.aiglobal.jobfinder.ui.components.SecondaryButton
import com.aiglobal.jobfinder.ui.components.SkeletonBox
import com.aiglobal.jobfinder.ui.components.isDarkTheme
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.ThemeMode
import com.aiglobal.jobfinder.ui.theme.extendedColors
import com.aiglobal.jobfinder.viewmodel.AppViewModel
import com.aiglobal.jobfinder.viewmodel.ProfileViewModel

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    appViewModel: AppViewModel,
    onOpenAi: () -> Unit,
    onOpenExpat: (String) -> Unit = {},
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()

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
                        text = "Add your German A2 certificate and a second reference to reach 100%.",
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

        item { AutoApplyCard(enabled = current.autoApplyEnabled, onToggle = viewModel::setAutoApply) }

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
                text = profile.fullName,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = profile.headline,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(
                    text = profile.location,
                    containerColor = Color.White.copy(alpha = 0.14f),
                    contentColor = Color.White,
                    leadingIcon = Icons.Filled.Place,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(
                    text = "${profile.yearsOfExperience} yrs experience",
                    containerColor = Color.White.copy(alpha = 0.14f),
                    contentColor = Color.White,
                    leadingIcon = Icons.Filled.WorkOutline,
                )
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
