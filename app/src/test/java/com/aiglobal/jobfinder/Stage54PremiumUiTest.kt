package com.aiglobal.jobfinder

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.aiglobal.jobfinder.data.repository.SampleJobRepository
import com.aiglobal.jobfinder.data.repository.SampleData
import com.aiglobal.jobfinder.ui.components.BannerTone
import com.aiglobal.jobfinder.ui.components.InfoBanner
import com.aiglobal.jobfinder.ui.components.ScreenHeader
import com.aiglobal.jobfinder.ui.components.StatItem
import com.aiglobal.jobfinder.ui.components.StatStrip
import com.aiglobal.jobfinder.ui.components.TabStrip
import com.aiglobal.jobfinder.ui.screens.ai.AiScreen
import com.aiglobal.jobfinder.ui.screens.applications.ApplicationsScreen
import com.aiglobal.jobfinder.ui.screens.jobs.JobsContent
import com.aiglobal.jobfinder.ui.screens.profile.ProfileScreen
import com.aiglobal.jobfinder.ui.theme.AiJobFinderTheme
import com.aiglobal.jobfinder.viewmodel.AppViewModel
import com.aiglobal.jobfinder.viewmodel.ApplicationsViewModel
import com.aiglobal.jobfinder.viewmodel.JobsUiState
import com.aiglobal.jobfinder.viewmodel.ProfileViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 54: the four tabs must be built from one shared set of premium primitives, so a change
 * to the header, stat row, tab strip or banner lands on every screen at once. These tests render
 * each primitive and each screen and pin the visible furniture, so a regression in one tab fails
 * here rather than only drifting silently out of step with the others.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class Stage54PremiumUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val repository = SampleJobRepository(refreshDelayMillis = 0)
    private val container by lazy { AppContainer(ApplicationProvider.getApplicationContext()) }

    @Test
    fun theScreenHeaderShowsItsTitleAndSubtitle() {
        composeRule.setContent {
            AiJobFinderTheme {
                ScreenHeader(title = "Find jobs", subtitle = "3 of 10 postings shown")
            }
        }
        composeRule.onNodeWithText("Find jobs").assertIsDisplayed()
        composeRule.onNodeWithText("3 of 10 postings shown").assertIsDisplayed()
    }

    @Test
    fun theStatStripShowsEveryValueAndLabel() {
        composeRule.setContent {
            AiJobFinderTheme {
                StatStrip(
                    items = listOf(
                        StatItem(icon = Icons.Filled.Work, value = "12", label = "Total", accent = Color.Blue),
                        StatItem(icon = Icons.Filled.Work, value = "4", label = "Preparing", accent = Color.Blue),
                        StatItem(icon = Icons.Filled.Work, value = "2", label = "Active", accent = Color.Blue),
                        StatItem(icon = Icons.Filled.Work, value = "1", label = "Offers", accent = Color.Blue),
                    ),
                )
            }
        }
        listOf("12", "Total", "4", "Preparing", "2", "Active", "1", "Offers").forEach { text ->
            composeRule.onNodeWithText(text).assertIsDisplayed()
        }
    }

    @Test
    fun theTabStripMarksTheSelectedTabAndReportsTaps() {
        var selected: Int? = null
        composeRule.setContent {
            AiJobFinderTheme {
                TabStrip(
                    tabs = listOf("All", "Saved", "Preparing", "Applied"),
                    selectedIndex = 0,
                    onSelect = { selected = it },
                )
            }
        }
        composeRule.onNodeWithText("All").assertIsSelected()
        composeRule.onNodeWithText("Applied").performClick()
        composeRule.waitForIdle()
        org.junit.Assert.assertEquals(3, selected)
    }

    @Test
    fun theInfoBannerShowsItsMessage() {
        composeRule.setContent {
            AiJobFinderTheme {
                InfoBanner(
                    icon = Icons.Filled.Info,
                    text = "No verified submission route yet — nothing is sent automatically.",
                    tone = BannerTone.Info,
                )
            }
        }
        composeRule
            .onNodeWithText("No verified submission route yet", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun theJobsScreenOpensWithItsPremiumHeader() {
        val jobs = kotlinx.coroutines.runBlocking { SampleData.jobs(System.currentTimeMillis()) }
        composeRule.setContent {
            AiJobFinderTheme {
                Box(Modifier.fillMaxSize()) {
                    JobsContent(
                        state = JobsUiState(isLoading = false, jobs = jobs),
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
        composeRule.onNodeWithText("Find jobs").assertIsDisplayed()
        composeRule.onNodeWithText("Search role, company or country").assertIsDisplayed()
    }

    @Test
    fun theApplicationsScreenShowsItsHeaderStatsAndTabs() {
        composeRule.setContent {
            AiJobFinderTheme {
                Box(Modifier.fillMaxSize()) {
                    ApplicationsScreen(
                        viewModel = ApplicationsViewModel(repository, container.applicationCenter),
                        onBrowseJobs = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Applications").assertIsDisplayed()
        composeRule.onNodeWithText("Total").assertIsDisplayed()
        // "Preparing" and "Offers" appear both in the stat strip and as status tabs.
        composeRule.onAllNodesWithText("Preparing").onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("Offers").onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("All").onFirst().assertIsSelected()
    }

    @Test
    fun theAiScreenOpensWithItsPremiumHeader() {
        composeRule.setContent {
            AiJobFinderTheme {
                Box(Modifier.fillMaxSize()) {
                    AiScreen(onBrowseJobs = {}, onOpenCv = {}, autoRun = false)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("AI Assistant").assertIsDisplayed()
    }

    @Test
    fun theProfileScreenShowsItsHeaderAndProfileStats() {
        composeRule.setContent {
            AiJobFinderTheme {
                Box(Modifier.fillMaxSize()) {
                    ProfileScreen(
                        viewModel = ProfileViewModel(repository),
                        appViewModel = AppViewModel(container),
                        onOpenAi = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Profile").assertIsDisplayed()
        composeRule.onNodeWithText("Skills").assertIsDisplayed()
        composeRule.onNodeWithText("Years").assertIsDisplayed()
        composeRule.onNodeWithText("Education").assertIsDisplayed()
        composeRule.onNodeWithText("Countries").assertIsDisplayed()
    }
}

/**
 * The premium furniture must also survive the narrowest supported width, where a stat strip or
 * a tab strip is most likely to clip a label away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp-hdpi")
class Stage54NarrowPremiumUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theStatStripAndTabStripFitAtNarrowWidth() {
        composeRule.setContent {
            AiJobFinderTheme {
                Box(Modifier.fillMaxSize()) {
                    StatStrip(
                        items = listOf(
                            StatItem(icon = Icons.Filled.Work, value = "12", label = "Total", accent = Color.Blue),
                            StatItem(icon = Icons.Filled.Work, value = "4", label = "Preparing", accent = Color.Blue),
                            StatItem(icon = Icons.Filled.Work, value = "2", label = "Active", accent = Color.Blue),
                            StatItem(icon = Icons.Filled.Work, value = "1", label = "Offers", accent = Color.Blue),
                        ),
                    )
                    TabStrip(
                        tabs = listOf("All", "Saved", "Preparing", "Applied"),
                        selectedIndex = 0,
                        onSelect = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Total").assertIsDisplayed()
        composeRule.onNodeWithText("Offers").assertIsDisplayed()
        composeRule.onNodeWithText("All").assertIsDisplayed()
    }
}
