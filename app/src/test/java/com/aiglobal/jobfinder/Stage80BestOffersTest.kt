package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.offer.OfferMatchEngine
import com.aiglobal.jobfinder.data.jobs.offer.OfferMatchRanking
import com.aiglobal.jobfinder.data.jobs.search.RoleFamily
import com.aiglobal.jobfinder.data.model.AvailabilityResult
import com.aiglobal.jobfinder.data.model.AvailabilityStatus
import com.aiglobal.jobfinder.data.model.BenefitEvidence
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.BestOfferFilter
import com.aiglobal.jobfinder.data.model.BestOfferSort
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.EvidenceStrength
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobBenefitTypes
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.OfferMatchBand
import com.aiglobal.jobfinder.data.model.OfferMatchDimension
import com.aiglobal.jobfinder.data.model.QualificationGap
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 80: "Mes meilleures offres" — the CV compatibility score and the ranking that
 * surfaces the best-fitting recent postings.
 *
 * The promises under test are the honest-scoring ones:
 *
 *  - the six weights sum to 100 and every point is traceable to a dimension;
 *  - a missing publication date earns no freshness points and is never called fresh;
 *  - a disqualifier (local-only, expired, passed deadline) caps the score and sinks the card;
 *  - "recent" means a verified date inside the window, never an undated posting;
 *  - a CV that names no skills scores the skills dimension neutrally, never at full marks;
 *  - the same vacancy from two sources is shown once.
 */
class Stage80BestOffersTest {

    private companion object {
        const val DAY = 86_400_000L
        const val NOW = 1_760_000_000_000L
    }

    private val engine = OfferMatchEngine()
    private val ranking = OfferMatchRanking()

    private fun profile(
        name: String = "Jonathan Rakoto",
        skills: List<String> = listOf(
            "Preventive maintenance",
            "Hydraulics",
            "Electrical diagnostics",
            "PLC",
            "Welding",
        ),
    ) = CandidateProfile(
        fullName = name,
        currentTitle = "Maintenance Technician",
        yearsOfExperience = 8,
        skills = skills,
        preferredRoles = listOf("Maintenance Technician"),
    )

    private fun job(
        id: String,
        title: String = "Maintenance Technician",
        company: String = "Acme",
        country: String = "Canada",
        postedAt: Long? = NOW - 1 * DAY,
        matchScore: Int = 80,
        eligibility: MadagascarEligibility = MadagascarEligibility.ELIGIBLE,
        roleFamily: RoleFamily = RoleFamily.TARGET,
        matchedSkills: List<String> = listOf("Preventive maintenance", "Hydraulics", "Electrical diagnostics"),
        missingSkills: List<String> = emptyList(),
        qualificationGaps: List<QualificationGap> = emptyList(),
        benefitEvidence: List<BenefitEvidence> = emptyList(),
        availability: AvailabilityResult = AvailabilityResult.UNKNOWN,
        expiresAtEpochMillis: Long? = null,
        sourceUrl: String = "https://example.com/$id",
    ) = Job(
        id = id,
        title = title,
        company = company,
        companyAccent = 0xFF1E4FD8,
        country = country,
        countryFlag = "🇨🇦",
        city = "Toronto",
        salaryMin = 60_000,
        salaryMax = 70_000,
        currencyCode = "CAD",
        salaryPeriod = SalaryPeriod.YEAR,
        matchScore = matchScore,
        eligibility = eligibility,
        visaSupport = VisaSupport.NOT_MENTIONED,
        scamRisk = ScamRisk.LOW,
        postedAtEpochMillis = postedAt,
        expiresAtEpochMillis = expiresAtEpochMillis,
        source = "Remotive",
        sourceUrl = sourceUrl,
        workMode = WorkMode.ONSITE,
        employmentType = EmploymentType.FULL_TIME,
        experienceYears = 5,
        matchedSkills = matchedSkills,
        missingSkills = missingSkills,
        languages = listOf("English"),
        summary = "Summary for $id",
        responsibilities = emptyList(),
        benefits = emptyList(),
        aiInsight = "insight",
        providerId = "remotive",
        roleFamily = roleFamily,
        matchedTargetRole = if (roleFamily == RoleFamily.TARGET) "Mechanical Maintenance Technician" else null,
        qualificationGaps = qualificationGaps,
        benefitEvidence = benefitEvidence,
        availability = availability,
    )

