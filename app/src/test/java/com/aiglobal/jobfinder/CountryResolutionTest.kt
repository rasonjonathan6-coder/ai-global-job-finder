package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.geo.CountryCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 76 — country resolution correctness.
 *
 * The audit found [CountryCatalog.resolve] silently mislabelling whole countries because it
 * picks the longest alias found as a whole word from a flat table: a compound name that
 * contains a shorter country name lost to that shorter name, and two over-broad aliases
 * ("america", "korea") captured everything they were a substring of. These tests pin the
 * exact cases the audit reproduced, so the fix cannot silently regress.
 *
 * Pure JVM: [CountryCatalog] is a stateless object with no Android dependency.
 */
class CountryResolutionTest {

    private fun name(location: String) = CountryCatalog.resolve(location).name

    // ---- The three Guineas stay distinct -----------------------------------

    @Test
    fun `a lone Guinea resolves to Guinea`() {
        assertEquals("Guinea", name("Guinea"))
    }

    @Test
    fun `Guinea-Bissau is not merged into Guinea`() {
        assertEquals("Guinea-Bissau", name("Guinea-Bissau"))
        assertEquals("Guinea-Bissau", name("Guinea Bissau"))
        assertEquals("Guinea-Bissau", name("Bissau, Guinea-Bissau"))
    }

    @Test
    fun `Equatorial Guinea is not merged into Guinea`() {
        assertEquals("Equatorial Guinea", name("Equatorial Guinea"))
        assertEquals("Equatorial Guinea", name("Malabo, Equatorial Guinea"))
    }

    @Test
    fun `Papua New Guinea is not merged into Guinea`() {
        assertEquals("Papua New Guinea", name("Papua New Guinea"))
    }

    @Test
    fun `a city inside Guinea still resolves to Guinea`() {
        assertEquals("Guinea", name("Conakry, Guinea"))
    }

    // ---- The two Congos stay distinct --------------------------------------

    @Test
    fun `the Republic of the Congo is not merged into the DRC`() {
        assertEquals("Republic of the Congo", name("Republic of the Congo"))
        assertEquals("Republic of the Congo", name("Republic of Congo"))
        assertEquals("Republic of the Congo", name("Congo-Brazzaville"))
        assertEquals("Republic of the Congo", name("Congo Brazzaville"))
        assertEquals("Republic of the Congo", name("Brazzaville"))
    }

    @Test
    fun `the Democratic Republic of the Congo keeps its own name`() {
        assertEquals("Democratic Republic of the Congo", name("Democratic Republic of the Congo"))
        assertEquals("Democratic Republic of the Congo", name("Democratic Republic of Congo"))
        assertEquals("Democratic Republic of the Congo", name("DR Congo"))
        assertEquals("Democratic Republic of the Congo", name("DRC"))
        assertEquals("Democratic Republic of the Congo", name("Kinshasa"))
    }

    // ---- North / South Korea -----------------------------------------------

    @Test
    fun `North Korea and South Korea resolve to their own countries`() {
        assertEquals("North Korea", name("North Korea"))
        assertEquals("South Korea", name("South Korea"))
    }

    @Test
    fun `a bare Korea is left unresolved rather than guessed`() {
        // The catalog must not pick a side: a qualifier-less "Korea" is not a country name it
        // can settle, so it returns the raw label with the unresolved marker, never South Korea.
        val korea = CountryCatalog.resolve("Korea")
        assertEquals("Korea", korea.name)
        assertEquals(CountryCatalog.UNRESOLVED_FLAG, korea.flag)
        assertNotEquals("South Korea", korea.name)
        assertNotEquals("North Korea", korea.name)
        assertFalse(korea.isRecognized)
    }

    // ---- The Americas are not the United States ----------------------------

    @Test
    fun `the American regions are not labelled the United States`() {
        listOf("South America", "Central America", "Latin America", "North America").forEach { region ->
            val country = CountryCatalog.resolve(region)
            assertNotEquals("the region \"$region\" must not be labelled the United States", "United States", country.name)
            assertFalse("\"$region\" is a region, not a country", country.isRecognized)
        }
    }

    @Test
    fun `the United States still resolves from its own names`() {
        assertEquals("United States", name("United States"))
        assertEquals("United States", name("United States of America"))
        assertEquals("United States", name("USA"))
    }

    // ---- Existing correct behaviour is preserved ---------------------------

    @Test
    fun `South Africa is not confused with Africa`() {
        assertEquals("South Africa", name("South Africa"))
        assertTrue(CountryCatalog.resolve("South Africa").isRecognized)
    }

    @Test
    fun `ordinary countries, cities and worldwide listings are unchanged`() {
        assertEquals("Canada", name("Toronto, Canada"))
        assertEquals("Germany", name("Berlin, Germany"))
        assertEquals(CountryCatalog.WORLDWIDE_FLAG, CountryCatalog.resolve("Worldwide").flag)
        assertEquals(CountryCatalog.WORLDWIDE_FLAG, CountryCatalog.resolve("Anywhere in the World").flag)
        assertEquals(CountryCatalog.UNRESOLVED_FLAG, CountryCatalog.resolve("Munich").flag)
        assertEquals(CountryCatalog.UNRESOLVED_FLAG, CountryCatalog.resolve("Europe").flag)
    }
}
