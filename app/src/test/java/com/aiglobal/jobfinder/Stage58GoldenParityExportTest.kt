package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.apply.ApplicationUrlInspector
import com.aiglobal.jobfinder.data.assistant.ApplicationPlanEngine
import com.aiglobal.jobfinder.data.assistant.ExpatIntelligenceEngine
import com.aiglobal.jobfinder.data.cv.CvCompleteness
import com.aiglobal.jobfinder.data.expat.ExpatBenefitExtractor
import com.aiglobal.jobfinder.data.expat.ExpatBenefitsEngine
import com.aiglobal.jobfinder.data.expat.ShortlistEngine
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
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.jobs.search.QualificationMatcher
import com.aiglobal.jobfinder.data.jobs.search.RequirementAnalyzer
import com.aiglobal.jobfinder.data.jobs.search.RoleClassifier
import com.aiglobal.jobfinder.data.model.BenefitEvidence
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.CertificationEntry
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.ExpatBenefitsAssessment
import com.aiglobal.jobfinder.data.model.ExpatIntelligence
import com.aiglobal.jobfinder.data.model.ExpatPreferences
import com.aiglobal.jobfinder.data.model.ApplicationPlan
import com.aiglobal.jobfinder.data.model.InternationalFitAssessment
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
import com.aiglobal.jobfinder.data.scam.ScamDetector
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
        education = emptyList(),
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
                "stage" to 62,
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

    // ------------------------------------------------------------------ JSON writer

    /**
     * A tiny, dependency-free canonical JSON writer. Key order is insertion order, so the
     * fixtures diff cleanly between runs; numbers keep their exact integer value.
     */
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