    private fun confirmedBenefit(key: String) = BenefitEvidence(
        type = JobBenefitTypes.all.first { it.key == key },
        status = BenefitStatus.CONFIRMED,
        evidence = "stated in the posting",
    )

    // ---------------------------------------------------------------- weights

    @Test
    fun theSixDimensionWeightsSumToOneHundred() {
        assertEquals(100, OfferMatchDimension.total)
        val weights = OfferMatchDimension.entries.map { it.weight }
        assertEquals(35, weights[0])
        assertEquals(25, weights[1])
        assertEquals(15, weights[2])
        assertEquals(10, weights[3])
        assertEquals(10, weights[4])
        assertEquals(5, weights[5])
    }

    @Test
    fun everyComponentCarriesItsOwnMaximumAndTheyMatchTheWeights() {
        val match = engine.assess(job("a"), profile(), NOW)
        assertEquals(OfferMatchDimension.entries.toSet(), match.components.map { it.dimension }.toSet())
        match.components.forEach { component ->
            assertEquals(component.dimension.weight, component.maxPoints)
            assertTrue(component.points in 0..component.maxPoints)
        }
    }

    // ---------------------------------------------------------------- eligibility

    @Test
    fun eligibilityFromMadagascarScoresHighestWhenOpen() {
        val open = engine.assess(job("open", eligibility = MadagascarEligibility.ELIGIBLE), profile(), NOW)
        val unclear = engine.assess(job("unclear", eligibility = MadagascarEligibility.UNCLEAR), profile(), NOW)
        assertTrue(open.score > unclear.score)
        assertEquals(25, open.components.first { it.dimension == OfferMatchDimension.MADAGASCAR_ELIGIBILITY }.points)
        assertFalse(open.disqualified)
    }

    @Test
    fun aLocalOnlyPostingIsCappedAndSunk() {
        val local = engine.assess(job("local", eligibility = MadagascarEligibility.RESTRICTED), profile(), NOW)
        assertTrue("a stated local-only restriction must disqualify", local.disqualified)
        assertTrue(local.hardStops.isNotEmpty())
        assertTrue(local.score <= OfferMatchEngine.DISQUALIFIED_CAP)
        assertEquals(OfferMatchBand.LOW, local.band)
    }

    @Test
    fun aHighScoringPostingWithAHardStopStillSinksBelowOneWithout() {
        val strong = job("strong", matchScore = 95)
        val blocked = job("blocked", matchScore = 95, eligibility = MadagascarEligibility.RESTRICTED)
        val ranked = ranking.rank(listOf(blocked, strong), profile(), NOW)
        assertEquals("strong", ranked.first().job.id)
        assertEquals("blocked", ranked.last().job.id)
        assertTrue(ranked.last().match.disqualified)
    }

    // ---------------------------------------------------------------- freshness

    @Test
    fun anUndatedPostingEarnsNoFreshnessPointsAndIsNeverCalledFresh() {
        val undated = engine.assess(job("undated", postedAt = null), profile(), NOW)
        assertFalse(undated.hasVerifiedDate)
        assertEquals(0, undated.components.first { it.dimension == OfferMatchDimension.VERIFIED_FRESHNESS }.points)
        assertTrue(undated.reasons.any { it.contains("did not state a publication date") })
    }

    @Test
    fun freshnessIsCreditedOnlyInsideTheWindow() {
        val fresh = engine.assess(job("fresh", postedAt = NOW - 1 * DAY), profile(), NOW)
        val old = engine.assess(job("old", postedAt = NOW - 40 * DAY), profile(), NOW)
        assertTrue(
            fresh.components.first { it.dimension == OfferMatchDimension.VERIFIED_FRESHNESS }.points >
                old.components.first { it.dimension == OfferMatchDimension.VERIFIED_FRESHNESS }.points,
        )
        assertEquals(0, old.components.first { it.dimension == OfferMatchDimension.VERIFIED_FRESHNESS }.points)
    }

    @Test
    fun aDateInTheFutureIsNotTreatedAsFresh() {
        val future = engine.assess(job("future", postedAt = NOW + 5 * DAY), profile(), NOW)
        assertFalse(future.hasVerifiedDate)
        assertEquals(0, future.components.first { it.dimension == OfferMatchDimension.VERIFIED_FRESHNESS }.points)
    }

    // ---------------------------------------------------------------- skills

