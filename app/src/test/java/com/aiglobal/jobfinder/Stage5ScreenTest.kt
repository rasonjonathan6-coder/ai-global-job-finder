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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.aiglobal.jobfinder.data.interview.InterviewCoach
import com.aiglobal.jobfinder.data.model.AutoApplyItem
import com.aiglobal.jobfinder.data.model.AutoApplyState
import com.aiglobal.jobfinder.data.model.AutoApplyStatus
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.JobAlert
import com.aiglobal.jobfinder.data.model.JobAlertRule
import com.aiglobal.jobfinder.data.model.JobAlertsState
import com.aiglobal.jobfinder.data.scam.ScamDetector
import com.aiglobal.jobfinder.data.repository.SampleData
import com.aiglobal.jobfinder.ui.screens.alerts.JobAlertsContent
import com.aiglobal.jobfinder.ui.screens.autoapply.AutoApplyContent
import com.aiglobal.jobfinder.ui.screens.interview.InterviewContent
import com.aiglobal.jobfinder.ui.screens.scam.ScamShieldContent
import com.aiglobal.jobfinder.ui.theme.AiJobFinderTheme
import com.aiglobal.jobfinder.viewmodel.InterviewUiState
import com.aiglobal.jobfinder.viewmodel.ScamUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout verification for the Stage 5 screens at a narrow phone width, where long
 * question prompts, scam evidence and reason strings are most likely to clip. Every
 * important string must be reachable by scrolling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp-hdpi")
class Stage5ScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_700_000_000_000L
    private val jobs = SampleData.jobs(now)
    private val profile = CandidateProfile(
        fullName = "Jonathan Rakoto",
        currentTitle = "Maintenance Technician",
        yearsOfExperience = 6,
        skills = listOf("Preventive maintenance", "Hydraulics", "PLC"),
    )

    private fun scrollToText(text: String) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun render(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent {
            AiJobFinderTheme {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    // ---- Interview coach ---------------------------------------------------

    private val interviewState by lazy {
        val job = jobs.first { it.matchScore == 95 }
        InterviewUiState(
            isLoading = false,
            job = job,
            prep = InterviewCoach(clock = { now }).prep(job, profile),
        )
    }

    @Test
    fun interviewCoachReachesEverySectionOnANarrowScreen() {
        render {
            InterviewContent(
                state = interviewState,
                onBack = {},
                onSelectQuestion = {},
                onAnswerChange = {},
                onConfidenceChange = {},
                onSubmit = {},
                onReset = {},
            )
        }
        composeRule.onNodeWithText("Interview coach").assertIsDisplayed()
        scrollToText("Focus on this")
        scrollToText("Rehearsal questions")
        scrollToText("👋 Opening")
    }

    @Test
    fun interviewEmptyStateExplainsItself() {
        render {
            InterviewContent(state = InterviewUiState(isLoading = false, job = null, prep = null), onBack = {})
        }
        composeRule.onNodeWithText("No interview plan yet").assertIsDisplayed()
    }

    // ---- Scam shield -------------------------------------------------------

    @Test
    fun scamShieldShowsTheHighRiskVerdictAndEvidence() {
        val risky = jobs.first { it.scamRisk == com.aiglobal.jobfinder.data.model.ScamRisk.HIGH }
        render {
            ScamShieldContent(
                state = ScamUiState(isLoading = false, job = risky, report = ScamDetector(clock = { now }).scan(risky)),
                onBack = {},
            )
        }
        composeRule.onNodeWithText("⛔ High scam risk").assertIsDisplayed()
        scrollToText("Stop and read")
        scrollToText("The rules behind this check")
    }

    @Test
    fun scamShieldShowsAGoodVerdictForACleanPosting() {
        val clean = jobs.first { it.matchScore == 95 }
        render {
            ScamShieldContent(
                state = ScamUiState(isLoading = false, job = clean, report = ScamDetector(clock = { now }).scan(clean)),
                onBack = {},
            )
        }
        composeRule.onNodeWithText("✅ Likely safe").assertIsDisplayed()
        scrollToText("Good signs")
    }

    // ---- Job alerts --------------------------------------------------------

    private val alertsState = JobAlertsState(
        rules = listOf(
            JobAlertRule(
                id = "r1",
                name = "Visa-sponsored roles",
                countries = listOf("Canada"),
                minMatchScore = 60,
                createdAtEpochMillis = now,
            ),
        ),
        alerts = listOf(
            JobAlert(
                ruleId = "r1",
                ruleName = "Visa-sponsored roles",
                jobId = "ca-mtc-001",
                jobTitle = "Maintenance Technician",
                company = "Northline Industrial",
                country = "Canada",
                countryFlag = "🇨🇦",
                matchScore = 95,
                isVisaFriendly = true,
                detectedAtEpochMillis = now,
            ),
        ),
    )

    @Test
    fun jobAlertsReachesRulesAndMatchesOnANarrowScreen() {
        render { JobAlertsContent(state = alertsState, onBack = {}) }
        composeRule.onNodeWithText("Job alerts").assertIsDisplayed()
        scrollToText("Saved searches")
        scrollToText("Visa-sponsored roles")
        scrollToText("Matched postings")
    }

    @Test
    fun jobAlertsEmptyStateExplainsItself() {
        render { JobAlertsContent(state = JobAlertsState(), onBack = {}) }
        scrollToText("No saved searches yet. Add one to be told when a matching posting appears.")
        scrollToText("No alerts yet")
    }

    // ---- Auto-apply --------------------------------------------------------

    private val autoApplyState = AutoApplyState(
        enabled = true,
        threshold = 70,
        items = listOf(
            AutoApplyItem(
                jobId = "ca-mtc-001",
                jobTitle = "Maintenance Technician",
                company = "Northline Industrial",
                country = "Canada",
                countryFlag = "🇨🇦",
                matchScore = 95,
                sourceUrl = "https://www.jobbank.gc.ca",
                status = AutoApplyStatus.DRAFT_READY,
                reason = "Match 95% and open to Madagascar applicants. Draft ready for your approval.",
                preparedAtEpochMillis = now,
            ),
        ),
    )

    @Test
    fun autoApplyShowsTheSafetyContractAndTheQueue() {
        render { AutoApplyContent(state = autoApplyState, onBack = {}) }
        scrollToText("What safe auto-apply will and will not do")
        scrollToText("Match threshold")
        scrollToText("Prepared applications")
    }

    @Test
    fun autoApplyOffStateExplainsItself() {
        render { AutoApplyContent(state = AutoApplyState(enabled = false), onBack = {}) }
        composeRule.onNodeWithText("Auto-apply is off").assertIsDisplayed()
    }
}
