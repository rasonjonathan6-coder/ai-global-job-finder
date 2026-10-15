package com.aiglobal.jobfinder.data.ai

import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobRequirements
import com.aiglobal.jobfinder.data.model.LanguageSkill

/**
 * The facts the assistant is allowed to use, assembled from the CV and the posting.
 *
 * This is the single place that decides what leaves the device when the user turns the
 * assistant on. It is deliberately narrow: the candidate's own verified fields and the
 * posting's own text, and nothing else. In particular it never includes the CV file, the
 * candidate's email, phone or full address, or any credential — a drafting assistant does
 * not need them, so they are not sent.
 */
data class GroundingContext(
    val candidateName: String,
    val currentTitle: String,
    val yearsOfExperience: Int?,
    val skills: List<String>,
    val industries: List<String>,
    val education: String,
    val certifications: List<String>,
    val languages: List<String>,
    val summary: String,
    val jobTitle: String,
    val company: String,
    val country: String,
    val jobSummary: String,
    val jobResponsibilities: List<String>,
    val matchedSkills: List<String>,
    val missingSkills: List<String>,
    val requirements: JobRequirements,
) {
    /** A short, plain rendering of the candidate for a prompt. Empty fields are omitted. */
    fun candidateBlock(): String = buildString {
        appendLine("CANDIDATE (facts taken from their CV — use only these):")
        appendLine("- Name: ${candidateName.ifBlank { "not stated" }}")
        appendLine("- Current title: ${currentTitle.ifBlank { "not stated" }}")
        yearsOfExperience?.let { appendLine("- Years of experience: $it") }
        if (skills.isNotEmpty()) appendLine("- Skills: ${skills.joinToString(", ")}")
        if (industries.isNotEmpty()) appendLine("- Industries: ${industries.joinToString(", ")}")
        if (education.isNotBlank()) appendLine("- Education: $education")
        if (certifications.isNotEmpty()) appendLine("- Certifications: ${certifications.joinToString(", ")}")
        if (languages.isNotEmpty()) appendLine("- Languages: ${languages.joinToString(", ")}")
        if (summary.isNotBlank()) appendLine("- Own summary: $summary")
    }

    /** A short rendering of the posting for a prompt. */
    fun jobBlock(): String = buildString {
        appendLine("POSTING (the job they are applying to):")
        appendLine("- Title: $jobTitle")
        appendLine("- Company: ${company.ifBlank { "not stated" }}")
        appendLine("- Country: ${country.ifBlank { "not stated" }}")
        if (jobSummary.isNotBlank()) appendLine("- Summary: $jobSummary")
        if (jobResponsibilities.isNotEmpty()) {
            appendLine("- Responsibilities: ${jobResponsibilities.take(6).joinToString("; ")}")
        }
        requirements.minYears?.let { appendLine("- Required experience: $it years") }
        if (requirements.certifications.isNotEmpty()) {
            appendLine("- Required certifications: ${requirements.certifications.take(6).joinToString(", ") { it.label }}")
        }
        if (matchedSkills.isNotEmpty()) appendLine("- Skills the CV already shows: ${matchedSkills.joinToString(", ")}")
        if (missingSkills.isNotEmpty()) appendLine("- Requirements the CV does not show: ${missingSkills.joinToString(", ")}")
    }

    companion object {
        /** Builds the context, trimming every value to what is safe and useful to send. */
        fun of(profile: CandidateProfile, job: Job): GroundingContext = GroundingContext(
            candidateName = profile.fullName.trim(),
            currentTitle = profile.currentTitle.trim(),
            yearsOfExperience = profile.yearsOfExperience,
            skills = profile.skills.take(30),
            industries = profile.industries.take(10),
            education = profile.education.take(2).joinToString("; ") { it.display() },
            certifications = profile.certifications.take(10).map { it.name },
            languages = profile.languages.take(6).map(LanguageSkill::displayLabel),
            summary = profile.summary.trim().take(600),
            jobTitle = job.title,
            company = job.company,
            country = job.country,
            jobSummary = job.summary.take(600),
            jobResponsibilities = job.responsibilities.take(8),
            matchedSkills = job.matchedSkills.take(20),
            missingSkills = job.missingSkills.take(20),
            requirements = job.requirements,
        )
    }
}

private fun com.aiglobal.jobfinder.data.model.EducationEntry.display(): String =
    listOf(degree, institution, country).filter { it.isNotBlank() }.joinToString(", ")
