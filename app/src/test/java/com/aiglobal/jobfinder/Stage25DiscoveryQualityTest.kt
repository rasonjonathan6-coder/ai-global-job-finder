package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.expat.ExpatBenefitExtractor
import com.aiglobal.jobfinder.data.jobs.search.RoleClassifier
import com.aiglobal.jobfinder.data.jobs.search.RoleFamily
import com.aiglobal.jobfinder.data.jobs.search.SearchCoverage
import com.aiglobal.jobfinder.data.jobs.search.SearchCoverageSource
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.JobBenefitTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 25 behaviour: technical job discovery quality and expat benefit evidence.
 *
 * Two live-audit findings drove this stage, both measured against 277 real postings pulled
 * from the app's own providers (Greenhouse, Ashby, Lever, Workday):
 *
 *  1. The relevance classifier let 21 non-trade roles through the "maintenance family" gate
 *     ("Process Technician", "EHS Technician", "Quality Technician", "Controls Technician")
 *     purely because the employer's blurb — read as the description head — said the company
 *     was "manufacturing" or "industrial". A generic "technician" title is not the
 *     candidate's trade, and an employer calling itself industrial is not evidence that it
 *     is. The support words are now split: concrete equipment and named plant/mining settings
 *     count from the title or the description, but the broad sector words count only from
 *     the title.
 *  2. The expat benefit extractor missed relocation/visa/health/transport wording that real
 *     European and FIFO postings actually use ("relocation assistance is available",
 *     "visa sponsorship provided", "medical aid", "company vehicle", "camp accommodation"),
 *     so a posting that plainly documented a package scored as if it documented nothing.
 *
 * Everything here is a pure function of the posting text, so it runs on the JVM.
 */
class Stage25DiscoveryQualityTest {

    private val extractor = ExpatBenefitExtractor()

    private fun byKey(text: String, title: String = "Maintenance Technician") =
        extractor.extract(title = title, description = text).associateBy { it.type.key }

    // ----------------------------------------------------- discovery quality

    @Test
    fun aGenericTechnicianTitleAtAnIndustrialEmployerIsNotClaimedAsMaintenance() {
        // The live false positive: a Form Energy posting whose *blurb* calls the company an
        // American manufacturing and energy technology company. The title names no trade.
        val relevance = RoleClassifier.classify(
            title = "Process Technician",
            descriptionHead = "Form Energy is an American manufacturing and energy technology company. " +
                "We are revolutionizing energy storage with cost-effective technology.",
        )
        assertEquals(RoleFamily.UNRELATED, relevance.family)
        assertFalse(relevance.isMaintenanceFamily)
    }

    @Test
    fun severalLiveGenericTechnicianTitlesAreRejectedDespiteIndustrialBlurbs() {
        val industrialBlurb = "We are an industrial manufacturing company building battery cells " +
            "in a state-of-the-art production facility."
        listOf(
            "EHS Technician",
            "Quality Technician",
            "Controls Technician",
            "Laboratory Technician",
            "Metrology Calibration Technician",
        ).forEach { title ->
            assertFalse(
                "expected \"$title\" not to be claimed as a trade role",
                RoleClassifier.classify(title, industrialBlurb).isMaintenanceFamily,
            )
        }
    }

    @Test
    fun aNamedPlantSettingInTheTitleStillSupportsAWeakTradeTitle() {
        // "Production Technician" names the shop floor in the title itself, which is the
        // employer saying the role is on the line — that must still count.
        val relevance = RoleClassifier.classify("Production Technician", "Operate and monitor packaging lines.")
        assertEquals(RoleFamily.MAINTENANCE_FAMILY, relevance.family)
        assertTrue(relevance.isMaintenanceFamily)
    }

    @Test
    fun aConcreteEquipmentWordInTheDescriptionStillSupportsAWeakTradeTitle() {
        val relevance = RoleClassifier.classify(
            title = "Site Technician",
            descriptionHead = "You will maintain pumps, conveyors and gearboxes across the plant.",
        )
        assertEquals(RoleFamily.MAINTENANCE_FAMILY, relevance.family)
    }

    @Test
    fun aSpecificProcessingSettingInTheDescriptionSupportsAWeakTradeTitle() {
        // "refinery"/"smelter" are specific enough to appear in the description; a mining
        // site technician is a real trade role the candidate would apply for.
        val relevance = RoleClassifier.classify(
            title = "Site Technician",
            descriptionHead = "Work at our nickel refinery and smelter on a rotation.",
        )
        assertEquals(RoleFamily.MAINTENANCE_FAMILY, relevance.family)
    }

    @Test
    fun miningEquipmentWordsInTheTitleAreRecognised() {
        listOf("Drill Rig Technician", "Excavator Technician", "Haul Truck Technician").forEach { title ->
            assertTrue(
                "expected \"$title\" to be a maintenance/trade role",
                RoleClassifier.classify(title).isMaintenanceFamily,
            )
        }
    }

