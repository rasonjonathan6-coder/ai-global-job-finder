package com.aiglobal.jobfinder.data.cv.analysis

import com.aiglobal.jobfinder.data.model.CandidateField
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.CompletenessHint
import com.aiglobal.jobfinder.data.model.ProfileCompleteness

/**
 * Combines a fresh extraction with what is already on file.
 *
 * The rule is simple and deliberately conservative: a field the user edited by hand is
 * never overwritten, and an extraction that found nothing never erases a value the user
 * already had. That keeps re-importing a CV from destroying manual corrections.
 */
object CvProfileMerger {

    fun merge(
        extracted: CandidateProfile,
        existing: CandidateProfile,
        protectedFields: Set<CandidateField>,
    ): CandidateProfile {
        fun <T> choose(field: CandidateField, fresh: T, previous: T, isPresent: (T) -> Boolean): T =
            when {
                protectedFields.contains(field) && isPresent(previous) -> previous
                isPresent(fresh) -> fresh
                else -> previous
            }

        val notBlank: (String) -> Boolean = { it.isNotBlank() }
        val notEmpty: (List<*>) -> Boolean = { it.isNotEmpty() }
        val present: (Any?) -> Boolean = { it != null }

        return CandidateProfile(
            fullName = choose(CandidateField.FULL_NAME, extracted.fullName, existing.fullName, notBlank),
            email = choose(CandidateField.EMAIL, extracted.email, existing.email, notBlank),
            phone = choose(CandidateField.PHONE, extracted.phone, existing.phone, notBlank),
            location = choose(CandidateField.LOCATION, extracted.location, existing.location, notBlank),
            currentTitle = choose(CandidateField.CURRENT_TITLE, extracted.currentTitle, existing.currentTitle, notBlank),
            previousTitles = choose(CandidateField.PREVIOUS_TITLES, extracted.previousTitles, existing.previousTitles, notEmpty),
            yearsOfExperience = choose(CandidateField.YEARS_OF_EXPERIENCE, extracted.yearsOfExperience, existing.yearsOfExperience, present),
            skills = choose(CandidateField.SKILLS, extracted.skills, existing.skills, notEmpty),
            industries = choose(CandidateField.INDUSTRIES, extracted.industries, existing.industries, notEmpty),
            education = choose(CandidateField.EDUCATION, extracted.education, existing.education, notEmpty),
            certifications = choose(CandidateField.CERTIFICATIONS, extracted.certifications, existing.certifications, notEmpty),
            languages = choose(CandidateField.LANGUAGES, extracted.languages, existing.languages, notEmpty),
            preferredCountries = choose(CandidateField.PREFERRED_COUNTRIES, extracted.preferredCountries, existing.preferredCountries, notEmpty),
            preferredRoles = choose(CandidateField.PREFERRED_ROLES, extracted.preferredRoles, existing.preferredRoles, notEmpty),
            salaryExpectationMin = choose(CandidateField.SALARY_EXPECTATION, extracted.salaryExpectationMin, existing.salaryExpectationMin, present),
            salaryExpectationMax = choose(CandidateField.SALARY_EXPECTATION, extracted.salaryExpectationMax, existing.salaryExpectationMax, present),
            salaryCurrency = choose(CandidateField.SALARY_EXPECTATION, extracted.salaryCurrency, existing.salaryCurrency, present),
            relocationPreference = if (
                protectedFields.contains(CandidateField.RELOCATION) &&
                existing.relocationPreference != com.aiglobal.jobfinder.data.model.RelocationPreference.UNKNOWN
            ) {
                existing.relocationPreference
            } else if (extracted.relocationPreference != com.aiglobal.jobfinder.data.model.RelocationPreference.UNKNOWN) {
                extracted.relocationPreference
            } else {
                existing.relocationPreference
            },
            needsVisaSponsorship = choose(CandidateField.VISA, extracted.needsVisaSponsorship, existing.needsVisaSponsorship, present),
            workPermitNotes = choose(CandidateField.VISA, extracted.workPermitNotes, existing.workPermitNotes, notBlank),
            summary = choose(CandidateField.SUMMARY, extracted.summary, existing.summary, notBlank),
        )
    }
}

/** Field-by-field scoring of how ready a profile is to be matched against real jobs. */
object CvCompleteness {

    private data class Rule(
        val field: CandidateField,
        val weight: Int,
        val isPresent: (CandidateProfile) -> Boolean,
    )

    private val rules = listOf(
        Rule(CandidateField.FULL_NAME, 10) { it.fullName.isNotBlank() },
        Rule(CandidateField.EMAIL, 12) { it.email.isNotBlank() },
        Rule(CandidateField.PHONE, 6) { it.phone.isNotBlank() },
        Rule(CandidateField.LOCATION, 6) { it.location.isNotBlank() },
        Rule(CandidateField.CURRENT_TITLE, 12) { it.currentTitle.isNotBlank() },
        Rule(CandidateField.YEARS_OF_EXPERIENCE, 10) { it.yearsOfExperience != null },
        Rule(CandidateField.SKILLS, 16) { it.skills.size >= 3 },
        Rule(CandidateField.EDUCATION, 10) { it.education.isNotEmpty() },
        Rule(CandidateField.LANGUAGES, 8) { it.languages.isNotEmpty() },
        Rule(CandidateField.PREFERRED_COUNTRIES, 6) { it.preferredCountries.isNotEmpty() },
        Rule(CandidateField.SUMMARY, 4) { it.summary.isNotBlank() },
    )

    fun of(profile: CandidateProfile): ProfileCompleteness {
        val total = rules.sumOf { it.weight }
        val earned = rules.filter { it.isPresent(profile) }.sumOf { it.weight }
        val missing = rules.filterNot { it.isPresent(profile) }.map {
            CompletenessHint(label = it.field.label, field = it.field)
        }
        return ProfileCompleteness(percent = (earned * 100) / total, missing = missing)
    }
}
