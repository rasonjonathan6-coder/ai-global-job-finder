package com.aiglobal.jobfinder

import androidx.test.core.app.ApplicationProvider
import com.aiglobal.jobfinder.data.cv.ByteCvSource
import com.aiglobal.jobfinder.data.cv.CvParserRegistry
import com.aiglobal.jobfinder.data.cv.CvTextNormalizer
import com.aiglobal.jobfinder.data.cv.HeuristicCvAnalyzer
import com.aiglobal.jobfinder.data.model.CandidateField
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.CvFileType
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * Regression coverage for real-CV extraction.
 *
 * The reported bug: a real PDF reported "Analysed" and found the job title, but the name,
 * e-mail, phone, location, skills, education and languages were all empty and completeness
 * stayed at 12% — exactly the weight of the title. The cause was field-by-field fragility:
 * the name check was too strict, keywords were matched case-insensitively but not
 * accent-insensitively, and education depended on an exact section heading.
 *
 * These tests drive the real analyzer and the real PDF parser. They assert that only data
 * which is actually in the document is produced — nothing is invented.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage28CvExtractionTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    // A header whose job title comes before the name and whose name sits in a Contact
    // block, which is exactly the layout the old strict name check failed on.
    private val realisticCv = listOf(
        "Maintenance Technician",
        "Jonathan Rakoto",
        "Antananarivo, Madagascar",
        "jrakoto1985@example.mg",
        "+261 34 12 345 67",
        "Profile",
        "Mechanical seal repair technician with six years of industrial maintenance experience.",
        "Experience",
        "Maintenance Technician - Star Malgache (2018 - present)",
        "Skills",
        "Preventive maintenance, mechanical repair, hydraulics, PLC, welding",
        "Education",
        "BTS Electrotechnics, IST Antananarivo, 2018",
        "Languages",
        "French - fluent",
        "English - intermediate",
    )

    @Test
    fun aRealisticPdfHeaderYieldsNameContactAndLocation() = runBlocking {
        val parsed = CvParserRegistry.default(context)
            .parse(ByteCvSource("cv.pdf", pdfBytes(realisticCv)), CvFileType.PDF)

        val analysis = HeuristicCvAnalyzer().analyze(
            text = parsed.text,
            existing = CandidateProfile(),
            protectedFields = emptySet(),
        )

        val profile = analysis.profile
        assertEquals("Jonathan Rakoto", profile.fullName)
        assertEquals("jrakoto1985@example.mg", profile.email)
        assertTrue("The phone number must be read", profile.phone.isNotBlank())
        assertTrue("The location must be read", profile.location.contains("Madagascar"))
        assertEquals("Maintenance Technician", profile.currentTitle)
    }

    @Test
    fun skillsEducationAndLanguagesAreAllReadFromASectionedPdf() = runBlocking {
        val parsed = CvParserRegistry.default(context)
            .parse(ByteCvSource("cv.pdf", pdfBytes(realisticCv)), CvFileType.PDF)

        val profile = HeuristicCvAnalyzer().analyze(
            text = parsed.text,
            existing = CandidateProfile(),
            protectedFields = emptySet(),
        ).profile

        assertTrue("Several skills must be matched", profile.skills.size >= 3)
        assertTrue(profile.skills.any { it.contains("maintenance", ignoreCase = true) })
        assertTrue("Education must be read", profile.education.isNotEmpty())
        assertTrue(profile.education.any { it.degree.contains("BTS", ignoreCase = true) })
        assertTrue("Languages must be read", profile.languages.size >= 2)
    }

    @Test
    fun aFrenchCvWithAccentsAndFrenchSectionNamesIsRead() = runBlocking {
        val text = """
            Technicien de maintenance
            Jonathan Rakoto
            Antananarivo
            jonathan@example.mg
            +261 32 11 222 33

            Profil
            Spécialiste en maintenance préventive et mécanique industrielle.

            Expérience professionnelle
            Technicien - Star Malgache (2019 - présent)
            Diagnostic électrique, hydraulique, soudure.

            Formation
            BTS Électrotechnique, IST Antananarivo, 2018

            Langues
            Malgache - langue maternelle
            Français - courant
            Anglais - intermédiaire
        """.trimIndent()

        val profile = HeuristicCvAnalyzer().analyze(
            text = text,
            existing = CandidateProfile(),
            protectedFields = emptySet(),
        ).profile

        assertEquals("Jonathan Rakoto", profile.fullName)
        assertTrue("Accented skills must match", profile.skills.any { it.contains("Mechanical", ignoreCase = true) })
        assertTrue("Accented education must be read", profile.education.isNotEmpty())
        // "Français" is French, "Malgache" is Malagasy, "Anglais" is English.
        val names = profile.languages.map { it.name }
        assertTrue("French must be recognised", names.contains("French"))
        assertTrue("Malagasy must be recognised", names.contains("Malagasy"))
        assertTrue("English must be recognised", names.contains("English"))
        assertEquals("Intermediary level must map to Intermédiaire",
            com.aiglobal.jobfinder.data.model.LanguageLevel.INTERMEDIATE,
            profile.languages.first { it.name == "English" }.level)
    }

    @Test
    fun theJobTitleAloneDoesNotHideTheRestOfTheDocument() = runBlocking {
        // Title first, name second: the old code locked onto the title and lost the rest.
        val text = "Maintenance Technician with over six years of experience\n" +
            "Aina Rasoa\naina@example.mg\n+261 33 44 555 66"

        val profile = HeuristicCvAnalyzer().analyze(
            text = text,
            existing = CandidateProfile(),
            protectedFields = emptySet(),
        ).profile

        assertEquals("Aina Rasoa", profile.fullName)
        assertEquals("aina@example.mg", profile.email)
    }

    @Test
    fun nothingIsInventedWhenTheDocumentHasNoSuchData() = runBlocking {
        val text = "Just some words with no name, no contact details and no sections at all."

        val profile = HeuristicCvAnalyzer().analyze(
            text = text,
            existing = CandidateProfile(),
            protectedFields = setOf(CandidateField.FULL_NAME),
        ).profile

        assertTrue(profile.fullName.isEmpty())
        assertTrue(profile.email.isEmpty())
        assertTrue(profile.phone.isEmpty())
        assertTrue(profile.skills.isEmpty())
        assertTrue(profile.education.isEmpty())
        assertTrue(profile.languages.isEmpty())
    }

    @Test
    fun aNameIsNotReadFromAJobTitleOrASectionHeading() = runBlocking {
        val text = "Maintenance Technician\nExperience\nJonathan Rakoto\njonathan@example.mg"

        val profile = HeuristicCvAnalyzer().analyze(
            text = text,
            existing = CandidateProfile(),
            protectedFields = emptySet(),
        ).profile

        assertEquals("Jonathan Rakoto", profile.fullName)
    }

    @Test
    fun warningsDistinguishAMissingSectionFromAnUnreadableOne() = runBlocking {
        val withUnreadableSkills = "Skills\nzigbee quokka walrus narwhal\nLanguages\nxylophone yodel zephyr"
        val withNothing = "This document is just a long paragraph of plain prose with no section " +
            "headings anywhere at all, and it describes nothing in particular about anyone."

        val unreadable = HeuristicCvAnalyzer().analyze(
            text = withUnreadableSkills,
            existing = CandidateProfile(),
            protectedFields = setOf(CandidateField.FULL_NAME),
        )
        val absent = HeuristicCvAnalyzer().analyze(
            text = withNothing,
            existing = CandidateProfile(),
            protectedFields = setOf(CandidateField.FULL_NAME),
        )

        assertTrue(
            "A present-but-unreadable skills section must say so",
            unreadable.warnings.any { it.contains("no known skills", ignoreCase = true) },
        )
        assertTrue(
            "An absent skills section must be reported as absent, got: ${absent.warnings}",
            absent.warnings.any { it.contains("No skills section", ignoreCase = true) },
        )
    }

    // ---------------------------------------------------------------- normaliser

    @Test
    fun mojibakeFromADoubleDecodedPdfIsRepaired() {
        val broken = "MÃ©canicien Ã©lectrique"
        val fixed = CvTextNormalizer.normalize(broken)
        assertTrue("UTF-8 mis-read as Latin-1 must be repaired, got: $fixed", fixed.contains("Mécanicien"))
        assertTrue(fixed.contains("électrique"))
    }

    @Test
    fun aWordSplitAcrossLinesWithAHyphenIsRejoined() {
        val text = "Maintenance techni-\ncian with experience"
        val fixed = CvTextNormalizer.normalize(text)
        assertTrue(fixed.contains("technician"))
    }

    @Test
    fun foldingIsAccentAndCaseInsensitive() {
        assertEquals("mecanicien", CvTextNormalizer.fold("Mécanicien"))
        assertEquals("anglais", CvTextNormalizer.fold("Anglais"))
        assertEquals("electrotechnique", CvTextNormalizer.fold("Électrotechnique"))
    }

    @Test
    fun aCleanDocumentIsLeftUnchanged() {
        val clean = "Jonathan Rakoto\nAntananarivo, Madagascar"
        assertEquals(clean, CvTextNormalizer.normalize(clean))
        assertFalse(CvTextNormalizer.looksLikeMojibake(clean))
    }

    @Test
    fun anEmptyDocumentStaysEmpty() {
        assertEquals("", CvTextNormalizer.normalize(""))
        assertEquals("", CvTextNormalizer.normalize("   \n  \n "))
    }

    // ---------------------------------------------------------------- helpers

    /** Builds a real PDF in memory. ASCII only, so the standard Helvetica font can encode it. */
    private fun pdfBytes(lines: List<String>): ByteArray {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        try {
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 11f)
                content.newLineAtOffset(50f, 760f)
                for (line in lines) {
                    content.showText(line)
                    content.newLineAtOffset(0f, -15f)
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
}
