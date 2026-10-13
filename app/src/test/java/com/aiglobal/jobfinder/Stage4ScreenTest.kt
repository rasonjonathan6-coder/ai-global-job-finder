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
import com.aiglobal.jobfinder.data.expat.ExpatBenefitsCatalog
import com.aiglobal.jobfinder.data.model.ApplicationStatus
import com.aiglobal.jobfinder.data.repository.SampleData
import com.aiglobal.jobfinder.ui.screens.applicationdetail.ApplicationDetailContent
import com.aiglobal.jobfinder.ui.screens.coverletter.CoverLetterContent
import com.aiglobal.jobfinder.ui.screens.expat.ExpatContent
import com.aiglobal.jobfinder.ui.theme.AiJobFinderTheme
import com.aiglobal.jobfinder.viewmodel.ApplicationDetailUiState
import com.aiglobal.jobfinder.viewmodel.CoverLetterUiState
import com.aiglobal.jobfinder.viewmodel.ExpatUiState
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout verification for the Stage 4 screens at a narrow phone width, where long country
 * names, job titles and benefit details are most likely to clip. Every important string
 * must be reachable by scrolling, and the stage controls must report the right state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp-hdpi")
class Stage4ScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val interviewRecord = runBlocking { SampleData.applications(System.currentTimeMillis()) }
        .first { it.status == ApplicationStatus.INTERVIEW }

    @Test
    fun applicationDetailReachesEverySectionOnANarrowScreen() {
        renderApplicationDetail()
        composeRule.onNodeWithText("Progress").assertIsDisplayed()
        scrollToText("Reminders")
        scrollToText("Cover letter")
        scrollToText("Settlement in Canada")
        scrollToText("History")
    }

    @Test
    fun tappingAStageAdvancesTheApplication() {
        var selected: ApplicationStatus? = null
        renderApplicationDetail(onSetStatus = { selected = it })
        scrollToText("Offer")
        composeRule.onNodeWithText("Offer").performClick()
        assert(selected == ApplicationStatus.OFFER) { "Tapping a stage must move the application to it" }
    }

    @Test
    fun coverLetterShowsLetterAndGroundingOnANarrowScreen() {
        renderCoverLetter()
        composeRule.onNodeWithText("Language").assertIsDisplayed()
        composeRule.onNodeWithText("Generate again").assertIsDisplayed()
        scrollToText("Candidature — Maintenance Technician")
        scrollToText("Built from your CV")
    }

    @Test
    fun coverLetterEmptyStateAsksForACv() {
        renderCoverLetter(withLetter = false)
        composeRule.onNodeWithText("No letter yet").assertIsDisplayed()
        scrollToText("Generate cover letter")
        scrollToText("Import and analyse your CV first, so the letter can use your real details.")
    }

    @Test
    fun expatGuideListsBenefitsWithSourcesOnANarrowScreen() {
        renderExpat()
        composeRule.onAllNodesWithText("Canada").onFirst().assertIsDisplayed()
        scrollToText("Work permit & visa", substring = true)
        scrollToText("Practical notes")
    }

    @Test
    fun expatGuideEmptyStateExplainsMissingCoverage() {
        renderExpat(withGuide = false)
        composeRule.onNodeWithText("No guide for Canada yet").assertIsDisplayed()
    }

    /**
     * Scrolls a lazy list until the given text is composed. Off-screen items do not exist
     * in the semantics tree, so the list itself has to be scrolled.
     */
    private fun scrollToText(text: String, substring: Boolean = false) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = substring))
        composeRule.onAllNodesWithText(text, substring = substring).onFirst().assertIsDisplayed()
    }

    private fun renderApplicationDetail(onSetStatus: (ApplicationStatus) -> Unit = {}) {
        render {
            ApplicationDetailContent(
                state = ApplicationDetailUiState(
                    isLoading = false,
                    record = interviewRecord,
                    nextAction = "Prepare for the interview and confirm the time zone.",
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

    private fun renderCoverLetter(withLetter: Boolean = true) {
        render {
            CoverLetterContent(
                state = CoverLetterUiState(
                    isLoading = false,
                    job = runBlocking { SampleData.jobs(System.currentTimeMillis()) }
                        .first { it.id == interviewRecord.jobId },
                    language = com.aiglobal.jobfinder.data.model.LetterLanguage.FRENCH,
                    letter = if (withLetter) sampleLetter() else null,
                ),
                onBack = {},
                onSelectLanguage = {},
                onGenerate = {},
            )
        }
    }

    private fun renderExpat(withGuide: Boolean = true) {
        render {
            ExpatContent(
                state = ExpatUiState(
                    isLoading = false,
                    guide = if (withGuide) ExpatBenefitsCatalog.forCountry("Canada") else null,
                ),
                country = "Canada",
                onBack = {},
            )
        }
    }

    private fun sampleLetter() = com.aiglobal.jobfinder.data.model.CoverLetter(
        id = "letter-1",
        jobId = interviewRecord.jobId,
        language = com.aiglobal.jobfinder.data.model.LetterLanguage.FRENCH,
        subject = "Candidature — Maintenance Technician",
        salutation = "Madame, Monsieur,",
        paragraphs = listOf("Je vous adresse ma candidature au poste de Maintenance Technician."),
        closing = "Je vous prie d'agréer mes salutations distinguées.",
        signature = "Jonathan Rakoto",
        usedFacts = listOf("6 ans d'expérience"),
        missingFacts = listOf("Certification linguistique"),
        generatedAtEpochMillis = System.currentTimeMillis(),
    )

    private fun render(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent {
            AiJobFinderTheme {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
        composeRule.waitForIdle()
    }
}
