package com.aiglobal.jobfinder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush

/** Accessor for the meaning-carrying colors that sit outside the Material scheme. */
val extendedColors: ExtendedColors
    @Composable get() = LocalExtendedColors.current

/** Vertical brand gradient used for the hero surface and primary actions. */
@Composable
fun brandBrush(): Brush = Brush.linearGradient(extendedColors.brandGradient)

@Composable
fun accentBrush(): Brush = Brush.linearGradient(extendedColors.accentGradient)

/** Soft radial glow placed behind the home hero to signal "AI" without noise. */
@Composable
fun aiGlowBrush(): Brush = Brush.radialGradient(
    colors = listOf(
        MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
        Color.Transparent,
    ),
)
