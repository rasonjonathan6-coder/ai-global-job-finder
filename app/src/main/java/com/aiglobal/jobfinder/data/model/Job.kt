package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.aiglobal.jobfinder.data.jobs.search.RoleFamily
import kotlinx.serialization.Serializable

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
enum class MadagascarEligibility {
    ELIGIBLE, VISA_SPONSORED, UNCLEAR, RESTRICTED;

    /**
     * Ordering weight for "can I apply from here?": a direct eligibility or a sponsored
     * route outranks an unclear posting, which outranks one that rules expats out. Derived
     * here so the sort and the UI reason from the same value.
     */
    val rank: Int
        get() = when (this) {
            ELIGIBLE -> 3
            VISA_SPONSORED -> 2
            UNCLEAR -> 1
            RESTRICTED -> 0
        }
}

/** Relocation / visa posture advertised by the employer. */
enum class VisaSupport { FULL_SPONSORSHIP, RELOCATION_PACKAGE, SPONSORSHIP_AVAILABLE, NOT_MENTIONED, LOCAL_ONLY }

@Serializable
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
    /** When the source published the posting, or null when it did not state a date. */
    val postedAtEpochMillis: Long?,
    /** When the app retrieved this posting. Null only for records built before Stage 13. */
    val retrievedAtEpochMillis: Long? = null,
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
    /**
     * What the posting documents about expat benefits, with the sentence that proves it.
     * This is independent of the user's priorities, so it can be cached with the posting;
     * the [ExpatBenefitsAssessment] is derived later, once preferences are known.
     */
    val benefitEvidence: List<BenefitEvidence> = emptyList(),
    /**
     * Whether the posting's title is one of the candidate's target roles, the wider
     * maintenance family, or an unrelated profession. Computed locally because the remote
     * boards return broad samples; the Jobs screen can hide non-relevant postings with it.
     */
    val roleFamily: RoleFamily = RoleFamily.UNKNOWN,
    /** The canonical target role the title matched, when it is one of them. */
    val matchedTargetRole: String? = null,
    /** What the posting appears to require, read from its own text. */
    val requirements: JobRequirements = JobRequirements(),
    /** Requirements paired with whether the CV appears to meet them. */
    val qualificationGaps: List<QualificationGap> = emptyList(),
    /** Typed, source-linked evidence for every claim the app makes about this posting. */
    val evidence: List<Evidence> = emptyList(),
    val isSaved: Boolean = false,
    val isNew: Boolean = false,
) {
    val tier: MatchTier get() = MatchTier.fromScore(matchScore)

    /** True when the posting's title belongs to the candidate's trade. */
    val isMaintenanceRelevant: Boolean
        get() = roleFamily == RoleFamily.TARGET || roleFamily == RoleFamily.MAINTENANCE_FAMILY

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
