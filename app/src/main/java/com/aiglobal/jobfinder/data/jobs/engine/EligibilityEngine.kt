package com.aiglobal.jobfinder.data.jobs.engine

import androidx.compose.runtime.Immutable
import com.aiglobal.jobfinder.data.model.EligibilityReason
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.VisaSupport

/**
 * The eligibility verdict for one posting.
 *
 * [reasons] distinguishes official facts (quoted from the posting) from heuristics, so
 * the UI can say "the employer states X" separately from "we think Y". The engine never
 * asserts that sponsorship exists when the posting is silent.
 */
@Immutable
data class EligibilityAssessment(
    val eligibility: MadagascarEligibility,
    val visaSupport: VisaSupport,
    val reasons: List<EligibilityReason>,
    /** True when a quoted official fact about international hiring was found. */
    val hasOfficialSignal: Boolean,
)

/**
 * Decides whether a candidate currently in Madagascar can realistically apply.
 *
 * Rules, in priority order:
 *  1. The posting states it is open only to local/national candidates → RESTRICTED.
 *  2. The posting states international candidates are welcome, or it is explicitly
 *     remote/worldwide → ELIGIBLE.
 *  3. The posting offers visa sponsorship or relocation → VISA_SPONSORED.
 *  4. Nothing is stated → UNCLEAR, with a reminder to verify. Absence of a statement is
 *     never treated as sponsorship.
 */
class EligibilityEngine {

    fun assess(input: EligibilityInput): EligibilityAssessment {
        val text = buildString {
            append(input.title).append(' ')
            append(input.location).append(' ')
            append(input.employmentType.orEmpty()).append(' ')
            append(input.tags.joinToString(" ")).append(' ')
            append(input.description)
        }.lowercase()

        val reasons = mutableListOf<EligibilityReason>()
        var hasOfficial = false

        // ---- 1. Hard restrictions stated by the employer.
        val restriction = LOCAL_ONLY_MARKERS.firstOrNull { text.contains(it) }
        if (restriction != null) {
            reasons += EligibilityReason(
                label = "Restricted to local applicants",
                detail = "The posting states \"$restriction\". Employers that phrase it this way " +
                    "normally require an existing work authorisation.",
                official = true,
            )
            hasOfficial = true
            return EligibilityAssessment(
                eligibility = MadagascarEligibility.RESTRICTED,
                visaSupport = VisaSupport.LOCAL_ONLY,
                reasons = reasons,
                hasOfficialSignal = hasOfficial,
            )
        }

        // ---- 2. Sponsorship / relocation statements.
        val sponsorship = SPONSORSHIP_MARKERS.firstOrNull { text.contains(it) }
        val relocation = RELOCATION_MARKERS.firstOrNull { text.contains(it) }
        if (sponsorship != null) {
            reasons += EligibilityReason(
                label = "Employer states visa sponsorship",
                detail = "The posting says \"$sponsorship\". Confirm the conditions with the employer before applying.",
                official = true,
            )
            hasOfficial = true
        }
        if (relocation != null) {
            reasons += EligibilityReason(
                label = "Relocation support mentioned",
                detail = "The posting mentions \"$relocation\". This usually implies the employer expects to hire from abroad.",
                official = true,
            )
            hasOfficial = true
        }

        // ---- 3. Explicit international openness.
        val international = INTERNATIONAL_MARKERS.firstOrNull { text.contains(it) }
        if (international != null) {
            reasons += EligibilityReason(
                label = "Open to international applicants",
                detail = "The posting says \"$international\".",
                official = true,
            )
            hasOfficial = true
        }

        // ---- 4. A remote or worldwide location is a published fact of its own.
        val remoteAnywhere = input.isRemote && (input.location.isBlank() ||
            REMOTE_WORLDWIDE_LOCATIONS.any { input.location.lowercase().contains(it) })
        if (remoteAnywhere) {
            reasons += EligibilityReason(
                label = "Location listed as remote or worldwide",
                detail = "The source lists this role for \"${input.location.ifBlank { "remote" }}\", so there is no local work-permit requirement stated.",
                official = true,
            )
            hasOfficial = true
        }

        val eligibility = when {
            sponsorship != null -> MadagascarEligibility.VISA_SPONSORED
            international != null || remoteAnywhere -> MadagascarEligibility.ELIGIBLE
            relocation != null -> MadagascarEligibility.ELIGIBLE
            else -> MadagascarEligibility.UNCLEAR
        }

        if (eligibility == MadagascarEligibility.UNCLEAR) {
            reasons += EligibilityReason(
                label = "Eligibility not stated",
                detail = "The posting does not say whether it accepts applicants from Madagascar. " +
                    "Check the official listing or ask the employer before you rely on it.",
                official = false,
            )
        }

        val visaSupport = when {
            sponsorship != null -> VisaSupport.SPONSORSHIP_AVAILABLE
            relocation != null -> VisaSupport.RELOCATION_PACKAGE
            else -> VisaSupport.NOT_MENTIONED
        }

        return EligibilityAssessment(
            eligibility = eligibility,
            visaSupport = visaSupport,
            reasons = reasons,
            hasOfficialSignal = hasOfficial,
        )
    }

    private companion object {
        val LOCAL_ONLY_MARKERS = listOf(
            "local candidates only",
            "locals only",
            "local applicants only",
            "must be authorized to work in",
            "must already have the right to work",
            "must have the right to work in",
            "no visa sponsorship",
            "we do not sponsor",
            "we are unable to sponsor",
            "cannot sponsor",
            "without sponsorship",
            "citizens only",
            "citizens and permanent residents",
            "must be a citizen",
            "residing in the country",
            "must reside in",
        )

        val SPONSORSHIP_MARKERS = listOf(
            "visa sponsorship",
            "sponsorship available",
            "we sponsor visas",
            "sponsor a work visa",
            "sponsorship is available",
            "work permit sponsorship",
            "tier 2 sponsorship",
            "skilled worker visa",
            "we will sponsor",
        )

        val RELOCATION_MARKERS = listOf(
            "relocation package",
            "relocation support",
            "relocation assistance",
            "relocation bonus",
            "we help you relocate",
            "visa and relocation",
        )

        val INTERNATIONAL_MARKERS = listOf(
            "international applicants",
            "international candidates",
            "open to international",
            "applicants from anywhere",
            "candidates from anywhere",
            "any nationality",
            "all nationalities",
            "worldwide applicants",
            "global applicants",
            "remote worldwide",
            "work from anywhere",
            "anywhere in the world",
        )

        val REMOTE_WORLDWIDE_LOCATIONS = listOf(
            "worldwide",
            "anywhere",
            "global",
            "remote",
            "any location",
        )
    }
}

/** Everything the engine may look at. Kept explicit so it is obvious what drives a verdict. */
@Immutable
data class EligibilityInput(
    val title: String,
    val location: String,
    val description: String,
    val tags: List<String> = emptyList(),
    val employmentType: String? = null,
    val isRemote: Boolean = false,
)
