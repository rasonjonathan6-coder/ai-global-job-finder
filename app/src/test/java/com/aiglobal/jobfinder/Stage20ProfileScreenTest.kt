package com.aiglobal.jobfinder

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aiglobal.jobfinder.data.discovery.DiscoverySummary
import com.aiglobal.jobfinder.data.discovery.RefreshHistory
import com.aiglobal.jobfinder.data.discovery.RefreshOutcome
import com.aiglobal.jobfinder.data.discovery.RefreshProviderResult
import com.aiglobal.jobfinder.data.discovery.RefreshRunRecord
import com.aiglobal.jobfinder.data.discovery.RefreshTrigger
import com.aiglobal.jobfinder.data.model.ProviderStatus
import com.aiglobal.jobfinder.data.refresh.AlertThreshold
import com.aiglobal.jobfinder.data.refresh.RefreshInterval
import com.aiglobal.jobfinder.data.refresh.RefreshPreferences
import com.aiglobal.jobfinder.ui.screens.profile.RefreshAndNotificationsCard
import com.aiglobal.jobfinder.ui.theme.AiJobFinderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout and behaviour for the automatic-refresh card at a narrow phone width, where the
 * explanatory copy is most likely to clip. The test drives the card with plain callbacks so
 * it verifies the control wiring without a ViewModel or WorkManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp-hdpi")
