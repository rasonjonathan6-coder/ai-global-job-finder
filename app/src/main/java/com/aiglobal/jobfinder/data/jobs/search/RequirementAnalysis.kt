package com.aiglobal.jobfinder.data.jobs.search

import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.JobRequirements
import com.aiglobal.jobfinder.data.model.QualificationGap

/**
 * Compares what a posting asks for against what the CV actually shows, and reports the
 * gaps. A gap is never invented: when the profile does not record the thing being asked
 * about, the result is [QualificationGap.met] == null ("cannot tell"), not `false`.
 */
object QualificationMatcher {

    fun gaps(requirements: JobRequirements, profile: CandidateProfile): List<QualificationGap> {
        val gaps = mutableListOf<QualificationGap>()

        requirements.minYears?.let { minYears ->
            val met = profile.yearsOfExperience?.let { it >= minYears }
            gaps += QualificationGap(
                label = "At least $minYears years of experience",
                evidence = "The posting states a minimum of $minYears years.",
                met = met,
            )
        }

        requirements.certifications.forEach { requirement ->
            gaps += QualificationGap(
                label = requirement.label,
                evidence = requirement.evidence,
                met = matchesCandidate(requirement.label, profile),
            )
        }

        requirements.licences.forEach { requirement ->
            gaps += QualificationGap(
                label = requirement.label,
                evidence = requirement.evidence,
                met = licenceMatch(requirement.label, profile),
            )
        }

        return gaps.distinctBy { it.label }
    }

    private fun matchesCandidate(label: String, profile: CandidateProfile): Boolean? {
        val needles = label.lowercase().split(" ", "&", "/").filter { it.length >= 3 }
        if (needles.isEmpty()) return null
        val haystack = buildList {
            addAll(profile.certifications.map { it.name })
            addAll(profile.skills)
            add(profile.summary)
        }.joinToString(" ").lowercase()
        // Only a positive match is asserted. A miss is reported as "cannot tell" rather than
        // "you do not have this", because the CV may simply not mention it.
        return if (needles.any { haystack.contains(it) }) true else null
    }

    private fun licenceMatch(label: String, profile: CandidateProfile): Boolean? {
        val haystack = buildList {
            addAll(profile.certifications.map { it.name })
            addAll(profile.skills)
            add(profile.summary)
            add(profile.workPermitNotes)
        }.joinToString(" ").lowercase()
        return when {
            label.contains("driv", ignoreCase = true) ->
                if (haystack.contains("driv")) true else null
            label.contains("work permit", ignoreCase = true) ||
                label.contains("right to work", ignoreCase = true) ||
                label.contains("authoris", ignoreCase = true) ||
                label.contains("authoriz", ignoreCase = true) -> null
            else -> if (haystack.contains(label.lowercase())) true else null
        }
    }
}
