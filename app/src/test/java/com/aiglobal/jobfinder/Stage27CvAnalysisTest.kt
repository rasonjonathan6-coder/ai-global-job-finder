package com.aiglobal.jobfinder

import androidx.test.core.app.ApplicationProvider
import com.aiglobal.jobfinder.data.cv.ByteCvSource
import com.aiglobal.jobfinder.data.cv.CvParseException
import com.aiglobal.jobfinder.data.cv.CvParserRegistry
import com.aiglobal.jobfinder.data.cv.analysis.HeuristicCvAnalyzer
import com.aiglobal.jobfinder.data.cv.PdfCvParser
import com.aiglobal.jobfinder.data.model.CandidateField
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.CvFileType
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * Regression coverage for the device crash on "Analyse my CV".
 *
 * Root cause: PDFBox-Android loads its glyph lists and font metrics from the application
 * assets and aborts the whole process the first time text is extracted if
 * [PDFBoxResourceLoader] was never initialised. These tests exercise the real parser and
 * the real asset loading under Robolectric — where the assets are present but the loader is
 * not pre-initialised — so the bug cannot silently return.
 *
 * None of this ran on a physical device: no emulator or handset is available here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage27CvAnalysisTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val cvLines = listOf(
        "Jonathan Rakoto",
        "Maintenance Technician",
        "Antananarivo, Madagascar",
        "jonathan.rakoto@example.mg",
        "+261 34 12 345 67",
        "",
        "Experience",
        "Maintenance Technician - Star Malgache (2018 - present)",
        "Six years of experience in industrial maintenance.",
        "Skills: preventive maintenance, hydraulics, PLC, electrical wiring",
        "",
        "Education",
        "BTS Electrotechnics, IST Antananarivo, 2018",
        "",
        "Languages",
        "French - fluent",
        "English - intermediate",
    )

    @Test
    fun pdfTextIsExtractedThroughTheRealAssetBackedParser() = runBlocking {
        val bytes = pdfBytes(cvLines)
        val parser = PdfCvParser(context)

        val result = parser.parse(ByteCvSource("cv.pdf", bytes), CvFileType.PDF)

        assertTrue("PDFBox must report its resource loader as ready", PDFBoxResourceLoader.isReady())
        assertTrue("A text PDF must yield usable text", result.hasUsableText)
        assertTrue(result.text.contains("Maintenance Technician"))
        assertTrue(result.text.contains("Jonathan Rakoto"))
        assertEquals(1, result.pageCount)
    }

    @Test
    fun defaultRegistryReadsAPdfAndProducesAProfile() = runBlocking {
        val bytes = pdfBytes(cvLines)
        val registry = CvParserRegistry.default(context)

        val parsed = registry.parse(ByteCvSource("cv.pdf", bytes), CvFileType.PDF)
        assertTrue(parsed.hasUsableText)

        val analysis = HeuristicCvAnalyzer().analyze(
            text = parsed.text,
            existing = CandidateProfile(),
            protectedFields = emptySet(),
        )

        assertEquals("jonathan.rakoto@example.mg", analysis.profile.email)
        assertNotNull(analysis.profile.phone)
        assertTrue("Skills must be matched from the CV", analysis.profile.skills.isNotEmpty())
        assertTrue("Confidence must reflect real extracted data", analysis.confidence > 0)
    }

    @Test
    fun aPdfWithNoTextLayerIsReportedAsNoTextNotGuessedAt() = runBlocking {
        // A single blank page: a valid document with nothing to read.
        val bytes = pdfBytes(emptyList())

        val result = PdfCvParser(context).parse(ByteCvSource("scan.pdf", bytes), CvFileType.PDF)

        assertFalse(result.hasUsableText)
        assertTrue(result.warnings.any { it.contains("no text layer", ignoreCase = true) })
    }

    @Test
    fun aCorruptFileIsRejectedAsCorruptedNotCrashed() {
        val bytes = "this is not a pdf at all".toByteArray()

        assertThrows(CvParseException.Corrupted::class.java) {
            runBlocking { PdfCvParser(context).parse(ByteCvSource("broken.pdf", bytes), CvFileType.PDF) }
        }
    }

    @Test
    fun aLargePdfIsHandledWithoutFailing() = runBlocking {
        val bytes = pdfBytes(cvLines, pageCount = 40)

        val result = PdfCvParser(context).parse(ByteCvSource("large.pdf", bytes), CvFileType.PDF)

        assertEquals(40, result.pageCount)
        assertTrue(result.hasUsableText)
    }

    @Test
    fun aFormatWithNoParserIsReportedAsUnsupported() {
        val registry = CvParserRegistry(emptyList())

        assertThrows(CvParseException.Unsupported::class.java) {
            runBlocking { registry.parse(ByteCvSource("cv.pdf", ByteArray(0)), CvFileType.PDF) }
        }
    }

    @Test
    fun aFailedParseCanBeRetriedSuccessfully() = runBlocking {
        val parser = PdfCvParser(context)

        assertThrows(CvParseException.Corrupted::class.java) {
            runBlocking { parser.parse(ByteCvSource("broken.pdf", ByteArray(16) { 0x7F }), CvFileType.PDF) }
        }

        // The same parser instance must still work afterwards: a retry after a failure.
        val retry = parser.parse(ByteCvSource("cv.pdf", pdfBytes(cvLines)), CvFileType.PDF)
        assertTrue(retry.hasUsableText)
    }

    @Test
    fun analysisNeverInventsFieldsTheDocumentDoesNotContain() = runBlocking {
        val sparse = "Just some words here with nothing identifying at all in them whatsoever."

        val analysis = HeuristicCvAnalyzer().analyze(
            text = sparse,
            existing = CandidateProfile(),
            protectedFields = setOf(CandidateField.FULL_NAME),
        )

        assertTrue(analysis.profile.email.isEmpty())
        assertTrue(analysis.profile.skills.isEmpty())
        assertTrue(analysis.profile.education.isEmpty())
    }

    // ---------------------------------------------------------------- helpers

    /** Builds a real PDF in memory. ASCII only, so the standard Helvetica font can encode it. */
    private fun pdfBytes(lines: List<String>, pageCount: Int = 1): ByteArray {
        val document = PDDocument()
        try {
            repeat(pageCount) { pageIndex ->
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 12f)
                    content.newLineAtOffset(50f, 760f)
                    val pageLines = if (pageIndex == 0) lines else lines.take(1)
                    for (line in pageLines) {
                        content.showText(line)
                        content.newLineAtOffset(0f, -16f)
                    }
                    content.endText()
                }
            }
            val out = ByteArrayOutputStream()
            document.save(out)
            return out.toByteArray()
        } finally {
            document.close()
        }
    }
}