class Stage20ProfileScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun render(
        preferences: RefreshPreferences = RefreshPreferences(),
        notificationsEnabled: Boolean = false,
        summary: DiscoverySummary = DiscoverySummary(),
        searchingNow: Boolean = false,
        nextRefreshAt: Long? = null,
        history: RefreshHistory = RefreshHistory(),
        onAutoRefreshChange: (Boolean) -> Unit = {},
        onIntervalChange: (RefreshInterval) -> Unit = {},
        onNotificationsChange: (Boolean) -> Unit = {},
        onAlertThresholdChange: (AlertThreshold) -> Unit = {},
        onSearchNow: () -> Unit = {},
        onOpenHistory: () -> Unit = {},
    ) {
        composeRule.setContent {
            AiJobFinderTheme {
                RefreshAndNotificationsCard(
                    preferences = preferences,
                    notificationsEnabled = notificationsEnabled,
                    summary = summary,
                    searchingNow = searchingNow,
                    nextRefreshAt = nextRefreshAt,
                    history = history,
                    onAutoRefreshChange = onAutoRefreshChange,
                    onIntervalChange = onIntervalChange,
                    onNotificationsChange = onNotificationsChange,
                    onAlertThresholdChange = onAlertThresholdChange,
                    onSearchNow = onSearchNow,
                    onOpenHistory = onOpenHistory,
                )
            }
        }
    }

    @Test
    fun theCardShowsBothSwitchesAndTheCadence() {
        render()
        composeRule.onNodeWithText("Background refresh").assertIsDisplayed()
        composeRule.onNodeWithText("New match alerts").assertIsDisplayed()
        // The cadence is described as approximate, never as a fixed time.
        composeRule.onNodeWithText("Twice a day", substring = true).assertIsDisplayed()
    }

    @Test
    fun choosingAnIntervalReportsIt() {
        var chosen: RefreshInterval? = null
        render(onIntervalChange = { chosen = it })

        composeRule.onNodeWithText("Daily").performClick()
        composeRule.waitForIdle()
        assertEquals(RefreshInterval.DAILY, chosen)
    }

    @Test
    fun turningRefreshOffHidesTheIntervalChoices() {
        render(preferences = RefreshPreferences(enabled = false))
        composeRule.onNodeWithText("Every 6 hours").assertDoesNotExist()
        composeRule.onNodeWithText("Off — jobs refresh only when you open the app").assertIsDisplayed()
    }

    @Test
    fun theAlertBarIsHiddenWhenNotificationsAreOff() {
        render(notificationsEnabled = false)
        composeRule.onNodeWithText("Alert me when a new match reaches").assertDoesNotExist()
    }

    @Test
    fun theAlertBarReportsAChosenThreshold() {
        var chosen: AlertThreshold? = null
        render(notificationsEnabled = true, onAlertThresholdChange = { chosen = it })
        composeRule.onNodeWithText("Alert me when a new match reaches").assertIsDisplayed()
        composeRule.onNodeWithText("Excellent only").performClick()
        composeRule.waitForIdle()
        assertEquals(AlertThreshold.EXCELLENT, chosen)
    }

    @Test
    fun theStatusLineDescribesTheLastSearchHonestly() {
        render(
            summary = DiscoverySummary(
                lastSearchAtEpochMillis = 1_000,
                lastSuccessAtEpochMillis = 1_000,
                newCount = 2,
                knownCount = 40,
            ),
        )
        composeRule.onNodeWithText("2 new postings", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("40 postings tracked so far.").assertIsDisplayed()
        composeRule.onNodeWithText("Refresh now").assertIsDisplayed()
    }

    @Test
    fun aFreshInstallSaysNoSearchHasFinishedYet() {
        render()
        composeRule.onNodeWithText("No search has finished yet.").assertIsDisplayed()
    }

    @Test
    fun aBackgroundGiveUpIsSaidOutLoud() {
        // Stage 55: when the scheduled search exhausts its retries, the card must not stay
        // silent and pretend discovery is healthy — it names the reason it gave up.
        render(
            summary = DiscoverySummary(
                retryExhausted = true,
                backgroundFailureReason = "The job sources could not be reached.",
            ),
        )
        composeRule.onNodeWithText("The job sources could not be reached.").assertIsDisplayed()
    }

    @Test
    fun aHealthySearchShowsNoGiveUpLine() {
        render(
            summary = DiscoverySummary(
                lastSearchAtEpochMillis = 1_000,
                lastSuccessAtEpochMillis = 1_000,
                newCount = 1,
            ),
        )
        composeRule.onNodeWithText("could not be reached", substring = true).assertDoesNotExist()
    }

    @Test
    fun theCardReportsTheLastRecordedRun() {
        render(
            history = RefreshHistory(
                listOf(
                    RefreshRunRecord(
                        id = 1_000,
                        trigger = RefreshTrigger.SCHEDULED,
                        startedAtEpochMillis = 1_000,
                        endedAtEpochMillis = 2_000,
                        outcome = RefreshOutcome.SUCCEEDED,
                        providers = listOf(
                            RefreshProviderResult(
                                providerId = "remotive",
                                name = "Remotive",
                                status = ProviderStatus.SUCCESS_WITH_RESULTS,
                            ),
                            RefreshProviderResult(
                                providerId = "jobicy",
                                name = "Jobicy",
                                status = ProviderStatus.SUCCESS_WITH_RESULTS,
                            ),
                        ),
                        newCount = 3,
                    ),
                ),
            ),
        )
        composeRule.onNodeWithText("Last run: scheduled, succeeded, 2 of 2 sources answered.").assertIsDisplayed()
    }

    @Test
    fun aSkippedRequestIsNotDressedUpAsAFailure() {
        render(
            history = RefreshHistory(
                listOf(
                    RefreshRunRecord(
                        id = 1_000,
                        trigger = RefreshTrigger.MANUAL,
                        startedAtEpochMillis = 1_000,
                        endedAtEpochMillis = 2_000,
                        outcome = RefreshOutcome.NOT_ATTEMPTED,
                        reason = "A search was already running.",
                    ),
                ),
            ),
        )
        composeRule.onNodeWithText("skipped because a search was already running", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun theNextAutomaticRunIsShownWhenKnown() {
        val now = System.currentTimeMillis()
        render(nextRefreshAt = now + 3_600_000)
        composeRule.onNodeWithText("Next automatic search about", substring = true).assertIsDisplayed()
    }

    @Test
    fun theHistoryButtonOpensTheHistory() {
        var opened = false
        render(onOpenHistory = { opened = true })
        composeRule.onNodeWithText("History").performClick()
        assert(opened)
    }
}
