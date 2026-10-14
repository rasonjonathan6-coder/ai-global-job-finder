package com.aiglobal.jobfinder

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaCredentials
import com.aiglobal.jobfinder.ui.screens.profile.JobSourcesCard
import com.aiglobal.jobfinder.ui.theme.AiJobFinderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout verification for the job-source settings card at a narrow phone width, where
 * the longest hint strings are most likely to clip. The credentials stay on-device, so
 * the card must make clear which sources are on and where the keys come from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp-hdpi")
class JobSourcesScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun render(credentials: AdzunaCredentials, joobleKey: String) {
        composeRule.setContent {
            AiJobFinderTheme {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    JobSourcesCard(
                        credentials = credentials,
                        joobleKey = joobleKey,
                        onSaveAdzuna = { _, _ -> },
                        onSaveJooble = {},
                    )
                }
            }
        }
    }

    @Test
    fun bothSourcesOffShowAsUnconfigured() {
        render(AdzunaCredentials(), "")
        composeRule.onNodeWithText("Adzuna").assertIsDisplayed()
        composeRule.onNodeWithText("Jooble").assertIsDisplayed()
        composeRule.onNodeWithText("Arbeitnow, Remotive, Jobicy and Himalayas are always on.").assertIsDisplayed()
    }

    @Test
    fun expandingRevealsTheCredentialFields() {
        render(AdzunaCredentials(appId = "id", appKey = "key"), "jooble")
        composeRule.onNodeWithText("Add optional sources").performClick()
        composeRule.onNodeWithText("Adzuna app ID").assertIsDisplayed()
        composeRule.onNodeWithText("Jooble API key").assertIsDisplayed()
    }
}
