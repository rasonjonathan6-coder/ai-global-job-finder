package com.aiglobal.jobfinder

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aiglobal.jobfinder.data.refresh.RefreshInterval
import com.aiglobal.jobfinder.data.refresh.RefreshPreferences
import com.aiglobal.jobfinder.ui.screens.profile.RefreshAndNotificationsCard
import com.aiglobal.jobfinder.ui.theme.AiJobFinderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout and behaviour for the automatic-refresh card at a narrow phone width, where the
 * explanatory copy is most likely to clip. The test drives the card with plain callbacks so
 * it verifies the control wiring without a ViewModel or WorkManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp-hdpi")
class Stage20ProfileScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun render(
        preferences: RefreshPreferences = RefreshPreferences(),
        notificationsEnabled: Boolean = false,
        onAutoRefreshChange: (Boolean) -> Unit = {},
        onIntervalChange: (RefreshInterval) -> Unit = {},
        onNotificationsChange: (Boolean) -> Unit = {},
    ) {
        composeRule.setContent {
            AiJobFinderTheme {
                RefreshAndNotificationsCard(
                    preferences = preferences,
                    notificationsEnabled = notificationsEnabled,
                    onAutoRefreshChange = onAutoRefreshChange,
                    onIntervalChange = onIntervalChange,
                    onNotificationsChange = onNotificationsChange,
                )
            }
        }
    }

    @Test
    fun theCardShowsBothSwitchesAndTheCadence() {
        render()
        composeRule.onNodeWithText("Background refresh").assertIsDisplayed()
        composeRule.onNodeWithText("New match alerts").assertIsDisplayed()
        // The cadence is described as approximate, never as a fixed time.
        composeRule.onNodeWithText("Twice a day", substring = true).assertIsDisplayed()
    }

    @Test
    fun choosingAnIntervalReportsIt() {
        var chosen: RefreshInterval? = null
        render(onIntervalChange = { chosen = it })

        composeRule.onNodeWithText("Daily").performClick()
        composeRule.waitForIdle()
        assertEquals(RefreshInterval.DAILY, chosen)
    }

    @Test
    fun turningRefreshOffHidesTheIntervalChoices() {
        render(preferences = RefreshPreferences(enabled = false))
        composeRule.onNodeWithText("Every 6 hours").assertDoesNotExist()
        composeRule.onNodeWithText("Off — jobs refresh only when you open the app").assertIsDisplayed()
    }
}
