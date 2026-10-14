package com.aiglobal.jobfinder.ui.screens.alerts

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import com.aiglobal.jobfinder.data.model.AlertFrequency
import com.aiglobal.jobfinder.data.model.AlertScope
import com.aiglobal.jobfinder.data.model.JobAlert
import com.aiglobal.jobfinder.data.model.JobAlertRule
import com.aiglobal.jobfinder.ui.components.AppCard
import com.aiglobal.jobfinder.ui.components.EmptyState
import com.aiglobal.jobfinder.ui.components.FilterChip
import com.aiglobal.jobfinder.ui.components.IconChipButton
import com.aiglobal.jobfinder.ui.components.Pill
import com.aiglobal.jobfinder.ui.components.PrimaryButton
import com.aiglobal.jobfinder.ui.components.SecondaryButton
import com.aiglobal.jobfinder.ui.components.SectionHeader
import com.aiglobal.jobfinder.ui.components.SkeletonBox
import com.aiglobal.jobfinder.ui.components.clickableNoRipple
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors
import com.aiglobal.jobfinder.viewmodel.JobAlertsViewModel

/** Saved searches and the postings they catch, evaluated against the live feed. */
@Composable
fun JobAlertsScreen(
    viewModel: JobAlertsViewModel,
    onBack: () -> Unit,
    onOpenJob: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    JobAlertsContent(
        state = state,
        onBack = onBack,
        onOpenJob = onOpenJob,
        onRefresh = viewModel::refresh,
        onCreateRule = viewModel::createRule,
        onRemoveRule = viewModel::removeRule,
        onMarkRead = viewModel::markRead,
        onClearAll = viewModel::clearAll,
    )
}

