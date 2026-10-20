package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable
import com.aiglobal.jobfinder.data.cv.analysis.CvCompleteness
import kotlinx.serialization.Serializable

@Serializable
enum class ApplicationStatus { SAVED, PREPARING, APPLIED, INTERVIEW, OFFER, REJECTED, CLOSED }

@Serializable
@Immutable
data class ApplicationRecord(
    val id: String,
    val jobId: String,
    val jobTitle: String,
    val company: String,
    val country: String,
    val countryFlag: String,
    val companyAccent: Long,
    val status: ApplicationStatus,
    val matchScore: Int,
    val appliedAtEpochMillis: Long?,
    val updatedAtEpochMillis: Long,
    val nextStepLabel: String?,
    val nextStepAtEpochMillis: Long?,
    val hasCoverLetter: Boolean,
    val notes: String = "",
    /** Id of the generated cover letter for this application, when one exists. */
    val coverLetterId: String? = null,
    /** The interview date/time, once one is scheduled. Drives the interview reminders. */
    val interviewAtEpochMillis: Long? = null,
    /** When the user wants to be reminded to follow up, if at all. */
    val followUpAtEpochMillis: Long? = null,
    /** Append-only history of everything that happened to this application. */
    val timeline: List<ApplicationEvent> = emptyList(),
    /** Employer's own posting page, kept so the detail screen can link out. */
    val sourceUrl: String = "",
    /** Which job source supplied the posting, for attribution on the detail screen. */
    val source: String = "",
) {
    /** True when a follow-up is planned and its date has arrived or passed. */
    fun followUpDue(now: Long): Boolean =
        followUpAtEpochMillis?.let { it <= now && !status.isTerminal } ?: false

    /** True when the interview is scheduled and still ahead. */
    fun interviewUpcoming(now: Long): Boolean =
        interviewAtEpochMillis?.let { it > now && !status.isTerminal } ?: false
}

@Immutable
data class Skill(
    val name: String,
    val category: SkillCategory,
    val proficiency: Proficiency,
)

enum class SkillCategory { TECHNICAL, TOOLS, SOFT, LANGUAGE, CERTIFICATION }

enum class Proficiency { BASIC, INTERMEDIATE, ADVANCED, EXPERT }

@Immutable
data class ExperienceEntry(
    val role: String,
    val company: String,
    val country: String,
    val startYear: Int,
    val endYear: Int?,
    val highlights: List<String>,
)

@Immutable
data class EducationEntry(
    val degree: String,
    val institution: String,
    val country: String,
    val year: Int,
)

@Immutable
data class UserProfile(
    val fullName: String,
    val headline: String,
    val location: String,
    val email: String,
    val phone: String,
    val summary: String,
    val yearsOfExperience: Int,
    val skills: List<Skill>,
    val experience: List<ExperienceEntry>,
    val education: List<EducationEntry>,
    val languages: List<String>,
    val targetCountries: List<String>,
    val targetRoles: List<String>,
    val cvImported: Boolean,
    val cvFileName: String?,
    val cvUpdatedAtEpochMillis: Long?,
    val autoApplyEnabled: Boolean,
    val profileCompletion: Int,
) {
    val initials: String
        get() = fullName.trim().split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifEmpty { "JG" }
}

/**
 * Builds the display profile from what the app actually knows: the imported CV and the
 * user's auto-apply preference. Nothing is invented — every empty field stays empty, and
 * the UI shows an empty state until the candidate imports a CV.
 */
fun buildUserProfile(record: CvRecord?, autoApplyEnabled: Boolean): UserProfile {
    val profile = record?.profile ?: CandidateProfile()

    val skills = buildList {
        profile.skills.forEach { add(Skill(it, SkillCategory.TECHNICAL, Proficiency.ADVANCED)) }
        profile.certifications.forEach {
            add(Skill(it.name, SkillCategory.CERTIFICATION, Proficiency.INTERMEDIATE))
        }
        profile.languages.forEach { add(Skill(it.name, SkillCategory.LANGUAGE, Proficiency.INTERMEDIATE)) }
    }

    return UserProfile(
        fullName = profile.fullName,
        headline = profile.currentTitle,
        location = profile.location,
        email = profile.email,
        phone = profile.phone,
        summary = profile.summary,
        yearsOfExperience = profile.yearsOfExperience ?: 0,
        skills = skills,
        experience = emptyList(),
        education = profile.education,
        languages = profile.languages.map { it.name },
        targetCountries = profile.preferredCountries,
        targetRoles = profile.preferredRoles,
        cvImported = record != null && record.status == CvStatus.ANALYZED,
        cvFileName = record?.fileName,
        cvUpdatedAtEpochMillis = record?.analyzedAtEpochMillis,
        autoApplyEnabled = autoApplyEnabled,
        profileCompletion = CvCompleteness.of(profile).percent,
    )
}
