package com.aiglobal.jobfinder.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiglobal.jobfinder.ui.components.isDarkTheme

/**
 * Floating pill navigation bar. The active item expands into a labelled gradient
 * chip so the current section is unmistakable at a glance.
 */
@Composable
fun BottomNavBar(
    current: Destination,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isDarkTheme()
    val shape = RoundedCornerShape(26.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (dark) Modifier
                    else Modifier.shadow(
                        elevation = 18.dp,
                        shape = shape,
                        clip = false,
                        ambientColor = Color(0x240B1B33),
                        spotColor = Color(0x240B1B33),
                    )
                )
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Destination.entries.forEach { destination ->
                NavItem(
                    destination = destination,
                    selected = destination == current,
                    onClick = { onSelect(destination) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    destination: Destination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val contentAlpha by animateFloatAsState(if (selected) 1f else 0.72f, tween(200), label = "navAlpha")
    // The pill hugs its content instead of a fixed width, so a longer label such as
    // "Applications" expands the gradient rather than being clipped by it.
    val horizontalPadding by animateDpAsState(if (selected) 14.dp else 10.dp, tween(240), label = "navPad")
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val gradient = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(
                    if (selected) Brush.linearGradient(gradient) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)),
                )
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = horizontalPadding, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = destination.icon,
                contentDescription = destination.label,
                tint = contentColor.copy(alpha = contentAlpha),
                modifier = Modifier.size(if (selected) 20.dp else 22.dp),
            )
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn(tween(220, delayMillis = 60)) + expandHorizontally(tween(260), expandFrom = Alignment.Start),
                exit = fadeOut(tween(110)) + shrinkHorizontally(tween(200), shrinkTowards = Alignment.Start),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = destination.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Vertical spacer that keeps scrollable content clear of the floating bar. */
@Composable
fun NavBarSpacer(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Spacer(Modifier.height(96.dp))
    }
}
