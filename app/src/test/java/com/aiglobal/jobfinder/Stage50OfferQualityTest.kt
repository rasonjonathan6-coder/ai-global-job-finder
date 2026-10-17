package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.apply.ApplicationUrlInspector
import com.aiglobal.jobfinder.data.expat.ExpatBenefitExtractor
import com.aiglobal.jobfinder.data.geo.CurrencyConverter
import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityInput
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.engine.OfferVerificationEngine
import com.aiglobal.jobfinder.data.jobs.engine.RecordRejectionReason
import com.aiglobal.jobfinder.data.jobs.engine.RecordValidationEngine
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.jobs.search.RoleClassifier
import com.aiglobal.jobfinder.data.jobs.search.RoleFamily
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.JobBenefitTypes
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.OfferVerification
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.VerificationAspect
import com.aiglobal.jobfinder.data.model.VerificationState
import com.aiglobal.jobfinder.data.model.VisaSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 50 — offer quality: the rules that stop a dead or unknown posting being shown as a
 * live, applicable one, and that keep a missing fact from being read as a refusal.
 *
 * Everything here runs against the real engines the app ships, on the JVM, with no network
 * and no application ever sent. Each case names the exact honesty rule it pins, because the
 * value of these tests is that they fail the moment a rule is quietly weakened.
 */
class Stage50OfferQualityTest {

    private val engine = RecordValidationEngine()
    private val mapper = JobMapper(
        matchingEngine = MatchingEngine(),
        eligibilityEngine = EligibilityEngine(),
        benefitExtractor = ExpatBenefitExtractor(),
    )
    private val now = 1_700_000_000_000L

    // ---- Expiration -----------------------------------------------------------

    @Test
    fun aStatedDeadlineInThePastIsRejectedAsExpired() {
        val expired = raw(id = "expired", expiresAt = now - 86_400_000L)
        val validation = engine.validate(expired, now)

        assertFalse("an expired posting is not kept", validation.accepted)
        assertTrue(
            "the reason names the deadline, not the age",
            RecordRejectionReason.EXPIRED in validation.reasons,
        )
    }

    @Test
    fun aStatedDeadlineInTheFutureIsStillAccepted() {
        val open = raw(id = "open", expiresAt = now + 86_400_000L)
        val validation = engine.validate(open, now)

        assertTrue("a future deadline is not an expiry", validation.accepted)
        assertFalse(RecordRejectionReason.EXPIRED in validation.reasons)
    }

    @Test
    fun aPostingWithNoStatedDeadlineIsNeverRejectedForExpiry() {
        // Unknown is not expired. A source that states no deadline must never be dropped for
        // an expiry it never claimed.
        val validation = engine.validate(raw(id = "no-deadline", expiresAt = null), now)

        assertTrue(validation.accepted)
        assertFalse(RecordRejectionReason.EXPIRED in validation.reasons)
    }

    // ---- Application link -----------------------------------------------------

    @Test
    fun aBrokenApplicationLinkIsRejected() {
        val validation = engine.validate(raw(id = "bad", url = "not a url"), now)

        assertFalse(validation.accepted)
        assertTrue(RecordRejectionReason.INVALID_URL in validation.reasons)
    }

    @Test
    fun anInspectorRefusesToOpenAnUnusableLinkButOpensARealOne() {
        val inspector = ApplicationUrlInspector()
        val broken = inspector.inspect("javascript:alert(1)")
        val real = inspector.inspect("https://careers.weir.co.uk/job/123", sourceName = "Weir")

        assertFalse("a non-http link is not openable", broken.canOpen)
        assertTrue("a real employer link is openable", real.canOpen)
    }

    // ---- Explicit geographic restriction --------------------------------------

    @Test
    fun anExplicitLocalOnlyRestrictionIsReadAsRestrictedNotUnknown() {
        // The engine must read the employer's own restriction as a refusal, not as silence.
        val profile = CandidateProfile(currentTitle = "Maintenance Technician", skills = listOf("pumps"))
        val job = mapper.toJob(
            raw(
                id = "local",
                location = "Dublin, Ireland",
                description = "This role is open to local candidates only. You must be authorized to work in Ireland.",
            ),
            profile,
        )

        assertEquals(MadagascarEligibility.RESTRICTED, job.eligibility)
    }

    // ---- Unknown is not a refusal ---------------------------------------------

    @Test
    fun anUnknownVisaRouteStaysUnknownAndIsNotReportedAsRefused() {
        // A posting that says nothing about sponsorship must read UNKNOWN, never NOT_OFFERED.
        val findings = ExpatBenefitExtractor().extract(
            title = "Maintenance Technician",
            description = "Maintain pumps and rotating equipment on a mine site.",
        )
        val visa = findings.first { it.type.key == JobBenefitTypes.VISA_SPONSORSHIP.key }

        assertEquals(BenefitStatus.UNKNOWN, visa.status)
        assertFalse("unknown is not a documented refusal", visa.isDocumented)
    }

