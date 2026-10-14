package com.aiglobal.jobfinder

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.aiglobal.jobfinder.data.repository.SampleJobRepository
import com.aiglobal.jobfinder.ui.screens.profile.ProfileScreen
import com.aiglobal.jobfinder.ui.theme.AiJobFinderTheme
import com.aiglobal.jobfinder.viewmodel.AppViewModel
import com.aiglobal.jobfinder.viewmodel.ProfileViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout verification for the Stage 7 expat preference editor on the Profile screen at a
 * narrow phone width. Every benefit row and the visa gate must stay reachable by scrolling
 * so the priorities that drive the Expat Benefits Score can actually be set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp-hdpi")
class Stage7ScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val repository = SampleJobRepository(refreshDelayMillis = 0)
    private val container by lazy { AppContainer(ApplicationProvider.getApplicationContext()) }

    private fun scrollToText(text: String) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    @Test
    fun theExpatPriorityEditorIsReachableOnANarrowScreen() {
        composeRule.setContent {
            AiJobFinderTheme {
                Box(Modifier.fillMaxSize()) {
                    ProfileScreen(
                        viewModel = ProfileViewModel(repository),
                        appViewModel = AppViewModel(container),
                        onOpenAi = {},
                    )
                }
            }
        }

        scrollToText("Expat benefits")
        scrollToText("Visa sponsorship")
        scrollToText("Accommodation provided")
        scrollToText("Require documented visa sponsorship")
    }
}
