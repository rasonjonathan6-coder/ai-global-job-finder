package com.aiglobal.jobfinder.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing scale. Using one 4dp-based scale everywhere is what makes the
 * layout read as intentional rather than ad hoc.
 */
data class Spacing(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 20.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 40.dp,
    /** Standard horizontal gutter for screen content. */
    val gutter: Dp = 20.dp,
    /** Extra bottom padding so content clears the floating navigation bar. */
    val navBarClearance: Dp = 112.dp,
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }
