package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.apply.ApplicationUrlInspector
import com.aiglobal.jobfinder.data.assistant.ApplicationPlanEngine
import com.aiglobal.jobfinder.data.assistant.ExpatIntelligenceEngine
import com.aiglobal.jobfinder.data.coverletter.CoverLetterGenerator
import com.aiglobal.jobfinder.data.cv.analysis.CvCompleteness
import com.aiglobal.jobfinder.data.expat.ExpatBenefitExtractor
import com.aiglobal.jobfinder.data.expat.ExpatBenefitsEngine
import com.aiglobal.jobfinder.data.expat.ExpatOpportunityEngine
import com.aiglobal.jobfinder.data.expat.ShortlistEngine
import com.aiglobal.jobfinder.data.interview.InterviewCoach
import com.aiglobal.jobfinder.data.interview.InterviewQuestionBank
import com.aiglobal.jobfinder.data.geo.CurrencyConverter
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityInput
import com.aiglobal.jobfinder.data.jobs.engine.InternationalFitEngine
import com.aiglobal.jobfinder.data.jobs.engine.JobEvidenceEngine
import com.aiglobal.jobfinder.data.jobs.engine.MatchInput
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.engine.OfferVerificationEngine
import com.aiglobal.jobfinder.data.jobs.engine.RecordValidationEngine
import com.aiglobal.jobfinder.data.jobs.engine.SkillLexicon
import com.aiglobal.jobfinder.data.jobs.freshness.FreshnessWindow
import com.aiglobal.jobfinder.data.jobs.freshness.JobFreshnessEngine
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaProvider
import com.aiglobal.jobfinder.data.jobs.provider.ArbeitnowProvider
import com.aiglobal.jobfinder.data.jobs.provider.AshbyProvider
import com.aiglobal.jobfinder.data.jobs.provider.EuresProvider
import com.aiglobal.jobfinder.data.jobs.provider.GreenhouseProvider
import com.aiglobal.jobfinder.data.jobs.provider.HimalayasProvider
import com.aiglobal.jobfinder.data.jobs.provider.JobicyProvider
import com.aiglobal.jobfinder.data.jobs.provider.JobProvider
import com.aiglobal.jobfinder.data.jobs.provider.JoobleProvider
import com.aiglobal.jobfinder.data.jobs.provider.LeverProvider
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.jobs.provider.RemotiveProvider
import com.aiglobal.jobfinder.data.jobs.provider.SmartRecruitersProvider
import com.aiglobal.jobfinder.data.jobs.provider.WorkdayProvider
import com.aiglobal.jobfinder.data.jobs.search.QualificationMatcher
import com.aiglobal.jobfinder.data.jobs.search.QueryExpansion
import com.aiglobal.jobfinder.data.jobs.search.RelevanceFeed
import com.aiglobal.jobfinder.data.jobs.search.RequirementAnalyzer
import com.aiglobal.jobfinder.data.jobs.search.RoleClassifier
import com.aiglobal.jobfinder.data.jobs.search.SearchStrategy
import com.aiglobal.jobfinder.data.jobs.ExpatRankingEngine
import com.aiglobal.jobfinder.data.model.AiCapability
import com.aiglobal.jobfinder.data.model.AiCapabilityCatalog
import com.aiglobal.jobfinder.data.model.AnswerConfidence
import com.aiglobal.jobfinder.data.model.BenefitEvidence
import com.aiglobal.jobfinder.data.model.BenefitClaim
import com.aiglobal.jobfinder.data.model.BenefitFinding
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.CandidateField
import com.aiglobal.jobfinder.data.model.CertificationEntry
import com.aiglobal.jobfinder.data.model.CoverLetter
import com.aiglobal.jobfinder.data.model.EducationEntry
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.ExpatBenefitsAssessment
import com.aiglobal.jobfinder.data.model.ExpatIntelligence
import com.aiglobal.jobfinder.data.model.ExpatPreferences
import com.aiglobal.jobfinder.data.model.ExpatRankedJob
import com.aiglobal.jobfinder.data.model.ExpatRankingState
import com.aiglobal.jobfinder.data.model.RankingLabel
import com.aiglobal.jobfinder.data.model.ProviderDiagnostic
import com.aiglobal.jobfinder.data.model.ProviderStatus
import com.aiglobal.jobfinder.data.jobs.provider.DataSourceKind
import com.aiglobal.jobfinder.data.model.ApplicationPlan
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaCredentials
import com.aiglobal.jobfinder.data.model.InternationalFitAssessment
import com.aiglobal.jobfinder.data.model.InterviewAttempt
import com.aiglobal.jobfinder.data.model.InterviewQuestion
import com.aiglobal.jobfinder.data.model.LetterLanguage
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobRequirements
import com.aiglobal.jobfinder.data.model.OfferVerification
import com.aiglobal.jobfinder.data.model.LanguageLevel
import com.aiglobal.jobfinder.data.model.LanguageSkill
import com.aiglobal.jobfinder.data.model.ProfileCompleteness
import com.aiglobal.jobfinder.data.model.RelocationPreference
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.ShortlistEntry
import com.aiglobal.jobfinder.data.model.ShortlistState
import com.aiglobal.jobfinder.data.model.VisaSupport
import com.aiglobal.jobfinder.data.model.WorkMode
import com.aiglobal.jobfinder.data.alerts.JobAlertEngine
import com.aiglobal.jobfinder.data.autoapply.AutoApplyEngine
import com.aiglobal.jobfinder.data.model.AlertFrequency
import com.aiglobal.jobfinder.data.model.AlertScope
import com.aiglobal.jobfinder.data.model.ApplicationDraft
import com.aiglobal.jobfinder.data.model.AutoApplyItem
import com.aiglobal.jobfinder.data.model.AutoApplyStatus
import com.aiglobal.jobfinder.data.model.DraftAnswer
import com.aiglobal.jobfinder.data.model.DraftStatus
import com.aiglobal.jobfinder.data.model.JobAlertRule
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.submissions.OfficialSubmissionClassifier
import com.aiglobal.jobfinder.data.submissions.OfficialSubmissionOutcome
import com.aiglobal.jobfinder.data.submissions.SubmissionCapability
import com.aiglobal.jobfinder.data.submissions.SubmissionChannel
import com.aiglobal.jobfinder.data.submissions.SubmissionKey
import com.aiglobal.jobfinder.data.submissions.SubmissionLifecycle
import com.aiglobal.jobfinder.data.submissions.SubmissionPreflightCheck
import com.aiglobal.jobfinder.data.scam.ScamDetector
import com.aiglobal.jobfinder.data.cv.analysis.CvAnalysisContract
import com.aiglobal.jobfinder.data.cv.analysis.CvLexicon
import com.aiglobal.jobfinder.data.cv.analysis.CvSectionPresence
import com.aiglobal.jobfinder.data.cv.analysis.CvTextNormalizer
import com.aiglobal.jobfinder.data.cv.analysis.HeuristicCvAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stage 58 — golden parity fixtures for the web edition.
 *
 * The web edition ports the Kotlin engines to TypeScript. A port is only trustworthy when it
 * returns byte-identical results for the same input, so this test drives the **real** Kotlin
 * engines over a shared set of scenarios and serialises both the inputs and their outputs to
 * `web/src/core/parity/kotlin-parity.json`. The vitest parity suite replays the same inputs
 * through the TypeScript engines and asserts the outputs match.
 *
 * The fixtures are the contract: nothing here is hand-written. Every value in the JSON is the
 * output of the production engine it documents.
 */
class Stage58GoldenParityExportTest {

    private val fixedNow = 1_720_000_000_000L

    private fun buildProfile() = CandidateProfile(
        fullName = "Jonathan Rakoto",
        email = "jonathan@example.mg",
        location = "Antananarivo, Madagascar",
        currentTitle = "Maintenance Technician",
        previousTitles = listOf("Mechanical Fitter", "Pump Technician"),
        yearsOfExperience = 6,
        skills = listOf(
            "Preventive maintenance", "Hydraulics", "PLC", "Mechanical repair", "Welding",
            "Condition monitoring", "Heavy equipment",
        ),
        industries = listOf("Industrial maintenance", "Mining"),
        education = listOf(
            EducationEntry(
                degree = "Diploma in Mechanical Engineering",
                institution = "Institut Supérieur de Technologie",
                country = "Madagascar",
                year = 2016,
            ),
        ),
        certifications = listOf(
            CertificationEntry(name = "City & Guilds Mechanical Engineering", issuer = "City & Guilds", year = 2016),
            CertificationEntry(name = "First Aid", issuer = "Red Cross", year = 2020),
        ),
        languages = listOf(
            LanguageSkill(name = "English", level = LanguageLevel.FLUENT, evidence = "CV"),
            LanguageSkill(name = "French", level = LanguageLevel.ADVANCED, evidence = "CV"),
        ),
        preferredCountries = listOf("Canada", "Germany", "Saudi Arabia"),
        preferredRoles = listOf("Pump Technician"),
        summary = "Mechanical maintenance technician with pump, rotating equipment and mining experience.",
        relocationPreference = RelocationPreference.PREFERS_RELOCATION,
        needsVisaSponsorship = true,
    )

    private data class Case(
        val id: String,
        val title: String,
        val country: String,
        val city: String,
        val description: String,
        val tags: List<String> = emptyList(),
        val employmentType: String? = "Full-time",
        val isRemote: Boolean = false,
        val isRecognizedCountry: Boolean = true,
        val postedDaysAgo: Int? = 3,
        val company: String = "Northline Industrial",
        val source: String = "Workday",
        val providerId: String = "workday",
        val url: String = "https://careers.northline.example/jobs/1",
        val salaryMin: Int = 60_000,
        val salaryMax: Int = 72_000,
        val currency: String = "CAD",
        val period: SalaryPeriod = SalaryPeriod.YEAR,
        val salaryUnitUncertain: Boolean = false,
        val languages: List<String> = listOf("English"),
        val closedToNewApplicants: Boolean = false,
        val expiresAtEpochMillis: Long? = null,
    )

    private val cases = listOf(
        Case(
            id = "canada-maintenance",
            title = "Maintenance Technician",
            country = "Canada",
            city = "Toronto",
            description = "We are seeking a Maintenance Technician with 3+ years of experience. " +
                "You will perform preventive maintenance on pumps and hydraulic systems. " +
                "PLC experience required. Visa sponsorship available. English required.",
            tags = listOf("maintenance", "pump"),
            salaryMin = 68_000,
            salaryMax = 82_000,
            currency = "CAD",
        ),
        Case(
            id = "saudi-pump",
            title = "Pump Technician",
            country = "Saudi Arabia",
            city = "Dammam",
            description = "Pump technician required for a refinery. 5 years experience. " +
                "Relocation package and accommodation provided. Must have the right to work in Saudi Arabia. " +
                "Annual flights home.",
            tags = listOf("pump", "refinery"),
            salaryMin = 90_000,
            salaryMax = 110_000,
            currency = "SAR",
        ),
        Case(
            id = "germany-fitter",
            title = "Mechanical Fitter",
            country = "Germany",
            city = "Hamburg",
            description = "Mechanical fitter for a juice production plant. German language required. " +
                "2 years experience. We sponsor visas. City & Guilds certification preferred.",
            tags = listOf("fitter", "juice"),
            postedDaysAgo = 10,
            company = "Elbe Beverages GmbH",
            salaryMin = 42_000,
            salaryMax = 48_000,
            currency = "EUR",
            languages = listOf("German"),
        ),
        Case(
            id = "remote-worldwide",
            title = "Industrial Maintenance Technician",
            country = "Worldwide",
            city = "Remote",
            description = "Remote worldwide role. International applicants welcome. " +
                "Preventive maintenance and condition monitoring. 4+ years. English required.",
            tags = listOf("maintenance"),
            isRemote = true,
            isRecognizedCountry = false,
            postedDaysAgo = 1,
            salaryMin = 55_000,
            salaryMax = 55_000,
            currency = "USD",
        ),
        Case(
            id = "unrelated-marketing",
            title = "Marketing Manager",
            country = "France",
            city = "Paris",
            description = "Marketing manager with 5 years experience. Excel required. " +
                "Relocation support available.",
            tags = listOf("marketing"),
            postedDaysAgo = 20,
            company = "Lumiere SA",
            salaryMin = 60_000,
            salaryMax = 70_000,
            currency = "EUR",
        ),
        Case(
            id = "local-only-diesel",
            title = "Diesel Mechanic",
            country = "Australia",
            city = "Perth",
            description = "Local candidates only. Must have the right to work in Australia. " +
                "3 years experience with heavy equipment and diesel engines.",
            tags = listOf("diesel", "heavy equipment"),
            postedDaysAgo = 5,
            salaryMin = 95_000,
            salaryMax = 105_000,
            currency = "AUD",
        ),
        Case(
            id = "thin-lab-technician",
            title = "Lab Technician",
            country = "Netherlands",
            city = "Rotterdam",
            description = "Lab technician role at an industrial manufacturer. ISO 9001 quality control.",
            tags = listOf("quality"),
            postedDaysAgo = 2,
            company = "Delta Manufacturing",
            salaryMin = 38_000,
            salaryMax = 44_000,
            currency = "EUR",
        ),
        Case(
            id = "no-salary-stated",
            title = "Rotating Equipment Technician",
            country = "United Arab Emirates",
            city = "Abu Dhabi",
            description = "Rotating equipment technician for a processing plant. 6 years experience. " +
                "Vibration analysis and laser alignment. Visa sponsorship available.",
            tags = listOf("rotating equipment"),
            postedDaysAgo = null,
            salaryMin = 0,
            salaryMax = 0,
            currency = "AED",
            languages = emptyList(),
        ),
        Case(
            id = "missing-description",
            title = "Maintenance Planner",
            country = "Qatar",
            city = "Doha",
            description = "",
            tags = emptyList(),
            postedDaysAgo = 4,
            salaryMin = 70_000,
            salaryMax = 80_000,
            currency = "QAR",
        ),
    )