/** Stateless body, so layout can be verified without a live pipeline. */
@Composable
internal fun JobAlertsContent(
    state: com.aiglobal.jobfinder.data.model.JobAlertsState,
    onBack: () -> Unit,
    onOpenJob: (String) -> Unit = {},
    onRefresh: () -> Unit = {},
    onCreateRule: (String, List<String>, List<String>, AlertScope, AlertFrequency, Int) -> Unit = { _, _, _, _, _, _ -> },
    onRemoveRule: (String) -> Unit = {},
    onMarkRead: (String) -> Unit = {},
    onClearAll: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    var showEditor by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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
                    text = "Job alerts",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "${state.activeRuleCount} saved searches · ${state.unreadCount} new",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconChipButton(
                icon = Icons.Filled.Refresh,
                onClick = onRefresh,
                contentDescription = "Refresh alerts",
            )
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
            item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.NotificationsActive,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Spacer(Modifier.width(spacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (state.unreadCount > 0) "${state.unreadCount} new matches" else "You are up to date",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Rules are re-checked every time your feed refreshes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader(
                        title = "Saved searches",
                        subtitle = "What to watch for",
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = if (showEditor) "Close" else "New",
                        onClick = { showEditor = !showEditor },
                        leadingIcon = if (showEditor) null else Icons.Filled.Add,
                        height = 42.dp,
                    )
                }
            }

            item {
                AnimatedVisibility(visible = showEditor) {
                    RuleEditor(onSave = { name, keywords, countries, scope, frequency, score ->
                        onCreateRule(name, keywords, countries, scope, frequency, score)
                        showEditor = false
                    })
                }
            }

            if (state.rules.isEmpty()) {
                item {
                    Text(
                        text = "No saved searches yet. Add one to be told when a matching posting appears.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(state.rules.size) { index ->
                    RuleRow(rule = state.rules[index], onRemove = { onRemoveRule(state.rules[index].id) })
                }
            }

            item {
                SectionHeader(
                    title = "Matched postings",
                    subtitle = if (state.alerts.isEmpty()) "Nothing caught yet" else "${state.alerts.size} caught",
                    actionLabel = if (state.alerts.isNotEmpty()) "Clear" else null,
                    onActionClick = onClearAll,
                )
            }

            if (state.isRefreshing && state.alerts.isEmpty()) {
                item { SkeletonBox(modifier = Modifier.fillMaxWidth().height(84.dp), RoundedCornerShape(20.dp)) }
                item { SkeletonBox(modifier = Modifier.fillMaxWidth().height(84.dp), RoundedCornerShape(20.dp)) }
            } else if (state.alerts.isEmpty()) {
                item {
                    EmptyState(
                        emoji = "🔔",
                        title = "No alerts yet",
                        message = "When a posting matches one of your saved searches, it will appear here.",
                    )
                }
            } else {
                items(state.alerts.size) { index ->
                    val alert = state.alerts[index]
                    AlertRow(
                        alert = alert,
                        onClick = {
                            onMarkRead(alert.jobId)
                            onOpenJob(alert.jobId)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleRow(rule: JobAlertRule, onRemove: () -> Unit) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, extendedColors.cardStroke, RoundedCornerShape(18.dp))
            .padding(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rule.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append(rule.scope.label)
                    if (rule.minMatchScore > 0) append(" · ${rule.minMatchScore}%+")
                    if (rule.countries.isNotEmpty()) append(" · ${rule.countries.joinToString(", ")}")
                    if (rule.keywords.isNotEmpty()) append(" · ${rule.keywords.joinToString(", ")}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        Spacer(Modifier.width(spacing.xs))
        IconChipButton(
            icon = Icons.Filled.Delete,
            onClick = onRemove,
            contentDescription = "Delete saved search",
            tint = extendedColors.danger,
        )
    }
}

@Composable
private fun AlertRow(alert: JobAlert, onClick: () -> Unit) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, extendedColors.cardStroke, RoundedCornerShape(20.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = alert.countryFlag.ifBlank { "🌍" }, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.width(spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = alert.jobTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                text = "${alert.company} · ${alert.country}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(
                    text = "${alert.matchScore}% Match",
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    contentColor = MaterialTheme.colorScheme.primary,
                )
                if (alert.isVisaFriendly) {
                    Pill(
                        text = "Visa support",
                        containerColor = extendedColors.successContainer,
                        contentColor = extendedColors.success,
                    )
                }
                if (!alert.read) {
                    Pill(
                        text = "New",
                        containerColor = extendedColors.infoContainer,
                        contentColor = extendedColors.info,
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleEditor(
    onSave: (String, List<String>, List<String>, AlertScope, AlertFrequency, Int) -> Unit,
) {
    val spacing = LocalSpacing.current
    var name by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf("") }
    var countries by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(AlertScope.ANY_MATCH) }
    var frequency by remember { mutableStateOf(AlertFrequency.DAILY) }
    var score by remember { mutableStateOf(70) }

    AppCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = "New saved search", subtitle = "Keywords, countries and how strong a match must be")
        Spacer(Modifier.height(spacing.sm))

        EditorField(value = name, onValueChange = { name = it }, label = "Name", placeholder = "e.g. Welding jobs in Canada")
        Spacer(Modifier.height(spacing.xs))
        EditorField(value = keywords, onValueChange = { keywords = it }, label = "Keywords", placeholder = "welder, maintenance, HVAC")
        Spacer(Modifier.height(spacing.xs))
        EditorField(value = countries, onValueChange = { countries = it }, label = "Countries", placeholder = "Canada, Germany")

        Spacer(Modifier.height(spacing.sm))
        Text("Scope", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AlertScope.entries.forEach { option ->
                FilterChip(label = option.label, selected = scope == option, onClick = { scope = option })
            }
        }

        Spacer(Modifier.height(spacing.sm))
        Text("Frequency", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AlertFrequency.entries.forEach { option ->
                FilterChip(label = option.label, selected = frequency == option, onClick = { frequency = option })
            }
        }

        Spacer(Modifier.height(spacing.sm))
        Text(
            text = "Minimum match: $score%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(50, 60, 70, 80, 90).forEach { value ->
                FilterChip(label = "$value%", selected = score == value, onClick = { score = value })
            }
        }

        Spacer(Modifier.height(spacing.md))
        PrimaryButton(
            text = "Save search",
            onClick = {
                onSave(
                    name.trim(),
                    keywords.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    countries.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    scope,
                    frequency,
                    score,
                )
            },
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = Icons.Filled.Search,
        )
    }
}

@Composable
private fun EditorField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            decorationBox = { inner ->
                Box {
                    if (value.isBlank()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                }
            },
        )
    }
}
