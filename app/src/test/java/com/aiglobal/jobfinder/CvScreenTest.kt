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
import com.aiglobal.jobfinder.data.model.CandidateField
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.CertificationEntry
import com.aiglobal.jobfinder.data.model.CompletenessHint
import com.aiglobal.jobfinder.data.model.CvFileType
import com.aiglobal.jobfinder.data.model.CvRecord
import com.aiglobal.jobfinder.data.model.CvStatus
import com.aiglobal.jobfinder.data.model.EducationEntry
import com.aiglobal.jobfinder.data.model.LanguageLevel
import com.aiglobal.jobfinder.data.model.LanguageSkill
import com.aiglobal.jobfinder.data.model.ProfileCompleteness
import com.aiglobal.jobfinder.ui.screens.cv.CvScreen
import com.aiglobal.jobfinder.ui.theme.AiJobFinderTheme
import com.aiglobal.jobfinder.viewmodel.CvMessage
import com.aiglobal.jobfinder.viewmodel.CvUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout verification for the CV screen at a narrow phone width. Narrow screens are where
 * long file names, long skill labels and the editable rows are most likely to clip, so the
 * assertions run at 320dp and require the important text to be reachable by scrolling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp-hdpi")
class CvScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val analysedRecord = CvRecord(
        fileName = "A_very_long_cv_file_name_that_should_ellipsize.pdf",
        storedFileName = "x.pdf",
        fileType = CvFileType.PDF,
        sizeBytes = 3_400_000,
        importedAtEpochMillis = 1_760_000_000_000,
        analyzedAtEpochMillis = 1_760_000_120_000,
        status = CvStatus.ANALYZED,
        pageCount = 2,
        extractedCharacters = 4_812,
        engineId = "on-device-heuristic",
        engineLabel = "On-device analysis",
        profile = CandidateProfile(
            fullName = "Jonathan Rakoto",
            email = "jonathan.rakoto@example.mg",
            phone = "+261 34 12 345 67",
            location = "Antananarivo, Madagascar",
            currentTitle = "Maintenance Technician",
            yearsOfExperience = 6,
            skills = listOf(
                "Preventive maintenance",
                "Industrial hydraulics and pneumatics",
                "Programmable logic controllers",
                "Electrical wiring and panel building",
            ),
            languages = listOf(
                LanguageSkill("French", LanguageLevel.FLUENT),
                LanguageSkill("Malagasy", LanguageLevel.NATIVE),
            ),
            education = listOf(EducationEntry("BTS Electrotechnics", "IST Antananarivo", "Madagascar", 2018)),
            certifications = listOf(CertificationEntry("Industrial Safety Level 2", year = 2021)),
            summary = "Maintenance technician with six years in food-processing plants.",
        ),
    )

    @Test
    fun emptyStateInvitesImportAndOffersNoAnalysis() {
        render(CvUiState(isLoading = false))
        composeRule.onNodeWithText("AI CV Analysis").assertIsDisplayed()
        composeRule.onNodeWithText("Choose a file").assertIsDisplayed()
        composeRule.onNodeWithText("No CV imported yet").assertIsDisplayed()
        // Nothing has been imported, so there must be no analyse action to press.
        composeRule.onNodeWithText("Analyse my CV").assertDoesNotExist()
    }

    @Test
    fun importedButNotAnalysedShowsAnalysisPrompt() {
        render(CvUiState(isLoading = false, record = analysedRecord.copy(status = CvStatus.IMPORTED)))
        scrollToText("Your CV is ready to be analysed")
        scrollToText("Analyse my CV")
    }

    @Test
    fun analysedProfileShowsEverySectionAndReachesThemByScrolling() {
        render(
            CvUiState(
                isLoading = false,
                record = analysedRecord,
                completeness = ProfileCompleteness(
                    percent = 82,
                    missing = listOf(CompletenessHint("Preferred countries", CandidateField.PREFERRED_COUNTRIES)),
                ),
            ),
        )
        composeRule.onNodeWithText("Analysis complete").assertIsDisplayed()
        composeRule.onNodeWithText("82%").assertIsDisplayed()
        // The name is shown both as the profile title and as an editable extracted row.
        scrollToText("Jonathan Rakoto")
        scrollToText("Skills detected")
        scrollToText("Education")
        scrollToText("Certifications")
        scrollToText("Languages")
    }

    /**
     * Scrolls the CV list until the given text is composed and visible. Off-screen items in
     * a LazyColumn do not exist in the semantics tree, so the list itself must be scrolled
     * rather than the node.
     */
    private fun scrollToText(text: String) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        composeRule.onAllNodesWithText(text).onFirst().assertIsDisplayed()
    }

    @Test
    fun failedParseShowsRecoverableErrorWithRetry() {
        var retried = false
        render(
            CvUiState(
                isLoading = false,
                record = analysedRecord.copy(
                    status = CvStatus.FAILED,
                    warnings = listOf("This PDF has no text layer. Export it as text instead."),
                ),
            ),
            onPickFile = { retried = true },
        )
        composeRule.onNodeWithText("We could not read this CV").assertIsDisplayed()
        scrollToText("Try again")
        composeRule.onNodeWithText("Try again").performClick()
        assert(retried) { "Retry must route back to the file picker" }
    }

    @Test
    fun errorMessageIsShownAndDismissable() {
        var dismissed = false
        render(
            CvUiState(
                isLoading = false,
                message = CvMessage("Import failed. Please try another file."),
            ),
            onDismissMessage = { dismissed = true },
        )
        composeRule.onNodeWithText("Import failed. Please try another file.").assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performClick()
        assert(dismissed) { "Dismiss must clear the message" }
    }

    private fun render(
        state: CvUiState,
        onPickFile: () -> Unit = {},
        onDismissMessage: () -> Unit = {},
    ) {
        composeRule.setContent {
            AiJobFinderTheme {
                Box(Modifier.fillMaxSize()) {
                    CvScreen(
                        state = state,
                        onBack = {},
                        onPickFile = onPickFile,
                        onAnalyze = {},
                        onRemove = {},
                        onEditField = { _, _ -> },
                        onDismissMessage = onDismissMessage,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }
}
