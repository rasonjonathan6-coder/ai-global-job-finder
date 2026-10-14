package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.alerts.JobAlertEngine
import com.aiglobal.jobfinder.data.autoapply.AutoApplyEngine
import com.aiglobal.jobfinder.data.interview.InterviewCoach
import com.aiglobal.jobfinder.data.model.AlertScope
import com.aiglobal.jobfinder.data.model.AnswerConfidence
import com.aiglobal.jobfinder.data.model.AutoApplyStatus
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.InterviewAttempt
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobAlertRule
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.ScamVerdict
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import com.aiglobal.jobfinder.data.scam.ScamDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 5 behaviour: the interview coach, the scam detector, saved-search alerts and the
 * safe auto-apply queue. All four are pure functions of their inputs, so they run on the
 * JVM without Robolectric.
 */
class Stage5PipelineTest {

    private val coach = InterviewCoach(clock = { 1_700_000_000_000 })
    private val detector = ScamDetector(clock = { 1_700_000_000_000 })
    private val alerts = JobAlertEngine()
    private val autoApply = AutoApplyEngine(threshold = 70, clock = { 1_700_000_000_000 })

    private val profile = CandidateProfile(
        fullName = "Jonathan Rakoto",
        currentTitle = "Maintenance Technician",
        previousTitles = listOf("Industrial Electrician"),
        yearsOfExperience = 6,
        skills = listOf("Preventive maintenance", "Hydraulics", "PLC"),
    )

    private fun job(
        id: String = "job-1",
        title: String = "Maintenance Technician",
        company: String = "Northline Industrial",
        country: String = "Canada",
        matchScore: Int = 90,
        eligibility: MadagascarEligibility = MadagascarEligibility.VISA_SPONSORED,
        visaSupport: VisaSupport = VisaSupport.FULL_SPONSORSHIP,
        scamRisk: ScamRisk = ScamRisk.LOW,
        source: String = "Canada Job Bank",
        sourceUrl: String = "https://www.jobbank.gc.ca",
        summary: String = "Maintain production line machinery across two shifts.",
        matchedSkills: List<String> = listOf("Preventive maintenance", "Hydraulics"),
        missingSkills: List<String> = listOf("Canadian Red Seal certification"),
    ) = Job(
        id = id,
        title = title,
        company = company,
        companyAccent = 0xFF1E4FD8,
        country = country,
        countryFlag = "🇨🇦",
        city = "Calgary",
        salaryMin = 58000,
        salaryMax = 74000,
        currencyCode = "CAD",
        salaryPeriod = SalaryPeriod.YEAR,
        matchScore = matchScore,
        eligibility = eligibility,
        visaSupport = visaSupport,
        scamRisk = scamRisk,
        postedAtEpochMillis = 1_699_999_000_000,
        source = source,
        sourceUrl = sourceUrl,
        workMode = WorkMode.ONSITE,
        employmentType = EmploymentType.FULL_TIME,
        experienceYears = 3,
        matchedSkills = matchedSkills,
        missingSkills = missingSkills,
        languages = listOf("English (B2+)"),
        summary = summary,
        responsibilities = listOf("Run preventive maintenance", "Log work orders"),
        benefits = listOf("Relocation allowance"),
        aiInsight = "Strong technical fit.",
    )

    // ---- Interview coach ---------------------------------------------------

    @Test
    fun prepIsGroundedInThePostingAndTheCv() {
        val prep = coach.prep(job(), profile)
        assertTrue("The coach must produce questions", prep.questions.isNotEmpty())
        assertTrue(
            "The opening question must name the real posting",
            prep.questions.any { it.prompt.contains("Maintenance Technician") && it.prompt.contains("Northline Industrial") },
        )
        assertTrue(
            "Every question must carry its rationale",
            prep.questions.all { it.whyItMatters.isNotBlank() },
        )
        assertTrue("Focus areas must be offered", prep.focusAreas.isNotEmpty())
    }

