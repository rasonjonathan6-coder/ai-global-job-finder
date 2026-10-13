package com.aiglobal.jobfinder.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colors whose meaning is fixed regardless of light/dark theme.
 * Provided through [LocalExtendedColors] so screens can express
 * "this job is eligible" or "match is strong" without guessing.
 */
@Immutable
data class ExtendedColors(
    val success: Color,
    val onSuccessContainer: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val brandGradient: List<Color>,
    val accentGradient: List<Color>,
    val cardStroke: Color,
    val skeletonBase: Color,
    val skeletonHighlight: Color,
)

val LightExtendedColors = ExtendedColors(
    success = SuccessLight,
    onSuccessContainer = Color(0xFF075E41),
    successContainer = SuccessContainerLight,
    warning = WarningLight,
    warningContainer = WarningContainerLight,
    danger = DangerLight,
    dangerContainer = DangerContainerLight,
    info = InfoLight,
    infoContainer = InfoContainerLight,
    brandGradient = listOf(Navy700, Navy800),
    accentGradient = listOf(RoyalBlue, Teal),
    cardStroke = Color(0x140B1B33),
    skeletonBase = Color(0xFFE4E9F2),
    skeletonHighlight = Color(0xFFF3F6FB),
)

val DarkExtendedColors = ExtendedColors(
    success = SuccessDark,
    onSuccessContainer = Color(0xFF9DF3C7),
    successContainer = SuccessContainerDark,
    warning = WarningDark,
    warningContainer = WarningContainerDark,
    danger = DangerDark,
    dangerContainer = DangerContainerDark,
    info = InfoDark,
    infoContainer = InfoContainerDark,
    brandGradient = listOf(Navy700, Navy900),
    accentGradient = listOf(RoyalBlueLight, TealLight),
    cardStroke = Color(0x1FFFFFFF),
    skeletonBase = Color(0xFF1B2A45),
    skeletonHighlight = Color(0xFF26374F),
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }
