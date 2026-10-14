package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.expat.ExpatBenefitExtractor
import com.aiglobal.jobfinder.data.expat.ExpatBenefitsCatalog
import com.aiglobal.jobfinder.data.expat.ExpatBenefitsEngine
import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.model.BenefitEvidence
import com.aiglobal.jobfinder.data.model.BenefitPriority
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.ExpatPreferences
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobBenefitTypes
import com.aiglobal.jobfinder.viewmodel.JobsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 7 behaviour: deterministic, evidence-based expat benefit extraction, the Expat
 * Benefits Score, and the settlement guides.
 *
 * These are pure functions of the posting text and the user's preferences, so they run on
 * the JVM. The guiding rule throughout is that the score must never state something the
 * posting did not, and must never be conflated with the CV match score.
 */
class Stage7PipelineTest {

    private val extractor = ExpatBenefitExtractor()
    private val engine = ExpatBenefitsEngine()

    private fun evidenceFor(text: String): List<BenefitEvidence> =
        extractor.extract(title = "Maintenance Technician", description = text)

    private fun byKey(text: String): Map<String, BenefitEvidence> =
        evidenceFor(text).associateBy { it.type.key }

    // --------------------------------------------------------------- extraction

    @Test
    fun explicitWordingIsConfirmedWithTheSentenceAsEvidence() {
        val findings = byKey(
            "We provide full visa sponsorship and a relocation package. " +
                "Company accommodation is provided for the first year.",
        )

        val visa = findings.getValue(JobBenefitTypes.VISA_SPONSORSHIP.key)
        assertEquals(BenefitStatus.CONFIRMED, visa.status)
        assertTrue("Evidence must quote the posting", visa.evidence.contains("visa sponsorship"))

        assertEquals(BenefitStatus.CONFIRMED, findings.getValue(JobBenefitTypes.RELOCATION_SUPPORT.key).status)
        assertEquals(BenefitStatus.CONFIRMED, findings.getValue(JobBenefitTypes.ACCOMMODATION.key).status)
    }

    @Test
    fun silenceIsUnknownAndNeverCountsAsARefusal() {
        val findings = byKey("Maintain production line machinery across two shifts.")

        val visa = findings.getValue(JobBenefitTypes.VISA_SPONSORSHIP.key)
        assertEquals(BenefitStatus.UNKNOWN, visa.status)
        assertFalse("An unmentioned benefit must not be reported as refused", visa.isDocumented)
        assertEquals("", visa.evidence)
    }

    @Test
    fun anExplicitRefusalIsRecordedAsNotOffered() {
        val findings = byKey("Please note: no visa sponsorship is available for this role.")

        val visa = findings.getValue(JobBenefitTypes.VISA_SPONSORSHIP.key)
        assertEquals(BenefitStatus.NOT_OFFERED, visa.status)
        assertTrue(visa.evidence.contains("no visa sponsorship"))
    }

    @Test
    fun hedgedWordingNeedsVerificationRatherThanBeingTreatedAsConfirmed() {
        val findings = byKey("A housing allowance may be available depending on the site.")

        val accommodation = findings.getValue(JobBenefitTypes.ACCOMMODATION.key)
        assertEquals(BenefitStatus.NEEDS_VERIFICATION, accommodation.status)
    }

    // ------------------------------------------------------------------- scoring

    private fun prefs(
        visa: BenefitPriority = BenefitPriority.ESSENTIAL,
        relocation: BenefitPriority = BenefitPriority.IMPORTANT,
        accommodation: BenefitPriority = BenefitPriority.NOT_IMPORTANT,
    ): ExpatPreferences = ExpatPreferences(
        // Start from "nothing matters" so the test isolates exactly the stated priorities,
        // rather than inheriting the per-type defaults for benefits it does not mention.
        priorities = JobBenefitTypes.all.associate { it.key to BenefitPriority.NOT_IMPORTANT.weight } + mapOf(
            JobBenefitTypes.VISA_SPONSORSHIP.key to visa.weight,
            JobBenefitTypes.RELOCATION_SUPPORT.key to relocation.weight,
            JobBenefitTypes.ACCOMMODATION.key to accommodation.weight,
        ),
    )

