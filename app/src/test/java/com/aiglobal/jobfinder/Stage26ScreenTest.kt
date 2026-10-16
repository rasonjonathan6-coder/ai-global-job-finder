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
 * Layout verification for the Stage 26 shortlist-quality dashboard and the actionable
 * ranking, at a narrow phone width. The quality read, the two ranked sections and the
 * per-posting next step must all stay reachable by scrolling, so the user can see how
 * strong the shortlist is and what to do about each posting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp-hdpi")
class Stage26ScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val engine = ShortlistEngine(clock = { 1_700_000_000_000 })

    private val profile = CandidateProfile(
        fullName = "Jonathan Rakoto",
        currentTitle = "Maintenance Technician",
        yearsOfExperience = 6,
        skills = listOf("Preventive maintenance", "Hydraulics"),
    )

    private val documentedBenefits = listOf(
        BenefitEvidence(JobBenefitTypes.VISA_SPONSORSHIP, BenefitStatus.CONFIRMED, "Visa sponsorship provided."),
        BenefitEvidence(JobBenefitTypes.RELOCATION_SUPPORT, BenefitStatus.CONFIRMED, "Relocation support is included."),
        BenefitEvidence(JobBenefitTypes.ACCOMMODATION, BenefitStatus.CONFIRMED, "Accommodation is provided."),
        BenefitEvidence(JobBenefitTypes.FLIGHTS_HOME, BenefitStatus.CONFIRMED, "One flight home each year."),
        BenefitEvidence(JobBenefitTypes.HEALTH_INSURANCE, BenefitStatus.CONFIRMED, "Private health insurance."),
        BenefitEvidence(JobBenefitTypes.FAMILY_SUPPORT, BenefitStatus.CONFIRMED, "Dependants are supported."),
    )

    private fun job(
        id: String,
        title: String = "Maintenance Technician",
        matchScore: Int,
        eligibility: MadagascarEligibility = MadagascarEligibility.VISA_SPONSORED,
        visaSupport: VisaSupport = VisaSupport.FULL_SPONSORSHIP,
        eligibilityReasons: List<EligibilityReason> = listOf(
            EligibilityReason("Visa sponsorship offered", "The employer states visa sponsorship.", official = true),
        ),
        benefitEvidence: List<BenefitEvidence> = documentedBenefits,
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
        visaSupport = visaSupport,
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
        eligibilityReasons = eligibilityReasons,
        benefitEvidence = benefitEvidence,
    )

    private fun readyEntry(id: String = "job-1", title: String = "Maintenance Technician") = engine.assess(
        job = job(id = id, title = title, matchScore = 100),
        profile = profile,
        preferences = ExpatPreferences.defaults(),
        profileCompletenessPercent = 100,
        salaryEurPerYear = 60000,
    )

    /** A posting with an unstated route, so it is not actionable but is worth preparing for. */
    private fun prepareEntry(id: String = "job-p", title: String = "Line Mechanic") = engine.assess(
        job = job(
            id = id,
            title = title,
            matchScore = 60,
            eligibility = MadagascarEligibility.UNCLEAR,
            visaSupport = VisaSupport.NOT_MENTIONED,
            eligibilityReasons = emptyList(),
            benefitEvidence = emptyList(),
        ),
        profile = profile,
        preferences = ExpatPreferences.defaults(),
        profileCompletenessPercent = 50,
        salaryEurPerYear = null,
    )

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
    fun theQualityDashboardIsReachableOnANarrowScreen() {
        render(ShortlistState(isLoading = false, entries = listOf(readyEntry()), totalJobs = 12))

        scrollToText("Shortlist quality")
        scrollToText("Open to you")
        scrollToText("1 posting to start with")
    }

    @Test
    fun aReadyPostingOffersNoPreparationSection() {
        render(ShortlistState(isLoading = false, entries = listOf(readyEntry()), totalJobs = 12))

        scrollToText("Ready to act on")
        composeRule.onNodeWithText("Worth preparing for").assertDoesNotExist()
    }

    @Test
    fun aPostingWithAGapShowsItsSingleNextStep() {
        render(ShortlistState(isLoading = false, entries = listOf(prepareEntry()), totalJobs = 12))

        scrollToText("Nothing is ready yet")
        scrollToText("Worth preparing for")
        // The hardest blocker is the unstated route, so the next step is to check it — not to give up.
        scrollToText("Next: Check eligibility first")
    }

    @Test
    fun theEmptyStateExplainsWhyThereIsNothingToShow() {
        render(ShortlistState(isLoading = false, entries = emptyList(), totalJobs = 0))

        composeRule.onNodeWithText("No shortlist yet").assertIsDisplayed()
        composeRule.onNodeWithText("Refresh").assertIsDisplayed()
    }
}