    @Test
    fun anUnconfirmedHousingMentionIsNotTreatedAsConfirmed() {
        // "may provide accommodation" is a hint, not a commitment: it must not be CONFIRMED.
        val findings = ExpatBenefitExtractor().extract(
            title = "Maintenance Technician",
            description = "The company may provide accommodation support depending on the site.",
        )
        val housing = findings.first { it.type.key == JobBenefitTypes.ACCOMMODATION.key }

        assertNotEquals("a hedged mention must not be confirmed", BenefitStatus.CONFIRMED, housing.status)
        assertTrue(
            "a hedged mention is either unverified or absent, never a firm offer",
            housing.status == BenefitStatus.NEEDS_VERIFICATION || housing.status == BenefitStatus.UNKNOWN,
        )
    }

    // ---- Duplicates -----------------------------------------------------------

    @Test
    fun theSameRoleFromTwoSourcesIsCollapsedToOne() {
        val a = raw(id = "a", providerId = "adzuna", company = "Northline Industrial", title = "Maintenance Technician")
        val b = raw(id = "b", providerId = "jooble", company = "northline industrial ", title = "maintenance technician")

        val result = engine.deduplicate(listOf(a, b), now)

        assertEquals("only one copy survives", 1, result.totalKept)
        assertEquals("the repeat is counted as a duplicate", 1, result.totalDuplicates)
    }

    // ---- Currencies -----------------------------------------------------------

    @Test
    fun aSalaryInAKnownCurrencyConvertsAndAnUnknownOneIsNotInvented() {
        assertTrue(CurrencyConverter.canConvert("ZAR"))
        val converted = CurrencyConverter.toEurPerYear(1_000_000, "ZAR", SalaryPeriod.YEAR)
        assertTrue("a known currency converts to a positive figure", converted != null && converted > 0)

        assertFalse(CurrencyConverter.canConvert("XYZ"))
        assertNull("an unknown currency is never guessed", CurrencyConverter.toEurPerYear(1_000_000, "XYZ", SalaryPeriod.YEAR))
    }

    // ---- Stale evidence provenance --------------------------------------------

    @Test
    fun benefitEvidenceCarriesItsSourceAndDateSoAStaleClaimCanBeDetected() {
        val evidence = ExpatBenefitExtractor().extract(
            title = "Maintenance Technician",
            description = "We offer visa sponsorship and a relocation package.",
            source = "Adzuna",
            verifiedAtEpochMillis = now,
        )
        val visa = evidence.first { it.type.key == JobBenefitTypes.VISA_SPONSORSHIP.key }

        assertEquals(BenefitStatus.CONFIRMED, visa.status)
        assertTrue("the finding records where it came from", visa.hasProvenance)
        assertEquals("Adzuna", visa.source)
        assertFalse("a just-verified claim is not stale", visa.isStale(now))

        val muchLater = now + 90L * 86_400_000L
        assertTrue("the same claim is stale two months later", visa.isStale(muchLater))
    }

    // ---- New benefit types: work permit and paid leave -------------------------

    @Test
    fun workPermitSupportIsReadSeparatelyFromVisaSponsorship() {
        val findings = ExpatBenefitExtractor().extract(
            title = "Maintenance Technician",
            description = "We obtain your work permit and assist with the right to work paperwork.",
        )
        val permit = findings.first { it.type.key == JobBenefitTypes.WORK_PERMIT.key }
        val visa = findings.first { it.type.key == JobBenefitTypes.VISA_SPONSORSHIP.key }

        assertEquals("the work permit is documented", BenefitStatus.CONFIRMED, permit.status)
        assertEquals("a work permit alone is not visa sponsorship", BenefitStatus.UNKNOWN, visa.status)
    }

    @Test
    fun paidLeaveIsRecognisedAsItsOwnBenefit() {
        val findings = ExpatBenefitExtractor().extract(
            title = "Maintenance Technician",
            description = "The package includes 30 days paid annual leave and a rotation allowance.",
        )
        val leave = findings.first { it.type.key == JobBenefitTypes.PAID_LEAVE.key }

        assertEquals(BenefitStatus.CONFIRMED, leave.status)
    }

    // ---- New role coverage (mechanical seals, on-site services) ---------------

    @Test
    fun mechanicalSealAndOnSiteServicesRolesAreRecognisedAsTargetTrade() {
        val seal = RoleClassifier.classify("Mechanical Seal Technician", "Fitting mechanical seals on pumps.")
        val site = RoleClassifier.classify("On-site Services Technician", "Field maintenance at customer plants.")

        assertTrue("a mechanical seal role is a target role", seal.isTargeted)
        assertTrue("an on-site services role is a target role", site.isTargeted)
        assertTrue(
            "a mechanical seal role is in the candidate's family",
            seal.family == RoleFamily.TARGET || seal.isMaintenanceFamily,
        )
    }

    // ---- Live verification read (Part 7) --------------------------------------

