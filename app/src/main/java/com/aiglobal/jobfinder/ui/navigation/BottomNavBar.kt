package com.aiglobal.jobfinder.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiglobal.jobfinder.ui.components.isDarkTheme

/**
 * Responsive Material 3 navigation bar with five persistent labels.
 *
 * Every destination keeps its icon *and* its label visible, selected or not, so the bar never
 * degrades into an icon-only strip. The labels are the French tab names ("Accueil", "Emplois",
 * "Candidatures", "IA", "Profil"); the icon carries the stable English [Destination.contentDescription]
 * so screen readers and tests can name a destination without depending on the label language.
 *
 * The bar is laid out in normal flow by [com.aiglobal.jobfinder.ui.AiJobFinderRoot] rather than
 * floating over the content, so it can never obscure the last card of a list. A hairline divider
 * and a light shadow keep it visually separated from the content above it.
 */
@Composable
fun BottomNavBar(
    current: Destination,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isDarkTheme()
    val topDivider = MaterialTheme.colorScheme.outlineVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (dark) Modifier
                else Modifier.shadow(
                    elevation = 14.dp,
                    shape = RoundedCornerShape(0.dp),
                    clip = false,
                    ambientColor = Color(0x1F0B1B33),
                    spotColor = Color(0x1F0B1B33),
                )
            )
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(thickness = 1.dp, color = topDivider)
        NavigationBar(
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        ) {
            Destination.entries.forEach { destination ->
                NavigationBarItem(
                    selected = destination == current,
                    onClick = { onSelect(destination) },
                    // The stable English name is attached to the whole tab (not just the icon)
                    // so it survives semantics merging. This keeps accessibility and the
                    // navigation tests keyed to a destination identity that never changes,
                    // while the visible label stays French.
                    modifier = Modifier.semantics { contentDescription = destination.contentDescription },
                    icon = {
                        Icon(
                            imageVector = if (destination == current) destination.selectedIcon else destination.icon,
                            contentDescription = null,
                            modifier = Modifier.size(23.dp),
                        )
                    },
                    label = {
                        Text(
                            text = destination.label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 10.5.sp,
                                letterSpacing = 0.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 1.dp),
                        )
                    },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}
