package com.aiglobal.jobfinder.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aiglobal.jobfinder.ui.theme.LocalSpacing
import com.aiglobal.jobfinder.ui.theme.extendedColors

/**
 * Stage 54 shared primitives. Each screen used to rebuild the same header, hero,
 * stat row and chip flow by hand, so the four tabs drifted apart. These composables
 * are the single source for that furniture: same type scale, same gutter, same
 * status-bar inset, so Jobs, Applications, AI and Profile read as one product.
 */

/**
 * Standard screen title block. Applies the status-bar inset once so every screen's
 * headline starts at the same height, and keeps the subtitle to two lines so a long
 * honest sentence cannot push the content off a narrow screen.
 */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = LocalSpacing.current.gutter,
    trailing: (@Composable () -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = horizontalPadding, vertical = spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(spacing.sm))
                trailing()
            }
        }
    }
}

/**
 * Deep-navy hero surface with a single soft accent glow. Used for the AI analysis
 * card and the profile identity card so the two strongest moments in the app share
 * one recognisable identity. Dark mode keeps its own tuned navy rather than inverting.
 */
@Composable
fun HeroSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(26.dp),
    contentPadding: Dp = LocalSpacing.current.lg,
    glow: Color = MaterialTheme.colorScheme.secondary,
    content: @Composable () -> Unit,
) {
    val dark = isDarkTheme()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    if (dark) listOf(Color(0xFF152C5C), Color(0xFF0A1730))
                    else listOf(Color(0xFF14336B), Color(0xFF0A1D40)),
                )
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(190.dp)
                .background(Brush.radialGradient(listOf(glow.copy(alpha = 0.30f), Color.Transparent))),
        )
        Column(modifier = Modifier.padding(contentPadding)) { content() }
    }
}

/**
 * Inline notice in one of four semantic tones. Replaces the ad-hoc coloured Rows
 * that were copied into several screens, so a warning looks the same everywhere.
 */
@Composable
fun InfoBanner(
    icon: ImageVector,
    text: String,
    tone: BannerTone,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    val container = when (tone) {
        BannerTone.Info -> extendedColors.infoContainer
        BannerTone.Success -> extendedColors.successContainer
        BannerTone.Warning -> extendedColors.warningContainer
        BannerTone.Danger -> extendedColors.dangerContainer
    }
    val content = when (tone) {
        BannerTone.Info -> extendedColors.info
        BannerTone.Success -> extendedColors.success
        BannerTone.Warning -> extendedColors.warning
        BannerTone.Danger -> extendedColors.danger
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .padding(horizontal = spacing.sm, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Spacer(Modifier.width(spacing.xs))
            trailing()
        }
    }
}

enum class BannerTone { Info, Success, Warning, Danger }

/** One number in a [StatStrip]. */
data class StatItem(
    val icon: ImageVector,
    val value: String,
    val label: String,
    val accent: Color,
)

/**
 * A single card holding several headline numbers, divided by hairlines. Used on
 * Home, Applications and Profile so a "counts at a glance" row is identical across
 * tabs. Values are never blended: each tile states one fact with its own label.
 */
@Composable
fun StatStrip(
    items: List<StatItem>,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, extendedColors.cardStroke, RoundedCornerShape(20.dp))
            .padding(vertical = spacing.md, horizontal = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(34.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            StatTile(
                icon = item.icon,
                value = item.value,
                label = item.label,
                accent = item.accent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatTile(
    icon: ImageVector,
    value: String,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Underlined tab row used for stage/status selection on Applications. It replaces a
 * scrolling chip row for short, fixed sets where an underline reads as a segmented
 * control rather than a filter. The selected tab keeps a filled pill and a bottom
 * indicator so the state is visible without relying on colour alone.
 */
@Composable
fun TabStrip(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(horizontal = LocalSpacing.current.gutter),
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
    ) {
        itemsIndexed(tabs) { index, label ->
            TabChip(label = label, selected = index == selectedIndex, onClick = { onSelect(index) })
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(120), label = "tabPress")
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(
                width = if (selected) 0.dp else 1.dp,
                color = if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(50),
            )
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 15.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

/**
 * Wrapping chip container that animates the collapse of the "more filters" section.
 * Chips that do not fit a single row flow onto the next line rather than scrolling
 * sideways, which keeps every option reachable without a hidden gesture.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipFlow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val spacing = LocalSpacing.current
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        content()
    }
}

/** A labelled count pill, used for "3 active" style summaries beside a section title. */
@Composable
fun CountBadge(count: Int, label: String, accent: Color = MaterialTheme.colorScheme.primary) {
    Pill(
        text = "$count $label",
        containerColor = accent.copy(alpha = 0.14f),
        contentColor = accent,
    )
}

/**
 * Small "expand/collapse" text row shared by the AI transparency card and the Jobs
 * advanced-filter disclosure, so both read the same.
 */
@Composable
fun DisclosureRow(
    expanded: Boolean,
    labelExpanded: String,
    labelCollapsed: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (expanded) labelExpanded else labelCollapsed,
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

/** Convenience wrapper so callers can animate a disclosure without repeating the API. */
@Composable
fun AnimatedDisclosure(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = visible) { content() }
}

/** Rounded icon tile used as a section lead-in across the AI and Profile screens. */
@Composable
fun IconTile(
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(13.dp))
            .background(accent.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(size * 0.5f))
    }
}

/** Circular accent dot, e.g. a live "analysing" indicator. */
@Composable
fun AccentDot(accent: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(accent),
    )
}