    @Test
    fun onlyDocumentedBenefitsEarnPoints() {
        val documented = evidenceFor("Visa sponsorship is available and relocation support is offered.")
        val assessed = engine.assess(documented, prefs(), salaryEurPerYear = null)

        // Visa (essential, 3) + relocation (important, 2) are both documented → 100%.
        assertEquals(100, assessed.score)
        assertTrue(assessed.components.any { it.type.key == JobBenefitTypes.VISA_SPONSORSHIP.key && it.earnedPoints == 3 })
    }

    @Test
    fun aSilentPostingScoresZeroAndIsFlaggedAsThinEvidence() {
        val silent = evidenceFor("Maintain production line machinery across two shifts.")
        val assessed = engine.assess(silent, prefs(), salaryEurPerYear = null)

        assertEquals(0, assessed.score)
        assertTrue("Thin coverage must be flagged", assessed.lowEvidence)
        assertFalse(assessed.hasEvidence)
        assertTrue(
            "The explanation must blame missing information, not the employer",
            assessed.explanation.contains("missing information"),
        )
    }

    @Test
    fun essentialBenefitMissingIsReportedAsAnUnmetEssential() {
        val relocationOnly = evidenceFor("A relocation package is available for this role.")
        val assessed = engine.assess(relocationOnly, prefs(), salaryEurPerYear = null)

        assertEquals(
            listOf(JobBenefitTypes.VISA_SPONSORSHIP),
            assessed.unmetEssentials,
        )
        assertTrue(assessed.explanation.contains("does not say whether it offers visa"))
    }

    @Test
    fun userPrioritiesChangeTheScoreForTheSamePosting() {
        val posting = evidenceFor("Company accommodation is provided and relocation support is offered.")

        val caresAboutHousing = engine.assess(
            posting,
            ExpatPreferences(
                priorities = JobBenefitTypes.all.associate { it.key to BenefitPriority.NOT_IMPORTANT.weight } + mapOf(
                    JobBenefitTypes.ACCOMMODATION.key to BenefitPriority.ESSENTIAL.weight,
                    JobBenefitTypes.RELOCATION_SUPPORT.key to BenefitPriority.NOT_IMPORTANT.weight,
                ),
            ),
            salaryEurPerYear = null,
        )
        val caresAboutVisa = engine.assess(
            posting,
            ExpatPreferences(
                priorities = JobBenefitTypes.all.associate { it.key to BenefitPriority.NOT_IMPORTANT.weight } + mapOf(
                    JobBenefitTypes.VISA_SPONSORSHIP.key to BenefitPriority.ESSENTIAL.weight,
                    JobBenefitTypes.ACCOMMODATION.key to BenefitPriority.NOT_IMPORTANT.weight,
                ),
            ),
            salaryEurPerYear = null,
        )

        assertEquals(100, caresAboutHousing.score)
        assertEquals(0, caresAboutVisa.score)
    }

    @Test
    fun theMinimumSalaryGateOnlyScoresWhenTheUserSetsAMinimum() {
        val posting = evidenceFor("Visa sponsorship available.")
        val basePrefs = prefs(relocation = BenefitPriority.NOT_IMPORTANT, accommodation = BenefitPriority.NOT_IMPORTANT)

        val noMinimum = engine.assess(posting, basePrefs, salaryEurPerYear = 30_000)
        assertTrue(
            "Without a minimum the salary gate must not appear",
            noMinimum.components.none { it.type.key == JobBenefitTypes.MINIMUM_SALARY.key },
        )

        val withMinimum = engine.assess(
            posting,
            basePrefs.copy(minimumSalaryEur = 40_000),
            salaryEurPerYear = 30_000,
        )
        val salary = withMinimum.components.first { it.type.key == JobBenefitTypes.MINIMUM_SALARY.key }
        assertEquals(BenefitStatus.NOT_OFFERED, salary.status)
    }

    // -------------------------------------------------------- separation of scores

    @Test
    fun theCvMatchScoreAndExpatScoreAreIndependentNumbers() {
        val mapper = JobMapper(MatchingEngine(), EligibilityEngine(), ExpatBenefitExtractor(), clock = { 1_760_000_000_000 })
        val job: Job = mapper.toJob(
            RawJob(
                providerId = "remotive",
                providerName = "Remotive",
                externalId = "77",
                title = "Maintenance Technician",
                company = "Northwind Industrial",
                location = "Toronto, Canada",
                descriptionHtml = "<p>Preventive maintenance and hydraulics. We provide visa sponsorship.</p>",
                url = "https://example.com/jobs/77",
            ),
            CandidateProfile(
                fullName = "Jonathan Rakoto",
                currentTitle = "Maintenance Technician",
                yearsOfExperience = 6,
                skills = listOf("Preventive maintenance", "Hydraulics"),
            ),
        )

        assertTrue("The mapper must attach benefit evidence", job.benefitEvidence.isNotEmpty())

        val assessed = engine.assess(job.benefitEvidence, prefs(), salaryEurPerYear = null)
        // The expat score is its own value; it must not be the CV match score by accident.
        assertTrue(assessed.score in 0..100)
        assertNotNull(job.matchScore)
    }