    @Test
    fun exportGoldenParityFixtures() {
        val profile = buildProfile()
        val preferences = ExpatPreferences.defaults()
        val scenarios = cases.map { scenarioJson(it, profile, preferences) }
        val root = linkedMapOf<String, Any?>(
            "meta" to linkedMapOf(
                "stage" to 72,
                "generatedFrom" to "Kotlin production engines",
                "clockMillis" to fixedNow,
                "note" to "Inputs and expected outputs are both produced by the Kotlin engines.",
            ),
            "profile" to profileJson(profile),
            "expatPreferences" to preferencesJson(preferences),
            "scenarios" to scenarios,
            "recordValidation" to recordValidationJson(),
            "deduplication" to deduplicationJson(),
            "urlInspections" to urlInspectionsJson(),
            "shortlistPipeline" to scenarios.map { shortlistJson(it, profile, preferences) },
            "intelligence" to scenarios.map { intelligenceJson(it, profile, preferences) },
            "coverLetters" to coverLettersJson(profile),
            "interview" to scenarios.map { interviewJson(it, profile) },
            "aiCapabilities" to aiCapabilitiesJson(),
            "freshness" to freshnessJson(),
            "freshnessWindow" to freshnessWindowJson(),
            "availabilityFixture" to availabilityFixtureJson(),
            "expatOpportunity" to scenarios.map { expatOpportunityJson(it, profile, preferences) },
            "queryExpansion" to queryExpansionJson(),
            "searchStrategy" to searchStrategyJson(),
            "relevance" to relevanceJson(),
            "expatRanking" to expatRankingJson(scenarios, profile, preferences),
            "alertEngine" to alertEngineJson(),
            "autoApply" to autoApplyJson(scenarios),
            "submission" to submissionJson(scenarios),
            "cvAnalysis" to cvAnalysisJson(),
        )

        val json = JsonWriter.write(root)
        val output = File(repoRoot(), "web/src/core/parity/kotlin-parity.json")
        output.parentFile.mkdirs()
        output.writeText(json + "\n")

        // Also keep a copy under the module build dir for inspection during a Gradle run.
        File("build/parity/kotlin-parity.json").apply { parentFile.mkdirs() }.writeText(json + "\n")

        assertTrue("fixture should be non-trivial", json.length > 1_000)
    }

    // ------------------------------------------------------------------ scenarios

