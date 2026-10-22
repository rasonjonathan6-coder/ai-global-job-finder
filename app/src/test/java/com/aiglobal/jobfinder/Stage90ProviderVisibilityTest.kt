package com.aiglobal.jobfinder

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.aiglobal.jobfinder.data.jobs.provider.AshbyBoards
import com.aiglobal.jobfinder.data.jobs.provider.AshbyProvider
import com.aiglobal.jobfinder.data.jobs.provider.GreenhouseBoards
import com.aiglobal.jobfinder.data.jobs.provider.GreenhouseProvider
import com.aiglobal.jobfinder.data.jobs.provider.LeverBoards
import com.aiglobal.jobfinder.data.jobs.provider.LeverProvider
import com.aiglobal.jobfinder.data.jobs.provider.ProviderAuditRegistry
import com.aiglobal.jobfinder.data.jobs.provider.RemotiveProvider
import com.aiglobal.jobfinder.data.jobs.provider.SmartRecruitersBoards
import com.aiglobal.jobfinder.data.jobs.provider.SmartRecruitersProvider
import com.aiglobal.jobfinder.data.jobs.provider.WorkdayBoards
import com.aiglobal.jobfinder.data.jobs.provider.WorkdayProvider
import com.aiglobal.jobfinder.data.model.ProviderDiagnostic
import com.aiglobal.jobfinder.data.model.ProviderDiagnostics
import com.aiglobal.jobfinder.data.model.ProviderStatus
import com.aiglobal.jobfinder.ui.screens.diagnostics.LiveSearchValidationContent
import com.aiglobal.jobfinder.ui.theme.AiJobFinderTheme
import com.aiglobal.jobfinder.viewmodel.SourceDiagnosticsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 90 — the curated employer portals are visible in the app.
 *
 * The user could see the aggregating sources (Workday, Greenhouse, …) but not the individual
 * employers each of them reads, because the employer names lived only inside the provider board
 * lists and were never carried to the diagnostics screen. These tests pin both halves of the
 * fix: the audit derives the portal names from the very lists the feed walks (so it cannot name
 * an employer the source does not query), and the status screen renders them, collapsed by
 * default and one tap away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class Stage90ProviderVisibilityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun auditByProviderId() = ProviderAuditRegistry.audit(
        listOf(
            WorkdayProvider(),
            GreenhouseProvider(),
            AshbyProvider(),
            LeverProvider(),
            SmartRecruitersProvider(),
            RemotiveProvider(),
        ),
    ).associateBy { it.id }

    @Test
    fun eachEmployerBoardSourceCarriesItsCuratedPortalsByName() {
        val audit = auditByProviderId()

        assertEquals(
            WorkdayBoards.SITES.map { it.label },
            audit.getValue("workday").portals,
        )
        assertEquals(
            GreenhouseBoards.BOARDS.map { it.label },
            audit.getValue("greenhouse").portals,
        )
        assertEquals(
            AshbyBoards.BOARDS.map { it.label },
            audit.getValue("ashby").portals,
        )
        assertEquals(
            LeverBoards.BOARDS.map { it.label },
            audit.getValue("lever").portals,
        )
        assertEquals(
            SmartRecruitersBoards.BOARDS.map { it.label },
            audit.getValue("smartrecruiters").portals,
        )
    }

    @Test
    fun aSingleFeedSourceHasNoEmployerPortals() {
        // Remotive is one remote board, not a family of employer sites, so it must not claim
        // any employer portal it does not read.
        assertTrue(auditByProviderId().getValue("remotive").portals.isEmpty())
    }

    @Test
    fun everyCuratedPortalNameIsPresentExactlyOncePerSource() {
        val audit = auditByProviderId()
        listOf("workday", "greenhouse", "ashby", "lever", "smartrecruiters").forEach { id ->
            val portals = audit.getValue(id).portals
            assertTrue("$id must expose at least one portal", portals.isNotEmpty())
            assertTrue("$id has a blank portal name", portals.none { it.isBlank() })
            assertEquals("$id repeats a portal name", portals.distinct().size, portals.size)
        }
    }

    @Test
    fun theTotalCuratedEmployerPortalsAreAllExposed() {
        // 25 Workday + 9 Greenhouse + 3 Ashby + 6 Lever + 10 SmartRecruiters curated employers.
        val audit = auditByProviderId()
        val total = listOf("workday", "greenhouse", "ashby", "lever", "smartrecruiters")
            .sumOf { audit.getValue(it).portals.size }
        assertEquals(53, total)
    }

    @Test
    fun theStatusScreenShowsThePortalCountAndRevealsTheEmployersOnTap() {
        val audit = auditByProviderId()
        val workdayAudit = audit.getValue("workday")
        val diagnostic = ProviderDiagnostic(
            providerId = "workday",
            name = "Employer career sites",
            homepage = "https://www.myworkdayjobs.com",
            documentationUrl = "https://www.myworkdayjobs.com",
            status = ProviderStatus.SUCCESS_WITH_RESULTS,
            validJobCount = 42,
        )
        composeRule.setContent {
            AiJobFinderTheme {
                LiveSearchValidationContent(
                    state = SourceDiagnosticsUiState(
                        diagnostics = ProviderDiagnostics(providers = listOf(diagnostic)),
                        audit = listOf(workdayAudit),
                    ),
                    onBack = {},
                )
            }
        }

        // The count is visible immediately; the employer names are one tap away. The source
        // card sits below the run summary, so scroll the list to it first.
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("25 employer portals"))
        composeRule.onNodeWithText("25 employer portals").assertIsDisplayed()
        composeRule.onNodeWithText("Rio Tinto", substring = true).assertDoesNotExist()

        composeRule.onNodeWithText("25 employer portals").performClick()

        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("Rio Tinto", substring = true))
        composeRule.onNodeWithText("Rio Tinto", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Alfa Laval", substring = true).assertIsDisplayed()
    }
}
