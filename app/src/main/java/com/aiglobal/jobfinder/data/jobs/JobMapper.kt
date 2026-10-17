package com.aiglobal.jobfinder.data.jobs

import com.aiglobal.jobfinder.data.geo.CountryCatalog
import com.aiglobal.jobfinder.data.geo.CurrencyConverter
import com.aiglobal.jobfinder.data.expat.ExpatBenefitExtractor
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityInput
import com.aiglobal.jobfinder.data.jobs.engine.JobEvidenceEngine
import com.aiglobal.jobfinder.data.jobs.engine.MatchInput
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.engine.OfferVerificationEngine
import com.aiglobal.jobfinder.data.jobs.provider.HtmlText
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.jobs.search.QualificationMatcher
import com.aiglobal.jobfinder.data.jobs.search.RequirementAnalyzer
import com.aiglobal.jobfinder.data.jobs.search.RoleClassifier
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.EmploymentType
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.MatchReason
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.WorkMode
import kotlin.math.absoluteValue

/**
 * Turns a [RawJob] into the [Job] the UI renders, applying the matching and
 * eligibility engines.
 *
 * Design rules encoded here:
 *  - The country is resolved once and used for the flag, the filters and the engine.
 *  - Salary is only shown when the source published it; the card marks it as stated.
 *  - The match score and the eligibility verdict are always accompanied by the
 *    reasons that produced them, so nothing is asserted without explanation.
 */
