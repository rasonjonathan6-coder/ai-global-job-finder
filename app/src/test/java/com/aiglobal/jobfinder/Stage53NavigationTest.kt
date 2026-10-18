package com.aiglobal.jobfinder

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.aiglobal.jobfinder.data.repository.JobRepository
import com.aiglobal.jobfinder.data.repository.SampleJobRepository
import com.aiglobal.jobfinder.ui.navigation.BottomNavBar
import com.aiglobal.jobfinder.ui.navigation.Destination
import com.aiglobal.jobfinder.ui.theme.AiJobFinderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 53: the bottom navigation must show five persistent, readable labels and expose a
 * clear selected state. These tests render the bar on its own so a layout regression (a
 * clipped or dropped label) fails here rather than only in the full app walk-through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class Stage53BottomNavTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun render(current: Destination, onSelect: (Destination) -> Unit = {}) {
        composeRule.setContent {
            AiJobFinderTheme {
                BottomNavBar(current = current, onSelect = onSelect)
            }
        }
    }

    @Test
    fun allFiveLabelsAreVisible() {
        render(Destination.Home)
        listOf("Accueil", "Emplois", "Candidatures", "IA", "Profil").forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun everyDestinationIsReachableByItsStableName() {
        render(Destination.Home)
        Destination.entries.forEach { destination ->
            composeRule.onNodeWithContentDescription(destination.contentDescription).assertIsDisplayed()
        }
    }

    @Test
    fun theSelectedTabIsMarkedSelected() {
        render(Destination.Applications)
        composeRule.onNodeWithContentDescription("Applications").assertIsSelected()
        composeRule.onNodeWithContentDescription("Home").assertIsDisplayed()
    }

    @Test
    fun tappingALabelSelectsThatDestination() {
        var selected: Destination? = null
        render(Destination.Home) { selected = it }
        composeRule.onNodeWithContentDescription("Profile").performClick()
        assertEquals(Destination.Profile, selected)
    }
}

/**
 * The five labels must survive the narrowest supported width and a raised system font scale.
 * The bar is rendered inside a constrained box so the labels have to share a genuinely narrow
 * row; every label must still be displayed, not clipped away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp-hdpi")
class Stage53NarrowNavTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun allFiveLabelsFitAtNarrowWidthWithLargeFontScale() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                AiJobFinderTheme {
                    Box(Modifier.fillMaxSize()) {
                        BottomNavBar(current = Destination.Home, onSelect = {})
                    }
                }
            }
        }
        listOf("Accueil", "Emplois", "Candidatures", "IA", "Profil").forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }
}

/**
 * Integration check: launch the real activity and confirm every tab shows its French label in
 * the navigation bar while the destination's screen is open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi", application = Stage53AppNavigationTest.TestApp::class)
class Stage53AppNavigationTest {

    class TestApp : AiJobFinderApp() {
        override fun createContainer(): AppContainer = object : AppContainer(this) {
            override fun createJobRepository(): JobRepository = SampleJobRepository(refreshDelayMillis = 0)
            override val seedsSampleData: Boolean get() = true
        }
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val labelByTab = listOf(
        "Home" to "Accueil",
        "Jobs" to "Emplois",
        "Applications" to "Candidatures",
        "AI" to "IA",
        "Profile" to "Profil",
    )

    @Test
    fun everyTabKeepsItsLabelVisibleWhileSelected() {
        composeRule.waitForIdle()
        labelByTab.forEach { (tab, label) ->
            composeRule.onNodeWithContentDescription(tab).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription(tab).assertIsSelected()
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }
}
