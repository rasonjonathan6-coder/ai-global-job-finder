package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.engine.MatchInput
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.engine.SkillLexicon
import com.aiglobal.jobfinder.data.model.CandidateProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 94 — the offer-side skill vocabulary now understands the industrial mechanical vocabulary
 * a maintenance / pump / seal CV is written in, in both English and French.
 *
 * These tests pin three things the change must not break:
 *
 *  1. the new phrases are detected in a posting, in English and in French;
 *  2. whole-word matching still holds, so "pumpkin" never fires "Rotating equipment" and a bare
 *     "seal" never fires "Mechanical seals";
 *  3. the detected canonical names intersect the CV analyser's canonical names, so the offer side
 *     and the CV side actually meet instead of producing false "missing" skills.
 */
class Stage94MechanicalMatchTest {

    // ---- English detection -----------------------------------------------------

    @Test
    fun anEnglishPumpAndSealPostingIsReadAsTheCvCanonicalSkills() {
        val detected = SkillLexicon.detect(
            "Overhaul centrifugal pumps and rotating equipment; fit mechanical seals and gland packing. " +
                "Carry out preventive maintenance on the plant.",
        )

        assertTrue("pumps must be read as rotating equipment", detected.contains("Rotating equipment"))
        assertTrue("seals must be read", detected.contains("Mechanical seals"))
        assertTrue(detected.contains("Preventive maintenance"))
    }

    @Test
    fun componentRepairIsReadAsMechanicalRepair() {
        val detected = SkillLexicon.detect("Component repair and overhaul of industrial gearboxes.")

        assertTrue(detected.contains("Mechanical repair"))
    }

    // ---- French detection ------------------------------------------------------

    @Test
    fun frenchMaintenanceVocabularyIsReadAsTheSameCanonicalSkills() {
        val detected = SkillLexicon.detect(
            "Entretien des pompes centrifuges et remplacement des joints mécaniques. " +
                "Maintenance préventive et hydraulique.",
        )

        assertTrue("pompes must be read as rotating equipment", detected.contains("Rotating equipment"))
        assertTrue("joints mécaniques must be read", detected.contains("Mechanical seals"))
        assertTrue(detected.contains("Preventive maintenance"))
        assertTrue(detected.contains("Hydraulics"))
    }

    // ---- No false positives ----------------------------------------------------

    @Test
    fun aPumpkinIsNotAPump() {
        // Whole-word matching: the short stem "pump" must not fire inside "pumpkin".
        assertFalse(SkillLexicon.detect("Pumpkin farm operations manager").contains("Rotating equipment"))
    }

    @Test
    fun aBareSealIsNotAMechanicalSeal() {
        assertFalse(SkillLexicon.detect("Seal the envelope and send it to the client").contains("Mechanical seals"))
    }

    @Test
    fun anUnrelatedPostingDoesNotFireTheMechanicalSkills() {
        val detected = SkillLexicon.detect(
            "Digital marketing manager. Own SEO, social media campaigns and newsletter copywriting.",
        )

        val mechanical = setOf("Rotating equipment", "Mechanical seals", "Mechanical repair", "Preventive maintenance")
        assertTrue("no industrial mechanical skill may fire on a marketing posting", detected.intersect(mechanical).isEmpty())
    }

    // ---- Offer side meets CV side ---------------------------------------------

    @Test
    fun aPumpCvMatchesAPumpPostingWithNoFalseMissingSkills() {
        val profile = CandidateProfile(
            fullName = "Jonathan Rakoto",
            currentTitle = "Maintenance Technician",
            skills = listOf("Rotating equipment", "Mechanical seals", "Preventive maintenance"),
        )

        val assessment = MatchingEngine().assess(
            MatchInput(
                title = "Pump Technician",
                country = "Canada",
                description = "Maintain centrifugal pumps and mechanical seals. Preventive maintenance.",
            ),
            profile,
        )

        assertTrue("a pump CV must match a pump posting", assessment.matchedSkills.contains("Rotating equipment"))
        assertTrue(assessment.matchedSkills.contains("Mechanical seals"))
        assertTrue("nothing the CV covers may be reported missing", assessment.missingSkills.isEmpty())
    }

    @Test
    fun theMechanicalSkillsAreReportedMissingWhenTheCvLacksThem() {
        val profile = CandidateProfile(
            fullName = "Someone Else",
            currentTitle = "Marketing Manager",
            skills = listOf("Marketing"),
        )

        val assessment = MatchingEngine().assess(
            MatchInput(
                title = "Pump Technician",
                country = "Canada",
                description = "Maintain centrifugal pumps and mechanical seals.",
            ),
            profile,
        )

        assertTrue(assessment.missingSkills.contains("Rotating equipment"))
        assertTrue(assessment.missingSkills.contains("Mechanical seals"))
        assertFalse("a skill the CV lacks is not a match", assessment.matchedSkills.contains("Rotating equipment"))
    }

    // ---- Canonical names line up with the CV lexicon ---------------------------

    @Test
    fun freeFormCvSkillNamesCanonicalizeOntoTheJobVocabulary() {
        assertEquals("Rotating equipment", SkillLexicon.canonicalize("pompes"))
        assertEquals("Rotating equipment", SkillLexicon.canonicalize("Rotating equipment"))
        assertEquals("Mechanical seals", SkillLexicon.canonicalize("Mechanical seals"))
    }
}