    @Test
    fun theMissingSkillIsRaisedAsAGapRatherThanInvented() {
        val prep = coach.prep(job(missingSkills = listOf("Canadian Red Seal certification")), profile)
        val gap = prep.questions.firstOrNull { it.prompt.contains("Canadian Red Seal") }
        assertTrue("A skill missing from the CV must be asked about", gap != null)
        assertTrue("The gap must be recorded, not hidden", gap!!.missingFacts.any { it.contains("Red Seal") })
    }

    @Test
    fun structuredAnswerScoresHigherThanAOneLiner() {
        val question = coach.prep(job(), profile).questions.first()
        val thin = coach.feedback(question, "I am a technician.")
        val strong = coach.feedback(
            question,
            "I am a maintenance technician with six years in food-processing plants. At my last employer " +
                "I led the preventive maintenance plan for two production lines. Because I introduced a " +
                "weekly inspection schedule, unplanned downtime fell by 30 percent. As a result the plant " +
                "saved money, and I want to bring that same discipline to Northline in Canada.",
        )
        assertTrue("A structured answer must outscore a one-liner", strong.score > thin.score)
        assertTrue("Thin answers must be told what is missing", thin.improvements.isNotEmpty())
    }

    @Test
    fun readinessIsDerivedFromSavedAttempts() {
        val questions = coach.prep(job(), profile).questions
        val attempts = listOf(
            InterviewAttempt(questions[0].id, AnswerConfidence.CONFIDENT, 1),
            InterviewAttempt(questions[1].id, AnswerConfidence.GETTING_THERE, 2),
        )
        val readiness = coach.readiness(questions, attempts)
        assertEquals(2, readiness.attempted)
        assertEquals(questions.size, readiness.total)
        assertEquals(1, readiness.confident)
        assertFalse(readiness.isComplete)
    }

    // ---- Scam detector -----------------------------------------------------

    @Test
    fun aFeeDemandingPostingIsFlaggedHighRisk() {
        val report = detector.scan(
            job(
                title = "Care Assistant",
                company = "Brightpath Care Homes",
                eligibility = MadagascarEligibility.RESTRICTED,
                visaSupport = VisaSupport.LOCAL_ONLY,
                scamRisk = ScamRisk.HIGH,
                source = "Unverified listing",
                sourceUrl = "https://example.com",
                summary = "To secure this placement you must pay a registration fee up front. " +
                    "We will contact you on WhatsApp only. Write to us at careers.gmail.com.",
                matchedSkills = emptyList(),
                missingSkills = listOf("UK right to work"),
            ),
        )
        assertEquals(ScamVerdict.HIGH_RISK, report.verdict)
        assertTrue("The fee demand must be surfaced as a danger", report.dangers.isNotEmpty())
        assertTrue(
            "The exact wording that triggered the flag must be shown",
            report.dangers.any { it.evidence.isNotBlank() },
        )
        assertTrue("A high-risk posting must score low", report.safetyScore < 50)
    }

    @Test
    fun aReputableSponsoredPostingIsLikelySafe() {
        val report = detector.scan(job())
        assertEquals(ScamVerdict.LIKELY_SAFE, report.verdict)
        assertTrue("A clean posting must have no dangers", report.dangers.isEmpty())
        assertTrue("Good signs must be reported", report.reassurances.isNotEmpty())
    }

    @Test
    fun silenceAboutSponsorshipIsACautionNotAReassurance() {
        val report = detector.scan(job(visaSupport = VisaSupport.NOT_MENTIONED))
        assertTrue(
            "An unstated visa route must never read as a good sign",
            report.reassurances.none { it.label.contains("sponsorship", ignoreCase = true) },
        )
    }

    // ---- Job alerts --------------------------------------------------------

    private fun rule(
        id: String = "rule-1",
        scope: AlertScope = AlertScope.ANY_MATCH,
        countries: List<String> = emptyList(),
        keywords: List<String> = emptyList(),
        minMatchScore: Int = 60,
    ) = JobAlertRule(
        id = id,
        name = "Test rule",
        keywords = keywords,
        countries = countries,
        scope = scope,
        minMatchScore = minMatchScore,
        createdAtEpochMillis = 1,
    )

