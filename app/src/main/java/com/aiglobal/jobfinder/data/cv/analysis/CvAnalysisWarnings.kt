package com.aiglobal.jobfinder.data.cv.analysis

import com.aiglobal.jobfinder.data.model.CandidateProfile

/**
 * Which sections the document actually carried, so an absent section can be reported
 * differently from one that is present but could not be read. Recorded from the deterministic
 * pass and carried on the analysis result, because the AI pass cannot see the raw sections.
 */
data class CvSectionPresence(
    val skills: Boolean = false,
    val education: Boolean = false,
    val languages: Boolean = false,
) {
    companion object {
        val NONE = CvSectionPresence()
    }
}

/**
 * The single source of the "worth checking" notes.
 *
 * Every note is derived from the *same* [CandidateProfile] the UI renders, so a note can never
 * contradict a section the user can see: if education is present, no "no education" note is
 * produced. This is what a previous stage got wrong — the AI path built its notes from the
 * on-device profile alone, so a profile that gained education and languages from the model was
 * still warned about as if they were missing.
 *
 * The optional [presence] only changes the *wording* ("a section was found but nothing was
 * readable" vs "no section was found"); it never decides whether a note appears.
 */
object CvAnalysisWarnings {

    fun warningsFor(
        profile: CandidateProfile,
        presence: CvSectionPresence = CvSectionPresence.NONE,
    ): List<String> = buildList {
        if (profile.skills.isEmpty()) {
            add(
                if (presence.skills) {
                    "A skills section was found but no known skills were recognised in it. Add yours by hand."
                } else {
                    "No skills section was found. Add your skills to improve matching."
                },
            )
        }
        if (profile.education.isEmpty()) {
            add(
                if (presence.education) {
                    "A training section was found but no degree or diploma could be read from it."
                } else {
                    "No education or training section was found."
                },
            )
        }
        if (profile.languages.isEmpty()) {
            add(
                if (presence.languages) {
                    "A languages section was found but no known language was recognised in it."
                } else {
                    "No languages were listed. Many international roles require a language level."
                },
            )
        }
        if (profile.email.isBlank()) {
            add("No email address was found. Add one so applications can be sent.")
        }
        if (profile.phone.isBlank()) {
            add("No phone number was found.")
        }
        if (profile.fullName.isBlank()) {
            add("The candidate name could not be identified. Check the document header.")
        }
    }
}
