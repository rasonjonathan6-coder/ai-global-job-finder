package com.aiglobal.jobfinder.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Work
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The five top-level destinations of the bottom navigation bar.
 *
 * [label] is the always-visible French tab label. [contentDescription] is the English
 * accessibility / test identifier: it is kept stable (and distinct from the visible label)
 * so navigation tests and screen readers target a destination by name rather than by the
 * language the label happens to be written in.
 */
enum class Destination(
    val route: String,
    val label: String,
    val contentDescription: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Home("home", "Accueil", "Home", Icons.Outlined.Home, Icons.Filled.Home),
    Jobs("jobs", "Emplois", "Jobs", Icons.Outlined.Work, Icons.Filled.Work),
    Applications("applications", "Candidatures", "Applications", Icons.Outlined.Badge, Icons.Filled.Badge),
    Ai("ai", "IA", "AI", Icons.Outlined.AutoAwesome, Icons.Outlined.AutoAwesome),
    Profile("profile", "Profil", "Profile", Icons.Outlined.Person, Icons.Filled.Person);

    companion object {
        fun fromRoute(route: String?): Destination =
            entries.firstOrNull { it.route == route } ?: Home
    }
}
