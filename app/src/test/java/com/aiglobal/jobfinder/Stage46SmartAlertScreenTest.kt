package com.aiglobal.jobfinder

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.aiglobal.jobfinder.data.refresh.RefreshPreferences
import com.aiglobal.jobfinder.ui.screens.profile.RefreshAndNotificationsCard
import com.aiglobal.jobfinder.ui.theme.AiJobFinderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The smart-alert rules must be visible where the user sets the alert bar, so the promise is
 * not a hidden behaviour. Checked at a narrow phone width, where the copy is most likely to
 * clip, and only when alerts are on — the section is meaningless otherwise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp-hdpi")
class Stage46SmartAlertScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun render(notificationsEnabled: Boolean) {
        composeRule.setContent {
            AiJobFinderTheme {
                RefreshAndNotificationsCard(
                    preferences = RefreshPreferences(),
                    notificationsEnabled = notificationsEnabled,
                    onAutoRefreshChange = {},
                    onIntervalChange = {},
                    onNotificationsChange = {},
                )
            }
        }
    }

    @Test
    fun theSmartAlertRulesAreExplainedWhenAlertsAreOn() {
        render(notificationsEnabled = true)
        composeRule.onNodeWithText(
            "genuinely new postings",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun theRulesAreHiddenWhenAlertsAreOff() {
        render(notificationsEnabled = false)
        composeRule.onNodeWithText(
            "genuinely new postings",
            substring = true,
        ).assertDoesNotExist()
    }
}
