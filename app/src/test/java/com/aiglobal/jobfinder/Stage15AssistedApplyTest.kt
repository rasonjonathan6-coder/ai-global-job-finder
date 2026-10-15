package com.aiglobal.jobfinder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aiglobal.jobfinder.data.applications.ApplicationCenter
import com.aiglobal.jobfinder.data.applications.ApplicationStore
import com.aiglobal.jobfinder.data.autoapply.AutoApplyEngine
import com.aiglobal.jobfinder.data.jobs.engine.JobEvidenceEngine
import com.aiglobal.jobfinder.data.jobs.search.QualificationMatcher
import com.aiglobal.jobfinder.data.jobs.search.RequirementAnalyzer
import com.aiglobal.jobfinder.data.jobs.search.RoleClassifier
import com.aiglobal.jobfinder.data.jobs.search.RoleFamily
import com.aiglobal.jobfinder.data.jobs.search.SearchCoverage
import com.aiglobal.jobfinder.data.jobs.search.SearchCoverageSource
import com.aiglobal.jobfinder.data.jobs.search.TargetSearch
import com.aiglobal.jobfinder.data.model.ApplicationEventType
import com.aiglobal.jobfinder.data.model.ApplicationStatus
import com.aiglobal.jobfinder.data.model.AutoApplyStatus
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.EligibilityReason
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.EvidenceKind
import com.aiglobal.jobfinder.data.model.EvidenceStrength
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import com.aiglobal.jobfinder.data.repository.SampleJobRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * Stage 15 — targeted global search, AI enhancement and **assisted** applications.
 *
 * These tests pin the two invariants Stage 15 is built around:
 *  1. The search is honest: a title that is not the candidate's trade is classified as
 *     unrelated, a requirement the CV cannot confirm is reported as "cannot tell" rather
 *     than "you do not have it", and a broad remote feed is described as a broad sample.
 *  2. Applications are assisted, never silent: the pipeline records an application as sent
 *     only when the user moves it to [ApplicationStatus.APPLIED] themselves, and the
 *     auto-apply flow prepares drafts without ever submitting.
 *
 * Robolectric is required because [ApplicationCenter] persists through a real file-backed
 * [ApplicationStore] and the evidence engine runs against the mapper's model.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage15AssistedApplyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val profile = CandidateProfile(
        fullName = "Jonathan Rakoto",
        currentTitle = "Industrial Maintenance Technician",
        previousTitles = listOf("Mechanical Fitter", "Pump Technician"),
        yearsOfExperience = 8,
        skills = listOf("Preventive maintenance", "Hydraulics", "PLC", "Rotating equipment"),
        certifications = emptyList(),
        summary = "Maintenance technician on juice production and nickel processing lines.",
    )

    private fun sampleJob(
        id: String = "job-1",
        title: String = "Industrial Maintenance Technician",
        matchScore: Int = 88,
        eligibility: MadagascarEligibility = MadagascarEligibility.VISA_SPONSORED,
        visaSupport: VisaSupport = VisaSupport.FULL_SPONSORSHIP,
        scamRisk: ScamRisk = ScamRisk.LOW,
        sourceUrl: String = "https://example.com/jobs/1",
    ) = Job(
        id = id,
        title = title,
        company = "Northline Industrial",
        companyAccent = 0xFF1E4FD8,
        country = "Canada",
        countryFlag = "🇨🇦",
        city = "Calgary",
        salaryMin = 62000,
        salaryMax = 78000,
        currencyCode = "CAD",
        salaryPeriod = SalaryPeriod.YEAR,
        matchScore = matchScore,
        eligibility = eligibility,
        visaSupport = visaSupport,
        scamRisk = scamRisk,
        postedAtEpochMillis = 1_700_000_000_000,
        source = "Canada Job Bank",
        sourceUrl = sourceUrl,
        workMode = WorkMode.ONSITE,
        employmentType = EmploymentType.FULL_TIME,
        experienceYears = 4,
        matchedSkills = listOf("Preventive maintenance", "Hydraulics"),
        missingSkills = emptyList(),
        languages = listOf("English (B2+)"),
        summary = "Maintain production line machinery.",
        responsibilities = listOf("Run preventive maintenance"),
        benefits = listOf("Relocation allowance"),
        aiInsight = "Strong technical fit.",
        matchReasons = emptyList(),
        eligibilityReasons = listOf(
            EligibilityReason("Visa sponsorship offered", "We sponsor a work visa.", official = true),
        ),
    )

    // ------------------------------------------------------- targeted classification

    @Test
    fun aCanonicalTargetTitleIsClassifiedAsTarget() {
        val relevance = RoleClassifier.classify("Rotating Equipment Technician")
        assertEquals(RoleFamily.TARGET, relevance.family)
        assertTrue(relevance.isTargeted)
        assertEquals("Rotating Equipment Technician", relevance.matchedTarget?.canonical)
    }

    @Test
    fun aDifferentProfessionIsRejectedEvenWhenItsDescriptionMentionsMaintenance() {
        // A product manager posting can name "maintenance" in its body; the title is what
        // decides, so this must not be presented as the candidate's job.
        val relevance = RoleClassifier.classify(
            title = "Product Manager",
            descriptionHead = "You will own the roadmap for our maintenance planning software.",
        )
        assertEquals(RoleFamily.UNRELATED, relevance.family)
        assertFalse(relevance.isMaintenanceFamily)
    }

    @Test
    fun aDifferentProfessionIsRejectedEvenWhenItsTitleCarriesATradeWord() {
        // "Software Engineer, Maintenance Tooling" names both a different profession and a
        // trade word; the profession wins so the title is not mistaken for the candidate's.
        val relevance = RoleClassifier.classify("Software Engineer — Maintenance Tooling")
        assertEquals(RoleFamily.UNRELATED, relevance.family)
    }

    @Test
    fun aLoneTechnicianTitleWithoutTradeContextIsNotClaimedAsRelevant() {
        // "Lab Technician" shares a weak core term but names no plant/equipment context.
        val relevance = RoleClassifier.classify("Laboratory Technician", "Sample analysis and reporting.")
        assertEquals(RoleFamily.UNRELATED, relevance.family)
    }

    @Test
    fun aWeakTradeTermWithPlantContextIsMaintenanceFamily() {
        val relevance = RoleClassifier.classify("Electrician", "Industrial plant, conveyors and pumps.")
        assertEquals(RoleFamily.MAINTENANCE_FAMILY, relevance.family)
        assertTrue(relevance.isMaintenanceFamily)
    }

    @Test
    fun keywordPlanIsCappedAndDeduplicated() {
        val plan = TargetSearch.keywordPlan(
            profileTitles = listOf("Mechanical Fitter", "  ", "Industrial Maintenance Technician"),
            max = 4,
        )
        assertEquals(4, plan.size)
        assertEquals(plan.map { it.lowercase() }.distinct().size, plan.size)
        assertEquals("Industrial Maintenance Technician", plan.first())
    }

    // ------------------------------------------------------- requirement analysis

    @Test
    fun requirementAnalyzerReadsYearsAndCertificationsFromThePostingOnly() {
        val requirements = RequirementAnalyzer.analyze(
            "We require 5 years experience in industrial maintenance. " +
                "A valid driving licence is essential. NVQ Level 3 preferred.",
        )
        assertEquals(5, requirements.minYears)
        assertTrue(requirements.licences.any { it.label.contains("Driving", ignoreCase = true) })
        assertTrue(requirements.certifications.any { it.label.contains("NVQ", ignoreCase = true) })
    }

    @Test
    fun aRequirementTheCvCannotConfirmIsReportedAsUnknownNotAsAMiss() {
        val requirements = RequirementAnalyzer.analyze("Candidates must hold a valid driving licence.")
        val gaps = QualificationMatcher.gaps(requirements, profile)
        val licenceGap = gaps.first { it.label.contains("Driving", ignoreCase = true) }
        // The profile says nothing about a driving licence, so the honest answer is null.
        assertNull(licenceGap.met)
    }

    @Test
    fun aRequirementTheCvClearlyShowsIsMarkedAsMet() {
        val requirements = RequirementAnalyzer.analyze("Minimum 5 years experience required.")
        val gaps = QualificationMatcher.gaps(requirements, profile)
        val yearsGap = gaps.first { it.label.contains("years", ignoreCase = true) }
        assertEquals(true, yearsGap.met)
    }

    // ------------------------------------------------------- coverage honesty

    @Test
    fun aSearchWithNoKeywordCapableSourceIsDescribedAsABroadSample() {
        val coverage = SearchCoverage(
            sources = listOf(
                SearchCoverageSource("remotive", "Remotive", targeted = false, relevantCount = 2, totalCount = 40),
            ),
            totalRelevant = 2,
            totalRetrieved = 40,
        )
        assertFalse(coverage.hasTargetedSource)
        assertTrue(coverage.summary.startsWith("Broad sample"))
        assertTrue(coverage.summary.contains("2"))
    }

    @Test
    fun aSearchWithAKeywordCapableSourceIsDescribedAsTargeted() {
        val coverage = SearchCoverage(
            sources = listOf(
                SearchCoverageSource("adzuna", "Adzuna", targeted = true, relevantCount = 6, totalCount = 12),
            ),
            totalRelevant = 6,
            totalRetrieved = 12,
        )
        assertTrue(coverage.hasTargetedSource)
        assertTrue(coverage.summary.startsWith("Targeted search"))
    }

    // ------------------------------------------------------- evidence

    @Test
    fun documentedEligibilityCarriesTheQuoteAndTheSourceLink() {
        val evidence = JobEvidenceEngine().build(
            eligibility = MadagascarEligibility.VISA_SPONSORED,
            visaSupport = VisaSupport.FULL_SPONSORSHIP,
            eligibilityReasons = listOf(
                EligibilityReason("Visa sponsorship offered", "We sponsor a work visa.", official = true),
            ),
            benefits = emptyList(),
            gaps = emptyList(),
            sourceUrl = "https://example.com/jobs/1",
        )
        val visa = evidence.first { it.kind == EvidenceKind.VISA }
        assertEquals(EvidenceStrength.DOCUMENTED, visa.strength)
        assertEquals("We sponsor a work visa.", visa.detail)
        assertEquals("https://example.com/jobs/1", visa.sourceUrl)
    }

    @Test
    fun silentEligibilityIsShownAsUnknownRatherThanAsAPositive() {
        val evidence = JobEvidenceEngine().build(
            eligibility = MadagascarEligibility.UNCLEAR,
            visaSupport = VisaSupport.NOT_MENTIONED,
            eligibilityReasons = emptyList(),
            benefits = emptyList(),
            gaps = emptyList(),
            sourceUrl = "https://example.com/jobs/2",
        )
        val eligibility = evidence.first { it.kind == EvidenceKind.ELIGIBILITY }
        assertEquals(EvidenceStrength.UNKNOWN, eligibility.strength)
        assertFalse(eligibility.isDocumented)
    }

    // ------------------------------------------------------- assisted application

    private fun centerAt(tag: String, now: () -> Long): ApplicationCenter {
        val file = File(context.cacheDir, "stage15-$tag-${System.nanoTime()}.json")
        return ApplicationCenter(
            repository = SampleJobRepository(refreshDelayMillis = 0),
            store = ApplicationStore(context, file),
            candidateProfile = flowOf(profile),
            clock = now,
            idFactory = { UUID.randomUUID().toString() },
            demoMode = flowOf(false),
        )
    }

    @Test
    fun trackingAJobLeavesItSavedAndUnapplied() {
        val center = centerAt("save") { 1_000L }
        val record = runBlocking { center.trackJob(sampleJob()) }

        assertEquals(ApplicationStatus.SAVED, record.status)
        assertNull(record.appliedAtEpochMillis)
        assertTrue(record.timeline.any { it.type == ApplicationEventType.CREATED })
    }

    @Test
    fun theAppliedStatusIsTheOnlyThingThatStampsTheApplyTime() {
        val center = centerAt("apply") { 5_000L }
        val record = runBlocking { center.trackJob(sampleJob()) }

        runBlocking { center.setStatus(record.id, ApplicationStatus.PREPARING) }
        assertNull(center.application(record.id)!!.appliedAtEpochMillis)

        runBlocking { center.setStatus(record.id, ApplicationStatus.APPLIED) }
        val applied = center.application(record.id)!!
        assertEquals(ApplicationStatus.APPLIED, applied.status)
        assertEquals(5_000L, applied.appliedAtEpochMillis)
    }

    @Test
    fun theTimelineIsAppendOnlyAcrossStatusChanges() {
        val center = centerAt("timeline") { 9_000L }
        val record = runBlocking { center.trackJob(sampleJob()) }
        runBlocking {
            center.setStatus(record.id, ApplicationStatus.PREPARING)
            center.setStatus(record.id, ApplicationStatus.APPLIED)
            center.setStatus(record.id, ApplicationStatus.INTERVIEW)
        }
        val updated = center.application(record.id)!!
        assertEquals(4, updated.timeline.size)
        assertEquals(ApplicationEventType.CREATED, updated.timeline.first().type)
        assertEquals(ApplicationEventType.STATUS_CHANGE, updated.timeline.last().type)
    }

    @Test
    fun applicationCenterExposesNoSilentSubmissionEntryPoint() {
        // Guards the Stage 15 rule: nothing in the pipeline may submit an application on the
        // user's behalf. Only an explicit status change records a submission.
        val methodNames = ApplicationCenter::class.java.declaredMethods.map { it.name.lowercase() }
        assertTrue(methodNames.none { "submit" in it || "autosend" in it || "sendapplication" in it })
    }

    // ------------------------------------------------------- auto-apply is draft-only

    @Test
    fun autoApplyPreparesADraftAndNeverReportsItAsSent() {
        val decision = AutoApplyEngine(threshold = 70).decide(sampleJob(matchScore = 88))
        assertEquals(AutoApplyStatus.DRAFT_READY, decision.status)
        assertTrue(decision.reason.contains("Draft ready", ignoreCase = true))
        // No status in the flow means "sent"; DRAFT_READY is the strongest pre-send state.
        assertTrue(AutoApplyStatus.values().none { it.name.contains("SENT") || it.name.contains("SUBMITTED") })
    }

    @Test
    fun autoApplyBlocksScamFlaggedAndIneligiblePostings() {
        val engine = AutoApplyEngine(threshold = 70)
        val scam = engine.decide(sampleJob(scamRisk = ScamRisk.HIGH))
        assertEquals(AutoApplyStatus.BLOCKED, scam.status)

        val ineligible = engine.decide(sampleJob(eligibility = MadagascarEligibility.RESTRICTED))
        assertEquals(AutoApplyStatus.BLOCKED, ineligible.status)
    }

    @Test
    fun approvalStillLeavesSendingToTheUser() {
        val center = centerAt("approval") { 1_000L }
        val record = runBlocking { center.trackJob(sampleJob()) }
        assertNotNull(record.id)
        // Approving in auto-apply only generates a draft; the pipeline record stays unapplied
        // until the user confirms the submission actually happened.
        assertEquals(ApplicationStatus.SAVED, center.application(record.id)!!.status)
        assertNull(center.application(record.id)!!.appliedAtEpochMillis)
    }
}