    @Test
    fun aVisaFriendlyRuleOnlyCatchesSponsoredPostings() {
        val sponsored = job(id = "sponsored", visaSupport = VisaSupport.FULL_SPONSORSHIP)
        val localOnly = job(id = "local", visaSupport = VisaSupport.LOCAL_ONLY)
        val matches = alerts.alertsFor(listOf(rule(scope = AlertScope.VISA_FRIENDLY)), listOf(sponsored, localOnly), 100)
        assertEquals(1, matches.size)
        assertEquals("sponsored", matches.first().jobId)
    }

    @Test
    fun aRuleRespectsItsThresholdAndCountry() {
        val rule = rule(countries = listOf("Canada"), minMatchScore = 80)
        val strong = job(id = "strong", matchScore = 90, country = "Canada")
        val weak = job(id = "weak", matchScore = 70, country = "Canada")
        val elsewhere = job(id = "elsewhere", matchScore = 90, country = "Germany")
        val matches = alerts.alertsFor(listOf(rule), listOf(strong, weak, elsewhere), 100)
        assertEquals(listOf("strong"), matches.map { it.jobId })
    }

    @Test
    fun keywordRuleMatchesTheTitleAndTheSummary() {
        val rule = rule(keywords = listOf("hydraulics"), minMatchScore = 50)
        val matches = alerts.alertsFor(listOf(rule), listOf(job()), 100)
        assertEquals(1, matches.size)
    }

    @Test
    fun aDisabledRuleProducesNothing() {
        val matches = alerts.alertsFor(listOf(rule().copy(enabled = false)), listOf(job()), 100)
        assertTrue(matches.isEmpty())
    }

    // ---- Safe auto-apply ---------------------------------------------------

    @Test
    fun aHighScamRiskPostingIsBlockedNotQueued() {
        val decision = autoApply.decide(job(scamRisk = ScamRisk.HIGH))
        assertEquals(AutoApplyStatus.BLOCKED, decision.status)
        assertFalse(decision.eligible)
        assertTrue("The refusal must be explained", decision.reason.isNotBlank())
    }

    @Test
    fun aPostingClosedToMadagascarIsBlocked() {
        val decision = autoApply.decide(job(eligibility = MadagascarEligibility.RESTRICTED))
        assertEquals(AutoApplyStatus.BLOCKED, decision.status)
        assertTrue(decision.reason.contains("Madagascar"))
    }

    @Test
    fun aPostingBelowThresholdIsSkippedNotBlocked() {
        val decision = autoApply.decide(job(matchScore = 55))
        assertEquals(AutoApplyStatus.SKIPPED, decision.status)
    }

    @Test
    fun anEligibleStrongMatchBecomesADraftOnly() {
        val decision = autoApply.decide(job())
        assertEquals(AutoApplyStatus.DRAFT_READY, decision.status)
        assertTrue(decision.eligible)
    }

    @Test
    fun buildQueueSortsByMatchAndKeepsApprovals() {
        val jobs = listOf(
            job(id = "low", matchScore = 80),
            job(id = "high", matchScore = 95),
        )
        val existing = listOf(
            com.aiglobal.jobfinder.data.model.AutoApplyItem(
                jobId = "low",
                jobTitle = "Maintenance Technician",
                company = "Northline Industrial",
                country = "Canada",
                countryFlag = "🇨🇦",
                matchScore = 80,
                sourceUrl = "https://www.jobbank.gc.ca",
                status = AutoApplyStatus.APPROVED,
                reason = "You approved this draft.",
                preparedAtEpochMillis = 1,
            ),
        )
        val queue = autoApply.buildQueue(jobs, existing)
        assertEquals(listOf("high", "low"), queue.map { it.jobId })
        assertEquals(
            "An approved item must survive a refresh",
            AutoApplyStatus.APPROVED,
            queue.first { it.jobId == "low" }.status,
        )
    }

    @Test
    fun aPostingWithNoApplicationLinkCannotBePrepared() {
        val decision = autoApply.decide(job(sourceUrl = ""))
        assertEquals(AutoApplyStatus.BLOCKED, decision.status)
    }
}