    private fun scenarioJson(
        case: Case,
        profile: CandidateProfile,
        preferences: ExpatPreferences,
    ): Map<String, Any?> {
        val role = RoleClassifier.classify(case.title, case.description)
        val eligibility = EligibilityEngine().assess(
            EligibilityInput(
                title = case.title,
                location = case.country,
                description = case.description,
                tags = case.tags,
                employmentType = case.employmentType,
                isRemote = case.isRemote,
                isRecognizedCountry = case.isRecognizedCountry,
            ),
        )
        val matching = MatchingEngine().assess(
            MatchInput(
                title = case.title,
                country = case.country,
                description = case.description,
                tags = case.tags,
                postedDaysAgo = case.postedDaysAgo,
            ),
            profile,
        )
        val requirements = RequirementAnalyzer.analyze(case.description)
        val gaps = QualificationMatcher.gaps(requirements, profile)
        val skills = SkillLexicon.detect("${case.title} ${case.description}").toList().sorted()

        val benefits = ExpatBenefitExtractor().extract(
            title = case.title,
            description = case.description,
            tags = case.tags,
            source = case.source,
            verifiedAtEpochMillis = fixedNow,
        )
        val salaryEurPerYear =
            if (case.salaryMin > 0) CurrencyConverter.toEurPerYear(case.salaryMin, case.currency, case.period) else null
        val expat = ExpatBenefitsEngine().assess(benefits, preferences, salaryEurPerYear)

        val job = Job(
            id = case.id,
            title = case.title,
            company = case.company,
            companyAccent = 0xFF1E4FD8,
            country = case.country,
            countryFlag = "🌍",
            city = case.city,
            salaryMin = case.salaryMin,
            salaryMax = case.salaryMax,
            currencyCode = case.currency,
            salaryPeriod = case.period,
            matchScore = matching.score,
            eligibility = eligibility.eligibility,
            visaSupport = eligibility.visaSupport,
            scamRisk = ScamRisk.LOW,
            postedAtEpochMillis = case.postedDaysAgo?.let { fixedNow - it * 86_400_000L },
            expiresAtEpochMillis = case.expiresAtEpochMillis,
            retrievedAtEpochMillis = fixedNow,
            source = case.source,
            sourceUrl = case.url,
            workMode = if (case.isRemote) WorkMode.REMOTE else WorkMode.ONSITE,
            employmentType = EmploymentType.FULL_TIME,
            experienceYears = requirements.minYears ?: 0,
            matchedSkills = matching.matchedSkills,
            missingSkills = matching.missingSkills,
            languages = case.languages,
            summary = case.description,
            responsibilities = emptyList(),
            benefits = emptyList(),
            aiInsight = "",
            providerId = case.providerId,
            salaryPeriodInferred = false,
            salaryUnitUncertain = case.salaryUnitUncertain,
            matchReasons = matching.reasons,
            eligibilityReasons = eligibility.reasons,
            benefitEvidence = benefits,
            roleFamily = role.family,
            matchedTargetRole = role.matchedTarget?.canonical,
            requirements = requirements,
            qualificationGaps = gaps,
            evidence = emptyList(),
            openToApplicants = null,
        )

        val fit = InternationalFitEngine().assess(job, profile, expat)
        val evidence = JobEvidenceEngine().build(
            eligibility = eligibility.eligibility,
            visaSupport = eligibility.visaSupport,
            eligibilityReasons = eligibility.reasons,
            benefits = benefits,
            gaps = gaps,
            sourceUrl = case.url,
        )
        val scam = ScamDetector { fixedNow }.scan(job)

        return linkedMapOf(
            "id" to case.id,
            "input" to linkedMapOf(
                "title" to case.title,
                "country" to case.country,
                "city" to case.city,
                "description" to case.description,
                "tags" to case.tags,
                "employmentType" to case.employmentType,
                "isRemote" to case.isRemote,
                "isRecognizedCountry" to case.isRecognizedCountry,
                "postedDaysAgo" to case.postedDaysAgo,
                "company" to case.company,
                "source" to case.source,
                "providerId" to case.providerId,
                "url" to case.url,
                "salaryMin" to case.salaryMin,
                "salaryMax" to case.salaryMax,
                "currency" to case.currency,
                "salaryPeriod" to case.period.name,
                "salaryUnitUncertain" to case.salaryUnitUncertain,
                "languages" to case.languages,
                "closedToNewApplicants" to case.closedToNewApplicants,
                "expiresAtEpochMillis" to case.expiresAtEpochMillis,
            ),
            "output" to linkedMapOf(
                "role" to linkedMapOf(
                    "family" to role.family.name,
                    "matchedTarget" to role.matchedTarget?.canonical,
                    "evidence" to role.evidence,
                ),
                "eligibility" to linkedMapOf(
                    "eligibility" to eligibility.eligibility.name,
                    "visaSupport" to eligibility.visaSupport.name,
                    "hasOfficialSignal" to eligibility.hasOfficialSignal,
                    "reasons" to eligibility.reasons.map {
                        linkedMapOf("label" to it.label, "detail" to it.detail, "official" to it.official)
                    },
                ),
                "matching" to linkedMapOf(
                    "score" to matching.score,
                    "matchedSkills" to matching.matchedSkills,
                    "missingSkills" to matching.missingSkills,
                    "requiredYears" to matching.requiredYears,
                    "requiredLanguages" to matching.requiredLanguages,
                    "reasons" to matching.reasons.map {
                        linkedMapOf(
                            "label" to it.label,
                            "detail" to it.detail,
                            "signal" to it.signal.name,
                            "weight" to it.weight,
                        )
                    },
                ),
                "requirements" to requirementsJson(requirements),
                "gaps" to gaps.map {
                    linkedMapOf("label" to it.label, "evidence" to it.evidence, "met" to it.met)
                },
                "skills" to skills,
                "salaryEurPerYear" to salaryEurPerYear,
                "expatBenefits" to expatJson(expat),
                "internationalFit" to fitJson(fit),
                "evidence" to evidence.map {
                    linkedMapOf(
                        "kind" to it.kind.name,
                        "strength" to it.strength.name,
                        "label" to it.label,
                        "detail" to it.detail,
                        "sourceUrl" to it.sourceUrl,
                    )
                },
                "scam" to linkedMapOf(
                    "risk" to scam.risk.name,
                    "verdict" to scam.verdict.name,
                    "safetyScore" to scam.safetyScore,
                    "signals" to scam.signals.map {
                        linkedMapOf(
                            "label" to it.label,
                            "detail" to it.detail,
                            "evidence" to it.evidence,
                            "tone" to it.tone.name,
                        )
                    },
                ),
                "job" to jobJson(job),
                "verification" to verificationJson(
                    OfferVerificationEngine().assess(job, fixedNow),
                ),
                // Étape B: the single-question availability read, pinned to the same fixed
                // clock as the verification panel so the web mirror can be checked against it.
                // Only the network-free indicators are used here (source flag, deadline), so
                // the golden output is deterministic and needs no request.
                "availability" to availabilityJson(
                    com.aiglobal.jobfinder.data.jobs.availability.OfferAvailabilityEngine()
                        .assess(job, fixedNow),
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ stage 62: pipeline golden parity

    /**
     * Stage 62 — golden fixtures for the shortlist → intelligence → plan pipeline.
     *
     * These three engines sit on top of the ones exported above and are the ones the web
     * dashboard, jobs screen and AI assistant read from. They are driven here over the same
     * scenarios, plus the real profile-completeness scorer, and every output is serialised so
     * the vitest suite can replay the identical inputs through the TypeScript ports.
     */
    private fun shortlistJson(
        scenario: Map<String, Any?>,
        profile: CandidateProfile,
        preferences: ExpatPreferences,
    ): Map<String, Any?> {
        val input = scenario["input"] as Map<*, *>
        val job = jobFromJson(scenario["output"] as Map<*, *>, input)

        val completeness = CvCompleteness.of(profile)
        val salaryEur = (scenario["output"] as Map<*, *>)["salaryEurPerYear"] as Int?
        val entry = ShortlistEngine { fixedNow }.assess(
            job = job,
            profile = profile,
            preferences = preferences,
            profileCompletenessPercent = completeness.percent,
            salaryEurPerYear = salaryEur,
        )

        // Two variants exercise the plan's branching: a bare entry with no cover letter and no
        // interview rehearsal, and a fully prepared one with a caution verdict.
        val barePlan = ApplicationPlanEngine { fixedNow }.build(
            job = job,
            entry = entry,
            profileCompletenessPercent = completeness.percent,
            hasCoverLetter = false,
        )
        val preparedPlan = ApplicationPlanEngine { fixedNow }.build(
            job = job,
            entry = entry,
            profileCompletenessPercent = completeness.percent,
            hasCoverLetter = true,
            interviewAttempted = 3,
            interviewTotal = 3,
            scamVerdict = com.aiglobal.jobfinder.data.model.ScamVerdict.NEEDS_CAUTION,
            hasApplied = true,
        )

        return linkedMapOf(
            "id" to scenario["id"],
            "profileCompleteness" to completenessJson(completeness),
            "entry" to entryJson(entry),
            "plan" to planJson(barePlan),
            "preparedPlan" to planJson(preparedPlan),
        )
    }

    private fun intelligenceJson(
        scenario: Map<String, Any?>,
        profile: CandidateProfile,
        preferences: ExpatPreferences,
    ): Map<String, Any?> {
        val input = scenario["input"] as Map<*, *>
        val job = jobFromJson(scenario["output"] as Map<*, *>, input)
        val completeness = CvCompleteness.of(profile)
        val salaryEur = (scenario["output"] as Map<*, *>)["salaryEurPerYear"] as Int?

        val entry = ShortlistEngine { fixedNow }.assess(
            job = job,
            profile = profile,
            preferences = preferences,
            profileCompletenessPercent = completeness.percent,
            salaryEurPerYear = salaryEur,
        )
        // A single-entry state and a two-entry state (the same posting twice under different
        // countries) so the country roll-up, sorting and summary text are all exercised.
        val single = ShortlistState(
            isLoading = false,
            entries = listOf(entry),
            totalJobs = 1,
            generatedAtEpochMillis = fixedNow,
        )
        val duplicated = ShortlistState(
            isLoading = false,
            entries = listOf(entry, entry.copy(job = job.copy(country = "Germany", countryFlag = "🇩🇪"))),
            totalJobs = 2,
            generatedAtEpochMillis = fixedNow,
        )
        val empty = ShortlistState(isLoading = false, entries = emptyList(), totalJobs = 0, generatedAtEpochMillis = fixedNow)

        return linkedMapOf(
            "id" to scenario["id"],
            "single" to intelligenceOf(single),
            "duplicated" to intelligenceOf(duplicated),
            "empty" to intelligenceOf(empty),
        )
    }

    private fun intelligenceOf(state: ShortlistState): Map<String, Any?> {
        val intel = ExpatIntelligenceEngine { fixedNow }.build(state)
        return intelligenceJsonValue(intel)
    }

    private fun completenessJson(completeness: ProfileCompleteness): Map<String, Any?> = linkedMapOf(
        "percent" to completeness.percent,
        "missing" to completeness.missing.map {
            linkedMapOf("label" to it.label, "field" to it.field.name)
        },
    )

    private fun entryJson(entry: ShortlistEntry): Map<String, Any?> = linkedMapOf(
        "readinessScore" to entry.readinessScore,
        "readiness" to entry.readiness.name,
        "matchScore" to entry.matchScore,
        "expatScore" to entry.expatScore,
        "expatEvidenceCoverage" to entry.expatEvidenceCoverage,
        "benefitsMatched" to entry.benefitsMatched,
        "benefitsConsidered" to entry.benefitsConsidered,
        "hasOfficialRoute" to entry.hasOfficialRoute,
        "blockers" to entry.blockers.map { blockerJson(it) },
        "orderedBlockers" to entry.orderedBlockers.map { blockerJson(it) },
        "highlights" to entry.highlights,
        "rankComponents" to entry.rankComponents.map {
            linkedMapOf("label" to it.label, "detail" to it.detail, "points" to it.points, "maxPoints" to it.maxPoints)
        },
        "nextStep" to linkedMapOf(
            "label" to entry.nextStep.label,
            "detail" to entry.nextStep.detail,
            "action" to entry.nextStep.action.name,
        ),
        "blockerSummary" to entry.blockerSummary,
    )

    private fun blockerJson(blocker: com.aiglobal.jobfinder.data.model.ReadinessBlocker): Map<String, Any?> =
        linkedMapOf(
            "label" to blocker.label,
            "detail" to blocker.detail,
            "kind" to blocker.kind.name,
            "points" to blocker.points,
            "isProfileGap" to blocker.isProfileGap,
        )

    private fun planJson(plan: ApplicationPlan): Map<String, Any?> = linkedMapOf(
        "jobId" to plan.jobId,
        "jobTitle" to plan.jobTitle,
        "company" to plan.company,
        "country" to plan.country,
        "matchScore" to plan.matchScore,
        "readinessScore" to plan.readinessScore,
        "readiness" to plan.readiness.name,
        "steps" to plan.steps.map {
            linkedMapOf(
                "kind" to it.kind.name,
                "status" to it.status.name,
                "title" to it.title,
                "detail" to it.detail,
                "action" to it.action,
                "weight" to it.weight,
                "official" to it.official,
            )
        },
        "tailoring" to linkedMapOf(
            "jobTitle" to plan.tailoring.jobTitle,
            "leadSkills" to plan.tailoring.leadSkills.map {
                linkedMapOf("skill" to it.skill, "detail" to it.detail)
            },
            "addressSkills" to plan.tailoring.addressSkills,
            "keywords" to plan.tailoring.keywords,
            "note" to plan.tailoring.note,
        ),
        "documents" to plan.documents.map {
            linkedMapOf("label" to it.label, "detail" to it.detail, "required" to it.required)
        },
        "routeSummary" to plan.routeSummary,
        "nextAction" to plan.nextAction,
        "hasCoverLetter" to plan.hasCoverLetter,
        "scamVerdict" to plan.scamVerdict?.name,
        "doneCount" to plan.doneCount,
        "totalCount" to plan.totalCount,
        "progressPercent" to plan.progressPercent,
        "isReadyToApply" to plan.isReadyToApply,
        "openSteps" to plan.openSteps.map { it.kind.name },
        "nextStep" to plan.nextStep?.kind?.name,
    )

    private fun intelligenceJsonValue(intel: ExpatIntelligence): Map<String, Any?> = linkedMapOf(
        "totalJobs" to intel.totalJobs,
        "actionableCount" to intel.actionableCount,
        "priorityCount" to intel.priorityCount,
        "officialRouteCount" to intel.officialRouteCount,
        "countries" to intel.countries.map {
            linkedMapOf(
                "country" to it.country,
                "countryFlag" to it.countryFlag,
                "actionableCount" to it.actionableCount,
                "totalCount" to it.totalCount,
                "bestReadiness" to it.bestReadiness,
                "hasOfficialRoute" to it.hasOfficialRoute,
            )
        },
        "recurringProfileGaps" to intel.recurringProfileGaps.map {
            linkedMapOf("label" to it.label, "kind" to it.kind.name, "points" to it.points)
        },
        "topOpportunities" to intel.topOpportunities.map { it.job.id },
        "summary" to intel.summary,
    )

    // ------------------------------------------------------------------ stage 62 (suite): cover letter / interview / AI catalog

    /**
     * Stage 62 — golden fixtures for the three remaining on-device engines the web edition
     * mirrors: the cover-letter writer, the interview coach and the AI capability catalogue.
     *
     * The clock and the id factory are fixed so the outputs are byte-stable: the web suite
     * replays the identical inputs through the TypeScript ports and compares the strings.
     */
    private fun coverLettersJson(profile: CandidateProfile): Map<String, Any?> {
        val generator = CoverLetterGenerator(clock = { fixedNow }, idFactory = { "letter-fixed" })
        val languageByCountry = listOf("Canada", "Germany", "France", "Saudi Arabia").associateWith {
            LetterLanguage.forCountry(it)
        }
        return linkedMapOf(
            "generatorClockMillis" to fixedNow,
            "languageByCountry" to languageByCountry.mapValues { it.value.name },
            "letters" to cases.map { case ->
                val job = jobForCase(case)
                linkedMapOf(
                    "jobId" to case.id,
                    "languages" to LetterLanguage.entries.map { language ->
                        linkedMapOf(
                            "language" to language.name,
                            "letter" to coverLetterJson(generator.generate(job, profile, language)),
                        )
                    },
                )
            },
        )
    }

    private fun coverLetterJson(letter: CoverLetter): Map<String, Any?> = linkedMapOf(
        "id" to letter.id,
        "jobId" to letter.jobId,
        "language" to letter.language.name,
        "subject" to letter.subject,
        "salutation" to letter.salutation,
        "paragraphs" to letter.paragraphs,
        "closing" to letter.closing,
        "signature" to letter.signature,
        "usedFacts" to letter.usedFacts,
        "missingFacts" to letter.missingFacts,
        "generatedAtEpochMillis" to letter.generatedAtEpochMillis,
        "edited" to letter.edited,
        "plainText" to letter.asPlainText(),
        "wordCount" to letter.wordCount,
    )

    private fun interviewJson(scenario: Map<String, Any?>, profile: CandidateProfile): Map<String, Any?> {
        val input = scenario["input"] as Map<*, *>
        val job = jobFromJson(scenario["output"] as Map<*, *>, input)

        val prep = InterviewCoach(clock = { fixedNow }).prep(job, profile)
        val focusAreas = InterviewQuestionBank().focusAreas(job, profile)

        // Two rehearsals: a partial one (some questions answered, mixed confidence) and a full
        // one (every question answered confidently). Both are replayed by the web suite.
        val partialAttempts = prep.questions.filterIndexed { index, _ -> index % 2 == 0 }.mapIndexed { index, q ->
            InterviewAttempt(
                questionId = q.id,
                confidence = if (index % 3 == 0) AnswerConfidence.CONFIDENT else AnswerConfidence.GETTING_THERE,
                atEpochMillis = fixedNow,
            )
        }
        val fullAttempts = prep.questions.map {
            InterviewAttempt(questionId = it.id, confidence = AnswerConfidence.CONFIDENT, atEpochMillis = fixedNow)
        }

        val coach = InterviewCoach(clock = { fixedNow })
        val firstQuestion = prep.questions.first()

        return linkedMapOf(
            "id" to scenario["id"],
            "focusAreas" to focusAreas,
            "prep" to linkedMapOf(
                "jobId" to prep.jobId,
                "jobTitle" to prep.jobTitle,
                "company" to prep.company,
                "country" to prep.country,
                "countryFlag" to prep.countryFlag,
                "generatedAtEpochMillis" to prep.generatedAtEpochMillis,
                "questionCount" to prep.questionCount,
                "questions" to prep.questions.map { questionJson(it) },
            ),
            "readiness" to linkedMapOf(
                "none" to readinessJson(coach.readiness(prep.questions, emptyList())),
                "partial" to readinessJson(coach.readiness(prep.questions, partialAttempts)),
                "full" to readinessJson(coach.readiness(prep.questions, fullAttempts)),
            ),
            "feedback" to listOf(
                "I used a hydraulic press for 3 years and reduced pump failures by 40 percent. " +
                    "First I inspected the seals, then I repaired the leaking joints, and the result was fewer stoppages. " +
                    "My team delivered the overhaul on time because we planned the parts early and tested every unit before restart.",
                "I fixed a pump.",
                "I managed a team. We improved results.",
            ).map { answer ->
                linkedMapOf(
                    "questionId" to firstQuestion.id,
                    "answer" to answer,
                    "feedback" to feedbackJson(coach.feedback(firstQuestion, answer)),
                )
            },
        )
    }

    private fun questionJson(q: InterviewQuestion): Map<String, Any?> = linkedMapOf(
        "id" to q.id,
        "category" to q.category.name,
        "prompt" to q.prompt,
        "whyItMatters" to q.whyItMatters,
        "structure" to q.structure,
        "grounding" to q.grounding,
        "missingFacts" to q.missingFacts,
    )

    private fun readinessJson(r: com.aiglobal.jobfinder.data.model.InterviewReadiness): Map<String, Any?> = linkedMapOf(
        "attempted" to r.attempted,
        "total" to r.total,
        "confident" to r.confident,
        "percent" to r.percent,
        "isComplete" to r.isComplete,
        "label" to r.label,
    )

    private fun feedbackJson(f: com.aiglobal.jobfinder.data.model.AnswerFeedback): Map<String, Any?> = linkedMapOf(
        "score" to f.score,
        "headline" to f.headline,
        "strengths" to f.strengths,
        "improvements" to f.improvements,
    )

    private fun aiCapabilitiesJson(): Map<String, Any?> = linkedMapOf(
        "hasOnDeviceModel" to AiCapabilityCatalog.HAS_ON_DEVICE_MODEL,
        "hasRemoteModel" to AiCapabilityCatalog.HAS_REMOTE_MODEL,
        "summary" to AiCapabilityCatalog.summary,
        "capabilities" to AiCapabilityCatalog.capabilities.map { capabilityJson(it) },
    )

    private fun capabilityJson(c: AiCapability): Map<String, Any?> = linkedMapOf(
        "name" to c.name,
        "kind" to c.kind.name,
        "kindLabel" to c.kind.label,
        "location" to c.location.name,
        "locationLabel" to c.location.label,
        "description" to c.description,
        "note" to c.note,
    )

    // ------------------------------------------------------------------ stage 65: freshness & expatriate opportunity

    /**
     * Stage 65 — golden fixtures for the two engines ported / verified here.
     *
     * [freshnessJson] drives the real `JobFreshnessEngine` over boundary and missing-data
     * cases: a null publication date, a future date (a source clock fault), the 2/14/stale
     * day boundaries, a custom window, and a listing with no retrieval date. [expatOpportunityJson]
     * drives the real `ExpatOpportunityEngine` over the shared scenarios, each with its real
     * `InternationalFitAssessment`, so the TypeScript port is pinned to the Kotlin output for
     * the same input. Neither engine writes anything: a missing date stays missing.
     */
    private fun freshnessJson(): List<Map<String, Any?>> {
        val engine = JobFreshnessEngine()
        val custom = JobFreshnessEngine(staleAfterDays = 30)

        fun at(daysAgo: Long?): Long? = daysAgo?.let { fixedNow - it * 86_400_000L }

        data class FreshnessCase(
            val id: String,
            val postedAtEpochMillis: Long?,
            val retrievedAtEpochMillis: Long?,
        )

        val cases = listOf(
            FreshnessCase("null-date", null, fixedNow),
            FreshnessCase("future-date", fixedNow + 3 * 86_400_000L, fixedNow),
            FreshnessCase("epoch-zero", 0L, fixedNow),
            FreshnessCase("boundary-fresh-1d", at(1), fixedNow),
            FreshnessCase("boundary-recent-2d", at(2), fixedNow),
            FreshnessCase("boundary-recent-13d", at(13), fixedNow),
            FreshnessCase("boundary-aging-14d", at(14), fixedNow),
            FreshnessCase("boundary-aging-120d", at(120), fixedNow),
            FreshnessCase("boundary-stale-121d", at(121), fixedNow),
            FreshnessCase("no-retrieval-date", at(10), null),
        )

        // A single base job whose dates are overwritten per case, so the engine sees a real
        // posting rather than a synthetic stub. The freshness engine only reads the dates.
        val baseJob = jobForCase(this.cases.first())

        return cases.map { case ->
            val jobForCase = baseJob.copy(
                postedAtEpochMillis = case.postedAtEpochMillis,
                retrievedAtEpochMillis = case.retrievedAtEpochMillis,
            )
            val assessment = engine.assess(jobForCase, fixedNow)
            linkedMapOf(
                "id" to case.id,
                "input" to linkedMapOf(
                    "postedAtEpochMillis" to case.postedAtEpochMillis,
                    "retrievedAtEpochMillis" to case.retrievedAtEpochMillis,
                ),
                "output" to linkedMapOf(
                    "freshness" to assessment.freshness.name,
                    "label" to assessment.label,
                    "detail" to assessment.detail,
                ),
                "custom30" to custom.assess(jobForCase, fixedNow).freshness.name,
            )
        }
    }

    /**
     * Stage A — the "recent and still open" date window. Pins the Kotlin [FreshnessWindow]
     * predicate against a fixed clock so the TypeScript mirror must agree, and proves the rule
     * that matters most: an undated, zero or future date is never counted as recent by any
     * window, while a real date is included up to and including the window's last day.
     */
    private fun freshnessWindowJson(): List<Map<String, Any?>> {
        fun at(daysAgo: Long): Long = fixedNow - daysAgo * 86_400_000L
        val cases = listOf(
            "null" to null,
            "zero" to 0L,
            "future" to (fixedNow + 3 * 86_400_000L),
            "today" to at(0),
            "day-7" to at(7),
            "day-8" to at(8),
            "day-14" to at(14),
            "day-15" to at(15),
            "day-30" to at(30),
            "day-31" to at(31),
        )
        return cases.map { (id, posted) ->
            linkedMapOf(
                "id" to id,
                "postedAtEpochMillis" to posted,
                "sevenDays" to FreshnessWindow.SEVEN_DAYS.includes(posted, fixedNow),
                "fourteenDays" to FreshnessWindow.FOURTEEN_DAYS.includes(posted, fixedNow),
                "thirtyDays" to FreshnessWindow.THIRTY_DAYS.includes(posted, fixedNow),
            )
        }
    }

    private fun expatOpportunityJson(
        scenario: Map<String, Any?>,
        profile: CandidateProfile,
        preferences: ExpatPreferences,
    ): Map<String, Any?> {
        val input = scenario["input"] as Map<*, *>
        val output = scenario["output"] as Map<*, *>
        val job = jobFromJson(output, input)
        val salaryEur = output["salaryEurPerYear"] as Int?

        // Rebuild the exact fit the scenario produced, so the opportunity engine sees the
        // same InternationalFitAssessment the pipeline did.
        val expatAssessment = ExpatBenefitsEngine().assess(job.benefitEvidence, preferences, salaryEur)
        val fit = InternationalFitEngine().assess(job, profile, expatAssessment)

        val engine = ExpatOpportunityEngine { fixedNow }
        val opportunity = engine.assess(job, preferences, fit)

        // Variant cases that exercise the honesty rules, all on the same posting:
        //  - as-is (the real fit),
        //  - with no fit at all (a posting opened before a CV is analysed),
        //  - with a high scam risk (must cap the score and disqualify),
        //  - restricted eligibility (a hard stop),
        //  - an expired application link (a hard stop).
        val noFit = engine.assess(job, preferences, null)
        val scammy = engine.assess(job.copy(scamRisk = ScamRisk.HIGH), preferences, fit)
        val restricted = engine.assess(
            job.copy(eligibility = com.aiglobal.jobfinder.data.model.MadagascarEligibility.RESTRICTED),
            preferences,
            fit,
        )
        val deadLink = engine.assess(
            job.copy(
                verification = job.verification.copy(
                    link = com.aiglobal.jobfinder.data.model.VerificationState.INACCESSIBLE,
                ),
            ),
            preferences,
            fit,
        )

        return linkedMapOf(
            "id" to scenario["id"],
            "opportunity" to opportunityJson(opportunity),
            "noFit" to opportunityJson(noFit),
            "highScamRisk" to opportunityJson(scammy),
            "restrictedEligibility" to opportunityJson(restricted),
            "expiredLink" to opportunityJson(deadLink),
        )
    }

    private fun opportunityJson(o: com.aiglobal.jobfinder.data.model.ExpatOpportunity): Map<String, Any?> = linkedMapOf(
        "score" to o.score,
        "band" to o.band.name,
        "confidence" to o.confidence.name,
        "confidenceNote" to o.confidenceNote,
        "hardStops" to o.hardStops,
        "disqualified" to o.disqualified,
        "labels" to o.labels,
        "routeSummary" to o.routeSummary,
        "explanation" to o.explanation,
        "dimensions" to o.dimensions.map {
            linkedMapOf(
                "dimension" to it.dimension.name,
                "points" to it.points,
                "maxPoints" to it.maxPoints,
                "summary" to it.summary,
                "strength" to it.strength.name,
            )
        },
        "claims" to o.claims.map {
            linkedMapOf(
                "label" to it.label,
                "finding" to it.finding.name,
                "evidence" to it.evidence,
                "sourceUrl" to it.sourceUrl,
                "sourceType" to it.sourceType,
                "explicit" to it.explicit,
                "limitations" to it.limitations,
            )
        },
        "salary" to o.salary?.let {
            linkedMapOf(
                "originalAmount" to it.originalAmount,
                "originalCurrency" to it.originalCurrency,
                "originalPeriod" to it.originalPeriod.name,
                "eurPerYear" to it.eurPerYear,
                "rateSource" to it.rateSource,
                "rateAsOfEpochMillis" to it.rateAsOfEpochMillis,
                "converted" to it.converted,
                "note" to it.note,
            )
        },
    )

    // ------------------------------------------------------------------ stage 66: query expansion, search strategy & expat ranking

    /**
     * Stage 66 — golden fixtures for the three capabilities ported here.
     *
     * [queryExpansionJson] drives the real `QueryExpansion` over the taxonomy's own queries plus
     * partial input and near-misses, so the TypeScript port is pinned to the same whole-word and
     * word-prefix behaviour. [searchStrategyJson] runs `SearchStrategy` against the **real**
     * provider instances and **real** diagnostics, so the plan (server-side vs broad sample) and
     * the coverage wording are the production output, not a paraphrase. [expatRankingJson] runs
     * the real `ExpatRankingEngine` over the real shortlist of every scenario, so the rank,
     * labels, reasons and counts are exactly what the Android screen shows.
     */
    private fun queryExpansionJson(): Map<String, Any?> {
        val queries = listOf(
            "Mechanical Seal Technician", // the original failing query
            "pump",
            "Pump Technician",
            "rotating equipment",
            "rotat", // partial input, matches as a word prefix
            "maintenance",
            "diesel mechanic",
            "vibration analysis",
            "Marketing", // a non-trade query
            "pumpkin", // a complete term must not fire inside another word
            "pum", // partial, below the three-character floor
            "  ", // blank
        )
        val haystacks = listOf(
            "Rotating Equipment Maintenance Technician",
            "Mechanical Seal Fitter",
            "Pump Technician",
            "Field Service Engineer",
            "Frontend Developer",
            "Heavy Equipment Diesel Mechanic",
            "Pumpkin Farmer",
        )
        return linkedMapOf(
            "knownPhrases" to QueryExpansion.knownPhrases().sorted(),
            "cases" to queries.map { query ->
                linkedMapOf(
                    "query" to query,
                    "expanded" to QueryExpansion.expand(query),
                    "isTrade" to QueryExpansion.isTradeQuery(query),
                    "matches" to haystacks.associateWith { QueryExpansion.matches(query, it) },
                )
            },
        )
    }

    private fun searchStrategyJson(): Map<String, Any?> {
        val titles = buildProfile().let {
            (listOf(it.currentTitle) + it.previousTitles + it.preferredRoles).filter { t -> t.isNotBlank() }
        }
        val providers: List<JobProvider> = listOf(
            ArbeitnowProvider(),
            RemotiveProvider(),
            JobicyProvider(),
            HimalayasProvider(),
            EuresProvider(),
            WorkdayProvider(),
            GreenhouseProvider(),
            AshbyProvider(),
            LeverProvider(),
            SmartRecruitersProvider(),
            AdzunaProvider(credentials = { AdzunaCredentials() }),
            JoobleProvider(apiKey = { "" }),
        )
        val plans = providers.map { provider ->
            val plan = SearchStrategy.planFor(provider, titles)
            linkedMapOf(
                "providerId" to plan.providerId,
                "providerName" to plan.providerName,
                "serverSideSearch" to plan.serverSideSearch,
                "broadSample" to plan.broadSample,
                "keywordSets" to plan.keywordSets,
            )
        }

        // A hand-built diagnostics list over the same provider ids, so the coverage report is
        // driven by the real `coverageFrom` with realistic counts (including a source that
        // returned postings none of which matched the trade).
        val relevant = linkedMapOf(
            "adzuna" to 4, "eures" to 3, "smartrecruiters" to 2,
        )
        val totals = linkedMapOf(
            "arbeitnow" to 60, "remotive" to 40, "jobicy" to 35, "himalayas" to 50,
            "eures" to 12, "workday" to 18, "greenhouse" to 9, "ashby" to 6,
            "lever" to 7, "smartrecruiters" to 11, "adzuna" to 20, "jooble" to 25,
        )
        val diagnostics = providers.map { provider ->
            ProviderDiagnostic(
                providerId = provider.id,
                name = provider.name,
                homepage = provider.homepage,
                documentationUrl = provider.documentationUrl,
                dataSource = DataSourceKind.LIVE,
                status = ProviderStatus.SUCCESS_WITH_RESULTS,
                enabled = true,
                serverSideSearch = provider.capabilities.serverSideKeyword,
                validJobCount = totals[provider.id] ?: 0,
            )
        }
        val coverage = SearchStrategy.coverageFrom(diagnostics, relevant, totals)
        val emptyCoverage = SearchStrategy.coverageFrom(emptyList(), emptyMap(), emptyMap())

        return linkedMapOf(
            "titles" to titles,
            "plans" to plans,
            "coverage" to linkedMapOf(
                "sources" to coverage.sources.map {
                    linkedMapOf(
                        "providerId" to it.providerId,
                        "providerName" to it.providerName,
                        "targeted" to it.targeted,
                        "relevantCount" to it.relevantCount,
                        "totalCount" to it.totalCount,
                    )
                },
                "totalRelevant" to coverage.totalRelevant,
                "totalRetrieved" to coverage.totalRetrieved,
                "targetedSourceCount" to coverage.targetedSourceCount,
                "broadSampleSourceCount" to coverage.broadSampleSourceCount,
                "hasTargetedSource" to coverage.hasTargetedSource,
                "hasRelevantResults" to coverage.hasRelevantResults,
                "relevantSharePercent" to coverage.relevantSharePercent,
                "summary" to coverage.summary,
            ),
            "emptyCoverage" to linkedMapOf(
                "totalRelevant" to emptyCoverage.totalRelevant,
                "totalRetrieved" to emptyCoverage.totalRetrieved,
                "summary" to emptyCoverage.summary,
            ),
        )
    }

    /**
     * Stage 70 — the live relevance filter and the shortlist correction, both driven by the
     * real engines over the shared scenario titles so the web port can replay the same verdicts.
     */
    private fun relevanceJson(): Map<String, Any?> {
        // Classify every scenario title (with its description head) and a few extra titles that
        // pin the boundary cases: a genuine trade, a maintenance-family role, an unrelated role
        // whose description merely mentions maintenance, and a title too thin to judge.
        val extraTitles = listOf(
            Triple("Maintenance Technician", "Preventive maintenance on pumps and hydraulics.", false),
            Triple("Software Engineer, Maintenance Tooling", "Build tooling for the maintenance team.", false),
            Triple("Lab Technician", "Industrial manufacturer. ISO 9001 quality control.", false),
            Triple("Industrial Maintenance Technician", "Remote worldwide role.", false),
            Triple("Millwright", "Install and align rotating equipment at a mine site.", false),
            // Stage 71 — the live European sample names the same trades in German, Dutch and
            // Norwegian. These pin that a genuine trade title is kept regardless of language,
            // and that a lone foreign "technician"/"electrician"/"painter" is still excluded.
            Triple("Instandhaltungsmechaniker Rotating Equipment (w/m/d)", "Sulzer, Schkopau.", false),
            Triple("Onderhoudstechnieker | 3 Ploegen", "Productiemachines onderhouden.", false),
            Triple("Vedlikeholdstekniker", "Vedlikehold på roterende utstyr.", false),
            Triple("Servicetechniker", "Service an Pumpen und Kompressoren.", false),
            Triple("Monteur middenspanning", "Elektrische installaties.", false),
            Triple("Elektricien spoorwegen Antwerpen", "Spoorweginfrastructuur.", false),
            Triple("Schilder", "Schilderwerk binnenshuis.", false),
        )
        val titled = cases.map { Triple(it.title, it.description, true) } + extraTitles
        val verdicts = titled.map { (title, description, fromScenario) ->
            val head = description.take(400)
            val relevance = RoleClassifier.classify(title, head)
            linkedMapOf(
                "title" to title,
                "descriptionHead" to head,
                "fromScenario" to fromScenario,
                "family" to relevance.family.name,
                "matchedTarget" to relevance.matchedTarget?.canonical,
                "isMaintenanceRelevant" to relevance.isMaintenanceFamily,
                "evidence" to relevance.evidence,
            )
        }

        // Feed the ranked scenario jobs through the real filter and record exactly which
        // survived, so the port's `relevantJobs`/`jobIsMaintenanceRelevant` must agree.
        val scenarioJobs = cases.map { case -> jobForCase(case) }
        val relevant = RelevanceFeed.relevant(scenarioJobs)
        val unrelated = RelevanceFeed.unrelated(scenarioJobs)

        return linkedMapOf(
            "verdicts" to verdicts,
            "feed" to linkedMapOf(
                "total" to scenarioJobs.size,
                "keptIds" to relevant.map { it.id },
                "droppedIds" to unrelated.map { it.id },
                "summary" to RelevanceFeed.summary(scenarioJobs.size, relevant.size),
                "emptySummary" to RelevanceFeed.summary(0, 0),
                "noneMatchedSummary" to RelevanceFeed.summary(3, 0),
                "allMatchedSummary" to RelevanceFeed.summary(2, 2),
            ),
        )
    }

    private fun expatRankingJson(
        scenarios: List<Map<String, Any?>>,
        profile: CandidateProfile,
        preferences: ExpatPreferences,
    ): Map<String, Any?> {
        val completeness = CvCompleteness.of(profile).percent
        val entries = scenarios.map { scenario ->
            val input = scenario["input"] as Map<*, *>
            val job = jobFromJson(scenario["output"] as Map<*, *>, input)
            val salaryEur = (scenario["output"] as Map<*, *>)["salaryEurPerYear"] as Int?
            ShortlistEngine { fixedNow }.assess(
                job = job,
                profile = profile,
                preferences = preferences,
                profileCompletenessPercent = completeness,
                salaryEurPerYear = salaryEur,
            )
        }

        fun stateJson(state: ExpatRankingState): Map<String, Any?> = linkedMapOf(
            "isLoading" to state.isLoading,
            "totalJobs" to state.totalJobs,
            "actionableCount" to state.actionableCount,
            "disqualifiedCount" to state.disqualifiedCount,
            "topOpportunityIds" to state.topOpportunities.map { it.job.id },
            "ranked" to state.ranked.map { rankedJobJson(it) },
        )

        val engine = ExpatRankingEngine(clock = { fixedNow })
        val ranked = engine.rank(
            shortlist = ShortlistState(
                isLoading = false,
                entries = entries,
                totalJobs = entries.size,
                isLive = true,
                sourceNote = "",
                generatedAtEpochMillis = fixedNow,
            ),
            preferences = preferences,
        )

        // A two-entry subset, fed in reverse order, proves the ordering is deterministic and
        // independent of input order: the engine sorts, it does not preserve.
        val subset = entries.take(2).reversed()
        val subsetRanked = engine.rank(
            shortlist = ShortlistState(
                isLoading = false,
                entries = subset,
                totalJobs = subset.size,
                isLive = true,
                sourceNote = "",
                generatedAtEpochMillis = fixedNow,
            ),
            preferences = preferences,
        )

        val empty = engine.rank(shortlist = ShortlistState(isLoading = true), preferences = preferences)

        return linkedMapOf(
            "full" to stateJson(ranked),
            "subset" to stateJson(subsetRanked),
            "empty" to linkedMapOf(
                "isLoading" to empty.isLoading,
                "totalJobs" to empty.totalJobs,
                "ranked" to empty.ranked,
                "generatedAtEpochMillis" to empty.generatedAtEpochMillis,
            ),
        )
    }

    // ------------------------------------------------------------------ behaviour engines

    private fun alertEngineJson(): Map<String, Any?> {
        val engine = JobAlertEngine()
        val rules = listOf(
            JobAlertRule(
                id = "any-strong", name = "Strong maintenance", keywords = emptyList(),
                frequency = AlertFrequency.DAILY, scope = AlertScope.STRONG_ONLY,
                minMatchScore = 60, createdAtEpochMillis = fixedNow,
            ),
            JobAlertRule(
                id = "visa-canada", name = "Visa-friendly Canada", keywords = listOf("maintenance"),
                countries = listOf("Canada"), frequency = AlertFrequency.WEEKLY,
                scope = AlertScope.VISA_FRIENDLY, minMatchScore = 50, createdAtEpochMillis = fixedNow,
            ),
            JobAlertRule(
                id = "any-pump", name = "Any pump role", keywords = listOf("pump"),
                frequency = AlertFrequency.MONTHLY, scope = AlertScope.ANY_MATCH,
                minMatchScore = 40, createdAtEpochMillis = fixedNow,
            ),
            JobAlertRule(
                id = "disabled", name = "Disabled rule", keywords = listOf("maintenance"),
                frequency = AlertFrequency.DAILY, scope = AlertScope.ANY_MATCH,
                minMatchScore = 40, createdAtEpochMillis = fixedNow, enabled = false,
            ),
        )
        val jobs = cases.map { jobForCase(it) }
        val alerts = engine.alertsFor(rules, jobs, fixedNow)

        return linkedMapOf(
            "rules" to rules.map { rule ->
                linkedMapOf(
                    "id" to rule.id,
                    "name" to rule.name,
                    "keywords" to rule.keywords,
                    "countries" to rule.countries,
                    "frequency" to rule.frequency.name,
                    "scope" to rule.scope.name,
                    "minMatchScore" to rule.minMatchScore,
                    "enabled" to rule.enabled,
                )
            },
            "matches" to rules.associate { rule ->
                rule.id to cases.associate { case ->
                    case.id to engine.matches(rule, jobForCase(case))
                }
            },
            "alerts" to alerts.map { alert ->
                linkedMapOf(
                    "ruleId" to alert.ruleId,
                    "ruleName" to alert.ruleName,
                    "jobId" to alert.jobId,
                    "jobTitle" to alert.jobTitle,
                    "company" to alert.company,
                    "country" to alert.country,
                    "countryFlag" to alert.countryFlag,
                    "matchScore" to alert.matchScore,
                    "isVisaFriendly" to alert.isVisaFriendly,
                    "detectedAtEpochMillis" to alert.detectedAtEpochMillis,
                    "read" to alert.read,
                )
            },
        )
    }

    private fun autoApplyItemJson(item: AutoApplyItem): Map<String, Any?> = linkedMapOf(
        "jobId" to item.jobId,
        "jobTitle" to item.jobTitle,
        "company" to item.company,
        "country" to item.country,
        "countryFlag" to item.countryFlag,
        "matchScore" to item.matchScore,
        "sourceUrl" to item.sourceUrl,
        "status" to item.status.name,
        "coverLetterId" to item.coverLetterId,
        "reason" to item.reason,
        "preparedAtEpochMillis" to item.preparedAtEpochMillis,
    )

    private fun autoApplyJson(scenarios: List<Map<String, Any?>>): Map<String, Any?> {
        val jobs = cases.map { jobForCase(it) }
        val engine = AutoApplyEngine(threshold = 70, clock = { fixedNow })

        val decisions = jobs.associate { job ->
            val decision = engine.decide(job)
            job.id to linkedMapOf(
                "status" to decision.status.name,
                "reason" to decision.reason,
                "eligible" to decision.eligible,
            )
        }

        val baseQueue = engine.buildQueue(jobs, emptyList())
        // A previously approved entry must survive a refresh, so the queue keeps APPROVED
        // (and an explicit SKIP) rather than re-deriving the status from the job alone.
        val existing = listOf(
            baseQueue.first().copy(status = AutoApplyStatus.APPROVED),
            baseQueue.getOrNull(1)?.copy(status = AutoApplyStatus.SKIPPED),
        ).filterNotNull()
        val preservedQueue = engine.buildQueue(jobs, existing)

        return linkedMapOf(
            "threshold" to 70,
            "decisions" to decisions,
            "baseQueue" to baseQueue.map { autoApplyItemJson(it) },
            "preservedQueue" to preservedQueue.map { autoApplyItemJson(it) },
        )
    }

    private fun submissionJson(scenarios: List<Map<String, Any?>>): Map<String, Any?> {
        // The channels, and the capability statement that reads the same gate.
        val channels = SubmissionChannel.values().map { channel ->
            linkedMapOf(
                "name" to channel.name,
                "label" to channel.label,
                "realSendSupported" to channel.realSendSupported,
                "compatibilityNote" to channel.compatibilityNote,
                "statement" to SubmissionCapability.statement(channel),
            )
        }

        // Channel inference over a handful of representative URLs.
        val inferUrls = listOf(
            "https://boards.greenhouse.io/acme/jobs/1",
            "https://jobs.lever.co/acme/abc",
            "https://acme.myworkdayjobs.com/en-US/careers/job/1",
            "mailto:hr@acme.example",
            "https://careers.acme.example/apply/9",
            "",
            "not a url",
        )
        val inference = inferUrls.map { url ->
            val probe = jobForCase(cases.first()).copy(sourceUrl = url)
            linkedMapOf(
                "url" to url,
                "channel" to SubmissionChannel.infer(probe).name,
            )
        }

        // Identity keys, including the employer-blank fallback and cross-source stability.
        val identity = cases.take(3).map { case ->
            val job = jobForCase(case)
            linkedMapOf(
                "jobId" to job.id,
                "key" to SubmissionKey.of(job).value,
                "normalizedCompany" to SubmissionKey.of(job).normalizedCompany,
                "normalizedTitle" to SubmissionKey.of(job).normalizedTitle,
                "normalizedCountry" to SubmissionKey.of(job).normalizedCountry,
            )
        }
        val blankEmployerKey = SubmissionKey.of(
            jobForCase(cases.first()).copy(company = "", title = "Field Service Engineer"),
        ).value

        // The classifier over a matrix of statuses and ids.
        val classifierCases = listOf(
            null to null,
            null to "abc",
            201 to "app-42",
            200 to "",
            200 to null,
            409 to "existing-1",
            429 to "later",
            500 to "",
            503 to "maybe",
            400 to "bad-request",
            422 to "",
            302 to "redirect",
        )
        val classified = classifierCases.map { (status, id) ->
            val outcome = OfficialSubmissionClassifier.classify(status, id)
            val (kind, detail) = when (outcome) {
                is OfficialSubmissionOutcome.Confirmed -> "CONFIRMED" to outcome.remoteApplicationId
                is OfficialSubmissionOutcome.Failed -> "FAILED" to outcome.reason
                is OfficialSubmissionOutcome.Unknown -> "UNKNOWN" to outcome.reason
            }
            linkedMapOf(
                "httpStatus" to status,
                "remoteApplicationId" to id,
                "kind" to kind,
                "detail" to detail,
                "lifecycle" to OfficialSubmissionClassifier.lifecycleOf(outcome).name,
            )
        }

        // The lifecycle mapping for every draft status.
        val lifecycle = DraftStatus.entries.map { status ->
            linkedMapOf(
                "draftStatus" to status.name,
                "lifecycle" to SubmissionLifecycle.of(status).name,
                "isConfirmedSent" to SubmissionLifecycle.of(status).isConfirmedSent,
                "isNotSent" to SubmissionLifecycle.of(status).isNotSent,
            )
        }

        // Preflight over the cases that matter: blocked, content-changed, and allowed.
        fun draft(
            status: DraftStatus,
            contentHash: String,
            approvedHash: String?,
            cvSummary: String = "Maintenance technician, 8 years.",
            coverLetterText: String = "Dear team, I am applying.",
        ) = ApplicationDraft(
            id = "draft-1",
            jobId = cases.first().id,
            jobTitle = cases.first().title,
            company = cases.first().company,
            country = cases.first().country,
            countryFlag = "🌍",
            sourceUrl = cases.first().url,
            channelKey = SubmissionChannel.EMPLOYER_PORTAL.name,
            cvSummary = cvSummary,
            coverLetterId = "cl-1",
            coverLetterText = coverLetterText,
            answers = listOf(DraftAnswer(question = "Notice period?", answer = "One month")),
            documents = listOf("cv.pdf"),
            status = status,
            contentHash = contentHash,
            approvedHash = approvedHash,
            approvedAtEpochMillis = if (approvedHash != null) fixedNow else null,
            preparedAtEpochMillis = fixedNow,
            updatedAtEpochMillis = fixedNow,
        )

        val notApproved = draft(DraftStatus.READY_FOR_REVIEW, "h1", null)
        val approved = draft(DraftStatus.APPROVED, "h1", "h1")
        val changed = draft(DraftStatus.APPROVED, "h2", "h1")
        val missingContent = draft(DraftStatus.APPROVED, "h1", "h1", cvSummary = "", coverLetterText = "")

        fun preflightJson(
            label: String,
            d: ApplicationDraft,
            realSubmissionAvailable: Boolean,
            consentGiven: Boolean,
            alreadyApplied: Boolean,
        ): Pair<String, Map<String, Any?>> {
            val result = SubmissionPreflightCheck.evaluate(d, realSubmissionAvailable, consentGiven, alreadyApplied)
            return label to linkedMapOf(
                "blockers" to result.blockers.map { it.name },
                "allowed" to result.allowed,
                "primaryBlocker" to result.primaryBlocker?.name,
                "explanation" to result.explanation,
            )
        }

        val preflights = listOf(
            preflightJson("not-approved", notApproved, realSubmissionAvailable = false, consentGiven = false, alreadyApplied = false),
            preflightJson("approved-but-no-route", approved, realSubmissionAvailable = false, consentGiven = true, alreadyApplied = false),
            preflightJson("changed-content", changed, realSubmissionAvailable = true, consentGiven = true, alreadyApplied = false),
            preflightJson("missing-content", missingContent, realSubmissionAvailable = true, consentGiven = true, alreadyApplied = false),
            preflightJson("no-consent", approved, realSubmissionAvailable = true, consentGiven = false, alreadyApplied = false),
            preflightJson("already-applied", approved, realSubmissionAvailable = true, consentGiven = true, alreadyApplied = true),
            preflightJson("allowed", approved, realSubmissionAvailable = true, consentGiven = true, alreadyApplied = false),
        )

        return linkedMapOf(
            "channels" to channels,
            "capability" to linkedMapOf(
                "sendsNothing" to SubmissionCapability.sendsNothing,
                "supportedChannelCount" to SubmissionCapability.supportedChannelCount,
            ),
            "inference" to inference,
            "identity" to identity,
            "blankEmployerKey" to blankEmployerKey,
            "classifier" to classified,
            "lifecycle" to lifecycle,
            "preflights" to preflights.toMap(),
        )
    }

    private fun rankedJobJson(item: ExpatRankedJob): Map<String, Any?> = linkedMapOf(
        "jobId" to item.job.id,
        "rank" to item.rank,
        "finalScore" to item.finalScore,
        "readinessScore" to item.readinessScore,
        "readiness" to item.readiness.name,
        "disqualified" to item.opportunity.disqualified,
        "opportunityScore" to item.opportunity.score,
        "opportunityBand" to item.opportunity.band.name,
        "labels" to item.labels.map { it.name },
        "reasons" to item.reasons,
    )

    /** A standalone `Job` for the cover-letter and interview fixtures, built the same way as a scenario. */
    private fun jobForCase(case: Case): Job {
        val role = RoleClassifier.classify(case.title, case.description)
        val matching = MatchingEngine().assess(
            MatchInput(
                title = case.title,
                country = case.country,
                description = case.description,
                tags = case.tags,
                postedDaysAgo = case.postedDaysAgo,
            ),
            buildProfile(),
        )
        val eligibility = EligibilityEngine().assess(
            EligibilityInput(
                title = case.title,
                location = case.country,
                description = case.description,
                tags = case.tags,
                employmentType = case.employmentType,
                isRemote = case.isRemote,
                isRecognizedCountry = case.isRecognizedCountry,
            ),
        )
        val requirements = RequirementAnalyzer.analyze(case.description)
        val gaps = QualificationMatcher.gaps(requirements, buildProfile())
        val benefits = ExpatBenefitExtractor().extract(
            title = case.title,
            description = case.description,
            tags = case.tags,
            source = case.source,
            verifiedAtEpochMillis = fixedNow,
        )
        return Job(
            id = case.id,
            title = case.title,
            company = case.company,
            companyAccent = 0xFF1E4FD8,
            country = case.country,
            countryFlag = "🌍",
            city = case.city,
            salaryMin = case.salaryMin,
            salaryMax = case.salaryMax,
            currencyCode = case.currency,
            salaryPeriod = case.period,
            matchScore = matching.score,
            eligibility = eligibility.eligibility,
            visaSupport = eligibility.visaSupport,
            scamRisk = ScamRisk.LOW,
            postedAtEpochMillis = case.postedDaysAgo?.let { fixedNow - it * 86_400_000L },
            expiresAtEpochMillis = case.expiresAtEpochMillis,
            retrievedAtEpochMillis = fixedNow,
            source = case.source,
            sourceUrl = case.url,
            workMode = if (case.isRemote) WorkMode.REMOTE else WorkMode.ONSITE,
            employmentType = EmploymentType.FULL_TIME,
            experienceYears = requirements.minYears ?: 0,
            matchedSkills = matching.matchedSkills,
            missingSkills = matching.missingSkills,
            languages = case.languages,
            summary = case.description,
            responsibilities = emptyList(),
            benefits = emptyList(),
            aiInsight = "",
            providerId = case.providerId,
            salaryPeriodInferred = false,
            salaryUnitUncertain = case.salaryUnitUncertain,
            matchReasons = matching.reasons,
            eligibilityReasons = eligibility.reasons,
            benefitEvidence = benefits,
            roleFamily = role.family,
            matchedTargetRole = role.matchedTarget?.canonical,
            requirements = requirements,
            qualificationGaps = gaps,
            evidence = emptyList(),
            openToApplicants = null,
        )
    }

    /**
     * Rebuilds the `Job` the Kotlin exporter scored from the serialised fixture map, so the
     * shortlist / intelligence / plan engines see exactly the same input the scenario did.
     * `extraInput` supplies the fields (`salaryPeriod`, `languages`, …) that live on the
     * scenario input rather than the serialised job.
     */
    @Suppress("UNCHECKED_CAST")
    private fun jobFromJson(output: Map<*, *>, input: Map<*, *>): Job {
        @Suppress("UNCHECKED_CAST")
        val job = output["job"] as Map<*, *>
        fun str(key: String) = job[key] as String
        fun int(key: String) = job[key] as Int
        fun intOrNull(key: String) = job[key] as Int?
        fun longOrNull(key: String) = (job[key] as Number?)?.toLong()
        val languages = (input["languages"] as List<*>).map { it as String }
        return Job(
            id = str("id"),
            title = str("title"),
            company = str("company"),
            companyAccent = (job["companyAccent"] as Number).toLong(),
            country = str("country"),
            countryFlag = str("countryFlag"),
            city = str("city"),
            salaryMin = int("salaryMin"),
            salaryMax = int("salaryMax"),
            currencyCode = str("currencyCode"),
            salaryPeriod = SalaryPeriod.valueOf(str("salaryPeriod")),
            matchScore = int("matchScore"),
            eligibility = com.aiglobal.jobfinder.data.model.MadagascarEligibility.valueOf(str("eligibility")),
            visaSupport = VisaSupport.valueOf(str("visaSupport")),
            scamRisk = ScamRisk.valueOf(str("scamRisk")),
            postedAtEpochMillis = longOrNull("postedAtEpochMillis"),
            expiresAtEpochMillis = longOrNull("expiresAtEpochMillis"),
            retrievedAtEpochMillis = (job["retrievedAtEpochMillis"] as Number).toLong(),
            source = str("source"),
            sourceUrl = str("sourceUrl"),
            workMode = WorkMode.valueOf(str("workMode")),
            employmentType = EmploymentType.valueOf(str("employmentType")),
            experienceYears = int("experienceYears"),
            matchedSkills = (job["matchedSkills"] as List<*>).map { it as String },
            missingSkills = (job["missingSkills"] as List<*>).map { it as String },
            languages = languages,
            summary = str("summary"),
            responsibilities = emptyList(),
            benefits = emptyList(),
            aiInsight = str("aiInsight"),
            providerId = str("providerId"),
            salaryIsEstimated = job["salaryIsEstimated"] as Boolean,
            salaryPeriodInferred = job["salaryPeriodInferred"] as Boolean,
            salaryUnitUncertain = job["salaryUnitUncertain"] as Boolean,
            matchReasons = (job["matchReasons"] as List<*>).map { raw ->
                val m = raw as Map<*, *>
                com.aiglobal.jobfinder.data.model.MatchReason(
                    label = m["label"] as String,
                    detail = m["detail"] as String,
                    signal = com.aiglobal.jobfinder.data.model.MatchSignal.valueOf(m["signal"] as String),
                    weight = m["weight"] as Int,
                )
            },
            eligibilityReasons = (job["eligibilityReasons"] as List<*>).map { raw ->
                val m = raw as Map<*, *>
                com.aiglobal.jobfinder.data.model.EligibilityReason(
                    label = m["label"] as String,
                    detail = m["detail"] as String,
                    official = m["official"] as Boolean,
                )
            },
            benefitEvidence = (job["benefitEvidence"] as List<*>).map { raw ->
                val m = raw as Map<*, *>
                BenefitEvidence(
                    type = com.aiglobal.jobfinder.data.model.JobBenefitTypes.byKey(m["typeKey"] as String)
                        ?: error("unknown benefit type ${m["typeKey"]}"),
                    status = com.aiglobal.jobfinder.data.model.BenefitStatus.valueOf(m["status"] as String),
                    evidence = m["evidence"] as String,
                    source = m["source"] as String,
                    verifiedAtEpochMillis = (m["verifiedAtEpochMillis"] as Number).toLong(),
                )
            },
            roleFamily = com.aiglobal.jobfinder.data.jobs.search.RoleFamily.valueOf(str("roleFamily")),
            matchedTargetRole = job["matchedTargetRole"] as String?,
            requirements = requirementsFromJson(job["requirements"] as Map<*, *>),
            qualificationGaps = (job["qualificationGaps"] as List<*>).map { raw ->
                val m = raw as Map<*, *>
                com.aiglobal.jobfinder.data.model.QualificationGap(
                    label = m["label"] as String,
                    evidence = m["evidence"] as String,
                    met = m["met"] as Boolean?,
                )
            },
            evidence = emptyList(),
            openToApplicants = job["openToApplicants"] as Boolean?,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun requirementsFromJson(map: Map<*, *>): JobRequirements {
        fun labels(key: String) = (map[key] as List<*>).map { raw ->
            val m = raw as Map<*, *>
            com.aiglobal.jobfinder.data.model.JobRequirement(
                label = m["label"] as String,
                evidence = m["evidence"] as String,
            )
        }
        return JobRequirements(
            minYears = map["minYears"] as Int?,
            certifications = labels("certifications"),
            licences = labels("licences"),
        )
    }

    // ------------------------------------------------------------------ record validation

    private fun rawJob(
        providerId: String,
        externalId: String,
        title: String,
        company: String,
        location: String,
        description: String,
        url: String,
        postedDaysAgo: Int? = 3,
        salaryMin: Int? = 60_000,
        salaryMax: Int? = 70_000,
        currency: String? = "CAD",
        period: SalaryPeriod? = SalaryPeriod.YEAR,
        salaryUnitUncertain: Boolean = false,
        closedToNewApplicants: Boolean = false,
        expiresAtEpochMillis: Long? = null,
    ) = RawJob(
        providerId = providerId,
        providerName = providerId.replaceFirstChar { it.uppercase() },
        externalId = externalId,
        title = title,
        company = company,
        location = location,
        descriptionHtml = description,
        url = url,
        tags = emptyList(),
        postedAtEpochMillis = postedDaysAgo?.let { fixedNow - it * 86_400_000L },
        expiresAtEpochMillis = expiresAtEpochMillis,
        retrievedAtEpochMillis = fixedNow,
        salaryMin = salaryMin,
        salaryMax = salaryMax,
        salaryCurrency = currency,
        salaryPeriod = period,
        salaryUnitUncertain = salaryUnitUncertain,
        closedToNewApplicants = closedToNewApplicants,
    )

    private fun validationCases(): List<Pair<String, RawJob>> = listOf(
        "ok" to rawJob("workday", "1", "Maintenance Technician", "Northline", "Canada", "Fix pumps.", "https://a.example/1"),
        "missing-title" to rawJob("workday", "2", "", "Northline", "Canada", "Fix pumps.", "https://a.example/2"),
        "missing-employer-and-title" to rawJob("workday", "3", "", "", "Canada", "Fix pumps.", "https://a.example/3"),
        "missing-location" to rawJob("workday", "4", "Fitter", "Northline", "", "Fix pumps.", "https://a.example/4"),
        "missing-description" to rawJob("workday", "5", "Fitter", "Northline", "Canada", "", "https://a.example/5"),
        "missing-url" to rawJob("workday", "6", "Fitter", "Northline", "Canada", "Fix pumps.", ""),
        "invalid-url" to rawJob("workday", "7", "Fitter", "Northline", "Canada", "Fix pumps.", "not-a-url"),
        "stale" to rawJob("workday", "8", "Fitter", "Northline", "Canada", "Fix pumps.", "https://a.example/8", postedDaysAgo = 400),
        "expired" to rawJob(
            "workday", "9", "Fitter", "Northline", "Canada", "Fix pumps.", "https://a.example/9",
            expiresAtEpochMillis = fixedNow - 86_400_000L,
        ),
        "closed" to rawJob(
            "workday", "10", "Fitter", "Northline", "Canada", "Fix pumps.", "https://a.example/10",
            closedToNewApplicants = true,
        ),
        "implausible-salary" to rawJob(
            "workday", "11", "Fitter", "Northline", "Canada", "Fix pumps.", "https://a.example/11",
            salaryMin = 5_000_000, salaryMax = 6_000_000,
        ),
        "inverted-salary" to rawJob(
            "workday", "12", "Fitter", "Northline", "Canada", "Fix pumps.", "https://a.example/12",
            salaryMin = 90_000, salaryMax = 50_000,
        ),
        "low-salary" to rawJob(
            "workday", "13", "Fitter", "Northline", "Canada", "Fix pumps.", "https://a.example/13",
            salaryMin = 500, salaryMax = 500,
        ),
        "uncertain-unit-salary" to rawJob(
            "workday", "14", "Fitter", "Northline", "Canada", "Fix pumps.", "https://a.example/14",
            salaryMin = 106_000, salaryMax = 106_000, salaryUnitUncertain = true, period = SalaryPeriod.HOUR,
        ),
        "future-date" to rawJob("workday", "15", "Fitter", "Northline", "Canada", "Fix pumps.", "https://a.example/15", postedDaysAgo = -5),
    )

    private fun recordValidationJson(): List<Map<String, Any?>> {
        val engine = RecordValidationEngine()
        return validationCases().map { (id, raw) ->
            val result = engine.validate(raw, fixedNow)
            linkedMapOf(
                "id" to id,
                "raw" to rawJobJson(raw),
                "output" to linkedMapOf(
                    "accepted" to result.accepted,
                    "reasons" to result.reasons.map { it.name },
                    "recordId" to result.recordId,
                    "dedupKey" to result.dedupKey,
                ),
            )
        }
    }

    private fun deduplicationJson(): Map<String, Any?> {
        val engine = RecordValidationEngine()
        val records = listOf(
            rawJob("workday", "100", "Maintenance Technician", "Northline Industrial", "Canada", "Fix pumps.", "https://a.example/100"),
            // Same employer + title from another source: a cross-source duplicate.
            rawJob("adzuna", "200", "maintenance technician ", "northline industrial", "Canada", "Fix pumps.", "https://b.example/200"),
            // Different role at the same employer: kept.
            rawJob("workday", "101", "Pump Technician", "Northline Industrial", "Canada", "Fix pumps.", "https://a.example/101"),
            // Rejected record should not affect the accepted set.
            rawJob("adzuna", "201", "", "Northline Industrial", "Canada", "Fix pumps.", "https://b.example/201"),
        )
        val result = engine.deduplicate(records, fixedNow)
        return linkedMapOf(
            "records" to records.map { rawJobJson(it) },
            "output" to linkedMapOf(
                "keptIds" to result.kept.map { RecordValidationEngine.recordId(it) },
                "totalReceived" to result.totalReceived,
                "totalKept" to result.totalKept,
                "totalDuplicates" to result.totalDuplicates,
                "totalRejected" to result.totalRejected,
                "summaries" to result.summaries.map { summary ->
                    linkedMapOf(
                        "providerId" to summary.providerId,
                        "receivedCount" to summary.receivedCount,
                        "acceptedCount" to summary.acceptedCount,
                        "duplicateCount" to summary.duplicateCount,
                        "rejectedCount" to summary.rejectedCount,
                        "rejectedByReason" to summary.rejectedByReason.entries
                            .sortedBy { it.key.name }
                            .associate { it.key.name to it.value },
                    )
                },
            ),
        )
    }

    // ------------------------------------------------------------------ url inspections

    private fun urlInspectionsJson(): List<Map<String, Any?>> {
        val inspector = ApplicationUrlInspector()
        val cases = listOf(
            "" to "Workday",
            "   " to "Workday",
            "not-a-url" to "Workday",
            "ftp://careers.example.com/job" to "Workday",
            "https://careers.northline.example/jobs/1" to "Workday",
            "https://www.adzuna.com/details/12345" to "Adzuna",
            "https://jobbank.gc.ca/jobsearch/1" to "Job Bank",
            "https://immi.homeaffairs.gov.au/visas" to "Australia Home Affairs",
            "https://careers.example.tk/apply" to "Workday",
            "https://xn--80ak6aa92e.com/job" to "Workday",
            "http://192.168.0.1/apply" to "Workday",
            "https://unknown-employer-xyz.com/apply" to "Workday",
            "https://remoteok.workday.com/jobs/9" to "Workday",
            // Applicant-tracking hosts the app's own sources link to. These are the employer's
            // own posting pages, so they must read as the employer's site, not an unknown host.
            "https://sulzer.wd502.myworkdayjobs.com/en-US/External/job/Mannheim/Maintenance-Mechanic_R1" to "Employer career sites",
            "https://firstquantum.wd3.myworkdaysite.com/en-US/recruiting/fqml/External/job/Panama" to "Employer career sites",
            "https://boards.greenhouse.io/lucidmotors/jobs/5231094007" to "Industrial career sites",
            "https://jobs.lever.co/sila/eaebee13-fd57-4187-a16e-a39c033afd24" to "Industrial employer boards",
            "https://jobs.ashbyhq.com/formenergy/a5c18c3f-da88-4743-9d11-58a5205d2883" to "Advanced manufacturing sites",
            "https://jobs.smartrecruiters.com/Wabtec/3743990016234090-assembler-2-compressor-technician" to "Employer job boards",
            // The same suffix rule must not turn an unrelated look-alike into an employer site.
            "https://careers.example.com/lever.co/job" to "Workday",
        )
        return cases.map { (url, source) ->
            val inspection = inspector.inspect(url, source)
            linkedMapOf(
                "url" to url,
                "source" to source,
                "output" to linkedMapOf(
                    "url" to inspection.url,
                    "host" to inspection.host,
                    "destination" to inspection.destination.name,
                    "canOpen" to inspection.canOpen,
                    "message" to inspection.message,
                    "warnings" to inspection.warnings,
                ),
            )
        }
    }

    // ------------------------------------------------------------------ serialisers

    private fun profileJson(profile: CandidateProfile): Map<String, Any?> = linkedMapOf(
        "fullName" to profile.fullName,
        "email" to profile.email,
        "phone" to profile.phone,
        "location" to profile.location,
        "currentTitle" to profile.currentTitle,
        "previousTitles" to profile.previousTitles,
        "yearsOfExperience" to profile.yearsOfExperience,
        "skills" to profile.skills,
        "industries" to profile.industries,
        "education" to profile.education.map {
            linkedMapOf(
                "degree" to it.degree,
                "institution" to it.institution,
                "country" to it.country,
                "year" to it.year,
            )
        },
        "certifications" to profile.certifications.map {
            linkedMapOf("name" to it.name, "issuer" to it.issuer, "year" to it.year)
        },
        "languages" to profile.languages.map {
            linkedMapOf("name" to it.name, "level" to it.level?.name, "evidence" to it.evidence)
        },
        "preferredCountries" to profile.preferredCountries,
        "preferredRoles" to profile.preferredRoles,
        "relocationPreference" to profile.relocationPreference.name,
        "needsVisaSponsorship" to profile.needsVisaSponsorship,
        "workPermitNotes" to profile.workPermitNotes,
        "summary" to profile.summary,
    )

    private fun preferencesJson(preferences: ExpatPreferences): Map<String, Any?> = linkedMapOf(
        "priorities" to preferences.priorities.toSortedMap(),
        "minimumSalaryEur" to preferences.minimumSalaryEur,
        "targetCountries" to preferences.targetCountries.sorted(),
        "minExpatScore" to preferences.minExpatScore,
        "minMatchScore" to preferences.minMatchScore,
        "requireVisaSponsorship" to preferences.requireVisaSponsorship,
    )

    private fun requirementsJson(requirements: JobRequirements): Map<String, Any?> =
        linkedMapOf(
            "minYears" to requirements.minYears,
            "certifications" to requirements.certifications.map {
                linkedMapOf("label" to it.label, "evidence" to it.evidence)
            },
            "licences" to requirements.licences.map {
                linkedMapOf("label" to it.label, "evidence" to it.evidence)
            },
        )

    private fun expatJson(expat: ExpatBenefitsAssessment): Map<String, Any?> =
        linkedMapOf(
            "score" to expat.score,
            "evidenceCoverage" to expat.evidenceCoverage,
            "explanation" to expat.explanation,
            "unmetEssentials" to expat.unmetEssentials.map { it.key },
            "lowEvidence" to expat.lowEvidence,
            "components" to expat.components.map {
                linkedMapOf(
                    "typeKey" to it.type.key,
                    "priority" to it.priority.weight,
                    "status" to it.status.name,
                    "evidence" to it.evidence,
                    "credit" to it.credit.toDouble(),
                    "potentialPoints" to it.potentialPoints,
                    "earnedPoints" to it.earnedPoints,
                )
            },
        )

    private fun fitJson(fit: InternationalFitAssessment): Map<String, Any?> =
        linkedMapOf(
            "score" to fit.score,
            "band" to fit.band.name,
            "blockers" to fit.blockers,
            "routeSummary" to fit.routeSummary,
            "expatBenefitsScore" to fit.expatBenefitsScore,
            "expatEvidenceCoverage" to fit.expatEvidenceCoverage,
            "expatLowEvidence" to fit.expatLowEvidence,
            "explanation" to fit.explanation,
            "components" to fit.components.map {
                linkedMapOf(
                    "label" to it.label,
                    "detail" to it.detail,
                    "strength" to it.strength.name,
                    "points" to it.points,
                    "maxPoints" to it.maxPoints,
                )
            },
        )

    private fun benefitEvidenceJson(evidence: BenefitEvidence): Map<String, Any?> = linkedMapOf(
        "typeKey" to evidence.type.key,
        "status" to evidence.status.name,
        "evidence" to evidence.evidence,
        "source" to evidence.source,
        "verifiedAtEpochMillis" to evidence.verifiedAtEpochMillis,
    )

    private fun jobJson(job: Job): Map<String, Any?> = linkedMapOf(
        "id" to job.id,
        "title" to job.title,
        "company" to job.company,
        "companyAccent" to job.companyAccent,
        "country" to job.country,
        "countryFlag" to job.countryFlag,
        "city" to job.city,
        "salaryMin" to job.salaryMin,
        "salaryMax" to job.salaryMax,
        "currencyCode" to job.currencyCode,
        "salaryPeriod" to job.salaryPeriod.name,
        "matchScore" to job.matchScore,
        "eligibility" to job.eligibility.name,
        "visaSupport" to job.visaSupport.name,
        "scamRisk" to job.scamRisk.name,
        "postedAtEpochMillis" to job.postedAtEpochMillis,
        "expiresAtEpochMillis" to job.expiresAtEpochMillis,
        "retrievedAtEpochMillis" to job.retrievedAtEpochMillis,
        "source" to job.source,
        "sourceUrl" to job.sourceUrl,
        "workMode" to job.workMode.name,
        "employmentType" to job.employmentType.name,
        "experienceYears" to job.experienceYears,
        "matchedSkills" to job.matchedSkills,
        "missingSkills" to job.missingSkills,
        "languages" to job.languages,
        "summary" to job.summary,
        "responsibilities" to job.responsibilities,
        "benefits" to job.benefits,
        "aiInsight" to job.aiInsight,
        "providerId" to job.providerId,
        "salaryIsEstimated" to job.salaryIsEstimated,
        "salaryPeriodInferred" to job.salaryPeriodInferred,
        "salaryUnitUncertain" to job.salaryUnitUncertain,
        "matchReasons" to job.matchReasons.map {
            linkedMapOf("label" to it.label, "detail" to it.detail, "signal" to it.signal.name, "weight" to it.weight)
        },
        "eligibilityReasons" to job.eligibilityReasons.map {
            linkedMapOf("label" to it.label, "detail" to it.detail, "official" to it.official)
        },
        "benefitEvidence" to job.benefitEvidence.map { benefitEvidenceJson(it) },
        "roleFamily" to job.roleFamily.name,
        "matchedTargetRole" to job.matchedTargetRole,
        "requirements" to requirementsJson(job.requirements),
        "qualificationGaps" to job.qualificationGaps.map {
            linkedMapOf("label" to it.label, "evidence" to it.evidence, "met" to it.met)
        },
        "evidence" to job.evidence.map {
            linkedMapOf(
                "kind" to it.kind.name,
                "strength" to it.strength.name,
                "label" to it.label,
                "detail" to it.detail,
                "sourceUrl" to it.sourceUrl,
            )
        },
        "openToApplicants" to job.openToApplicants,
        "isSaved" to job.isSaved,
        "isNew" to job.isNew,
    )

    /**
     * Étape B: golden fixtures for the network-free availability read, one row per rule the
     * engine must obey. The web mirror replays the same inputs and must reach the same verdict,
     * decisive signal and evidence, so the two platforms can never drift on what "open",
     * "expired" and "unconfirmed" mean.
     */
    private fun availabilityFixtureJson(): List<Map<String, Any?>> {
        fun baseJob(open: Boolean?, expires: Long?): Job = Job(
            id = "availability-fixture",
            title = "Maintenance Technician",
            company = "Northline Industrial",
            companyAccent = 0xFF1E4FD8,
            country = "Canada",
            countryFlag = "🌍",
            city = "Calgary",
            salaryMin = 0,
            salaryMax = 0,
            currencyCode = "CAD",
            salaryPeriod = com.aiglobal.jobfinder.data.model.SalaryPeriod.YEAR,
            matchScore = 80,
            eligibility = MadagascarEligibility.VISA_SPONSORED,
            visaSupport = com.aiglobal.jobfinder.data.model.VisaSupport.FULL_SPONSORSHIP,
            scamRisk = ScamRisk.LOW,
            postedAtEpochMillis = fixedNow - 86_400_000L,
            expiresAtEpochMillis = expires,
            retrievedAtEpochMillis = fixedNow,
            source = "Canada Job Bank",
            sourceUrl = "https://www.jobbank.gc.ca/job/1",
            workMode = WorkMode.ONSITE,
            employmentType = EmploymentType.FULL_TIME,
            experienceYears = 3,
            matchedSkills = emptyList(),
            missingSkills = emptyList(),
            languages = emptyList(),
            summary = "",
            responsibilities = emptyList(),
            benefits = emptyList(),
            aiInsight = "",
            openToApplicants = open,
        )
        val engine = com.aiglobal.jobfinder.data.jobs.availability.OfferAvailabilityEngine()
        val cases = listOf(
            "flag-open" to (true to null),
            "flag-closed" to (false to null),
            "deadline-passed" to (null to (fixedNow - 2 * 86_400_000L)),
            "deadline-future" to (null to (fixedNow + 5 * 86_400_000L)),
            "silent" to (null to null),
            "flag-open-deadline-passed" to (true to (fixedNow - 2 * 86_400_000L)),
        )
        return cases.map { (id, spec) ->
            val (open, expires) = spec
            linkedMapOf(
                "id" to id,
                "openToApplicants" to open,
                "expiresAtEpochMillis" to expires,
                "output" to availabilityJson(engine.assess(baseJob(open, expires), fixedNow)),
            )
        }
    }

    /**
     * Étape B: the availability read, serialised so the web mirror can be pinned to the
     * Kotlin output. Every field the UI could show is included, so a divergence in the
     * verdict, the decisive signal, the checked-at stamp or any reason is caught by parity.
     */
    private fun availabilityJson(
        availability: com.aiglobal.jobfinder.data.model.AvailabilityResult,
    ): Map<String, Any?> = linkedMapOf(
        "status" to availability.status.name,
        "decisiveSignal" to availability.decisiveSignal.name,
        "lastCheckedAtEpochMillis" to availability.lastCheckedAtEpochMillis,
        "evidence" to availability.evidence.map {
            linkedMapOf(
                "type" to it.type.name,
                "detail" to it.detail,
                "sourceUrl" to it.sourceUrl,
            )
        },
    )

    private fun verificationJson(verification: OfferVerification): Map<String, Any?> = linkedMapOf(
        "activity" to verification.activity.name,
        "deadline" to verification.deadline.name,
        "link" to verification.link.name,
        "visa" to verification.visa.name,
        "relocation" to verification.relocation.name,
        "lastRetrievedAtEpochMillis" to verification.lastRetrievedAtEpochMillis,
        "freshness" to verification.freshness.name,
        "facts" to verification.facts.map {
            linkedMapOf(
                "aspect" to it.aspect.name,
                "strength" to it.strength.name,
                "label" to it.label,
                "detail" to it.detail,
                "sourceUrl" to it.sourceUrl,
            )
        },
        "caveats" to verification.caveats,
        "summary" to verification.summary,
    )

    private fun rawJobJson(raw: RawJob): Map<String, Any?> = linkedMapOf(
        "providerId" to raw.providerId,
        "providerName" to raw.providerName,
        "externalId" to raw.externalId,
        "title" to raw.title,
        "company" to raw.company,
        "location" to raw.location,
        "descriptionHtml" to raw.descriptionHtml,
        "url" to raw.url,
        "tags" to raw.tags,
        "employmentType" to raw.employmentType,
        "postedAtEpochMillis" to raw.postedAtEpochMillis,
        "expiresAtEpochMillis" to raw.expiresAtEpochMillis,
        "retrievedAtEpochMillis" to raw.retrievedAtEpochMillis,
        "isRemote" to raw.isRemote,
        "salaryMin" to raw.salaryMin,
        "salaryMax" to raw.salaryMax,
        "salaryCurrency" to raw.salaryCurrency,
        "salaryPeriod" to raw.salaryPeriod?.name,
        "salaryPeriodInferred" to raw.salaryPeriodInferred,
        "salaryUnitUncertain" to raw.salaryUnitUncertain,
        "closedToNewApplicants" to raw.closedToNewApplicants,
        "openToApplicants" to raw.openToApplicants,
    )

    /** Walks up from the test working directory until it finds the Gradle root. */
    private fun repoRoot(): File {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").exists() && dir.parentFile != null) {
            dir = dir.parentFile
        }
        return dir
    }

    // ------------------------------------------------------------------ cv analysis

    /**
     * Golden fixtures for the shareable CV analysis core (`data/cv/analysis`).
     *
     * The text here is synthetic — never a real CV — so the fixture carries no personal data.
     * Each case records the input text, the fields treated as user-protected, the profile
     * merged into, and the full analysis output the real Kotlin core produced. The web port
     * replays the same text through its own copy of the core and must match field for field.
     */
    private fun cvAnalysisJson(): Map<String, Any?> {
        val analyzer = HeuristicCvAnalyzer()
        val cases = cvAnalysisCases()
        val results = cases.map { case ->
            runBlocking {
                val result = analyzer.analyze(case.text, case.existing, case.protectedFields)
                linkedMapOf(
                    "id" to case.id,
                    "text" to case.text,
                    "protectedFields" to case.protectedFields.map { it.name }.sorted(),
                    "existing" to profileJson(case.existing),
                    "output" to linkedMapOf(
                        "profile" to profileJson(result.profile),
                        "warnings" to result.warnings,
                        "confidence" to result.confidence,
                        "evidence" to result.evidence.entries
                            .sortedBy { it.key.name }
                            .associate { it.key.name to it.value },
                        "effectiveEngineId" to result.effectiveEngineId,
                        "effectiveEngineLabel" to result.effectiveEngineLabel,
                        "presence" to presenceJson(result.presence),
                    ),
                )
            }
        }

        val normalizeCases = listOf(
            "Moji" to "MÃ©canicien",
            "soft-hyphen" to "Mainte-\nnance",
            "bullets" to "\u2022 first\n\u25CF second",
            "dashes" to "2018 \u2013 2020",
            "multi-space" to "A    B\t\tC",
            "empty" to "",
            "blank" to "   \n  \n ",
            "mixed-newlines" to "A\r\nB\u000CC",
        ).map { (id, raw) -> linkedMapOf("id" to id, "raw" to raw, "output" to CvTextNormalizer.normalize(raw)) }

        val foldCases = listOf(
            "accent" to "Mécanicien",
            "anglais" to "Anglais",
            "electro" to "Électrotechnique",
            "ligature" to "Œuvre",
            "upper" to "PLOMBERIE",
            "plain" to "maintenance",
        ).map { (id, raw) -> linkedMapOf("id" to id, "raw" to raw, "output" to CvTextNormalizer.fold(raw)) }

        return linkedMapOf(
            "schemaVersion" to CvAnalysisContract.SCHEMA_VERSION,
            "cases" to results,
            "normalize" to normalizeCases,
            "fold" to foldCases,
            "lexicon" to lexiconJson(),
        )
    }

    private data class CvCase(
        val id: String,
        val text: String,
        val existing: CandidateProfile = CandidateProfile(),
        val protectedFields: Set<CandidateField> = emptySet(),
    )

    private fun cvAnalysisCases(): List<CvCase> {
        val full = listOf(
            "Jonathan Rakoto",
            "Maintenance Technician",
            "Antananarivo, Madagascar",
            "jonathan.rakoto@example.mg",
            "+261 34 12 345 67",
            "",
            "Summary",
            "Mechanical maintenance technician with six years of experience on pumps and rotating equipment.",
            "",
            "Experience",
            "Maintenance Technician - Star Malgache (2018 - present)",
            "Performed preventive maintenance, hydraulics repair and PLC diagnostics.",
            "Mechanical Fitter - Port Services (2015 - 2018)",
            "",
            "Education",
            "BTS Electrotechnics, IST Antananarivo, 2018",
            "",
            "Skills",
            "Preventive maintenance, hydraulics, PLC, welding, CMMS",
            "",
            "Languages",
            "French - fluent",
            "English - intermediate",
            "",
            "Certifications",
            "City & Guilds Mechanical Engineering, 2016",
            "First Aid, 2020",
            "",
            "Willing to relocate. Needs visa sponsorship.",
        ).joinToString("\n")

        val french = listOf(
            "Rasoa Marie",
            "Technicien de maintenance",
            "Toamasina, Madagascar",
            "rasoa.marie@example.mg",
            "034 12 345 67",
            "",
            "Expérience",
            "Technicien de maintenance - Sucrerie (2016 - 2021)",
            "Maintenance préventive et diagnostic électrique.",
            "",
            "Compétences",
            "Maintenance préventive, mécanique, pneumatique, soudure",
            "",
            "Langues",
            "Malgache - langue maternelle",
            "Français - courant",
            "Anglais - intermédiaire",
            "",
            "Formation",
            "Licence en électrotechnique, Université d'Antananarivo, 2016",
            "",
            "Mobile, disponible pour expatriation.",
        ).joinToString("\n")

        val thin = "Too short."

        val existing = CandidateProfile(
            fullName = "Existing Name",
            email = "kept@example.mg",
            skills = listOf("Kept skill"),
            yearsOfExperience = 9,
        )

        return listOf(
            CvCase("full-english", full),
            CvCase("full-french", french),
            CvCase("thin-text", thin),
            CvCase("empty", ""),
            CvCase(
                id = "merge-preserves-protected",
                text = full,
                existing = existing,
                protectedFields = setOf(CandidateField.EMAIL, CandidateField.SKILLS),
            ),
            CvCase("merge-unprotected", text = full, existing = existing),
        )
    }

    private fun presenceJson(presence: CvSectionPresence): Map<String, Any?> = linkedMapOf(
        "skills" to presence.skills,
        "education" to presence.education,
        "languages" to presence.languages,
    )

    private fun lexiconJson(): Map<String, Any?> = linkedMapOf(
        "skills" to CvLexicon.skills.map { skill ->
            linkedMapOf(
                "canonical" to skill.canonical,
                "category" to skill.category.name,
                "aliases" to skill.aliases,
            )
        },
        "languages" to CvLexicon.languages,
        "languageAliases" to CvLexicon.languageAliases.entries
            .sortedBy { it.key }
            .associate { it.key to it.value },
        "degreeMarkers" to CvLexicon.degreeMarkers,
        "institutionMarkers" to CvLexicon.institutionMarkers,
        "certificationMarkers" to CvLexicon.certificationMarkers,
        "titleMarkers" to CvLexicon.titleMarkers,
        "countries" to CvLexicon.countries,
        "cities" to CvLexicon.cities,
        "foldedPlaces" to CvLexicon.foldedPlaces.sorted(),
        "sectionKeywords" to CvLexicon.sectionKeywords.entries
            .sortedBy { it.key }
            .associate { it.key to it.value.name },
    )

    private object JsonWriter {
        fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

        private fun append(sb: StringBuilder, value: Any?) {
            when (value) {
                null -> sb.append("null")
                is String -> appendString(sb, value)
                is Boolean -> sb.append(value)
                is Int, is Long -> sb.append(value)
                is Float, is Double -> {
                    val d = (value as Number).toDouble()
                    if (d == Math.floor(d) && !d.isInfinite()) sb.append(d.toLong()) else sb.append(d)
                }
                is Map<*, *> -> {
                    sb.append('{')
                    var first = true
                    for ((k, v) in value) {
                        if (!first) sb.append(',')
                        first = false
                        appendString(sb, k.toString())
                        sb.append(':')
                        append(sb, v)
                    }
                    sb.append('}')
                }
                is Iterable<*> -> {
                    sb.append('[')
                    var first = true
                    for (item in value) {
                        if (!first) sb.append(',')
                        first = false
                        append(sb, item)
                    }
                    sb.append(']')
                }
                else -> appendString(sb, value.toString())
            }
        }

        private fun appendString(sb: StringBuilder, value: String) {
            sb.append('"')
            for (ch in value) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
                }
            }
            sb.append('"')
        }
    }
}
