package com.aiglobal.jobfinder.data.expat

import com.aiglobal.jobfinder.data.model.BenefitEvidence
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.BenefitType
import com.aiglobal.jobfinder.data.model.JobBenefitTypes

/**
 * Reads the expat benefits a posting documents, together with the sentence that proves
 * it. This is a pure function of the posting text: it never guesses, and it never treats
 * silence as a refusal.
 *
 * Rules, in priority order for each benefit:
 *  1. A phrase that explicitly refuses the benefit → [BenefitStatus.NOT_OFFERED].
 *  2. A phrase that states it, split into strong and hedged wording. Strong wording is
 *     the employer committing ("we provide accommodation"); hedged wording only hints at
 *     it ("may include"), so it is reported as [BenefitStatus.NEEDS_VERIFICATION].
 *  3. Nothing at all → [BenefitStatus.UNKNOWN]. This is the common case and must not be
 *     read as a negative statement about the employer.
 */
class ExpatBenefitExtractor {

    fun extract(title: String, description: String, tags: List<String> = emptyList()): List<BenefitEvidence> {
        val sentences = sentencesOf(listOf(title, tags.joinToString(" "), description).joinToString(". "))
        return JobBenefitTypes.all.map { type -> assess(type, sentences) }
    }

    private fun assess(type: BenefitType, sentences: List<String>): BenefitEvidence {
        val rules = RULES.getValue(type.key)

        val refusal = rules.refusals.firstNotNullOfOrNull { phrase ->
            sentences.firstOrNull { it.contains(phrase) }?.let { phrase to it }
        }
        if (refusal != null) {
            return BenefitEvidence(type, BenefitStatus.NOT_OFFERED, refusal.second.trim())
        }

        val strong = rules.strong.firstNotNullOfOrNull { phrase ->
            sentences.firstOrNull { it.contains(phrase) }?.let { phrase to it }
        }
        if (strong != null) {
            return BenefitEvidence(type, BenefitStatus.CONFIRMED, strong.second.trim())
        }

        val hedged = rules.hedged.firstNotNullOfOrNull { phrase ->
            sentences.firstOrNull { it.contains(phrase) }?.let { phrase to it }
        }
        if (hedged != null) {
            return BenefitEvidence(type, BenefitStatus.NEEDS_VERIFICATION, hedged.second.trim())
        }

        return BenefitEvidence(type, BenefitStatus.UNKNOWN, "")
    }

    /** Splits text into trimmed lowercase sentences, dropping empties. */
    private fun sentencesOf(text: String): List<String> =
        text.split(SENTENCE_SPLIT)
            .map { it.trim().lowercase() }
            .filter { it.length >= 3 }

    private data class Rules(
        val strong: List<String>,
        val hedged: List<String> = emptyList(),
        val refusals: List<String> = emptyList(),
    )

    private companion object {
        val SENTENCE_SPLIT = Regex("[.\\n;!?•]+")

        /**
         * Phrase tables per benefit key. Strong phrases are commitments; hedged phrases
         * are weak enough that the listing has to be checked before the user relies on
         * them; refusal phrases are explicit "not offered" statements.
         */
        val RULES: Map<String, Rules> = mapOf(
            JobBenefitTypes.VISA_SPONSORSHIP.key to Rules(
                strong = listOf(
                    "visa sponsorship", "sponsorship available", "sponsorship is available",
                    "we sponsor visas", "we will sponsor", "sponsor a work visa",
                    "work permit sponsorship", "tier 2 sponsorship", "skilled worker visa",
                    "visa and relocation", "employer-sponsored visa", "sponsor your work visa",
                    "we provide visa", "visa support provided",
                ),
                hedged = listOf("visa support", "sponsorship considered", "may sponsor"),
                refusals = listOf(
                    "no visa sponsorship", "we do not sponsor", "we are unable to sponsor",
                    "cannot sponsor", "without sponsorship", "no sponsorship",
                ),
            ),
            JobBenefitTypes.RELOCATION_SUPPORT.key to Rules(
                strong = listOf(
                    "relocation package", "relocation support", "relocation assistance",
                    "relocation allowance", "relocation bonus", "we help you relocate",
                    "we will help you relocate", "relocation covered", "settling-in allowance",
                ),
                hedged = listOf("relocation", "help with moving"),
                refusals = listOf("no relocation", "relocation is not provided", "no relocation support"),
            ),
            JobBenefitTypes.ACCOMMODATION.key to Rules(
                strong = listOf(
                    "accommodation provided", "company accommodation", "housing provided",
                    "provided accommodation", "free accommodation", "staff accommodation",
                    "accommodation is provided", "furnished accommodation",
                ),
                hedged = listOf("housing allowance", "accommodation allowance", "accommodation support"),
                refusals = listOf("no accommodation", "accommodation not provided"),
            ),
            JobBenefitTypes.FLIGHTS_HOME.key to Rules(
                strong = listOf(
                    "annual flight", "annual flights", "flight home", "flights home",
                    "return flight", "airfare provided", "flight allowance", "repatriation flight",
                ),
                hedged = listOf("flight allowance", "flights"),
                refusals = listOf("no flights", "flight not provided"),
            ),
            JobBenefitTypes.HEALTH_INSURANCE.key to Rules(
                strong = listOf(
                    "health insurance", "medical insurance", "health cover", "healthcare provided",
                    "private medical", "medical cover", "health insurance provided",
                ),
                hedged = listOf("insurance", "health care"),
                refusals = listOf("no health insurance", "no medical insurance"),
            ),
            JobBenefitTypes.MEALS.key to Rules(
                strong = listOf("meals provided", "free meals", "meal allowance", "food allowance", "canteen"),
                hedged = listOf("meals"),
                refusals = listOf("no meals", "meals not provided"),
            ),
            JobBenefitTypes.TRANSPORT.key to Rules(
                strong = listOf(
                    "transport provided", "company transport", "transport allowance",
                    "travel allowance", "shuttle service", "company car",
                ),
                hedged = listOf("transport", "travel reimbursement"),
                refusals = listOf("no transport", "transport not provided"),
            ),
            JobBenefitTypes.LANGUAGE_TRAINING.key to Rules(
                strong = listOf(
                    "language training", "language course", "german course", "language classes",
                    "paid language", "language support", "language tuition",
                ),
                hedged = listOf("language training", "language support"),
                refusals = listOf("no language training"),
            ),
            JobBenefitTypes.FAMILY_SUPPORT.key to Rules(
                strong = listOf(
                    "family visa", "family sponsorship", "spouse can work", "dependant visa",
                    "family relocation", "family support", "dependants supported",
                ),
                hedged = listOf("family", "dependants"),
                refusals = listOf("no family support", "family not supported"),
            ),
        )
    }
}
