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
import com.aiglobal.jobfinder.data.submissions.SubmissionChannel
import com.aiglobal.jobfinder.data.submissions.SubmissionEntry
import com.aiglobal.jobfinder.data.submissions.SubmissionOutcome
import com.aiglobal.jobfinder.ui.screens.submissions.SubmissionHistoryContent
import com.aiglobal.jobfinder.ui.theme.AiJobFinderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 31: the submission history screen at a narrow phone width, where long titles and
 * reasons are most likely to clip. Every important string must be reachable by scrolling,
 * and each outcome must be distinguishable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp-hdpi")
class Stage31SubmissionHistoryScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_700_000_000_000L

    private fun entry(
        key: String,
        title: String = "Maintenance Technician",
        company: String = "Northline Industrial",
        outcome: SubmissionOutcome = SubmissionOutcome.DRY_RUN,
        pendingReview: String = "",
        sent: Boolean = false,
    ) = SubmissionEntry(
        key = key,
        jobId = key,
        jobTitle = title,
        company = company,
        country = "Canada",
        providerRecordId = "adzuna:$key",
        canonicalUrl = "adzuna.ca/land/ad/$key",
        source = "Adzuna",
        channel = SubmissionChannel.EMPLOYER_PORTAL,
        outcome = outcome,
        sent = sent,
        seenSources = listOf("Adzuna"),
        firstRequestedAtEpochMillis = now,
        updatedAtEpochMillis = now,
        reason = when (outcome) {
            SubmissionOutcome.DRY_RUN -> "Simulated: the full application was walked through, but nothing was sent."
            SubmissionOutcome.BLOCKED_DUPLICATE -> "This opening is already in your application registry (via Adzuna). It will not be submitted again."
            SubmissionOutcome.NEEDS_VERIFICATION -> "This looks close to an existing entry — Maintenance Technician at Northline Industrial (from Adzuna) — but the employer or title differs slightly. Nothing was sent. Confirm whether it is the same posting."
            else -> "Handled."
        },
        pendingReview = pendingReview,
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

    @Test
    fun emptyRegistryShowsAnEmptyState() {
        render {
            SubmissionHistoryContent(entries = emptyList(), sendingEnabled = false, onBack = {})
        }
        composeRule.onNodeWithText("No applications yet").assertIsDisplayed()
        composeRule.onNodeWithText("Real sending is locked").assertIsDisplayed()
    }

    @Test
    fun aSimulatedAttemptIsShownAsSimulated() {
        render {
            SubmissionHistoryContent(
                entries = listOf(entry("a", outcome = SubmissionOutcome.DRY_RUN)),
                sendingEnabled = false,
                onBack = {},
            )
        }
        scrollToText("Maintenance Technician")
        scrollToText("Simulated (nothing sent)")
    }

    @Test
    fun aBlockedDuplicateIsShownAsBlocked() {
        render {
            SubmissionHistoryContent(
                entries = listOf(
                    entry("a"),
                    entry("b", outcome = SubmissionOutcome.BLOCKED_DUPLICATE, company = "Northline Industries"),
                ),
                sendingEnabled = false,
                onBack = {},
            )
        }
        scrollToText("Already applied — blocked")
    }

    @Test
    fun aPostingNeedingVerificationOffersBothChoices() {
        render {
            SubmissionHistoryContent(
                entries = listOf(
                    entry("a"),
                    entry(
                        "b",
                        title = "Maintenance Technicians",
                        outcome = SubmissionOutcome.NEEDS_VERIFICATION,
                        pendingReview = "northline industrial|maintenance technician|canada",
                    ),
                ),
                sendingEnabled = false,
                onBack = {},
            )
        }
        scrollToText("Needs your verification")
        scrollToText("Same posting")
        scrollToText("Different")
    }

    @Test
    fun summaryCountsAreShown() {
        render {
            SubmissionHistoryContent(
                entries = listOf(
                    entry("a", outcome = SubmissionOutcome.DRY_RUN),
                    entry("b", outcome = SubmissionOutcome.BLOCKED_DUPLICATE),
                    entry("c", outcome = SubmissionOutcome.NEEDS_VERIFICATION, pendingReview = "x"),
                ),
                sendingEnabled = false,
                onBack = {},
            )
        }
        scrollToText("Registry summary")
        scrollToText("Duplicates blocked")
        scrollToText("Need verification")
    }
}
