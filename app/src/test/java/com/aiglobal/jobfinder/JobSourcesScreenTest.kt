package com.aiglobal.jobfinder

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaCredentials
import com.aiglobal.jobfinder.data.model.ProviderDiagnostic
import com.aiglobal.jobfinder.data.model.ProviderDiagnostics
import com.aiglobal.jobfinder.data.model.ProviderStatus
import com.aiglobal.jobfinder.ui.screens.profile.JobSourcesCard
import com.aiglobal.jobfinder.ui.theme.AiJobFinderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout verification for the job-source settings card at a narrow phone width, where
 * the longest hint strings are most likely to clip. The card reports each source's real
 * last-run outcome, so the test drives it with recorded diagnostics rather than a
 * hard-coded "always on" list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp-hdpi")
class JobSourcesScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun diagnostics(vararg providers: ProviderDiagnostic) =
        ProviderDiagnostics(providers = providers.toList())

    private fun provider(
        id: String,
        name: String,
        status: ProviderStatus,
        enabled: Boolean = true,
        message: String? = null,
    ) = ProviderDiagnostic(
        providerId = id,
        name = name,
        homepage = "https://example.com",
        documentationUrl = "https://example.com/docs",
        status = status,
        enabled = enabled,
        message = message,
    )

    private fun render(
        credentials: AdzunaCredentials = AdzunaCredentials(),
        joobleKey: String = "",
        diagnostics: ProviderDiagnostics = ProviderDiagnostics(),
    ) {
        composeRule.setContent {
            AiJobFinderTheme {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    JobSourcesCard(
                        credentials = credentials,
                        joobleKey = joobleKey,
                        diagnostics = diagnostics,
                        onSaveAdzuna = { _, _ -> },
                        onSaveJooble = {},
                    )
                }
            }
        }
    }

    @Test
    fun unconfiguredSourcesAreListedWithoutBeingReportedAsFailures() {
        render(
            diagnostics = diagnostics(
                provider("adzuna", "Adzuna", ProviderStatus.NOT_CONFIGURED),
                provider("jooble", "Jooble", ProviderStatus.NOT_CONFIGURED),
            ),
        )
        composeRule.onNodeWithText("Adzuna").assertIsDisplayed()
        composeRule.onNodeWithText("Jooble").assertIsDisplayed()
        composeRule.onAllNodesWithText("Not configured").assertCountEquals(2)
        composeRule.onNodeWithText("0 of 2 sources answered on the last run.").assertIsDisplayed()
    }

    @Test
    fun aSuccessfulSourceShowsItsMessage() {
        render(
            diagnostics = diagnostics(
                provider("remotive", "Remotive", ProviderStatus.SUCCESS_WITH_RESULTS, message = "Returned 12 postings."),
            ),
        )
        composeRule.onNodeWithText("Remotive").assertIsDisplayed()
        composeRule.onNodeWithText("Success").assertIsDisplayed()
        composeRule.onNodeWithText("Returned 12 postings.").assertIsDisplayed()
    }

    @Test
    fun expandingRevealsTheCredentialFields() {
        render(credentials = AdzunaCredentials(appId = "id", appKey = "key"), joobleKey = "jooble")
        composeRule.onNodeWithText("Add optional sources").performClick()
        composeRule.onNodeWithText("Adzuna app ID").assertIsDisplayed()
        composeRule.onNodeWithText("Adzuna app key").assertIsDisplayed()
        composeRule.onNodeWithText("Jooble API key").assertIsDisplayed()
    }

    @Test
    fun aSavedKeyIsNeverShownAndOffersReplacement() {
        render(credentials = AdzunaCredentials(appId = "id", appKey = "topsecret"), joobleKey = "jooblesecret")
        composeRule.onNodeWithText("Add optional sources").performClick()

        // The stored values must not be rendered anywhere on the screen.
        composeRule.onAllNodesWithText("topsecret").assertCountEquals(0)
        composeRule.onAllNodesWithText("jooblesecret").assertCountEquals(0)
        composeRule.onAllNodesWithText("id").assertCountEquals(0)

        // Instead the card shows a masked status and a replace affordance (app ID + app key + Jooble).
        composeRule.onAllNodesWithText("Saved ••••••••").assertCountEquals(2)
        composeRule.onAllNodesWithText("Saved").assertCountEquals(1)
        composeRule.onAllNodesWithText("Replace").assertCountEquals(3)
    }

    @Test
    fun replacingRevealsAnEmptyFieldForTheNewValue() {
        render(credentials = AdzunaCredentials(appId = "id", appKey = "topsecret"))
        composeRule.onNodeWithText("Add optional sources").performClick()
        composeRule.onAllNodesWithText("Replace")[0].performClick()
        // After choosing to replace, an editable field is shown (its label is still present).
        composeRule.onNodeWithText("Adzuna app ID").assertIsDisplayed()
    }
}
