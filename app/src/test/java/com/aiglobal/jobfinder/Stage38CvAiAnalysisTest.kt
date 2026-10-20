package com.aiglobal.jobfinder

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.aiglobal.jobfinder.data.cv.CvAiAnalyzer
import com.aiglobal.jobfinder.data.cv.CvParserRegistry
import com.aiglobal.jobfinder.data.cv.CvFileStore
import com.aiglobal.jobfinder.data.cv.analysis.HeuristicCvAnalyzer
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.CvAiStatus
import com.aiglobal.jobfinder.data.model.CvRecord
import com.aiglobal.jobfinder.data.model.CvStatus
import com.aiglobal.jobfinder.data.model.NvidiaConfig
import com.aiglobal.jobfinder.data.model.NvidiaResult
import com.aiglobal.jobfinder.data.model.NvidiaStatus
import com.aiglobal.jobfinder.data.repository.CvRepository
import com.aiglobal.jobfinder.data.repository.cvRecordFromJson
import com.aiglobal.jobfinder.data.repository.recordToJson
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 38 — the optional AI read of the CV, and honesty about when it did not happen.
 *
 * The gate that matters most is the consent one: the CV must never leave the device unless the
 * user has switched the assistant on *and* saved a key. Everything is driven through the
 * [CvAiAnalyzer] completion seam with canned replies, so no network is touched and the tests
 * assert both what would be sent and what the user is told about the outcome. **No live NVIDIA
 * call is made from this suite.**
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage38CvAiAnalysisTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val cvText = """
        Jonathan Rakoto
        Maintenance Technician
        Antananarivo, Madagascar
        jrakoto@example.mg
        +261 34 12 345 67
        Experience
        Maintenance Technician - Star Malgache (2018 - present)
        Six years of experience in industrial maintenance.
        Skills
        preventive maintenance, hydraulics, PLC, electrical wiring
        Education
        BTS Electrotechnics, IST Antananarivo, 2018
        Languages
        French - fluent
        English - intermediate
    """.trimIndent()

    /** A canned completion reply and a record of what the analyzer asked for. */
    private class FakeComplete(private val result: NvidiaResult) {
        var calls = 0
        var lastUserPrompt: String? = null
        suspend fun invoke(config: NvidiaConfig, system: String, user: String, max: Int): NvidiaResult {
            calls++
            lastUserPrompt = user
            return result
        }
    }

    private fun analyzerWith(
        config: NvidiaConfig,
        result: NvidiaResult,
        fake: FakeComplete = FakeComplete(result),
    ): CvAiAnalyzer = CvAiAnalyzer(
        configProvider = { config },
        complete = fake::invoke,
        maxChunkChars = 12_000,
    )

    private fun jsonProfile(): String = """
        {
          "full_name": "Jonathan Rakoto",
          "email": "jrakoto@example.mg",
          "phone": "+261 34 12 345 67",
          "location": "Antananarivo, Madagascar",
          "current_title": "Maintenance Technician",
          "previous_titles": ["Mechanical Fitter"],
          "years_of_experience": 6,
          "skills": ["Preventive maintenance", "Hydraulics", "PLC", "Electrical wiring"],
          "industries": ["Industrial maintenance"],
          "education": [{"degree": "BTS Electrotechnics", "institution": "IST Antananarivo", "country": "Madagascar", "year": 2018}],
          "certifications": [{"name": "Hydraulics Level 2", "issuer": "CNAM", "year": 2019}],
          "languages": [{"name": "French", "level": "fluent"}, {"name": "English", "level": "intermediate"}],
          "preferred_countries": ["Canada"],
          "preferred_roles": ["Maintenance Technician"],
          "salary_expectation": {"min": 2500, "max": 3500, "currency": "EUR"},
          "relocation_preference": "open_to_relocation",
          "needs_visa_sponsorship": true,
          "work_permit_notes": "Requires sponsorship",
          "summary": "Hands-on industrial maintenance technician."
        }
    """.trimIndent()

    private fun okResult() = NvidiaResult(NvidiaStatus.OK, text = jsonProfile())

    // ---- The consent gate --------------------------------------------------

    @Test
    fun withoutConsentNothingIsSentAndTheHeuristicResultIsUsed() = runBlocking {
        val fake = FakeComplete(okResult())
        val analyzer = analyzerWith(NvidiaConfig(apiKey = "nvapi-x", consentGranted = false), okResult(), fake)

        val result = analyzer.analyze(cvText, CandidateProfile(), emptySet())

        assertEquals("no request must leave the device without consent", 0, fake.calls)
        assertEquals(CvAiStatus.CONSENT_REQUIRED, result.aiStatus)
        assertTrue(result.warnings.any { it.contains("assistant is off", ignoreCase = true) })
        // The on-device pass still produced a real profile.
        assertEquals("Jonathan Rakoto", result.profile.fullName)
    }

    @Test
    fun withoutAKeyNothingIsSentAndTheHeuristicResultIsUsed() = runBlocking {
        val fake = FakeComplete(okResult())
        val analyzer = analyzerWith(NvidiaConfig(apiKey = "", consentGranted = true), okResult(), fake)

        val result = analyzer.analyze(cvText, CandidateProfile(), emptySet())

        assertEquals(0, fake.calls)
        assertEquals(CvAiStatus.NOT_CONFIGURED, result.aiStatus)
        assertTrue(result.warnings.any { it.contains("no NVIDIA key", ignoreCase = true) })
    }

    // ---- The happy path ----------------------------------------------------

    @Test
    fun withConsentTheModelReadsTheCvAndItsFieldsWin() = runBlocking {
        val fake = FakeComplete(okResult())
        val analyzer = analyzerWith(NvidiaConfig(apiKey = "nvapi-x", consentGranted = true), okResult(), fake)

        val result = analyzer.analyze(cvText, CandidateProfile(), emptySet())

        assertEquals(1, fake.calls)
        assertEquals(CvAiStatus.SUCCESS, result.aiStatus)
        assertEquals("Jonathan Rakoto", result.profile.fullName)
        assertEquals(6, result.profile.yearsOfExperience)
        assertTrue(result.profile.skills.any { it.equals("Hydraulics", ignoreCase = true) })
        assertEquals(1, result.profile.education.size)
        assertEquals("BTS Electrotechnics", result.profile.education.first().degree)
        assertTrue(result.profile.languages.any { it.name == "French" })
        // The prompt it sent carried the schema and asked for JSON, so the model can comply.
        val prompt = fake.lastUserPrompt!!
        assertTrue(prompt.contains("full_name"))
        assertTrue(prompt.contains("years_of_experience"))
        assertTrue(prompt.contains("Jonathan Rakoto"))
    }

    @Test
    fun aFencedAnswerIsStillParsed() = runBlocking {
        val fenced = NvidiaResult(
            NvidiaStatus.OK,
            text = "Here is the profile:\n```json\n${jsonProfile()}\n```",
        )
        val analyzer = analyzerWith(NvidiaConfig(apiKey = "k", consentGranted = true), fenced)

        val result = analyzer.analyze(cvText, CandidateProfile(), emptySet())

        assertEquals(CvAiStatus.SUCCESS, result.aiStatus)
        assertEquals("Jonathan Rakoto", result.profile.fullName)
    }

    // ---- Failure is honest ------------------------------------------------

    @Test
    fun aNetworkFailureFallsBackToTheOnDeviceResult() = runBlocking {
        val failed = NvidiaResult(NvidiaStatus.NETWORK, message = "The connection failed.")
        val analyzer = analyzerWith(NvidiaConfig(apiKey = "k", consentGranted = true), failed)

        val result = analyzer.analyze(cvText, CandidateProfile(), emptySet())

        assertEquals(CvAiStatus.FAILED, result.aiStatus)
        assertTrue(result.warnings.any { it.contains("on-device analysis was used", ignoreCase = true) })
        assertEquals("Jonathan Rakoto", result.profile.fullName)
    }

    @Test
    fun anUnparseableAnswerFallsBackToTheOnDeviceResult() = runBlocking {
        val garbage = NvidiaResult(NvidiaStatus.OK, text = "I cannot help with that.")
        val analyzer = analyzerWith(NvidiaConfig(apiKey = "k", consentGranted = true), garbage)

        val result = analyzer.analyze(cvText, CandidateProfile(), emptySet())

        assertEquals(CvAiStatus.FAILED, result.aiStatus)
        assertEquals("Jonathan Rakoto", result.profile.fullName)
    }

    // ---- The parser does not invent ---------------------------------------

    @Test
    fun aBlankModelAnswerProducesNoInventedFields() {
        val analyzer = analyzerWith(NvidiaConfig(), okResult())
        val empty = analyzer.parseProfileJson("{}")!!

        assertTrue(empty.fullName.isEmpty())
        assertTrue(empty.email.isEmpty())
        assertTrue(empty.phone.isEmpty())
        assertTrue(empty.skills.isEmpty())
        assertTrue(empty.education.isEmpty())
        assertTrue(empty.languages.isEmpty())
        assertEquals(null, empty.yearsOfExperience)
    }

    @Test
    fun anIllTypedYearIsDroppedRatherThanCoerced() {
        val analyzer = analyzerWith(NvidiaConfig(), okResult())
        val parsed = analyzer.parseProfileJson(
            """{"full_name":"A B","years_of_experience":"many","education":[{"degree":"BTS","institution":"IST","country":"MG","year":"2018"}]}""",
        )!!

        assertEquals(null, parsed.yearsOfExperience)
        // A numeric string is accepted; the non-numeric one was dropped above, not defaulted.
        assertEquals(2018, parsed.education.first().year)
        assertEquals("A B", parsed.fullName)
    }

    @Test
    fun noJsonObjectMeansNoProfile() {
        val analyzer = analyzerWith(NvidiaConfig(), okResult())
        assertEquals(null, analyzer.parseProfileJson("Sorry, I cannot read this."))
    }

    // ---- Chunking ----------------------------------------------------------

    @Test
    fun aLongCvIsSplitIntoChunksEachUnderTheLimit() {
        val analyzer = analyzerWith(NvidiaConfig(), okResult(), FakeComplete(okResult()))
        val long = (1..20).joinToString("\n\n") { "Paragraph $it " + "x".repeat(1_200) }

        val chunks = analyzer.chunk(long)

        assertTrue("a long CV must be split", chunks.size > 1)
        assertTrue(chunks.all { it.length <= 12_000 })
        assertTrue(chunks.joinToString("").contains("Paragraph 20"))
    }

    // ---- Persistence round-trip -------------------------------------------

    @Test
    fun theAiStatusSurvivesAPersistenceRoundTrip() {
        listOf(
            CvAiStatus.SUCCESS,
            CvAiStatus.CONSENT_REQUIRED,
            CvAiStatus.NOT_CONFIGURED,
            CvAiStatus.FAILED,
            CvAiStatus.NONE,
        ).forEach { status ->
            val restored = cvRecordFromJson(JSONObject(recordToJson(sampleRecord(aiStatus = status)).toString()))
            assertEquals(status, restored.aiStatus)
        }
    }

    @Test
    fun anOlderRecordWithoutAiStatusReadsAsNone() {
        val json = JSONObject(recordToJson(sampleRecord(aiStatus = CvAiStatus.SUCCESS)).toString()).apply { remove("aiStatus") }
        assertEquals(CvAiStatus.NONE, cvRecordFromJson(json).aiStatus)
    }

    // ---- End to end through the repository --------------------------------

    @Test
    fun theRepositoryRoutesThroughTheAiAnalyzerAndRecordsSuccess() = runBlocking {
        val repository = CvRepository(
            context = context,
            fileStore = CvFileStore(context),
            parserRegistry = CvParserRegistry.default(context),
            analyzer = analyzerWith(NvidiaConfig(apiKey = "k", consentGranted = true), okResult()),
        )
        importTxt(repository, "cv.txt", cvText)

        val record = repository.analyzeImported()

        assertEquals(CvStatus.ANALYZED, record.status)
        assertEquals(CvAiStatus.SUCCESS, record.aiStatus)
        assertEquals("nvidia-ai", record.engineId)
        assertEquals("Jonathan Rakoto", record.profile.fullName)
    }

    @Test
    fun theRepositoryRecordsAHeuristicOnlyRunWithoutConsent() = runBlocking {
        val repository = CvRepository(
            context = context,
            fileStore = CvFileStore(context),
            parserRegistry = CvParserRegistry.default(context),
            analyzer = analyzerWith(NvidiaConfig(apiKey = "k", consentGranted = false), okResult()),
        )
        importTxt(repository, "cv.txt", cvText)

        val record = repository.analyzeImported()

        assertEquals(CvStatus.ANALYZED, record.status)
        assertEquals(CvAiStatus.CONSENT_REQUIRED, record.aiStatus)
        assertEquals("Jonathan Rakoto", record.profile.fullName)
    }

    @Test
    fun theHeuristicAnalyzerItselfIsUnchanged() = runBlocking {
        val result = HeuristicCvAnalyzer().analyze(cvText, CandidateProfile(), emptySet())
        assertEquals(CvAiStatus.NONE, result.aiStatus)
        val profile = result.profile
        assertEquals("Jonathan Rakoto", profile.fullName)
        assertTrue(profile.skills.isNotEmpty())
        assertTrue(profile.education.isNotEmpty())
        assertTrue(profile.languages.isNotEmpty())
    }

    // ---- helpers -----------------------------------------------------------

    private fun sampleRecord(aiStatus: CvAiStatus = CvAiStatus.NONE): CvRecord = CvRecord(
        fileName = "cv.pdf",
        storedFileName = "stored-cv.pdf",
        fileType = com.aiglobal.jobfinder.data.model.CvFileType.PDF,
        sizeBytes = 1_024L,
        importedAtEpochMillis = 1L,
        aiStatus = aiStatus,
    )

    private fun importTxt(repository: CvRepository, name: String, text: String) {
        val uri = Uri.parse("content://stage38/$name")
        org.robolectric.Shadows.shadowOf(context.contentResolver)
            .registerInputStream(uri, java.io.ByteArrayInputStream(text.toByteArray()))
        runBlocking { repository.importFile(uri) }
    }
}
