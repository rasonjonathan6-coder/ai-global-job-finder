package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.search.RoleClassifier
import com.aiglobal.jobfinder.data.jobs.search.RoleFamily
import com.aiglobal.jobfinder.data.jobs.search.TargetSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 71 — live search quality validation.
 *
 * The live probe ([Stage71LiveSearchHarness]) showed the key-free European feeds returning
 * genuine trade postings whose title names the trade in German, Dutch or Norwegian
 * ("Instandhaltungsmechaniker", "Onderhoudstechnieker", "Vedlikeholdstekniker"). Those are
 * exactly the candidate's job, but the English-only taxonomy classified them as unrelated
 * and set them aside. These tests pin the language-aware trade terms and, just as
 * importantly, that a lone foreign "technician"/"electrician"/"painter" is still excluded —
 * the widening must not reopen the false-positive gate Stage 70 closed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage71RelevanceTest {

    private fun family(title: String, description: String = ""): RoleFamily =
        RoleClassifier.classify(title, description).family

    private fun kept(title: String, description: String = ""): Boolean =
        RoleClassifier.classify(title, description).isMaintenanceFamily

    // ---- genuine foreign trade titles are kept ------------------------------

    @Test
    fun germanTradeTitlesAreRecognised() {
        assertTrue(kept("Instandhaltungsmechaniker Rotating Equipment (w/m/d)", "Sulzer, Schkopau."))
        assertTrue(kept("Industriemechaniker Instandhaltung"))
        assertTrue(kept("Anlagenmechaniker SHK"))
        assertTrue(kept("Maschinenmechaniker"))
        assertTrue(kept("Schlosser Metallbau"))
        assertTrue(kept("Servicetechniker", "Service an Pumpen und Kompressoren."))
    }

    @Test
    fun dutchAndScandinavianTradeTitlesAreRecognised() {
        assertTrue(kept("Onderhoudstechnieker | 3 Ploegen", "Productiemachines onderhouden."))
        assertTrue(kept("Onderhoudstechnicus"))
        assertTrue(kept("Onderhoudselektricien", "Industrieel onderhoud."))
        assertTrue(kept("Mechanieker interventies"))
        assertTrue(kept("Vedlikeholdstekniker", "Vedlikehold på roterende utstyr."))
        assertTrue(kept("Mekaniker"))
    }

    // ---- the widening stays conservative ------------------------------------

    @Test
    fun aLoneForeignTechnicianOrElectricianIsStillExcluded() {
        // A weak foreign trade word with no equipment/industry context is not the trade, the
        // same way a lone English "technician" is not.
        assertFalse(kept("Monteur middenspanning", "Elektrische installaties."))
        assertFalse(kept("Elektricien spoorwegen Antwerpen", "Spoorweginfrastructuur."))
        assertFalse(kept("Technieker", "Algemene taken."))
    }

    @Test
    fun anUnrelatedForeignProfessionIsStillExcluded() {
        assertFalse(kept("Schilder", "Schilderwerk binnenshuis."))
        assertFalse(kept("Bakker", "Brood bakken."))
        assertFalse(kept("Calculator (staalbouw)", "Calculatie van staalconstructies."))
        assertFalse(kept("Magazijnier Fashion & Lifestyle", "Orderpicking."))
    }

    @Test
    fun theTradeTermsAreStillWholeWordMatches() {
        // "mechaniker" must not fire inside a longer word that merely contains it, and a
        // German word must not be matched loosely enough to catch an unrelated title.
        assertEquals(RoleFamily.UNRELATED, family("Robotiker"))
        assertEquals(RoleFamily.UNRELATED, family("Fotograf"))
    }

    @Test
    fun theTaxonomyCarriesTheForeignTradeWordsExactlyOnceEach() {
        val strong = TargetSearch.strongCoreTerms
        assertEquals(strong.size, strong.distinct().size)
        val weak = TargetSearch.weakCoreTerms
        assertEquals(weak.size, weak.distinct().size)
        // A word cannot be both strong and weak — that would make the verdict order-dependent.
        assertTrue(strong.intersect(weak.toSet()).isEmpty())
    }
}
