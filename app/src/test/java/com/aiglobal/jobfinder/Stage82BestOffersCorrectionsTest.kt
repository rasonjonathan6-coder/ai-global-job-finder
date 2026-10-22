package com.aiglobal.jobfinder

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 82: the targeted corrections to the "My best offers" section. Each test pins one of
 * the fixes — the first-launch actions, the honest refresh outcome, the kept-offers warning
 * and the source of the last-updated timestamp — so a regression fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class Stage82BestOffersCorrectionsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val DAY = 86_400_000L
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

    private fun source(id: String, state: SourceState) =
        JobSource(id = id, name = id.uppercase(), homepage = "https://$id.example", state = state)

    // --- 1. First-launch actions open the real CV screen and the real AI tab. ---

    @Test
    fun theImportCvActionOpensTheCvScreenNotTheJobsTab() {
        var openedCv = false
        var openedAi = false
        composeRule.setContent {
            AiJobFinderTheme {
                BestOffersContent(
                    state = baseState(hasCv = false, cvAnalysed = false, offers = emptyList()),
                    onBack = {},
                    onRefresh = {},
                    onOpenCv = { openedCv = true },
                    onOpenAi = { openedAi = true },
                )
            }
        }
        composeRule.onNodeWithText("Import your CV").performClick()
        assertTrue(openedCv)
        assertFalse(openedAi)
    }

    @Test
    fun theAnalyseCvActionOpensTheRealAiTab() {
        var openedCv = false
        var openedAi = false
        composeRule.setContent {
            AiJobFinderTheme {
                BestOffersContent(
                    state = baseState(cvAnalysed = false, offers = emptyList()),
                    onBack = {},
                    onRefresh = {},
                    onOpenCv = { openedCv = true },
                    onOpenAi = { openedAi = true },
                )
            }
        }
        composeRule.onNodeWithText("Open the AI tab").performClick()
        assertTrue(openedAi)
        assertFalse(openedCv)
    }

    // --- 2. Refresh outcome is read from the sync state, not from whether refresh() threw. ---

    @Test
    fun aPartialFailureIsAnnouncedAndCanBeAcknowledged() {
        val partial = JobSyncState(
            lastSyncedAtEpochMillis = NOW,
            sources = listOf(source("a", SourceState.SUCCESS), source("b", SourceState.ERROR)),
        )
        var state by mutableStateOf(baseState().copy(syncState = partial))
        composeRule.setContent {
            AiJobFinderTheme {
                BestOffersContent(
                    state = state,
                    onBack = {},
                    onRefresh = {},
                    onAcknowledgePartial = { state = state.copy(partialAcknowledged = true) },
                )
            }
        }
        composeRule.onNodeWithText("Some sources could not be reached", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Got it").performClick()
        // The sentence stays (it is still true) but the dismissal affordance goes away.
        composeRule.onNodeWithText("Got it").assertDoesNotExist()
    }

    @Test
    fun aCleanSuccessShowsNoFailureNotice() {
        val success = JobSyncState(
            lastSyncedAtEpochMillis = NOW,
            sources = listOf(source("a", SourceState.SUCCESS), source("b", SourceState.SUCCESS)),
        )
        composeRule.setContent {
            AiJobFinderTheme {
                BestOffersContent(
                    state = baseState().copy(syncState = success),
                    onBack = {},
                    onRefresh = {},
                )
            }
        }
        composeRule.onNodeWithText("could not be reached", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("we already had", substring = true).assertDoesNotExist()
    }

    @Test
    fun keptOffersAfterATotalFailureAreLabelledAsSuchAndNotCleared() {
        val cached = JobSyncState(
            lastSyncedAtEpochMillis = NOW,
            sources = listOf(source("a", SourceState.ERROR), source("b", SourceState.ERROR)),
            showingCachedOnly = true,
        )
        val state = baseState().copy(syncState = cached)
        composeRule.setContent {
            AiJobFinderTheme {
                BestOffersContent(state = state, onBack = {}, onRefresh = {})
            }
        }
        // The failure is stated honestly, and the previous offers are still on screen.
        composeRule.onNodeWithText("these offers are the ones we already had", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Maintenance Technician").assertIsDisplayed()
        composeRule.onNodeWithText("We could not reach the job sources").assertDoesNotExist()
    }

    @Test
    fun aTotalFailureWithNothingKeptShowsTheErrorState() {
        val failed = JobSyncState(
            sources = listOf(source("a", SourceState.ERROR), source("b", SourceState.ERROR)),
        )
        composeRule.setContent {
            AiJobFinderTheme {
                BestOffersContent(
                    state = baseState(offers = emptyList()).copy(totalCompatible = 0, syncState = failed),
                    onBack = {},
                    onRefresh = {},
                )
            }
        }
        composeRule.onNodeWithText("We could not reach the job sources").assertIsDisplayed()
    }

    @Test
    fun dormantSourcesAreNotMistakenForAnOutage() {
        val dormant = JobSyncState(
            sources = listOf(source("a", SourceState.IDLE), source("b", SourceState.IDLE)),
        )
        composeRule.setContent {
            AiJobFinderTheme {
                BestOffersContent(
                    state = baseState(offers = emptyList()).copy(totalCompatible = 0, syncState = dormant),
                    onBack = {},
                    onRefresh = {},
                )
            }
        }
        composeRule.onNodeWithText("No compatible offers yet").assertIsDisplayed()
        composeRule.onNodeWithText("We could not reach the job sources").assertDoesNotExist()
    }

    // --- 3. The last-updated line comes from the repository's own recorded timestamp. ---

    @Test
    fun theHeroShowsTheRepositorysOwnLastUpdateTime() {
        composeRule.setContent {
            AiJobFinderTheme {
                BestOffersContent(state = baseState(), onBack = {}, onRefresh = {})
            }
        }
        composeRule.onNodeWithText("Last updated", substring = true).assertIsDisplayed()
    }

    @Test
    fun withNoRecordedSyncTheHeroShowsNoTimestamp() {
        composeRule.setContent {
            AiJobFinderTheme {
                BestOffersContent(
                    state = baseState().copy(lastSyncedAtEpochMillis = null),
                    onBack = {},
                    onRefresh = {},
                )
            }
        }
        composeRule.onNodeWithText("Last updated", substring = true).assertDoesNotExist()
    }
}
