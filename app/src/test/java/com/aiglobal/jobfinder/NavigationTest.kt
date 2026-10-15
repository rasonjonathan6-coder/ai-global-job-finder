package com.aiglobal.jobfinder

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aiglobal.jobfinder.data.repository.JobRepository
import com.aiglobal.jobfinder.data.repository.SampleJobRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Launches the real activity and walks every bottom-navigation tab, so the graph,
 * the ViewModel wiring and the seeded data are all exercised together.
 *
 * The container is swapped for one backed by [SampleJobRepository]: a unit test has no
 * network, and the point here is the navigation graph, not the job feeds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi", application = NavigationTest.TestApp::class)
class NavigationTest {

    class TestApp : AiJobFinderApp() {
        override fun createContainer(): AppContainer = object : AppContainer(this) {
            override fun createJobRepository(): JobRepository = SampleJobRepository(refreshDelayMillis = 0)
            override val seedsSampleData: Boolean get() = true
        }
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun everyTabOpensItsScreen() {
        composeRule.waitForIdle()

        // Home greets the seeded user by name.
        composeRule.onNodeWithText("Hello, Jonathan 👋").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Jobs").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Find jobs").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Applications").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("in progress", substring = true).assertIsDisplayed()

        composeRule.onNodeWithContentDescription("AI").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("AI Assistant").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Profile").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Jonathan Rakoto").assertIsDisplayed()
    }

    /** Stage 4: the secondary screens must be reachable from the tabs they belong to. */
    @Test
    fun profileOpensASettlementGuide() {
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Profile").performClick()
        composeRule.waitForIdle()

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Canada"))
        composeRule.onNodeWithText("Canada").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Settlement guide").assertIsDisplayed()
        // The secondary screen hides the tab bar and offers its own back affordance.
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun anApplicationOpensItsDetailScreen() {
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Applications").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Maintenance Technician").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Progress").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    /** The detail screen's cover-letter action must open the generator for that job. */
    @Test
    fun applicationDetailOpensTheCoverLetterGenerator() {
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Applications").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Maintenance Technician").performClick()
        composeRule.waitForIdle()

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Open and edit letter"))
        composeRule.onNodeWithText("Open and edit letter").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Language").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    /** Stage 5: the AI tab's capability cards must open the four new screens. */
    @Test
    fun aiTabOpensTheInterviewCoach() {
        openAiTabAndTap("Start rehearsing")
        composeRule.onNodeWithText("Interview coach").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun aiTabOpensTheScamShield() {
        openAiTabAndTap("Check a posting")
        composeRule.onNodeWithText("Scam shield").assertIsDisplayed()
    }

    @Test
    fun aiTabOpensJobAlerts() {
        openAiTabAndTap("Manage alerts")
        composeRule.onNodeWithText("Job alerts").assertIsDisplayed()
    }

    @Test
    fun aiTabOpensSafeAutoApply() {
        openAiTabAndTap("Open auto-apply")
        composeRule.onNodeWithText("Safe auto-apply").assertIsDisplayed()
    }

    private fun openAiTabAndTap(actionLabel: String) {
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("AI").performClick()
        composeRule.waitForIdle()
        val list = composeRule.onNode(hasScrollAction())
        list.performScrollToNode(hasText(actionLabel))
        // The floating navigation bar overlays the list, so bring the card clear of it
        // before tapping, exactly as a user would scroll before reaching for it.
        list.performScrollToNode(hasText("Browse matched jobs"))
        composeRule.onNodeWithText(actionLabel).performClick()
        composeRule.waitForIdle()
    }
}
