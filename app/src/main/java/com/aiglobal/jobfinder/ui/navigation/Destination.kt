package com.aiglobal.jobfinder.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.ui.graphics.vector.ImageVector

/** The five top-level destinations of the bottom navigation bar. */
enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home("home", "Home", Icons.Filled.Home),
    Jobs("jobs", "Jobs", Icons.Filled.Work),
    Applications("applications", "Applications", Icons.Filled.Badge),
    Ai("ai", "AI", Icons.Outlined.AutoAwesome),
    Profile("profile", "Profile", Icons.Filled.Person);

    companion object {
        fun fromRoute(route: String?): Destination =
            entries.firstOrNull { it.route == route } ?: Home
    }
}