    @Test
    fun aStatedDeadlineThatHasPassedIsReadAsExpiredInTheVerification() {
        // The offer-quality read must carry the deadline as its own axis, so a dead posting is
        // visible as expired rather than merely "not recently retrieved".
        val profile = CandidateProfile(currentTitle = "Maintenance Technician", skills = listOf("pumps"))
        val job = mapper.toJob(
            raw(id = "expired", expiresAt = now - 86_400_000L),
            profile,
        )
        val verification = OfferVerificationEngine().assess(job, now)

        assertEquals(VerificationState.EXPIRED, verification.deadline)
        assertFalse("a passed deadline is not actionable", verification.isActionable)
        assertTrue(
            "the expired deadline is carried as a fact",
            verification.facts.any { it.aspect == VerificationAspect.APPLICATION_DEADLINE },
        )
    }

    @Test
    fun aPostingWithNoStatedDeadlineReadsDeadlineAsUnknownNotExpired() {
        // Silence about a deadline must never be upgraded to "expired": unknown is not a refusal.
        val profile = CandidateProfile(currentTitle = "Maintenance Technician", skills = listOf("pumps"))
        val job = mapper.toJob(raw(id = "no-deadline"), profile)
        val verification = OfferVerificationEngine().assess(job, now)

        assertEquals(VerificationState.UNKNOWN, verification.deadline)
        assertTrue("an unknown deadline does not by itself block applying", verification.isActionable)
    }

    @Test
    fun theDeadlineStateIsIndependentOfTheVacancyActivityState() {
        // Activity and deadline are separate questions; a source that says nothing about either
        // must leave both unknown rather than letting one stand in for the other.
        val profile = CandidateProfile(currentTitle = "Maintenance Technician", skills = listOf("pumps"))
        val job = mapper.toJob(raw(id = "silent"), profile)
        val verification = OfferVerificationEngine().assess(job, now)

        assertEquals(VerificationState.UNKNOWN, verification.activity)
        assertEquals(VerificationState.UNKNOWN, verification.deadline)
    }

    @Test
    fun anEmptyVerificationIsUnknownOnEveryAxisIncludingTheDeadline() {
        // The honest empty read must include the new axis, so nothing is left silently claimed.
        assertTrue(OfferVerification.UNKNOWN.deadline.isUnknown)
        assertTrue(OfferVerification.UNKNOWN.isEntirelyUnknown)
    }

    // ---- Stage 51: a stated refusal must not read as an offer -----------------

    @Test
    fun aStatedRefusalOfSponsorshipIsReadAsNotOfferedNotConfirmed() {
        // Found in a live probe: the posting "This position is not eligible for visa
        // sponsorship" was read as CONFIRMED sponsorship because "visa sponsorship" is a
        // strong phrase and the negation table did not cover "not eligible". Reading a
        // refusal as an offer tells the applicant the opposite of what the employer wrote.
        val visa = ExpatBenefitExtractor()
            .extract(
                title = "Senior Clinical Applications Associate",
                description = "This position is not eligible for visa sponsorship.",
            )
            .first { it.type.key == JobBenefitTypes.VISA_SPONSORSHIP.key }

        assertEquals(BenefitStatus.NOT_OFFERED, visa.status)
    }

    @Test
    fun aRefusedSponsorshipMakesThePostingRestrictedNotSponsored() {
        // The eligibility engine must reach the same conclusion: an employer who says it
        // cannot sponsor has stated a hard restriction, so the posting is RESTRICTED — not
        // VISA_SPONSORED, which would invite a Madagascar applicant to apply.
        val assessment = EligibilityEngine().assess(
            EligibilityInput(
                title = "Maintenance Technician",
                location = "United States",
                description = "This position is not eligible for visa sponsorship.",
            ),
        )

        assertEquals(MadagascarEligibility.RESTRICTED, assessment.eligibility)
        assertEquals(VisaSupport.LOCAL_ONLY, assessment.visaSupport)
    }

    @Test
    fun aGenuineSponsorshipStatementStillReadsAsConfirmed() {
        // Guard against over-negation: a real offer must still be read as an offer.
        val visa = ExpatBenefitExtractor()
            .extract(
                title = "Mechanical Fitter",
                description = "We provide visa sponsorship and a relocation package for this role.",
            )
            .first { it.type.key == JobBenefitTypes.VISA_SPONSORSHIP.key }

        assertEquals(BenefitStatus.CONFIRMED, visa.status)
    }

    // ---- Helpers --------------------------------------------------------------

    private fun raw(
        id: String,
        providerId: String = "test",
        company: String = "Weir Group",
        title: String = "Maintenance Technician",
        location: String = "Port Elizabeth, South Africa",
        description: String = "<p>Maintain pumps, mechanical seals and rotating equipment on site.</p>",
        url: String = "https://example.com/$id",
        expiresAt: Long? = null,
    ) = RawJob(
        providerId = providerId,
        providerName = "Test",
        externalId = id,
        title = title,
        company = company,
        location = location,
        descriptionHtml = description,
        url = url,
        postedAtEpochMillis = now - 86_400_000L,
        expiresAtEpochMillis = expiresAt,
    )
}
