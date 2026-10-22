package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.ai.AssistTask
import com.aiglobal.jobfinder.data.ai.GroundingContext
import com.aiglobal.jobfinder.data.ai.NvidiaJobAssistant
import com.aiglobal.jobfinder.data.model.AiLanguage
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.EducationEntry
import com.aiglobal.jobfinder.data.model.NvidiaConfig
import com.aiglobal.jobfinder.data.repository.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 98 — the assistant's output language.
 *
 * A candidate applying from Madagascar reads French, so French is the default the app must
 * fall back to whenever a stored preference is missing or unknown. These tests pin that
 * default, prove the chosen language reaches the model through the system prompt, and prove
 * that switching language never weakens the grounding or leaks contact details.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage98AiLanguageTest {

    private val profile = CandidateProfile(
        fullName = "Jonathan Rakoto",
        email = "jonathan.private@example.com",
        phone = "+261 34 000 0000",
        currentTitle = "Maintenance Technician",
        yearsOfExperience = 6,
        skills = listOf("Hydraulics", "Electrical diagnostics", "CMMS"),
        education = listOf(
            EducationEntry(
                degree = "BTS Maintenance Industrielle",
                institution = "Lycée Technique",
                country = "Madagascar",
                year = 2016,
            ),
        ),
        summary = "Hands-on industrial maintenance technician.",
    )

    private val job = SampleData.jobs(0L).first()

    @Test
    fun frenchIsTheDefaultDraftLanguage() {
        assertEquals(AiLanguage.FRENCH, AiLanguage.fromKey(null))
        assertEquals(AiLanguage.FRENCH, NvidiaConfig().language)
    }

    @Test
    fun anUnknownOrRenamedStoredKeyFallsBackToFrenchRatherThanCrashing() {
        assertEquals(AiLanguage.FRENCH, AiLanguage.fromKey("KLINGON"))
        assertEquals(AiLanguage.ENGLISH, AiLanguage.fromKey("ENGLISH"))
    }

    @Test
    fun theChosenLanguageReachesTheModelThroughTheSystemPrompt() {
        val assistant = NvidiaJobAssistant()
        val context = GroundingContext.of(profile, job)

        val (frenchSystem, _) = assistant.promptFor(AssistTask.COVER_LETTER, context, AiLanguage.FRENCH)
        val (englishSystem, _) = assistant.promptFor(AssistTask.COVER_LETTER, context, AiLanguage.ENGLISH)

        assertTrue(frenchSystem.contains("French"))
        assertTrue(englishSystem.contains("English"))
        assertFalse(frenchSystem == englishSystem)
    }

    @Test
    fun switchingLanguageDoesNotWeakenGroundingOrLeakContactDetails() {
        val assistant = NvidiaJobAssistant()
        val context = GroundingContext.of(profile, job)

        val (_, user) = assistant.promptFor(AssistTask.EXPLAIN_MATCH, context, AiLanguage.FRENCH)

        assertTrue("the prompt must stay grounded in the posting", user.contains(job.title))
        assertTrue("the prompt must stay grounded in the CV", user.contains("Jonathan Rakoto"))
        assertFalse("the email must never be sent", user.contains(profile.email))
        assertFalse("the phone must never be sent", user.contains(profile.phone))
    }

    @Test
    fun everyAssistTaskExplainsItselfInPlainLanguage() {
        AssistTask.entries.forEach { task ->
            assertTrue("label blank for $task", task.label.isNotBlank())
            assertTrue("blurb blank for $task", task.blurb.isNotBlank())
        }
    }

    @Test
    fun aFailedRemoteCallStillLeavesAnHonestOnDeviceDraftForEveryTask() {
        val assistant = NvidiaJobAssistant()
        AssistTask.entries.forEach { task ->
            val fallback = assistant.localFallback(task, job, profile)
            assertTrue("no fallback for $task", fallback.isNotBlank())
            assertTrue(
                "the fallback for $task must say nothing was sent",
                fallback.contains("no data was sent"),
            )
        }
    }
}
