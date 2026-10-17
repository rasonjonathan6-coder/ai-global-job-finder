package com.aiglobal.jobfinder

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.aiglobal.jobfinder.data.model.ApplicationStatus
import com.aiglobal.jobfinder.data.repository.SampleData
import com.aiglobal.jobfinder.ui.screens.applicationdetail.ApplicationDetailContent
import com.aiglobal.jobfinder.ui.screens.jobs.JobsContent
import com.aiglobal.jobfinder.ui.theme.AiJobFinderTheme
import com.aiglobal.jobfinder.viewmodel.ApplicationDetailUiState
import com.aiglobal.jobfinder.viewmodel.JobsUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 15 layout and behaviour verification at a narrow phone width.
 *
 * The single rule under test: the app records an application as sent only after the user
 * confirms they really submitted it on the employer's page. Every other stage moves on a
 * plain tap, but [ApplicationStatus.APPLIED] is gated behind an explicit dialog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp-hdpi")
class Stage15ScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val savedRecord = runBlocking { SampleData.applications(System.currentTimeMillis()) }
        .first { it.status == ApplicationStatus.SAVED || it.status == ApplicationStatus.PREPARING }

    private fun renderApplicationDetail(onSetStatus: (ApplicationStatus) -> Unit = {}) {
        composeRule.setContent {
            AiJobFinderTheme {
                Box(Modifier.fillMaxSize()) {
                    ApplicationDetailContent(
                        state = ApplicationDetailUiState(
                            isLoading = false,
                            record = savedRecord,
                            nextAction = "Decide whether to apply.",
                        ),
                        onBack = {},
                        onOpenCoverLetter = {},
                        onOpenExpat = {},
                        onSetStatus = onSetStatus,
                        onSetFollowUpAt = {},
                        onArchive = {},
                        onDelete = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun scrollToText(text: String) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    @Test
    fun tappingAppliedDoesNotChangeStatusUntilTheUserConfirms() {
        var selected: ApplicationStatus? = null
        renderApplicationDetail(onSetStatus = { selected = it })

        scrollToText("Applied")
        composeRule.onAllNodesWithText("Applied").onFirst().performClick()

        // The confirmation dialog appears and nothing has moved yet.
        composeRule.onNodeWithText("Mark as applied?").assertIsDisplayed()
        assertNull("No status change before the user confirms", selected)
    }

    @Test
    fun confirmingTheDialogRecordsTheAppliedStatus() {
        var selected: ApplicationStatus? = null
        renderApplicationDetail(onSetStatus = { selected = it })

        scrollToText("Applied")
        composeRule.onAllNodesWithText("Applied").onFirst().performClick()
        composeRule.onNodeWithText("Yes, I applied").performClick()

        assertEquals(ApplicationStatus.APPLIED, selected)
    }

    @Test
    fun dismissingTheDialogLeavesTheApplicationUntouched() {
        var selected: ApplicationStatus? = null
        renderApplicationDetail(onSetStatus = { selected = it })

        scrollToText("Applied")
        composeRule.onAllNodesWithText("Applied").onFirst().performClick()
        composeRule.onNodeWithText("Not yet").performClick()

        assertNull("Dismissing must not record a submission", selected)
    }

    @Test
    fun movingToAPlainStageDoesNotAskForConfirmation() {
        var selected: ApplicationStatus? = null
        renderApplicationDetail(onSetStatus = { selected = it })

        scrollToText("Interview")
        composeRule.onAllNodesWithText("Interview").onFirst().performClick()

        assertEquals(ApplicationStatus.INTERVIEW, selected)
        composeRule.onNodeWithText("Mark as applied?").assertDoesNotExist()
    }

    @Test
    fun theJobsStripDescribesABroadSampleHonestly() {
        val coverage = com.aiglobal.jobfinder.data.jobs.search.SearchCoverage(
            sources = listOf(
                com.aiglobal.jobfinder.data.jobs.search.SearchCoverageSource(
                    providerId = "remotive",
                    providerName = "Remotive",
                    targeted = false,
                    relevantCount = 1,
                    totalCount = 40,
                ),
            ),
            totalRelevant = 1,
            totalRetrieved = 40,
        )
        composeRule.setContent {
            AiJobFinderTheme {
                Box(Modifier.fillMaxSize()) {
                    JobsContent(
                        state = JobsUiState(
                            isLoading = false,
                            jobs = emptyList(),
                            coverage = coverage,
                        ),
                        onQueryChange = {},
                        onToggleSaved = {},
                        onToggleEligible = {},
                        onToggleSponsored = {},
                        onCountryChange = {},
                        onSourceChange = {},
                        onSortChange = {},
                        onClearFilters = {},
                        onRefresh = {},
                        onOpenAi = {},
                        onToggleSaveJob = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Broad sample", substring = true).assertIsDisplayed()
    }

    @Test
    fun newOnlyFilterShowsTheBannerAndJustTheDiscoveredPostings() {
        val jobs = SampleData.jobs(System.currentTimeMillis())
        val newId = jobs.first().id
        composeRule.setContent {
            AiJobFinderTheme {
                Box(Modifier.fillMaxSize()) {
                    JobsContent(
                        state = JobsUiState(
                            isLoading = false,
                            jobs = jobs,
                            newOnly = true,
                            newJobIds = setOf(newId),
                            firstSeenAtByJobId = mapOf(newId to System.currentTimeMillis()),
                        ),
                        onQueryChange = {},
                        onToggleSaved = {},
                        onToggleEligible = {},
                        onToggleSponsored = {},
                        onCountryChange = {},
                        onSourceChange = {},
                        onSortChange = {},
                        onClearFilters = {},
                        onRefresh = {},
                        onOpenAi = {},
                        onToggleSaveJob = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // The banner and the chip both state how many postings are new since the last review.
        composeRule.onNodeWithText("Showing 1 new posting since your last review.").assertIsDisplayed()
        composeRule.onNodeWithText("New (1)").assertIsDisplayed()
    }
}
