package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** How well the CV matches the posting, derived from [Job.matchScore]. */
enum class MatchTier { EXCELLENT, STRONG, GOOD, FAIR, LOW;

    companion object {
        fun fromScore(score: Int): MatchTier = when {
            score >= 90 -> EXCELLENT
            score >= 75 -> STRONG
            score >= 60 -> GOOD
            score >= 45 -> FAIR
            else -> LOW
        }
    }
}

/** Whether a candidate currently in Madagascar can realistically apply. */
enum class MadagascarEligibility { ELIGIBLE, VISA_SPONSORED, UNCLEAR, RESTRICTED }

/** Relocation / visa posture advertised by the employer. */
enum class VisaSupport { FULL_SPONSORSHIP, RELOCATION_PACKAGE, SPONSORSHIP_AVAILABLE, NOT_MENTIONED, LOCAL_ONLY }

enum class ScamRisk { LOW, MEDIUM, HIGH }

enum class WorkMode { ONSITE, HYBRID, REMOTE }

enum class EmploymentType { FULL_TIME, CONTRACT, PART_TIME, TEMPORARY, INTERNSHIP }

/**
 * A single job opportunity. Stage 1 renders curated sample records; later
 * stages populate the same shape from live sources such as EURES, UN Careers,
 * ReliefWeb, Canada Job Bank and Make it in Germany.
 */
@Immutable
data class Job(
    val id: String,
    val title: String,
    val company: String,
    val companyAccent: Long,
    val country: String,
    val countryFlag: String,
    val city: String,
    val salaryMin: Int,
    val salaryMax: Int,
    val currencyCode: String,
    val salaryPeriod: SalaryPeriod,
    val matchScore: Int,
    val eligibility: MadagascarEligibility,
    val visaSupport: VisaSupport,
    val scamRisk: ScamRisk,
    val postedAtEpochMillis: Long,
    val source: String,
    val sourceUrl: String,
    val workMode: WorkMode,
    val employmentType: EmploymentType,
    val experienceYears: Int,
    val matchedSkills: List<String>,
    val missingSkills: List<String>,
    val languages: List<String>,
    val summary: String,
    val responsibilities: List<String>,
    val benefits: List<String>,
    val aiInsight: String,
    /** Provider that supplied the posting, for the source badge and filters. */
    val providerId: String = "",
    /** True when the salary fields came from the posting; false means "not stated". */
    val salaryIsEstimated: Boolean = false,
    /** Every point of [matchScore], in display order. */
    val matchReasons: List<MatchReason> = emptyList(),
    /** Why the eligibility verdict was reached, official facts first. */
    val eligibilityReasons: List<EligibilityReason> = emptyList(),
    val isSaved: Boolean = false,
    val isNew: Boolean = false,
) {
    val tier: MatchTier get() = MatchTier.fromScore(matchScore)

    /** True when there is a believable path to apply from Madagascar. */
    val applyableFromMadagascar: Boolean
        get() = eligibility == MadagascarEligibility.ELIGIBLE ||
            eligibility == MadagascarEligibility.VISA_SPONSORED

    val accentColor: Color get() = Color(companyAccent)
}

enum class SalaryPeriod { YEAR, MONTH, DAY, HOUR }

/** Compact statistics surfaced on the home dashboard. */
@Immutable
data class DashboardStats(
    val matchingJobs: Int,
    val newOpportunities: Int,
    val applications: Int,
    val interviews: Int,
)
