package com.aiglobal.jobfinder.data.jobs.search

import androidx.compose.runtime.Immutable

/** How a posting's title relates to what the candidate is looking for. */
enum class RoleFamily {
    /** The title names one of the candidate's target roles. */
    TARGET,
    /** A maintenance/trade role that is not one of the canonical targets. */
    MAINTENANCE_FAMILY,
    /** The title is for a clearly different profession. */
    UNRELATED,
    /** There was not enough of a title to judge. */
    UNKNOWN,
}

/**
 * The verdict for one posting title, with the words that produced it so the reason can be
 * shown to the user rather than asserted.
 */
@Immutable
data class RoleRelevance(
    val family: RoleFamily,
    /** The target role the title matched, when it is one of the canonical targets. */
    val matchedTarget: TargetRole? = null,
    /** The words in the title/description that drove the verdict, for the explanation. */
    val evidence: List<String> = emptyList(),
) {
    val isTargeted: Boolean get() = family == RoleFamily.TARGET
    val isMaintenanceFamily: Boolean get() = family == RoleFamily.TARGET || family == RoleFamily.MAINTENANCE_FAMILY
}

/**
 * Decides whether a posting is a maintenance/trade role the candidate should see.
 *
 * This is deliberately title-led. A generic word such as "technician", "engineer" or
 * "maintenance" appearing once in a long description is not evidence that the posting is
 * the candidate's job; only a title that names the trade (or names plant machinery or a
 * specific plant/mining setting alongside a trade word) qualifies. The point is to reject
 * an unrelated global remote feed rather than to maximise the number of results.
 *
 * The one subtlety is that "industrial" and "manufacturing" are not the same as "mining"
 * or "conveyor". A maintenance role at an industrial employer is still a maintenance role,
 * but an employer's blurb describing itself as an industrial company is not evidence that
 * a "Lab Technician" posting is the candidate's trade — so the broad sector words only
 * count when they appear in the *title*, and the concrete equipment/mining words count
 * from the description as well.
 */
object RoleClassifier {

    /** How much of the description is read for supporting equipment/industry words. */
    private const val DESCRIPTION_HEAD_CHARS = 400

    fun classify(title: String, descriptionHead: String = ""): RoleRelevance {
        val titleText = title.lowercase().trim()
        if (titleText.isBlank()) return RoleRelevance(RoleFamily.UNKNOWN)

        val head = descriptionHead.lowercase().take(DESCRIPTION_HEAD_CHARS)

        // 0. A title that names a different profession is not the candidate's job, even if it
        //    also carries a maintenance word ("Software Engineer, Maintenance Tooling").
        val otherProfession = TargetSearch.otherProfessionMarkers.filter { Phrases.contains(titleText, it) }
        if (otherProfession.isNotEmpty()) {
            return RoleRelevance(RoleFamily.UNRELATED, evidence = otherProfession)
        }

        // 1. A canonical target role in the title always wins.
        val matchedTarget = TargetSearch.roles.firstOrNull { role ->
            role.titleSignals.any { Phrases.contains(titleText, it) }
        }
        if (matchedTarget != null) {
            val signals = matchedTarget.titleSignals.filter { Phrases.contains(titleText, it) }
            return RoleRelevance(RoleFamily.TARGET, matchedTarget, signals)
        }

        val evidence = mutableListOf<String>()
        val strong = TargetSearch.strongCoreTerms.filter { Phrases.contains(titleText, it) }
        val weak = TargetSearch.weakCoreTerms.filter { Phrases.contains(titleText, it) }
        // Equipment and a named plant/mining setting count from the title or the description
        // head. Broad sector words ("industrial", "manufacturing") count only from the title:
        // reading them out of an employer's blurb is what let a "Lab Technician" at a battery
        // company pass as a maintenance role.
        val equipment = TargetSearch.equipmentTerms.filter {
            Phrases.contains(titleText, it) || Phrases.contains(head, it)
        }
        val titleIndustry = TargetSearch.industryTerms.filter { Phrases.contains(titleText, it) }
        val descriptionIndustry = TargetSearch.descriptionIndustryTerms.filter { Phrases.contains(head, it) }
        val industry = (titleIndustry + descriptionIndustry).distinct()
        evidence += strong
        evidence += weak
        evidence += equipment
        evidence += industry

        // 2. A strong trade word in the title is maintenance family on its own.
        if (strong.isNotEmpty()) {
            return RoleRelevance(RoleFamily.MAINTENANCE_FAMILY, evidence = evidence.distinct())
        }

        // 3. A weak trade word needs a concrete machine, a named plant/mining setting in the
        //    title, or a specific processing setting in the description — not just an employer
        //    that calls itself industrial.
        if (weak.isNotEmpty() && (equipment.isNotEmpty() || titleIndustry.isNotEmpty() || descriptionIndustry.isNotEmpty())) {
            return RoleRelevance(RoleFamily.MAINTENANCE_FAMILY, evidence = evidence.distinct())
        }

        // 4. Everything else — including a lone "technician" with no trade context and any
        //    clearly different profession — is not the candidate's job.
        return RoleRelevance(RoleFamily.UNRELATED, evidence = evidence.distinct())
    }
}

/**
 * Whole-word/phrase containment, so "fit" does not fire inside "benefit" and "pump" does
 * not fire inside "pumpkin". Phrases may contain spaces.
 */
internal object Phrases {
    fun contains(haystack: String, phrase: String): Boolean {
        val needle = phrase.lowercase().trim()
        if (needle.isEmpty()) return false
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            val beforeOk = index == 0 || !haystack[index - 1].isLetterOrDigit()
            val afterIndex = index + needle.length
            val afterOk = afterIndex >= haystack.length || !haystack[afterIndex].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            index = haystack.indexOf(needle, index + 1)
        }
        return false
    }
}