class JobMapper(
    private val matchingEngine: MatchingEngine,
    private val eligibilityEngine: EligibilityEngine,
    private val benefitExtractor: ExpatBenefitExtractor = ExpatBenefitExtractor(),
    private val evidenceEngine: JobEvidenceEngine = JobEvidenceEngine(),
    private val verificationEngine: OfferVerificationEngine = OfferVerificationEngine(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun toJob(raw: RawJob, profile: CandidateProfile): Job {
        val country = CountryCatalog.resolve(raw.location)
        val plainDescription = HtmlText.toPlainText(raw.descriptionHtml)
        val now = clock()
        val postedDaysAgo = raw.postedAtEpochMillis?.let { ((now - it) / DAY_MS).toInt().coerceAtLeast(0) }

        val match = matchingEngine.assess(
            MatchInput(
                title = raw.title,
                country = country.name,
                description = plainDescription,
                tags = raw.tags,
                postedDaysAgo = postedDaysAgo,
            ),
            profile,
        )

        val eligibility = eligibilityEngine.assess(
            EligibilityInput(
                title = raw.title,
                location = raw.location,
                description = plainDescription,
                tags = raw.tags,
                employmentType = raw.employmentType,
                isRemote = raw.isRemote,
                // A remote listing whose location names a real country (not "Worldwide",
                // not a region such as "EMEA") is location-restricted: the employer bounded
                // where it may hire. `isRecognized` is false for regions, so a region-scoped
                // remote role is left for the engine's region rules to judge.
                isRecognizedCountry = raw.isRemote && country.isRecognized,
            ),
        )

        val summary = HtmlText.excerpt(raw.descriptionHtml, EXCERPT_CHARS)
        val benefitEvidence = benefitExtractor.extract(
            title = raw.title,
            description = plainDescription,
            tags = raw.tags,
            // Record where and when each claim was read, so a benefit can be re-checked
            // later and a stale claim shown as stale rather than as current.
            source = raw.providerName,
            verifiedAtEpochMillis = raw.retrievedAtEpochMillis ?: now,
        )

        val relevance = RoleClassifier.classify(
            title = raw.title,
            descriptionHead = plainDescription.take(DESCRIPTION_HEAD_CHARS),
        )
        val requirements = RequirementAnalyzer.analyze(plainDescription)
        val gaps = QualificationMatcher.gaps(requirements, profile)
        val evidence = evidenceEngine.build(
            eligibility = eligibility.eligibility,
            visaSupport = eligibility.visaSupport,
            eligibilityReasons = eligibility.reasons,
            benefits = benefitEvidence,
            gaps = gaps,
            sourceUrl = raw.url,
        )

        val job = Job(
            id = "${raw.providerId}:${raw.externalId}",
            title = raw.title.ifBlank { "Untitled role" },
            company = raw.company.ifBlank { "Unknown employer" },
            companyAccent = accentFor(raw.company),
            country = country.name,
            countryFlag = country.flag,
            city = cityOf(raw.location),
            salaryMin = raw.salaryMin ?: 0,
            salaryMax = raw.salaryMax ?: raw.salaryMin ?: 0,
            currencyCode = raw.salaryCurrency ?: "",
            salaryPeriod = raw.salaryPeriod ?: com.aiglobal.jobfinder.data.model.SalaryPeriod.YEAR,
            matchScore = match.score,
            eligibility = eligibility.eligibility,
            visaSupport = eligibility.visaSupport,
            scamRisk = scamRiskFor(raw),
            // Never invent a publication date: a month-old posting shown as "just now"
            // misleads the candidate. Keep null when the source did not state one.
            postedAtEpochMillis = raw.postedAtEpochMillis,
            expiresAtEpochMillis = raw.expiresAtEpochMillis,
            retrievedAtEpochMillis = raw.retrievedAtEpochMillis ?: now,
            source = raw.providerName,
            sourceUrl = raw.url,
            workMode = if (raw.isRemote) WorkMode.REMOTE else WorkMode.ONSITE,
            employmentType = employmentTypeOf(raw.employmentType, plainDescription),
            experienceYears = match.requiredYears ?: 0,
            matchedSkills = match.matchedSkills,
            missingSkills = match.missingSkills,
            languages = match.requiredLanguages,
            summary = summary,
            responsibilities = emptyList(),
            benefits = emptyList(),
            aiInsight = insightFor(match.score, match.reasons, eligibility.eligibility),
            providerId = raw.providerId,
            salaryIsEstimated = raw.salaryMin == null,
            matchReasons = match.reasons,
            eligibilityReasons = eligibility.reasons,
            benefitEvidence = benefitEvidence,
            roleFamily = relevance.family,
            matchedTargetRole = relevance.matchedTarget?.canonical,
            requirements = requirements,
            qualificationGaps = gaps,
            evidence = evidence,
            openToApplicants = raw.openToApplicants,
            isNew = postedDaysAgo != null && postedDaysAgo <= 3,
        )

        // The verification read depends on fields the Job now carries (link, eligibility,
        // documented benefits, retrieval time), so it is built from the assembled Job.
        return job.copy(verification = verificationEngine.assess(job, now))
    }

    /** Adds the normalised salary signal without changing the raw display values. */
    fun eurPerYear(job: Job): Int? =
        if (job.salaryMin <= 0) null
        else CurrencyConverter.toEurPerYear(job.salaryMin, job.currencyCode, job.salaryPeriod)

    private fun insightFor(score: Int, reasons: List<MatchReason>, eligibility: com.aiglobal.jobfinder.data.model.MadagascarEligibility): String {
        val strongest = reasons.filter { it.signal == com.aiglobal.jobfinder.data.model.MatchSignal.POSITIVE }
            .maxByOrNull { it.weight }
        val weakest = reasons.filter { it.signal == com.aiglobal.jobfinder.data.model.MatchSignal.NEGATIVE }
            .minByOrNull { it.weight }
        val eligibilityNote = when (eligibility) {
            com.aiglobal.jobfinder.data.model.MadagascarEligibility.ELIGIBLE -> "Applicants from Madagascar are accepted."
            com.aiglobal.jobfinder.data.model.MadagascarEligibility.VISA_SPONSORED -> "The employer states visa sponsorship."
            com.aiglobal.jobfinder.data.model.MadagascarEligibility.UNCLEAR -> "Eligibility for Madagascar applicants is not stated — verify first."
            com.aiglobal.jobfinder.data.model.MadagascarEligibility.RESTRICTED -> "This posting is limited to local applicants."
        }
        return buildString {
            append(eligibilityNote)
            strongest?.let { append(" Strongest signal: ${it.label.lowercase()}.") }
            weakest?.let { append(" Weakest: ${it.label.lowercase()} — ${it.detail}") }
        }
    }

    /** Remote/worldwide sources are legitimate, but not verified employers, so never "verified". */
    private fun scamRiskFor(raw: RawJob): ScamRisk = when {
        raw.company.isBlank() -> ScamRisk.MEDIUM
        raw.url.isBlank() -> ScamRisk.MEDIUM
        else -> ScamRisk.LOW
    }

    private fun employmentTypeOf(raw: String?, description: String): EmploymentType {
        val haystack = (raw.orEmpty() + " " + description).lowercase()
        return when {
            haystack.contains("part-time") || haystack.contains("part time") -> EmploymentType.PART_TIME
            haystack.contains("contract") || haystack.contains("freelance") -> EmploymentType.CONTRACT
            haystack.contains("intern") -> EmploymentType.INTERNSHIP
            haystack.contains("temporary") || haystack.contains("temp ") -> EmploymentType.TEMPORARY
            else -> EmploymentType.FULL_TIME
        }
    }

    /** The city part of "City, Country", or empty when the location is just a country. */
    private fun cityOf(location: String): String {
        val first = location.substringBefore(',').trim()
        return if (first.equals(CountryCatalog.WORLDWIDE, ignoreCase = true)) "" else first
    }

    /** Stable colour per employer, so the same company keeps the same accent. */
    private fun accentFor(company: String): Long {
        val palette = listOf(
            0xFF1E4FD8, 0xFF0E7490, 0xFF7C3AED, 0xFFB45309, 0xFF047857,
            0xFFBE123C, 0xFF2563EB, 0xFF0F766E,
        )
        if (company.isBlank()) return palette.first()
        return palette[company.hashCode().absoluteValue % palette.size]
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val EXCERPT_CHARS = 260
        const val DESCRIPTION_HEAD_CHARS = 400
    }
}
