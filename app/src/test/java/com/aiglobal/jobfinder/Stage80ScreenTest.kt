package com.aiglobal.jobfinder

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aiglobal.jobfinder.data.jobs.offer.OfferMatchRanking
import com.aiglobal.jobfinder.data.jobs.search.RoleFamily
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobSource
import com.aiglobal.jobfinder.data.model.JobSyncState
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.SourceState
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import com.aiglobal.jobfinder.ui.screens.bestoffers.BestOffersContent
import com.aiglobal.jobfinder.ui.theme.AiJobFinderTheme
import com.aiglobal.jobfinder.viewmodel.BestOffersUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 80: the "My best offers" screen must render each state it can be in and hand the
 * right postings to the right action. These tests pin the visible furniture so a regression
 * in the card, the empty states or the error state fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class Stage80ScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        const val DAY = 86_400_000L
        const val NOW = 1_760_000_000_000L
    }

    private val ranking = OfferMatchRanking()

    private val profile = CandidateProfile(
        fullName = "Jonathan Rakoto",
        currentTitle = "Maintenance Technician",
        yearsOfExperience = 8,
        skills = listOf("Preventive maintenance", "Hydraulics", "Electrical diagnostics", "PLC", "Welding"),
        preferredRoles = listOf("Maintenance Technician"),
    )

    private fun job(id: String = "j1") = Job(
        id = id,
        title = "Maintenance Technician",
        company = "Acme Industrial",
        companyAccent = 0xFF1E4FD8,
        country = "Canada",
        countryFlag = "🇨🇦",
        city = "Toronto",
        salaryMin = 60_000,
        salaryMax = 72_000,
        currencyCode = "CAD",
        salaryPeriod = SalaryPeriod.YEAR,
        matchScore = 90,
        eligibility = MadagascarEligibility.ELIGIBLE,
        visaSupport = VisaSupport.NOT_MENTIONED,
        scamRisk = ScamRisk.LOW,
        postedAtEpochMillis = NOW - DAY,
        source = "Remotive",
        sourceUrl = "https://example.com/$id",
        workMode = WorkMode.ONSITE,
        employmentType = EmploymentType.FULL_TIME,
        experienceYears = 5,
        matchedSkills = listOf("Preventive maintenance", "Hydraulics", "Electrical diagnostics"),
        missingSkills = emptyList(),
        languages = listOf("English"),
        summary = "Summary",
        responsibilities = emptyList(),
        benefits = emptyList(),
        aiInsight = "insight",
        providerId = "remotive",
        roleFamily = RoleFamily.TARGET,
        matchedTargetRole = "Mechanical Maintenance Technician",
    )

    private fun offers() = ranking.rank(listOf(job()), profile, NOW)

    private fun baseState(
        hasCv: Boolean = true,
        cvAnalysed: Boolean = true,
        offers: List<com.aiglobal.jobfinder.data.model.RankedOffer> = offers(),
    ) = BestOffersUiState(
        isLoading = false,
        hasCv = hasCv,
        cvAnalysed = cvAnalysed,
        profileName = profile.fullName,
        offers = offers,
        totalCompatible = offers.size,
        lastSyncedAtEpochMillis = NOW,
    )

    @Test
    fun withNoCvTheScreenAsksForAnImport() {
        composeRule.setContent {
            AiJobFinderTheme {
                BestOffersContent(
                    state = baseState(hasCv = false, cvAnalysed = false, offers = emptyList()),
                    onBack = {},
                    onRefresh = {},
                )
            }
        }
        composeRule.onNodeWithText("Import your CV to begin").assertIsDisplayed()
    }

    @Test
    fun anImportedButUnanalysedCvAsksForAnAnalysis() {
        composeRule.setContent {
            AiJobFinderTheme {
                BestOffersContent(
                    state = baseState(cvAnalysed = false, offers = emptyList()),
                    onBack = {},
                    onRefresh = {},
                )
            }
        }
        composeRule.onNodeWithText("Analyse your CV").assertIsDisplayed()
    }

    @Test
    fun anOfferRendersItsTitleCompanyBandAndActions() {
        composeRule.setContent {
            AiJobFinderTheme {
                BestOffersContent(state = baseState(), onBack = {}, onRefresh = {})
            }
        }
        composeRule.onNodeWithText("Maintenance Technician").assertIsDisplayed()
        composeRule.onNodeWithText("Acme Industrial").assertIsDisplayed()
        composeRule.onNodeWithText("Apply").assertIsDisplayed()
        composeRule.onNodeWithText("View job").assertIsDisplayed()
    }

    @Test
    fun anOutageRendersTheErrorStateRatherThanAnEmptyState() {
        val failed = JobSyncState(
            sources = listOf(
                JobSource(id = "a", name = "A", homepage = "https://a.example", state = SourceState.ERROR),
                JobSource(id = "b", name = "B", homepage = "https://b.example", state = SourceState.ERROR),
            ),
        )
        composeRule.setContent {
            AiJobFinderTheme {
                BestOffersContent(
                    state = baseState(offers = emptyList()).copy(
                        totalCompatible = 0,
                        syncState = failed,
                    ),
                    onBack = {},
                    onRefresh = {},
                )
            }
        }
        composeRule.onNodeWithText("We could not reach the job sources").assertIsDisplayed()
    }

    @Test
    fun theBackAffordanceReportsTheTap() {
        var backed = false
        composeRule.setContent {
            AiJobFinderTheme {
                BestOffersContent(state = baseState(), onBack = { backed = true }, onRefresh = {})
            }
        }
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(backed)
    }
}
