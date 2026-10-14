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
import com.aiglobal.jobfinder.data.expat.ShortlistEngine
import com.aiglobal.jobfinder.data.model.ApplicationReadiness
import com.aiglobal.jobfinder.data.model.BenefitEvidence
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.EligibilityReason
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.ExpatPreferences
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobBenefitTypes
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.ShortlistState
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import com.aiglobal.jobfinder.ui.screens.shortlist.ShortlistContent
import com.aiglobal.jobfinder.ui.theme.AiJobFinderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout verification for the Stage 11 expat shortlist at a narrow phone width. The
 * headline, the readiness band, the blocker summary and both actions must stay reachable
 * by scrolling so the user can read why a role is ready and act on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp-hdpi")
class Stage11ScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val engine = ShortlistEngine(clock = { 1_700_000_000_000 })

    private val profile = CandidateProfile(
        fullName = "Jonathan Rakoto",
        currentTitle = "Maintenance Technician",
        yearsOfExperience = 6,
        skills = listOf("Preventive maintenance", "Hydraulics"),
    )

    private fun job(
        id: String,
        title: String,
        matchScore: Int,
        eligibility: MadagascarEligibility,
        benefitEvidence: List<BenefitEvidence>,
    ) = Job(
        id = id,
        title = title,
        company = "Northline Industrial",
        companyAccent = 0xFF1E4FD8,
        country = "Canada",
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
        postedAtEpochMillis = 1_699_999_000_000,
        source = "Canada Job Bank",
        sourceUrl = "https://www.jobbank.gc.ca",
        workMode = WorkMode.ONSITE,
        employmentType = EmploymentType.FULL_TIME,
        experienceYears = 3,
        matchedSkills = listOf("Preventive maintenance"),
        missingSkills = emptyList(),
        languages = listOf("English (B2+)"),
        summary = "Maintain production line machinery across two shifts.",
        responsibilities = listOf("Run preventive maintenance"),
        benefits = listOf("Relocation allowance"),
        aiInsight = "Strong technical fit.",
        eligibilityReasons = listOf(
            EligibilityReason("Visa sponsorship offered", "The employer states visa sponsorship.", official = true),
        ),
        benefitEvidence = benefitEvidence,
    )

    /** Every benefit the default preferences weight, so the role lands in the top band. */
    private val documentedBenefits = listOf(
        BenefitEvidence(JobBenefitTypes.VISA_SPONSORSHIP, BenefitStatus.CONFIRMED, "Visa sponsorship provided."),
        BenefitEvidence(JobBenefitTypes.RELOCATION_SUPPORT, BenefitStatus.CONFIRMED, "Relocation support is included."),
        BenefitEvidence(JobBenefitTypes.ACCOMMODATION, BenefitStatus.CONFIRMED, "Accommodation is provided."),
        BenefitEvidence(JobBenefitTypes.FLIGHTS_HOME, BenefitStatus.CONFIRMED, "One flight home each year."),
        BenefitEvidence(JobBenefitTypes.HEALTH_INSURANCE, BenefitStatus.CONFIRMED, "Private health insurance."),
        BenefitEvidence(JobBenefitTypes.FAMILY_SUPPORT, BenefitStatus.CONFIRMED, "Dependants are supported."),
    )

    private fun stateWithOneReadyRole(): ShortlistState {
        val entry = engine.assess(
            job = job(
                id = "job-1",
                title = "Maintenance Technician",
                matchScore = 100,
                eligibility = MadagascarEligibility.VISA_SPONSORED,
                benefitEvidence = documentedBenefits,
            ),
            profile = profile,
            preferences = ExpatPreferences.defaults(),
            profileCompletenessPercent = 100,
            salaryEurPerYear = 60000,
        )
        return ShortlistState(isLoading = false, entries = listOf(entry), totalJobs = 12, sourceNote = "Live from 3 sources.")
    }

    private fun scrollToText(text: String) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun render(state: ShortlistState) {
        composeRule.setContent {
            AiJobFinderTheme {
                Box(Modifier.fillMaxSize()) {
                    ShortlistContent(state = state, onBack = {}, onRefresh = {})
                }
            }
        }
    }

    @Test
    fun theReadyRoleAndItsActionsAreReachableOnANarrowScreen() {
        render(stateWithOneReadyRole())

        // The header sits above the list, so it is asserted directly.
        composeRule.onNodeWithText("Expat shortlist").assertIsDisplayed()
        scrollToText("Ready to act on")
        scrollToText("Maintenance Technician")
        scrollToText("View job")
        scrollToText("Apply")
    }

    @Test
    fun theReadinessBandAndBlockerSummaryAreShown() {
        render(stateWithOneReadyRole())

        scrollToText(ApplicationReadiness.PRIORITY.label)
        scrollToText("Nothing is holding this back — the route and your profile both line up.")
    }

    @Test
    fun theEmptyStateExplainsWhyThereIsNothingToShow() {
        render(ShortlistState(isLoading = false, entries = emptyList(), totalJobs = 0))

        composeRule.onNodeWithText("No shortlist yet").assertIsDisplayed()
        composeRule.onNodeWithText("Refresh").assertIsDisplayed()
    }
}
