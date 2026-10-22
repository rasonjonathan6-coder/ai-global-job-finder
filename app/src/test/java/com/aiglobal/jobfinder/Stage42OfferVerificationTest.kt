package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.expat.ExpatBenefitExtractor
import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.engine.OfferVerificationEngine
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.jobs.search.RoleFamily
import com.aiglobal.jobfinder.data.model.BenefitEvidence
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.EvidenceStrength
import com.aiglobal.jobfinder.data.model.InternationalFitAssessment
import com.aiglobal.jobfinder.data.model.InternationalFitBand
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobBenefitTypes
import com.aiglobal.jobfinder.data.model.JobRequirements
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.OfferVerification
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.VerificationAspect
import com.aiglobal.jobfinder.data.model.VerificationState
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import com.aiglobal.jobfinder.viewmodel.JobSort
import com.aiglobal.jobfinder.viewmodel.JobsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 42 — a verifiable "is this offer still real and reachable?" read per posting.
 *
 * The app already keeps four numbers apart: the CV match, the Madagascar eligibility verdict,
 * the documented Expat Benefits Score and the international fit. This stage adds a fifth read
 * that answers a different question the candidate must settle before spending effort: **is the
 * listing still open, is the link usable, is there a documented visa route, and how recent is
 * the check?** The tests below prove it is honest:
 *
 *  1. A successful source flag is the only thing that confirms an open vacancy; silence is
 *     `UNKNOWN`, never "closed".
 *  2. "The employer says it does not sponsor" is `NOT_OFFERED`, a different fact from "the
 *     posting does not mention sponsorship" (`UNKNOWN`).
 *  3. A known dead end (closed, or an unusable link) is never allowed to outrank a live
 *     posting in a "best fit" sort, while an unknown stays neutral.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage42OfferVerificationTest {

    private companion object {
        const val DAY = 86_400_000L
        const val NOW = 1_760_000_000_000L
    }

    private val engine = OfferVerificationEngine()
    private val now = 1_700_000_000_000L
    private val dayMs = 86_400_000L

    private fun visaEvidence(status: BenefitStatus) = BenefitEvidence(
        type = JobBenefitTypes.VISA_SPONSORSHIP,
        status = status,
        evidence = "Visa sponsorship provided.",
    )

    private fun relocationEvidence(status: BenefitStatus) = BenefitEvidence(
        type = JobBenefitTypes.RELOCATION_SUPPORT,
        status = status,
        evidence = "Relocation package included.",
    )

    private fun job(
        id: String = "j1",
        openToApplicants: Boolean? = null,
        eligibility: MadagascarEligibility = MadagascarEligibility.UNCLEAR,
        visaSupport: VisaSupport = VisaSupport.NOT_MENTIONED,
        sourceUrl: String = "https://www.weir.com/careers/j1",
        source: String = "Workday",
        benefitEvidence: List<BenefitEvidence> = emptyList(),
        retrievedAtEpochMillis: Long? = now,
    ) = Job(
        id = id,
        title = "Maintenance Technician",
        company = "Weir Group",
        companyAccent = 0xFF1E4FD8,
        country = "Canada",
        countryFlag = "🇨🇦",
        city = "Calgary",
        salaryMin = 60_000,
        salaryMax = 60_000,
        currencyCode = "CAD",
        salaryPeriod = SalaryPeriod.YEAR,
        matchScore = 70,
        eligibility = eligibility,
        visaSupport = visaSupport,
        scamRisk = ScamRisk.LOW,
        postedAtEpochMillis = NOW - 5 * DAY,
        retrievedAtEpochMillis = retrievedAtEpochMillis,
        source = source,
        sourceUrl = sourceUrl,
        workMode = WorkMode.ONSITE,
        employmentType = EmploymentType.FULL_TIME,
        experienceYears = 3,
        matchedSkills = listOf("Preventive maintenance"),
        missingSkills = emptyList(),
        languages = listOf("English"),
        summary = "Summary",
        responsibilities = emptyList(),
        benefits = emptyList(),
        aiInsight = "insight",
        providerId = "workday",
        roleFamily = RoleFamily.TARGET,
        requirements = JobRequirements(minYears = 3),
        benefitEvidence = benefitEvidence,
        openToApplicants = openToApplicants,
    ).let { base ->
        // Attach the same verification read the live pipeline attaches, so the UI-state
        // tests exercise the real filter and sort paths rather than an empty default.
        base.copy(verification = engine.assess(base, now))
    }

    // ---- Vacancy activity: only the source's own flag confirms it --------------

    @Test
    fun sourceFlagConfirmsOpenAndAbsenceStaysUnknown() {
        val open = engine.assess(job(openToApplicants = true), now)
        val closed = engine.assess(job(openToApplicants = false), now)
        val unstated = engine.assess(job(openToApplicants = null), now)

        assertEquals(VerificationState.CONFIRMED, open.activity)
        assertEquals(VerificationState.EXPIRED, closed.activity)
        // No flag is not proof of closure: the honest answer is "unknown".
        assertEquals(VerificationState.UNKNOWN, unstated.activity)
    }

    // ---- Link: usable, or positively unusable ---------------------------------

    @Test
    fun linkIsConfirmedForARealUrlAndInaccessibleForAnEmptyOne() {
        val good = engine.assess(job(sourceUrl = "https://www.weir.com/careers/j1"), now)
        val missing = engine.assess(job(sourceUrl = ""), now)

        assertEquals(VerificationState.CONFIRMED, good.link)
        assertEquals(VerificationState.INACCESSIBLE, missing.link)
        assertFalse(missing.isActionable)
        assertTrue(missing.caveats.any { it.contains("no usable application link", ignoreCase = true) })
    }

    @Test
    fun insecureLinkIsPossibleNotConfirmed() {
        val http = engine.assess(job(sourceUrl = "http://www.weir.com/careers/j1"), now)
        assertEquals(VerificationState.POSSIBLE, http.link)
    }

    // ---- Visa: documented route in, never assumed from silence ----------------

    @Test
    fun documentedVisaBecomesConfirmedAndSilenceStaysUnknown() {
        val documented = engine.assess(
            job(benefitEvidence = listOf(visaEvidence(BenefitStatus.CONFIRMED))),
            now,
        )
        val silent = engine.assess(job(eligibility = MadagascarEligibility.UNCLEAR), now)

        assertEquals(VerificationState.CONFIRMED, documented.visa)
        assertEquals(VerificationState.UNKNOWN, silent.visa)
    }

    @Test
    fun explicitRefusalIsNotOfferedNotUnknown() {
        val refused = engine.assess(
            job(eligibility = MadagascarEligibility.RESTRICTED),
            now,
        )
        val unstated = engine.assess(
            job(eligibility = MadagascarEligibility.UNCLEAR),
            now,
        )

        // "The employer states it does not sponsor" is a different fact from "not stated".
        assertEquals(VerificationState.NOT_OFFERED, refused.visa)
        assertEquals(VerificationState.UNKNOWN, unstated.visa)
        assertTrue(refused.caveats.any { it.contains("does not sponsor", ignoreCase = true) })
    }

    // ---- Relocation is its own axis, separate from the visa -------------------

    @Test
    fun relocationIsASeparateAxisFromTheVisa() {
        val relocationOnly = engine.assess(
            job(benefitEvidence = listOf(relocationEvidence(BenefitStatus.CONFIRMED))),
            now,
        )
        assertEquals(VerificationState.CONFIRMED, relocationOnly.relocation)
        // A documented relocation package says nothing about sponsorship.
        assertEquals(VerificationState.UNKNOWN, relocationOnly.visa)
    }

    // ---- Freshness: how much the last retrieval can be trusted -----------------

    @Test
    fun freshnessClassifiesRetrievalAge() {
        val fresh = engine.assess(job(retrievedAtEpochMillis = now), now)
        val aging = engine.assess(job(retrievedAtEpochMillis = now - 10 * dayMs), now)
        val stale = engine.assess(job(retrievedAtEpochMillis = now - 40 * dayMs), now)
        val never = engine.assess(job(retrievedAtEpochMillis = null), now)

        assertEquals(VerificationState.CONFIRMED, fresh.freshness)
        assertEquals(VerificationState.POSSIBLE, aging.freshness)
        // An aged-out check is not proof the offer is dead, only that we cannot rely on it.
        assertEquals(VerificationState.UNKNOWN, stale.freshness)
        assertEquals(VerificationState.UNKNOWN, never.freshness)
    }

    // ---- Actionability: dead ends vs neutral unknowns --------------------------

    @Test
    fun deadEndsAreNotActionableAndUnknownsStayNeutral() {
        val openUnknown = engine.assess(job(openToApplicants = null), now)
        val closed = engine.assess(job(openToApplicants = false), now)
        val brokenLink = engine.assess(job(sourceUrl = ""), now)

        assertTrue(openUnknown.isActionable)
        assertFalse(closed.isActionable)
        assertFalse(brokenLink.isActionable)
    }

    // ---- Every axis is carried as a fact, even when unknown --------------------

    @Test
    fun everyFindingIsCarriedAsAFactEvenWhenUnknown() {
        val result = engine.assess(job(), now)
        val aspects = result.facts.map { it.aspect }.toSet()
        assertEquals(
            setOf(
                VerificationAspect.SOURCE,
                VerificationAspect.VACANCY_ACTIVITY,
                VerificationAspect.APPLICATION_DEADLINE,
                VerificationAspect.APPLICATION_LINK,
                VerificationAspect.VISA,
                VerificationAspect.RELOCATION,
                VerificationAspect.FRESHNESS,
            ),
            aspects,
        )
        // The gap is visible, not dropped: an unknown fact is present with UNKNOWN strength.
        val visaFact = result.facts.first { it.aspect == VerificationAspect.VISA }
        assertEquals(EvidenceStrength.UNKNOWN, visaFact.strength)
        val deadlineFact = result.facts.first { it.aspect == VerificationAspect.APPLICATION_DEADLINE }
        assertEquals(EvidenceStrength.UNKNOWN, deadlineFact.strength)
    }

    @Test
    fun documentedVisaFactQuotesTheEmployerAndIsMarkedDocumented() {
        val result = engine.assess(
            job(benefitEvidence = listOf(visaEvidence(BenefitStatus.CONFIRMED))),
            now,
        )
        val visaFact = result.facts.first { it.aspect == VerificationAspect.VISA }
        assertEquals(EvidenceStrength.DOCUMENTED, visaFact.strength)
        assertEquals("Visa sponsorship provided.", visaFact.detail)
        assertTrue(visaFact.isDocumented)
    }

    // ---- Mapper wires the source flag through to the Job -----------------------

    @Test
    fun mapperAttachesVerificationFromTheSourcesOwnFlag() {
        val mapper = JobMapper(
            matchingEngine = MatchingEngine(),
            eligibilityEngine = EligibilityEngine(),
            benefitExtractor = ExpatBenefitExtractor(),
            clock = { now },
        )
        val raw = RawJob(
            providerId = "workday",
            providerName = "Workday",
            externalId = "1",
            title = "Maintenance Technician",
            company = "Weir Group",
            location = "Calgary, Canada",
            descriptionHtml = "Maintenance technician role.",
            url = "https://www.weir.com/careers/1",
            openToApplicants = true,
            retrievedAtEpochMillis = now,
        )
        val mapped = mapper.toJob(raw, CandidateProfile())
        assertEquals(VerificationState.CONFIRMED, mapped.verification.activity)
        assertEquals(VerificationState.CONFIRMED, mapped.verification.link)
    }

    // ---- UI state: filters and sort -------------------------------------------

    private fun fit(score: Int): InternationalFitAssessment = InternationalFitAssessment(
        score = score,
        band = InternationalFitBand.fromScore(score),
        components = emptyList(),
        blockers = emptyList(),
        routeSummary = "route",
        expatBenefitsScore = null,
        expatEvidenceCoverage = 0,
        expatLowEvidence = false,
        explanation = "explanation",
    )

    @Test
    fun deadEndsAreDemotedBelowLivePostingsInTheFitSort() {
        val live = job(id = "live", openToApplicants = true).copy(matchScore = 50)
        val dead = job(id = "dead", openToApplicants = false).copy(matchScore = 99)
        val state = JobsUiState(nowMillis = NOW, 
            jobs = listOf(dead, live),
            sort = JobSort.INTERNATIONAL_FIT,
            internationalFit = mapOf("dead" to fit(95), "live" to fit(40)),
        )

        // A known dead end never leads a "best fit" list, whatever its score.
        assertEquals(listOf("live", "dead"), state.visibleJobs.map { it.id })
    }

    @Test
    fun openVacancyFilterKeepsOnlyConfirmedOpenAndDoesNotAssumeUnstatedMeansClosed() {
        val open = job(id = "open", openToApplicants = true)
        val closed = job(id = "closed", openToApplicants = false)
        val unstated = job(id = "unstated", openToApplicants = null)
        val state = JobsUiState(nowMillis = NOW, 
            jobs = listOf(open, closed, unstated),
            openVacancyOnly = true,
        )

        assertEquals(listOf("open"), state.visibleJobs.map { it.id })
        assertTrue(state.hasActiveFilters)
    }

    @Test
    fun verifiedVisaFilterKeepsOnlyDocumentedSponsorship() {
        val documented = job(
            id = "documented",
            benefitEvidence = listOf(visaEvidence(BenefitStatus.CONFIRMED)),
        )
        val silent = job(id = "silent", benefitEvidence = emptyList())
        val state = JobsUiState(nowMillis = NOW, 
            jobs = listOf(documented, silent),
            verifiedVisaOnly = true,
        )

        assertEquals(listOf("documented"), state.visibleJobs.map { it.id })
    }

    @Test
    fun emptyVerificationIsTheHonestDefaultForAnUncheckedPosting() {
        assertEquals(VerificationState.UNKNOWN, OfferVerification.UNKNOWN.activity)
        assertEquals(VerificationState.UNKNOWN, OfferVerification.UNKNOWN.link)
        assertTrue(OfferVerification.UNKNOWN.isEntirelyUnknown)
        assertTrue(OfferVerification.UNKNOWN.isActionable)
    }
}
