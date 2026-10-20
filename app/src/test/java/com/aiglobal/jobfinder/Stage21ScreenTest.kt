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
import com.aiglobal.jobfinder.data.assistant.ApplicationPlanEngine
import com.aiglobal.jobfinder.data.assistant.ExpatIntelligenceEngine
import com.aiglobal.jobfinder.data.expat.ShortlistEngine
import com.aiglobal.jobfinder.data.jobs.search.RoleClassifier
import com.aiglobal.jobfinder.data.model.BenefitEvidence
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.ExpatPreferences
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobBenefitTypes
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import com.aiglobal.jobfinder.ui.screens.assistant.ApplicationAssistantContent
import com.aiglobal.jobfinder.ui.screens.assistant.ExpatIntelligenceContent
import com.aiglobal.jobfinder.ui.theme.AiJobFinderTheme
import com.aiglobal.jobfinder.viewmodel.ApplicationPlanUiState
import com.aiglobal.jobfinder.viewmodel.ExpatIntelligenceUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout verification for the Stage 21 assistant screens at a narrow phone width. The
 * readiness headline, the checklist and the actions must all stay reachable by scrolling,
 * and the empty states must render rather than crash when there is nothing to show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp-hdpi")
class Stage21ScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_700_000_000_000L
    private val shortlistEngine = ShortlistEngine(clock = { now })
    private val planEngine = ApplicationPlanEngine(clock = { now })
    private val intelligenceEngine = ExpatIntelligenceEngine(clock = { now })

    private val profile = CandidateProfile(
        fullName = "Jonathan Rakoto",
        currentTitle = "Maintenance Technician",
        previousTitles = listOf("Industrial Electrician"),
        yearsOfExperience = 6,
        skills = listOf("Preventive maintenance", "Hydraulics", "PLC"),
    )

    private fun job(
        id: String = "job-1",
        title: String = "Maintenance Technician",
        country: String = "Canada",
        matchScore: Int = 92,
        eligibility: MadagascarEligibility = MadagascarEligibility.VISA_SPONSORED,
    ) = Job(
        id = id,
        title = title,
        company = "Northline Industrial",
        companyAccent = 0xFF1E4FD8,
        country = country,
        countryFlag = "🇨🇦",
        city = "Calgary",
        salaryMin = 58000,
        salaryMax = 74000,
        currencyCode = "CAD",
        salaryPeriod = SalaryPeriod.YEAR,
        matchScore = matchScore,
        eligibility = eligibility,
        visaSupport = VisaSupport.FULL_SPONSORSHIP,
        scamRisk = ScamRisk.LOW,
        postedAtEpochMillis = now - 86_400_000,
        source = "Canada Job Bank",
        sourceUrl = "https://www.jobbank.gc.ca",
        workMode = WorkMode.ONSITE,
        employmentType = EmploymentType.FULL_TIME,
        experienceYears = 3,
        matchedSkills = listOf("Preventive maintenance", "Hydraulics"),
        missingSkills = listOf("Canadian Red Seal certification"),
        languages = listOf("English (B2+)"),
        summary = "Maintain production line machinery across two shifts.",
        responsibilities = listOf("Run preventive maintenance", "Log work orders"),
        benefits = listOf("Relocation allowance"),
        aiInsight = "Strong technical fit.",
        benefitEvidence = listOf(
            BenefitEvidence(JobBenefitTypes.VISA_SPONSORSHIP, BenefitStatus.CONFIRMED, "Visa sponsorship provided."),
            BenefitEvidence(JobBenefitTypes.RELOCATION_SUPPORT, BenefitStatus.CONFIRMED, "Relocation support is included."),
        ),
        // The real mapper fills the role verdict from the title; these fixtures build Job by
        // hand, so they classify it the same way the pipeline would rather than defaulting.
        roleFamily = RoleClassifier.classify(title).family,
    )

    private fun planState(): ApplicationPlanUiState {
        val job = job()
        val entry = shortlistEngine.assess(job, profile, ExpatPreferences.defaults(), 100, 60_000)
        return ApplicationPlanUiState(
            isLoading = false,
            job = job,
            plan = planEngine.build(
                job = job,
                entry = entry,
                profileCompletenessPercent = 100,
                hasCoverLetter = false,
            ),
        )
    }

    private fun intelligenceState(): ExpatIntelligenceUiState {
        val shortlist = shortlistEngine.build(
            jobs = listOf(job()),
            profile = profile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 100,
            salaryEurPerYear = { 60_000 },
        )
        return ExpatIntelligenceUiState(isLoading = false, intelligence = intelligenceEngine.build(shortlist))
    }

    @Test
    fun theAssistantShowsTheHeadlineAndTheChecklist() {
        composeRule.setContent {
            AiJobFinderTheme {
                Box(Modifier.fillMaxSize()) {
                    ApplicationAssistantContent(
                        state = planState(),
                        onBack = {},
                        onRefresh = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Application assistant").assertIsDisplayed()
        composeRule.onNodeWithText("Next best action").assertIsDisplayed()

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Your application checklist"))
        composeRule.onNodeWithText("Your application checklist").assertIsDisplayed()

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Write a cover letter"))
        composeRule.onNodeWithText("Write a cover letter").assertIsDisplayed()
    }

    @Test
    fun theAssistantEmptyStateRendersWhenThePostingIsGone() {
        composeRule.setContent {
            AiJobFinderTheme {
                Box(Modifier.fillMaxSize()) {
                    ApplicationAssistantContent(
                        state = ApplicationPlanUiState(isLoading = false, message = "Posting gone."),
                        onBack = {},
                        onRefresh = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Posting unavailable").assertIsDisplayed()
    }

    @Test
    fun theIntelligenceDigestShowsTheRoutesAndTheStrongestRoles() {
        composeRule.setContent {
            AiJobFinderTheme {
                Box(Modifier.fillMaxSize()) {
                    ExpatIntelligenceContent(
                        state = intelligenceState(),
                        onBack = {},
                        onRefresh = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Expat intelligence").assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Where the routes are"))
        composeRule.onNodeWithText("Where the routes are").assertIsDisplayed()

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Strongest opportunities"))
        composeRule.onNodeWithText("Strongest opportunities").assertIsDisplayed()
    }

    @Test
    fun theIntelligenceEmptyStateRendersOnAnEmptyFeed() {
        composeRule.setContent {
            AiJobFinderTheme {
                Box(Modifier.fillMaxSize()) {
                    ExpatIntelligenceContent(
                        state = ExpatIntelligenceUiState(isLoading = false),
                        onBack = {},
                        onRefresh = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("No intelligence yet").assertIsDisplayed()
    }
}
