package com.aiglobal.jobfinder

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.aiglobal.jobfinder.data.ai.AssistTask
import com.aiglobal.jobfinder.ui.screens.ai.AiScreen
import com.aiglobal.jobfinder.ui.screens.ai.AssistantState
import com.aiglobal.jobfinder.ui.theme.AiJobFinderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 98 — the AI assistant workspace, checked on a real phone window.
 *
 * The panel is the one screen that can send data off the device, so the tests here pin that
 * every drafting task is reachable, that the user is told which posting will be read, and
 * that tapping a task runs exactly that task. A narrow `w411dp-h891dp` window is used because
 * the task list must not overflow or push a row off the card edge on a typical phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class Stage98AiAssistantScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun render(state: AssistantState, onRun: (AssistTask) -> Unit = {}) {
        composeRule.setContent {
            AiJobFinderTheme {
                AiScreen(
                    onBrowseJobs = {},
                    onOpenCv = {},
                    autoRun = false,
                    hasCv = true,
                    skillCount = 9,
                    strongMatchCount = 4,
                    assistant = state,
                    onRunAssistant = onRun,
                )
            }
        }
    }

    private val ready = AssistantState(
        configured = true,
        consent = true,
        ready = true,
        modelLabel = "Llama 3.1 8B Instruct",
        languageLabel = "Français",
        bestJobTitle = "Maintenance Technician",
        bestJobCompany = "Northwind Industrial",
        bestJobCountry = "Canada",
    )

    @Test
    fun thePanelNamesThePostingItWillReadAndTheDraftLanguage() {
        render(ready)
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("Maintenance Technician"))

        composeRule.onNodeWithText("Maintenance Technician").assertIsDisplayed()
        composeRule.onNodeWithText("Northwind Industrial · Canada").assertIsDisplayed()
        composeRule.onNodeWithText("Français", substring = true).assertIsDisplayed()
    }

    @Test
    fun everyDraftingTaskIsReachableOnAPhoneWindow() {
        render(ready)
        AssistTask.entries.forEach { task ->
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(task.label))
            composeRule.onNodeWithText(task.label).assertIsDisplayed()
        }
    }

    @Test
    fun tappingATaskRunsThatTask() {
        var ran: AssistTask? = null
        render(ready, onRun = { ran = it })

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(AssistTask.COVER_LETTER.label))
        composeRule.onNodeWithText(AssistTask.COVER_LETTER.label).performClick()

        assertEquals(AssistTask.COVER_LETTER, ran)
    }
}