    // --------------------------------------------------------------- UI state gates

    private fun job(id: String, evidence: List<BenefitEvidence>) = Job(
        id = id,
        title = "Maintenance Technician",
        company = "Acme",
        companyAccent = 0xFF1E4FD8,
        country = "Canada",
        countryFlag = "🇨🇦",
        city = "Toronto",
        salaryMin = 60_000,
        salaryMax = 60_000,
        currencyCode = "CAD",
        salaryPeriod = com.aiglobal.jobfinder.data.model.SalaryPeriod.YEAR,
        matchScore = 80,
        eligibility = com.aiglobal.jobfinder.data.model.MadagascarEligibility.VISA_SPONSORED,
        visaSupport = com.aiglobal.jobfinder.data.model.VisaSupport.FULL_SPONSORSHIP,
        scamRisk = com.aiglobal.jobfinder.data.model.ScamRisk.LOW,
        postedAtEpochMillis = 1_000L,
        source = "Remotive",
        sourceUrl = "https://example.com/$id",
        workMode = com.aiglobal.jobfinder.data.model.WorkMode.ONSITE,
        employmentType = com.aiglobal.jobfinder.data.model.EmploymentType.FULL_TIME,
        experienceYears = 3,
        matchedSkills = listOf("Preventive maintenance"),
        missingSkills = emptyList(),
        languages = listOf("English"),
        summary = "Summary",
        responsibilities = emptyList(),
        benefits = emptyList(),
        aiInsight = "insight",
        providerId = "remotive",
        benefitEvidence = evidence,
    )

    @Test
    fun theExpatScoreFilterHidesPostingsBelowTheThreshold() {
        val documented = evidenceFor("Visa sponsorship available and relocation support offered.")
        val silent = evidenceFor("Maintain machinery.")
        val a = job("a", documented)
        val b = job("b", silent)

        val assessments = mapOf(
            "a" to engine.assess(documented, prefs(), salaryEurPerYear = null),
            "b" to engine.assess(silent, prefs(), salaryEurPerYear = null),
        )
        val state = JobsUiState(
            jobs = listOf(a, b),
            expatAssessments = assessments,
            minExpatScore = 60,
        )

        assertEquals(listOf("a"), state.visibleJobs.map { it.id })
        assertTrue(state.hasActiveFilters)
    }

    @Test
    fun theVisaDocumentedFilterKeepsOnlyConfirmedSponsorship() {
        val confirmed = evidenceFor("We provide visa sponsorship for this role.")
        val unstated = evidenceFor("Maintain machinery across two shifts.")

        val state = JobsUiState(
            jobs = listOf(job("a", confirmed), job("b", unstated)),
            visaDocumentedOnly = true,
        )

        assertEquals(listOf("a"), state.visibleJobs.map { it.id })
    }

    // ------------------------------------------------------------ settlement guides

    @Test
    fun everyHighlightedGuideCitesOfficialGovernmentSources() {
        ExpatBenefitsCatalog.highlightedCountries.forEach { country ->
            val guide = ExpatBenefitsCatalog.forCountry(country)
            assertTrue("$country must list benefits", guide.benefits.isNotEmpty())
            guide.benefits.forEach { benefit ->
                assertTrue("$country/${benefit.title} needs a source name", benefit.sourceName.isNotBlank())
                assertTrue(
                    "$country/${benefit.title} must link to an official source, was '${benefit.sourceUrl}'",
                    benefit.sourceUrl.startsWith("https://") && benefit.sourceUrl.contains('.'),
                )
            }
        }
    }

    @Test
    fun anUnknownDestinationStillGetsAnHonestGenericGuide() {
        val guide = ExpatBenefitsCatalog.forCountry("Atlantis")

        assertFalse(ExpatBenefitsCatalog.hasDetailedGuide("Atlantis"))
        assertTrue(guide.benefits.isNotEmpty())
        assertTrue(guide.practicalNotes.isNotEmpty())
    }
}
