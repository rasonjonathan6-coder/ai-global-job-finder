package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.geo.CountryCatalog
import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 23 — the candidate journey for a job seeker applying from Madagascar.
 *
 * The live probe (Stage 14, run with `LIVE_PROBE=1`) surfaced a display defect that a
 * fixture never would: Arbeitnow publishes a bare city as the location ("Munich",
 * "Kulmbach"), which the catalog cannot map to a country. Those postings were labelled
 * with the worldwide globe and passed the worldwide remote rules, so a **local German
 * vacancy looked like a role open to applicants from anywhere** and joined the country
 * filter as its own "country". These tests pin the corrected behaviour: a location the
 * catalog cannot resolve is shown as a place to verify, never as worldwide, while a
 * genuine "Worldwide" / "Anywhere" listing keeps the globe and stays eligible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage23CandidateJourneyTest {

    private val mapper = JobMapper(
        matchingEngine = MatchingEngine(),
        eligibilityEngine = EligibilityEngine(),
        clock = { 1_760_000_000_000 },
    )

    private val profile = CandidateProfile(
        fullName = "Jonathan Rakoto",
        currentTitle = "Maintenance Technician",
        skills = listOf("preventive maintenance", "hydraulics", "pumps"),
    )

    private fun job(location: String, isRemote: Boolean = false, description: String = "") =
        mapper.toJob(
            RawJob(
                providerId = "arbeitnow",
                providerName = "Arbeitnow",
                externalId = "s23-${location.hashCode()}-$isRemote",
                title = "Maintenance Technician",
                company = "Example GmbH",
                location = location,
                descriptionHtml = "<p>${description.ifBlank { "Industrial maintenance role." }}</p>",
                url = "https://example.test/job",
                isRemote = isRemote,
            ),
            profile,
        )

    @Test
    fun aBareCityIsNotGivenTheWorldwideGlobeFlag() {
        val city = CountryCatalog.resolve("Munich")
        assertEquals("Munich", city.name)
        assertEquals(CountryCatalog.UNRESOLVED_FLAG, city.flag)
        assertNotEquals(CountryCatalog.WORLDWIDE_FLAG, city.flag)
    }

    @Test
    fun aRegionIsNotGivenTheWorldwideGlobeFlag() {
        val region = CountryCatalog.resolve("Europe")
        assertEquals(CountryCatalog.UNRESOLVED_FLAG, region.flag)
        assertFalse(region.isWorldwide)
    }

    @Test
    fun aGenuineWorldwideListingKeepsTheGlobe() {
        assertEquals(CountryCatalog.WORLDWIDE_FLAG, CountryCatalog.resolve("Worldwide").flag)
        assertEquals(CountryCatalog.WORLDWIDE_FLAG, CountryCatalog.resolve("Anywhere in the World").flag)
    }

    @Test
    fun aLocalCityVacancyIsNotScoredAsAWorldwideRemoteRole() {
        // The whole point: a Kulmbach on-site role must not inherit the worldwide-remote
        // eligibility bonus just because the catalog could not name its country.
        val local = job("Kulmbach", isRemote = false)
        assertFalse(local.countryFlag == CountryCatalog.WORLDWIDE_FLAG)
        assertNotEquals(MadagascarEligibility.ELIGIBLE, local.eligibility)
    }

    @Test
    fun aRemoteListingPinnedToAnUnmappedCityStaysUnclearNotEligible() {
        val remoteCity = job("Munich", isRemote = true, description = "Fully remote.")
        assertEquals(MadagascarEligibility.UNCLEAR, remoteCity.eligibility)
        assertEquals(CountryCatalog.UNRESOLVED_FLAG, remoteCity.countryFlag)
    }

    @Test
    fun aWorldwideRemoteRoleStaysEligibleForAMadagascarApplicant() {
        val worldwide = job("Worldwide", isRemote = true, description = "Work from anywhere.")
        assertEquals(MadagascarEligibility.ELIGIBLE, worldwide.eligibility)
        assertEquals(CountryCatalog.WORLDWIDE_FLAG, worldwide.countryFlag)
        assertTrue(worldwide.applyableFromMadagascar)
    }

    @Test
    fun aRecognisedCountryStillResolvesToItsOwnFlagAndName() {
        val canada = CountryCatalog.resolve("Toronto, Canada")
        assertEquals("Canada", canada.name)
        assertEquals("🇨🇦", canada.flag)
    }
}
