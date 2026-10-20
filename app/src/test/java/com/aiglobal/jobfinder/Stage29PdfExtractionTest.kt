package com.aiglobal.jobfinder

import androidx.test.core.app.ApplicationProvider
import com.aiglobal.jobfinder.data.cv.ByteCvSource
import com.aiglobal.jobfinder.data.cv.CvParseException
import com.aiglobal.jobfinder.data.cv.CvParserRegistry
import com.aiglobal.jobfinder.data.cv.analysis.HeuristicCvAnalyzer
import com.aiglobal.jobfinder.data.cv.PdfCvParser
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.CvFileType
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * Stage 29 — end-to-end coverage of PDF extraction.
 *
 * The reported symptom: a real 1.9 MB CV was reported "Analysed" after only ~96 characters
 * were recovered — the job title alone — with name, contact, skills, education and languages
 * all empty, and completeness frozen at 12% (the weight of the title). This suite pins the
 * behaviour that must hold instead: every page is read and counted, an image-only PDF is
 * reported honestly rather than as "Analysed", and a document that yields only a fragment is
 * labelled "Partly read" so a thin profile is never mistaken for a complete one.
 *
 * The tests build real PDFs in memory and drive the real [PdfCvParser] and [HeuristicCvAnalyzer]
 * under Robolectric. **The user's actual CV was not available in this repository, so it is not
 * tested here** — these are synthetic documents, and no claim is made about the real file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage29PdfExtractionTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    init {
        // Same one-time asset init the parser relies on; mirrors a real device start-up.
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun aMultiPageTextPdfRecoversEveryPageWithoutTruncation() = runBlocking {
        val pages = (1..5).map { page -> listOf("HEADING PAGE $page", "content marker $page-body-$page") }
        val bytes = textPdf(pages)

        val result = PdfCvParser(context).parse(ByteCvSource("multi.pdf", bytes), CvFileType.PDF)

        assertEquals(5, result.pageCount)
        assertEquals(5, result.pages.size)
        // Every page contributed text, so none was dropped.
        assertTrue(result.pages.none { it.isEmpty })
        assertTrue(result.hasUsableText)
        for (page in 1..5) {
            assertTrue("page $page body must survive", result.text.contains("content marker $page-body-$page"))
            assertTrue("page $page must report a positive character count", result.pages[page - 1].characterCount > 0)
        }
    }

    @Test
    fun everyPageIsCountedEvenWhenOnePageHoldsNoText() = runBlocking {
        // Page bodies: real text on 1 and 3, nothing on 2. The count must still be 3, and the
        // empty page must be visible in the per-page breakdown rather than silently skipped.
        val bytes = textPdf(
            listOf(
                listOf("First page with plenty of readable text to analyse here."),
                emptyList(),
                listOf("Third page with plenty of readable text to analyse here as well."),
            ),
        )

        val result = PdfCvParser(context).parse(ByteCvSource("gap.pdf", bytes), CvFileType.PDF)

        assertEquals(3, result.pageCount)
        assertEquals(3, result.pages.size)
        assertTrue("page 2 must be reported empty", result.pages[1].isEmpty)
        assertFalse(result.pages[0].isEmpty)
        assertFalse(result.pages[2].isEmpty)
        assertTrue(result.warnings.any { it.contains("no text", ignoreCase = true) })
    }

    @Test
    fun aFrenchPdfWithAccentsKeepsItsDiacritics() = runBlocking {
        val french = listOf(
            "Technicien de maintenance",
            "Jonathan Rakoto",
            "Antananarivo, Madagascar",
            "jrakoto@example.mg",
            "+261 34 12 345 67",
            "Formation",
            "BTS Électrotechnique, IST Antananarivo, 2018",
            "Langues",
            "Français - courant",
            "Malgache - langue maternelle",
        )
        // Helvetica under WinAnsiEncoding carries the accented letters, so no external font
        // file is needed and the accents travel through the real extraction path.
        val bytes = textPdf(listOf(french))

        val parsed = CvParserRegistry.default(context).parse(ByteCvSource("cv_fr.pdf", bytes), CvFileType.PDF)
        assertTrue(parsed.hasUsableText)
        assertTrue("accents must survive extraction", parsed.text.contains("Français"))
        assertTrue(parsed.text.contains("Électrotechnique"))

        val profile = HeuristicCvAnalyzer().analyze(parsed.text, CandidateProfile(), emptySet()).profile
        assertEquals("Jonathan Rakoto", profile.fullName)
        assertTrue(profile.languages.any { it.name == "French" })
    }

    @Test
    fun aTwoColumnPdfKeepsTheContentOfBothColumns() = runBlocking {
        // Left column x=50, right column x=320 — the classic layout that a naive extractor
        // can interleave and mangle.
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

            val result = PdfCvParser(context).parse(ByteCvSource("two.pdf", out.toByteArray()), CvFileType.PDF)

            assertEquals(1, result.pageCount)
            assertTrue(result.text.contains("LEFT COLUMN SKILLS"))
            assertTrue(result.text.contains("preventive maintenance hydraulics"))
            assertTrue(result.text.contains("RIGHT COLUMN EXPERIENCE"))
            assertTrue(result.text.contains("six years industrial maintenance"))
        } finally {
            document.close()
        }
    }

    @Test
    fun aPdfWithOnlyAFragmentIsReportedAsPartialNotComplete() = runBlocking {
        // Exactly the reported shape: a title, and nothing else that is readable.
        val bytes = textPdf(listOf(listOf("Mechanical Seal Repair Technician")))

        val result = PdfCvParser(context).parse(ByteCvSource("fragment.pdf", bytes), CvFileType.PDF)

        assertFalse("a title alone is not a usable CV", result.hasUsableText)
        assertTrue("but something was recovered", result.hasAnythingExtractable)
        assertTrue("so it is partial, not empty", result.isPartial)
        assertTrue(result.warnings.any { it.contains("fragment", ignoreCase = true) })
    }

    @Test
    fun anImageOnlyPdfIsReportedAsNoUsableTextNotParsed() = runBlocking {
        val bytes = imageOnlyPdf()

        val result = PdfCvParser(context).parse(ByteCvSource("scan.pdf", bytes), CvFileType.PDF)

        assertFalse(result.hasUsableText)
        assertFalse(result.hasAnythingExtractable)
        assertTrue("the image-only case must be named, not guessed", result.imagesOnly)
        assertTrue(result.warnings.any { it.contains("image", ignoreCase = true) && it.contains("OCR", ignoreCase = true) })
        assertEquals(1, result.pageCount)
    }

    @Test
    fun aCorruptPdfIsRejectedWithoutCrashing() {
        val bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x00, 0x7F, 0x13)

        assertThrows(CvParseException.Corrupted::class.java) {
            runBlocking { PdfCvParser(context).parse(ByteCvSource("broken.pdf", bytes), CvFileType.PDF) }
        }
    }

    @Test
    fun aPasswordProtectedPdfIsRejectedAsEncrypted() {
        val bytes = encryptedPdf(userPassword = "secret", ownerPassword = "owner")

        assertThrows(CvParseException.Encrypted::class.java) {
            runBlocking { PdfCvParser(context).parse(ByteCvSource("locked.pdf", bytes), CvFileType.PDF) }
        }
    }

    @Test
    fun aFragmentNeverProducesInventedProfileData() = runBlocking {
        val result = PdfCvParser(context).parse(
            ByteCvSource("fragment.pdf", textPdf(listOf(listOf("Mechanical Seal Repair Technician")))),
            CvFileType.PDF,
        )

        val analysis = HeuristicCvAnalyzer().analyze(result.text, CandidateProfile(), emptySet())
        val profile = analysis.profile

        // Only what is genuinely there: a title. No invented name, contact, education or languages.
        assertTrue(profile.fullName.isEmpty())
        assertTrue(profile.email.isEmpty())
        assertTrue(profile.phone.isEmpty())
        assertTrue(profile.location.isEmpty())
        assertTrue(profile.education.isEmpty())
        assertTrue(profile.languages.isEmpty())
    }

    @Test
    fun stage27AndStage28PdfBehaviourIsPreserved() = runBlocking {
        val full = listOf(
            "Maintenance Technician",
            "Jonathan Rakoto",
            "Antananarivo, Madagascar",
            "jrakoto@example.mg",
            "+261 34 12 345 67",
            "Experience",
            "Maintenance Technician - Star Malgache (2018 - present)",
            "Skills",
            "preventive maintenance, mechanical repair, hydraulics, PLC",
            "Education",
            "BTS Electrotechnics, IST Antananarivo, 2018",
            "Languages",
            "French - fluent",
            "English - intermediate",
        )
        val bytes = textPdf(listOf(full))

        val result = PdfCvParser(context).parse(ByteCvSource("cv.pdf", bytes), CvFileType.PDF)

        // Stage 27: the asset-backed loader works and a text PDF yields usable text.
        assertTrue(PDFBoxResourceLoader.isReady())
        assertTrue(result.hasUsableText)
        assertEquals(1, result.pageCount)

        // Stage 28: the real fields are recovered, not just the title.
        val profile = HeuristicCvAnalyzer().analyze(result.text, CandidateProfile(), emptySet()).profile
        assertEquals("Jonathan Rakoto", profile.fullName)
        assertEquals("jrakoto@example.mg", profile.email)
        assertTrue(profile.skills.isNotEmpty())
        assertTrue(profile.education.isNotEmpty())
        assertTrue(profile.languages.isNotEmpty())
    }

    // ---------------------------------------------------------------- helpers

    private fun textPdf(pages: List<List<String>>): ByteArray {
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        try {
            for (lines in pages) {
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 11f)
                    content.newLineAtOffset(50f, 780f)
                    for (line in lines) {
                        content.showText(line)
                        content.newLineAtOffset(0f, -15f)
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

    private fun imageOnlyPdf(): ByteArray {
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        try {
            val page = PDPage()
            document.addPage(page)
            val png = onePixelPng()
            val image = PDImageXObject.createFromByteArray(document, png, "scan.png")
            PDPageContentStream(document, page).use { content ->
                content.drawImage(image, 40f, 500f, 200f, 260f)
            }
            val out = ByteArrayOutputStream()
            document.save(out)
            return out.toByteArray()
        } finally {
            document.close()
        }
    }

    private fun encryptedPdf(userPassword: String, ownerPassword: String): ByteArray {
        val document = PDDocument()
        try {
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 11f)
                content.newLineAtOffset(50f, 760f)
                content.showText("Protected CV contents")
                content.endText()
            }
            val policy = StandardProtectionPolicy(ownerPassword, userPassword, AccessPermission())
            policy.encryptionKeyLength = 128
            document.protect(policy)
            val out = ByteArrayOutputStream()
            document.save(out)
            return out.toByteArray()
        } finally {
            document.close()
        }
    }

    /** Smallest valid PNG (1x1, red) so an image XObject can be embedded. */
    private fun onePixelPng(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53, 0xDE.toByte(),
        0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, 0x54,
        0x78, 0x9C.toByte(), 0x63, 0xF8.toByte(), 0xCF.toByte(), 0xC0.toByte(), 0x00, 0x00,
        0x03, 0x01, 0x01, 0x00, 0xC9.toByte(), 0xFE.toByte(), 0x92.toByte(), 0xEF.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )
}