    @Test
    fun aCvWithNoSkillsDoesNotEarnFullMarksForSkills() {
        val bare = engine.assess(job("bare"), CandidateProfile(), NOW)
        val component = bare.components.first { it.dimension == OfferMatchDimension.CV_SKILLS_AND_EXPERIENCE }
        assertTrue("an empty CV must not score full skills marks", component.points < component.maxPoints)
    }

    @Test
    fun matchingEveryNamedSkillScoresTheFullSkillsWeight() {
        val full = engine.assess(
            job(
                "full",
                matchedSkills = listOf("Preventive maintenance", "Hydraulics", "Electrical diagnostics", "PLC", "Welding"),
                missingSkills = emptyList(),
            ),
            profile(),
            NOW,
        )
        assertEquals(
            35,
            full.components.first { it.dimension == OfferMatchDimension.CV_SKILLS_AND_EXPERIENCE }.points,
        )
    }

    @Test
    fun unknownRequirementsAreNotScoredAsMisses() {
        val unknown = engine.assess(
            job("unknown-req", qualificationGaps = listOf(QualificationGap("5 years", "…", met = null))),
            profile(),
            NOW,
        )
        val missed = engine.assess(
            job("missed-req", qualificationGaps = listOf(QualificationGap("5 years", "…", met = false))),
            profile(),
            NOW,
        )
        assertTrue(
            "a requirement we cannot check must score above one we know is unmet",
            unknown.score > missed.score,
        )
    }

    // ---------------------------------------------------------------- benefits

    @Test
    fun onlyConfirmedBenefitsEarnPoints() {
        val none = engine.assess(job("no-benefit"), profile(), NOW)
        val some = engine.assess(
            job(
                "with-benefit",
                benefitEvidence = listOf(
                    confirmedBenefit(JobBenefitTypes.VISA_SPONSORSHIP.key),
                    confirmedBenefit(JobBenefitTypes.ACCOMMODATION.key),
                ),
            ),
            profile(),
            NOW,
        )
        assertEquals(0, none.components.first { it.dimension == OfferMatchDimension.CONFIRMED_EXPAT_BENEFITS }.points)
        assertTrue(
            some.components.first { it.dimension == OfferMatchDimension.CONFIRMED_EXPAT_BENEFITS }.points > 0,
        )
    }

    @Test
    fun mentionedButUnconfirmedBenefitsEarnNothing() {
        val mentioned = BenefitEvidence(
            type = JobBenefitTypes.VISA_SPONSORSHIP,
            status = BenefitStatus.NEEDS_VERIFICATION,
            evidence = "may sponsor",
        )
        val match = engine.assess(job("mentioned", benefitEvidence = listOf(mentioned)), profile(), NOW)
        assertEquals(0, match.components.first { it.dimension == OfferMatchDimension.CONFIRMED_EXPAT_BENEFITS }.points)
    }

    // ---------------------------------------------------------------- expired

    @Test
    fun anExpiredPostingIsDisqualified() {
        val expired = engine.assess(
            job("expired", availability = AvailabilityResult(status = AvailabilityStatus.EXPIRED)),
            profile(),
            NOW,
        )
        assertTrue(expired.disqualified)
        assertTrue(expired.hardStops.any { it.contains("expired") })
    }

    @Test
    fun aPassedDeadlineIsDisqualified() {
        val passed = engine.assess(job("passed", expiresAtEpochMillis = NOW - DAY), profile(), NOW)
        assertTrue(passed.disqualified)
        assertTrue(passed.hardStops.any { it.contains("deadline") })
    }

    // ---------------------------------------------------------------- ranking

    @Test
    fun rankingOrdersByScoreStrongestFirst() {
        val ranked = ranking.rank(
            listOf(
                job("low", matchScore = 40, matchedSkills = emptyList()),
                job("high", matchScore = 90),
            ),
            profile(),
            NOW,
        )
        assertTrue(ranked.first().score >= ranked.last().score)
        assertEquals(1, ranked.first().rank)
        assertEquals(2, ranked.last().rank)
    }

    @Test
    fun recentFilterKeepsOnlyVerifiedDatesInsideTheWindow() {
        val jobs = listOf(
            job("recent", postedAt = NOW - 3 * DAY),
            job("undated", postedAt = null),
            job("old", postedAt = NOW - 60 * DAY),
        )
        val ranked = ranking.rank(jobs, profile(), NOW, filter = BestOfferFilter.RECENT)
        assertEquals(listOf("recent"), ranked.map { it.job.id })
    }

