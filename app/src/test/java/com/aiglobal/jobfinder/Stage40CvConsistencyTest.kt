package com.aiglobal.jobfinder

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.aiglobal.jobfinder.data.cv.ByteCvSource
import com.aiglobal.jobfinder.data.cv.CvAiAnalyzer
import com.aiglobal.jobfinder.data.cv.CvFileStore
import com.aiglobal.jobfinder.data.cv.CvParserRegistry
import com.aiglobal.jobfinder.data.cv.analysis.HeuristicCvAnalyzer
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.CvAiStatus
import com.aiglobal.jobfinder.data.model.CvFileType
import com.aiglobal.jobfinder.data.model.CvRecord
import com.aiglobal.jobfinder.data.model.CvStatus
import com.aiglobal.jobfinder.data.model.NvidiaConfig
import com.aiglobal.jobfinder.data.model.NvidiaResult
import com.aiglobal.jobfinder.data.model.NvidiaStatus
import com.aiglobal.jobfinder.data.repository.CvRepository
import com.aiglobal.jobfinder.data.repository.cvRecordFromJson
import com.aiglobal.jobfinder.data.repository.recordToJson
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * Stage 40 — the extraction/consistency defects a real device test exposed.
 *
 * The reported symptom: a PDF that yielded very few characters still produced a full profile
 * (education, languages), and yet the notes at the same time claimed education, languages, a
 * name, an email and a phone were all missing. The cause was that the AI analyser built its
 * notes from the on-device profile *only*, not from the merged profile the user is shown.
 *
 * These tests pin the fix: every note is derived from the profile that is actually displayed,
 * the character count shown is the real non-whitespace count, and the engine named is the one
 * that really produced the fields. Synthetic documents only; the user's own CV is not here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage40CvConsistencyTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** Text long enough to analyse, but that the on-device heuristics read only thinly. */
    private val thinText = "Senior Industrial Maintenance Technician with proven experience " +
        "across heavy manufacturing lines and rotating equipment. Preventive maintenance and hydraulics."

    /** The reply that recovers education and languages but leaves contact fields empty. */
    private val aiReply = """
        {"full_name":null,"email":null,"phone":null,"location":null,
         "current_title":"Maintenance Technician","years_of_experience":6,
         "skills":["preventive maintenance","hydraulics"],"industries":[],
         "education":[{"degree":"BTS Electrotechnique","institution":"IST Antananarivo","country":"Madagascar","year":2018}],
         "certifications":[],"languages":[{"name":"French","level":"fluent"},{"name":"English","level":"intermediate"}],
         "preferred_countries":[],"preferred_roles":[],"salary_expectation":null,
         "relocation_preference":null,"needs_visa_sponsorship":null,"work_permit_notes":null,"summary":null}
    """.trimIndent()

    private fun aiAnalyzer(reply: String, consent: Boolean = true, key: String = "nvapi-x") = CvAiAnalyzer(
        configProvider = { NvidiaConfig(apiKey = key, modelId = "meta/llama-3.2-11b-vision-instruct", consentGranted = consent) },
        complete = { _, _, _, _ -> NvidiaResult(NvidiaStatus.OK, text = reply) },
        maxChunkChars = 12_000,
    )

    // ---- Warnings describe the profile the user sees -----------------------

    @Test
    fun educationAndLanguagesFromTheAiAreNotAlsoReportedAsMissing() = runBlocking {
        val result = aiAnalyzer(aiReply).analyze(thinText, CandidateProfile(), emptySet())

        assertEquals(CvAiStatus.SUCCESS, result.aiStatus)
        assertTrue(result.profile.education.isNotEmpty())
        assertTrue(result.profile.languages.isNotEmpty())

        // The reported contradiction must be gone: a present section is never called missing.
        assertFalse(
            "Education is present, so it must not be warned about: ${result.warnings}",
            result.warnings.any { it.contains("education or training", ignoreCase = true) },
        )
        assertFalse(
            "Languages are present, so they must not be warned about: ${result.warnings}",
            result.warnings.any { it.contains("languages were listed", ignoreCase = true) },
        )
        // The old AI-path wording must not reappear either.
        assertFalse(result.warnings.any { it.contains("AI could not find", ignoreCase = true) })
    }

    @Test
    fun genuinelyMissingFieldsAreStillReported() = runBlocking {
        val result = aiAnalyzer(aiReply).analyze(thinText, CandidateProfile(), emptySet())

        // No name, email or phone in either the text or the reply: those notes are honest and stay.
        assertTrue(result.profile.fullName.isBlank())
        assertTrue(result.warnings.any { it.contains("name could not be identified", ignoreCase = true) })
        assertTrue(result.warnings.any { it.contains("No email address", ignoreCase = true) })
        assertTrue(result.warnings.any { it.contains("No phone number", ignoreCase = true) })
    }

    @Test
    fun theOnDevicePathProducesNoContradictoryNotes() = runBlocking {
        val text = "Jonathan Rakoto\nMaintenance Technician\njrakoto@example.mg\n+261 34 12 345 67\n" +
            "Skills\npreventive maintenance, hydraulics, PLC\nEducation\nBTS Electrotechnics 2018\n" +
            "Languages\nFrench - fluent\nEnglish - intermediate"
        val result = HeuristicCvAnalyzer().analyze(text, CandidateProfile(), emptySet())

        assertTrue(result.profile.education.isNotEmpty())
        assertTrue(result.profile.languages.isNotEmpty())
        assertFalse(result.warnings.any { it.contains("education or training", ignoreCase = true) })
        assertFalse(result.warnings.any { it.contains("languages were listed", ignoreCase = true) })
    }

    @Test
    fun aUserEditedFieldNeverTriggersItsOwnWarning() = runBlocking {
        // The user typed an education entry by hand; the document has no education section.
        val existing = CandidateProfile(
            education = listOf(
                com.aiglobal.jobfinder.data.model.EducationEntry(
                    degree = "Licence Pro",
                    institution = "University of Antananarivo",
                    country = "Madagascar",
                    year = 2020,
                ),
            ),
        )
        val result = HeuristicCvAnalyzer().analyze(thinText, existing, setOf(com.aiglobal.jobfinder.data.model.CandidateField.EDUCATION))

        assertTrue(result.profile.education.isNotEmpty())
        assertFalse(
            "A value the user can see must never be warned about: ${result.warnings}",
            result.warnings.any { it.contains("education or training", ignoreCase = true) },
        )
    }

    // ---- The engine named is the engine that produced the fields -----------

    @Test
    fun aFallbackToOnDeviceNamesTheOnDeviceEngine() = runBlocking {
        val analyzer = CvAiAnalyzer(
            configProvider = { NvidiaConfig(apiKey = "k", consentGranted = true) },
            complete = { _, _, _, _ -> NvidiaResult(NvidiaStatus.NETWORK, message = "The connection failed.") },
            maxChunkChars = 12_000,
        )
        val result = analyzer.analyze(thinText, CandidateProfile(), emptySet())

        assertEquals(CvAiStatus.FAILED, result.aiStatus)
        assertEquals(HeuristicCvAnalyzer().id, result.effectiveEngineId)
        assertEquals(HeuristicCvAnalyzer().label, result.effectiveEngineLabel)
    }

    @Test
    fun aFragmentFallsBackToOnDeviceWithoutAskingTheModel() = runBlocking {
        var calls = 0
        val analyzer = CvAiAnalyzer(
            configProvider = { NvidiaConfig(apiKey = "k", consentGranted = true) },
            complete = { _, _, _, _ -> calls++; NvidiaResult(NvidiaStatus.OK, text = aiReply) },
            maxChunkChars = 12_000,
        )
        val result = analyzer.analyze("Just a title", CandidateProfile(), emptySet())

        assertEquals("a fragment must not be sent to the model", 0, calls)
        assertEquals(CvAiStatus.NONE, result.aiStatus)
        assertEquals(HeuristicCvAnalyzer().id, result.effectiveEngineId)
    }

    @Test
    fun theRepositoryNamesTheEngineThatProducedTheShownProfile() = runBlocking {
        // AI configured but failing: the record must say on-device, matching the fields shown.
        val repository = CvRepository(
            context = context,
            fileStore = CvFileStore(context),
            parserRegistry = CvParserRegistry.default(context),
            analyzer = CvAiAnalyzer(
                configProvider = { NvidiaConfig(apiKey = "k", consentGranted = true) },
                complete = { _, _, _, _ -> NvidiaResult(NvidiaStatus.NETWORK, message = "The connection failed.") },
                maxChunkChars = 12_000,
            ),
        )
        importTxt(repository, "cv.txt", fullCvText)

        val record = repository.analyzeImported()

        assertEquals(CvAiStatus.FAILED, record.aiStatus)
        assertEquals(HeuristicCvAnalyzer().label, record.engineLabel)
    }

    @Test
    fun aSuccessfulAiRunKeepsTheAiEngineName() = runBlocking {
        val repository = CvRepository(
            context = context,
            fileStore = CvFileStore(context),
            parserRegistry = CvParserRegistry.default(context),
            analyzer = aiAnalyzer(aiReply),
        )
        importTxt(repository, "cv.txt", fullCvText)

        val record = repository.analyzeImported()

        assertEquals(CvAiStatus.SUCCESS, record.aiStatus)
        assertEquals("nvidia-ai", record.engineId)
        assertEquals("AI analysis (NVIDIA)", record.engineLabel)
    }

    @Test
    fun theRepositoryWarningsNeverContradictTheStoredProfile() = runBlocking {
        val repository = CvRepository(
            context = context,
            fileStore = CvFileStore(context),
            parserRegistry = CvParserRegistry.default(context),
            analyzer = aiAnalyzer(aiReply),
        )
        importTxt(repository, "cv.txt", thinText)

        val record = repository.analyzeImported()

        assertTrue(record.profile.education.isNotEmpty())
        assertTrue(record.profile.languages.isNotEmpty())
        assertFalse(record.warnings.any { it.contains("education or training", ignoreCase = true) })
        assertFalse(record.warnings.any { it.contains("languages were listed", ignoreCase = true) })
    }

    // ---- Character counts --------------------------------------------------

    @Test
    fun theNonWhitespaceCountIsPersistedAndReadBack() {
        val record = CvRecord(
            fileName = "cv.pdf",
            storedFileName = "s.pdf",
            fileType = CvFileType.PDF,
            sizeBytes = 100L,
            importedAtEpochMillis = 1L,
            status = CvStatus.ANALYZED,
            extractedCharacters = 4_812,
            extractedNonWhitespaceCharacters = 3_990,
        )

        val restored = cvRecordFromJson(recordToJson(record))

        assertEquals(4_812, restored.extractedCharacters)
        assertEquals(3_990, restored.extractedNonWhitespaceCharacters)
    }

    @Test
    fun anOlderRecordWithoutTheNonWhitespaceCountReadsAsZero() {
        val legacy = JSONObject().apply {
            put("fileName", "old.pdf")
            put("storedFileName", "s.pdf")
            put("fileType", "PDF")
            put("sizeBytes", 10)
            put("importedAt", 1L)
            put("extractedCharacters", 96)
        }

        val restored = cvRecordFromJson(legacy)

        assertEquals(96, restored.extractedCharacters)
        assertEquals(0, restored.extractedNonWhitespaceCharacters)
    }

    // ---- Extraction recovers the text --------------------------------------

    @Test
    fun theParserNeverRecoversLessThanTheDefaultReadingOrder() = runBlocking {
        val bytes = textPdf((1..30).map { "Line $it: preventive maintenance hydraulics PLC electrical wiring" })

        val parsed = CvParserRegistry.default(context).parse(ByteCvSource("dense.pdf", bytes), CvFileType.PDF)

        val plain = PDDocument.load(bytes.inputStream()).use {
            PDFTextStripper().apply { startPage = 1; endPage = 1 }.getText(it).count { c -> !c.isWhitespace() }
        }
        assertTrue(
            "the layout pass must not lose text the default order found ($plain)",
            parsed.nonWhitespaceCount >= plain,
        )
        assertTrue("a dense single-column CV must be read fully", parsed.nonWhitespaceCount > 1_000)
    }

    @Test
    fun aTwoColumnCvKeepsBothColumns() = runBlocking {
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        try {
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 11f)
                content.newLineAtOffset(50f, 780f)
                content.showText("LEFT COLUMN SKILLS")
                content.newLineAtOffset(0f, -16f)
                content.showText("preventive maintenance hydraulics")
                content.endText()
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 11f)
                content.newLineAtOffset(320f, 780f)
                content.showText("RIGHT COLUMN EXPERIENCE")
                content.newLineAtOffset(0f, -16f)
                content.showText("six years industrial maintenance")
                content.endText()
            }
            val out = ByteArrayOutputStream()
            document.save(out)

            val parsed = CvParserRegistry.default(context).parse(ByteCvSource("two.pdf", out.toByteArray()), CvFileType.PDF)

            assertTrue(parsed.text.contains("LEFT COLUMN SKILLS"))
            assertTrue(parsed.text.contains("RIGHT COLUMN EXPERIENCE"))
            assertTrue(parsed.text.contains("six years industrial maintenance"))
        } finally {
            document.close()
        }
    }

    // ---- helpers -----------------------------------------------------------

    private val fullCvText = """
        Jonathan Rakoto
        Maintenance Technician
        Antananarivo, Madagascar
        jrakoto@example.mg
        +261 34 12 345 67
        Skills
        preventive maintenance, hydraulics, PLC
        Education
        BTS Electrotechnics, IST Antananarivo, 2018
        Languages
        French - fluent
        English - intermediate
    """.trimIndent()

    private fun textPdf(lines: List<String>): ByteArray {
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        try {
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 11f)
                content.newLineAtOffset(50f, 780f)
                for (line in lines) {
                    content.showText(line)
                    content.newLineAtOffset(0f, -14f)
                }
                content.endText()
            }
            val out = ByteArrayOutputStream()
            document.save(out)
            return out.toByteArray()
        } finally {
            document.close()
        }
    }

    private fun importTxt(repository: CvRepository, name: String, text: String) {
        val uri = Uri.parse("content://stage40/$name")
        org.robolectric.Shadows.shadowOf(context.contentResolver)
            .registerInputStream(uri, java.io.ByteArrayInputStream(text.toByteArray()))
        runBlocking { repository.importFile(uri) }
    }
}