    @Test
    fun genuineTradeTitlesAreStillMaintenanceFamily() {
        listOf(
            "Maintenance Technician",
            "Millwright",
            "Mechanical Fitter",
            "Rotating Equipment Technician",
            "Industrial Mechanic",
            "Diesel Mechanic",
        ).forEach { title ->
            assertTrue(
                "expected \"$title\" to remain a target/maintenance role",
                RoleClassifier.classify(title).isMaintenanceFamily,
            )
        }
    }

    @Test
    fun aBroadSectorWordInTheDescriptionDoesNotRescueAnUnrelatedTechnician() {
        // Guards the exact regression: "manufacturing"/"industrial" must not be readable
        // out of the description to upgrade a lone technician title.
        val relevance = RoleClassifier.classify(
            title = "IT Support Technician",
            descriptionHead = "Join our industrial manufacturing team supporting the factory floor.",
        )
        assertEquals(RoleFamily.UNRELATED, relevance.family)
    }

    // ------------------------------------------------- expat benefit evidence

    @Test
    fun europeanRelocationAndVisaWordingIsDocumented() {
        val findings = byKey(
            "Relocation assistance is available and visa sponsorship provided for the right candidate. " +
                "We also provide immigration support for your family.",
        )
        assertEquals(BenefitStatus.CONFIRMED, findings.getValue(JobBenefitTypes.RELOCATION_SUPPORT.key).status)
        assertEquals(BenefitStatus.CONFIRMED, findings.getValue(JobBenefitTypes.VISA_SPONSORSHIP.key).status)
    }

    @Test
    fun europeanHealthCoverWordingIsDocumented() {
        val findings = byKey(
            "We offer private health insurance and a medical aid scheme for you and your dependants.",
        )
        assertEquals(BenefitStatus.CONFIRMED, findings.getValue(JobBenefitTypes.HEALTH_INSURANCE.key).status)
    }

    @Test
    fun companyVehicleAndSiteShuttleAreDocumentedAsTransport() {
        val findings = byKey(
            "You will keep your company vehicle clean and use the site shuttle between the camp and the plant.",
        )
        assertEquals(BenefitStatus.CONFIRMED, findings.getValue(JobBenefitTypes.TRANSPORT.key).status)
    }

    @Test
    fun fifoCampAndFlightsWordingIsDocumented() {
        val findings = byKey(
            "Camp accommodation is provided and annual return flights are paid on a fly-in fly-out roster.",
        )
        assertEquals(BenefitStatus.CONFIRMED, findings.getValue(JobBenefitTypes.ACCOMMODATION.key).status)
        assertEquals(BenefitStatus.CONFIRMED, findings.getValue(JobBenefitTypes.FLIGHTS_HOME.key).status)
    }

    @Test
    fun anExplicitRefusalToSponsorIsRecordedAsNotOffered() {
        // The refusal phrasing real employers use must beat the strong phrase inside it.
        val findings = byKey("We are unable to offer sponsorship for this position.")
        assertEquals(BenefitStatus.NOT_OFFERED, findings.getValue(JobBenefitTypes.VISA_SPONSORSHIP.key).status)
    }

    @Test
    fun incidentalProseStillDoesNotInventBenefits() {
        // The new phrases must not open the door to false positives: a company describing
        // itself, or ordinary operational prose, is not a documented benefit.
        val findings = byKey(
            "Our family-owned business runs an industrial manufacturing plant around the clock. " +
                "The team coordinates transportation of raw materials and reports on production output.",
        )
        JobBenefitTypes.all.forEach { type ->
            assertFalse(
                "'${type.key}' must stay unknown when the posting only uses ordinary prose, " +
                    "but evidence was '${findings.getValue(type.key).evidence}'",
                findings.getValue(type.key).isDocumented,
            )
        }
    }

    // ------------------------------------------------------- coverage honesty

    @Test
    fun aBroadSampleSummaryStatesHowMuchOfTheFeedMatchedTheTrade() {
        val coverage = SearchCoverage(
            sources = listOf(
                SearchCoverageSource("remotive", "Remotive", targeted = false, relevantCount = 2, totalCount = 40),
            ),
            totalRelevant = 2,
            totalRetrieved = 40,
        )
        assertTrue(coverage.summary.startsWith("Broad sample"))
        assertTrue("summary must state the trade match count", coverage.summary.contains("2 of 40"))
        assertTrue("summary must state the share", coverage.summary.contains("5%"))
        assertEquals(5, coverage.relevantSharePercent)
    }

    @Test
    fun aTargetedSearchSummaryIsStillDescribedAsTargeted() {
        val coverage = SearchCoverage(
            sources = listOf(
                SearchCoverageSource("adzuna", "Adzuna", targeted = true, relevantCount = 6, totalCount = 12),
            ),
            totalRelevant = 6,
            totalRetrieved = 12,
        )
        assertTrue(coverage.summary.startsWith("Targeted search"))
    }
}