    @Test
    fun madagascarFilterKeepsOnlyOpenOrSponsoredPostings() {
        val jobs = listOf(
            job("open", eligibility = MadagascarEligibility.ELIGIBLE),
            job("sponsored", eligibility = MadagascarEligibility.VISA_SPONSORED),
            job("unclear", eligibility = MadagascarEligibility.UNCLEAR),
            job("local", eligibility = MadagascarEligibility.RESTRICTED),
        )
        val ranked = ranking.rank(jobs, profile(), NOW, filter = BestOfferFilter.MADAGASCAR_OPEN)
        assertEquals(setOf("open", "sponsored"), ranked.map { it.job.id }.toSet())
    }

    @Test
    fun theSameVacancyFromTwoSourcesIsShownOnce() {
        val a = job("a", company = "Acme", sourceUrl = "https://example.com/jobs/42?utm_source=x")
        val b = job("b", company = "Acme", sourceUrl = "https://example.com/jobs/42")
        val ranked = ranking.rank(listOf(a, b), profile(), NOW)
        assertEquals(1, ranked.size)
    }

    @Test
    fun theCountReflectsCompatiblePostingsBeforeTheDisplayLimit() {
        val jobs = (1..5).map { job("j$it") }
        assertEquals(5, ranking.count(jobs, profile(), NOW))
        assertEquals(3, ranking.rank(jobs, profile(), NOW, limit = 3).size)
    }

    @Test
    fun freshnessSortPlacesDatedPostingsAboveUndatedOnes() {
        val ranked = ranking.rank(
            listOf(job("undated", postedAt = null), job("dated", postedAt = NOW - DAY)),
            profile(),
            NOW,
            sort = BestOfferSort.FRESHNESS,
        )
        assertEquals("dated", ranked.first().job.id)
    }

    // ---------------------------------------------------------------- banding

    @Test
    fun bandsFollowTheDocumentedThresholds() {
        assertEquals(OfferMatchBand.EXCELLENT, OfferMatchBand.fromScore(90))
        assertEquals(OfferMatchBand.STRONG, OfferMatchBand.fromScore(75))
        assertEquals(OfferMatchBand.GOOD, OfferMatchBand.fromScore(60))
        assertEquals(OfferMatchBand.FAIR, OfferMatchBand.fromScore(45))
        assertEquals(OfferMatchBand.LOW, OfferMatchBand.fromScore(10))
    }

    @Test
    fun aFullyMatchingOpenRecentPostingScoresInTheTopBands() {
        val match = engine.assess(
            job(
                "perfect",
                postedAt = NOW - DAY,
                eligibility = MadagascarEligibility.ELIGIBLE,
                roleFamily = RoleFamily.TARGET,
                matchedSkills = listOf("Preventive maintenance", "Hydraulics", "Electrical diagnostics", "PLC", "Welding"),
                benefitEvidence = listOf(
                    confirmedBenefit(JobBenefitTypes.VISA_SPONSORSHIP.key),
                    confirmedBenefit(JobBenefitTypes.ACCOMMODATION.key),
                    confirmedBenefit(JobBenefitTypes.RELOCATION_SUPPORT.key),
                    confirmedBenefit(JobBenefitTypes.HEALTH_INSURANCE.key),
                ),
            ),
            profile(),
            NOW,
        )
        assertTrue("expected a top-band score, got ${match.score}", match.score >= OfferMatchBand.STRONG_MIN)
        assertFalse(match.disqualified)
        assertTrue(match.reasons.isNotEmpty())
    }

    @Test
    fun reasonsAreNeverEmptyAndQuoteTheDimensions() {
        val match = engine.assess(job("reasons"), profile(), NOW)
        assertTrue(match.reasons.size >= 6)
        assertTrue(match.reasons.any { it.contains("Madagascar") || it.contains("applicants") })
    }

    @Test
    fun theRankedComponentsLeadWithTheStrongestDimension() {
        val match = engine.assess(job("ranked-components"), profile(), NOW)
        val points = match.rankedComponents.map { it.points }
        assertEquals(points.sortedDescending(), points)
    }

    @Test
    fun unknownEvidenceStrengthIsCarriedForUndatedPostings() {
        val match = engine.assess(job("undated2", postedAt = null), profile(), NOW)
        val freshness = match.components.first { it.dimension == OfferMatchDimension.VERIFIED_FRESHNESS }
        assertEquals(EvidenceStrength.UNKNOWN, freshness.strength)
    }
}
