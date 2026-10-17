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

    /**
     * @param source the source name the text came from, recorded on each finding so the
     *   claim can be traced back to where it was read.
     * @param verifiedAtEpochMillis when the text was read, recorded on each finding so a
     *   later reader can tell a fresh claim from a stale one. Null means "not recorded",
     *   which is left null rather than filled with a plausible-looking timestamp.
     */
    fun extract(
        title: String,
        description: String,
        tags: List<String> = emptyList(),
        source: String = "",
        verifiedAtEpochMillis: Long? = null,
    ): List<BenefitEvidence> {
        val sentences = sentencesOf(listOf(title, tags.joinToString(" "), description).joinToString(". "))
        return JobBenefitTypes.all.map { type ->
            assess(type, sentences).copy(source = source, verifiedAtEpochMillis = verifiedAtEpochMillis)
        }
    }

    private fun assess(type: BenefitType, sentences: List<String>): BenefitEvidence {
        val rules = COMPILED.getValue(type.key)

        val refusal = rules.refusals.firstNotNullOfOrNull { regex ->
            sentences.firstOrNull { regex.containsMatchIn(it) }?.let { regex to it }
        }
        if (refusal != null) {
            return BenefitEvidence(type, BenefitStatus.NOT_OFFERED, refusal.second.trim())
        }

        val strong = rules.strong.firstNotNullOfOrNull { regex ->
            sentences.firstOrNull { regex.containsMatchIn(it) }?.let { regex to it }
        }
        if (strong != null) {
            // "Visa sponsorship ... is not available" contains a strong phrase, but the
            // sentence refuses it. Reading that as a confirmed benefit would tell the user
            // they are sponsored for a job that has just said the opposite.
            val status = if (NEGATION.containsMatchIn(strong.second)) BenefitStatus.NOT_OFFERED else BenefitStatus.CONFIRMED
            return BenefitEvidence(type, status, strong.second.trim())
        }

        val hedged = rules.hedged.firstNotNullOfOrNull { regex ->
            sentences.firstOrNull { regex.containsMatchIn(it) }?.let { regex to it }
        }
        if (hedged != null) {
            val status = if (NEGATION.containsMatchIn(hedged.second)) BenefitStatus.NOT_OFFERED else BenefitStatus.NEEDS_VERIFICATION
            return BenefitEvidence(type, status, hedged.second.trim())
        }

        return BenefitEvidence(type, BenefitStatus.UNKNOWN, "")
    }

    /**
     * Splits text into trimmed sentences, dropping empties. Casing is preserved so the
     * evidence quoted back to the user reads exactly as the employer wrote it; matching
     * itself is case-insensitive because the phrase table is compiled that way.
     */
    private fun sentencesOf(text: String): List<String> =
        text.split(SENTENCE_SPLIT)
            .map { it.trim() }
            .filter { it.length >= 3 }

    private data class Rules(
        val strong: List<String>,
        val hedged: List<String> = emptyList(),
        val refusals: List<String> = emptyList(),
    )

    private data class CompiledRules(
        val strong: List<Regex>,
        val hedged: List<Regex>,
        val refusals: List<Regex>,
    )

    private companion object {
        val SENTENCE_SPLIT = Regex("[.\\n;!?•]+")

        /**
         * A sentence that contains a strong phrase but also negates it is a refusal, not an
         * offer. Kept to unambiguous negations so that an offer phrased with "no cost" or
         * "no fee" is not misread as a refusal.
         */
        val NEGATION = Regex(
            "\\b(cannot|can not|can't|do not|don't|does not|doesn't|will not|won't|unable to|" +
                "not available|not offered|not provided|not eligible|not able to|" +
                "isn't|aren't|no longer|no sponsorship)\\b",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Matches a phrase only on whole words. A plain substring search would read
         * "family" out of "family-owned company" and report a family-support benefit that
         * was never offered, which is exactly the kind of false positive that makes a
         * benefits score untrustworthy.
         */
        fun phraseRegex(phrase: String): Regex =
            Regex("\\b" + Regex.escape(phrase) + "\\b", RegexOption.IGNORE_CASE)

        /**
         * Phrase tables per benefit key. Strong phrases are commitments; hedged phrases
         * are weak enough that the listing has to be checked before the user relies on
         * them; refusal phrases are explicit "not offered" statements.
         *
         * The hedged lists are deliberately short. A bare noun ("transport", "flights",
         * "insurance", "family") appears in ordinary prose about the work itself, so it
         * is not evidence of a benefit and must stay out of these lists.
         */
        val RULES: Map<String, Rules> = mapOf(
            JobBenefitTypes.VISA_SPONSORSHIP.key to Rules(
                strong = listOf(
                    "visa sponsorship", "sponsorship available", "sponsorship is available",
                    "visa sponsorship available", "visa sponsorship provided",
                    "work visa sponsorship", "immigration support",
                    "we sponsor visas", "we will sponsor", "we can sponsor", "sponsor a work visa",
                    "work permit sponsorship", "tier 2 sponsorship", "skilled worker visa",
                    "visa and relocation", "employer-sponsored visa", "sponsor your work visa",
                    "we provide visa", "visa support provided",
                ),
                hedged = listOf("visa support", "sponsorship considered", "may sponsor"),
                refusals = listOf(
                    "no visa sponsorship", "we do not sponsor", "we are unable to sponsor",
                    "unable to offer sponsorship", "not able to sponsor", "cannot sponsor",
                    "without sponsorship", "no sponsorship",
                ),
            ),
            JobBenefitTypes.WORK_PERMIT.key to Rules(
                strong = listOf(
                    "work permit", "work permits", "work permit support", "work permit provided",
                    "work permit assistance", "we obtain your work permit", "work authorisation",
                    "work authorization", "right to work", "work visa", "employment permit",
                    "residence permit", "we handle the work permit", "permit sponsorship",
                ),
                hedged = listOf("permit assistance", "work permit support available"),
                refusals = listOf(
                    "no work permit", "work permit not provided", "must have the right to work",
                    "must already have the right to work", "no work authorisation",
                    "no work authorization",
                ),
            ),
            JobBenefitTypes.RELOCATION_SUPPORT.key to Rules(
                strong = listOf(
                    "relocation package", "relocation support", "relocation assistance",
                    "relocation assistance is available", "relocation benefits", "relocation program",
                    "relocation programme", "relocation reimbursement", "relocation expenses",
                    "relocation costs", "we offer relocation", "paid relocation",
                    "help with relocation", "support with relocation", "assistance with relocation",
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
                    "company housing", "staff housing", "camp accommodation", "site accommodation",
                    "subsidised accommodation", "subsidized accommodation", "living allowance",
                    "accommodation is provided", "furnished accommodation",
                ),
                hedged = listOf("housing allowance", "accommodation allowance", "accommodation support", "lodging"),
                refusals = listOf("no accommodation", "accommodation not provided"),
            ),
            JobBenefitTypes.FLIGHTS_HOME.key to Rules(
                strong = listOf(
                    "annual flight", "annual flights", "annual return flight", "flight home",
                    "flights home", "return flight", "return flights", "rotation flights",
                    "airfare", "air ticket", "air tickets", "airfare provided", "flight allowance",
                    "repatriation flight",
                ),
                hedged = listOf("flights provided"),
                refusals = listOf("no flights", "flight not provided"),
            ),
            JobBenefitTypes.HEALTH_INSURANCE.key to Rules(
                strong = listOf(
                    "health insurance", "medical insurance", "health cover", "healthcare provided",
                    "private medical", "medical cover", "health insurance provided",
                    "private health insurance", "medical aid", "medical scheme", "health benefits",
                    "medical benefits",
                ),
                hedged = listOf("health care"),
                refusals = listOf("no health insurance", "no medical insurance"),
            ),
            JobBenefitTypes.PAID_LEAVE.key to Rules(
                strong = listOf(
                    "paid leave", "paid annual leave", "annual leave", "paid vacation",
                    "paid holiday", "holiday entitlement", "leave entitlement", "paid time off",
                    "annual leave allowance", "vacation allowance", "rotation leave",
                ),
                hedged = listOf("leave allowance", "days leave"),
                refusals = listOf("no paid leave", "unpaid leave only"),
            ),
            JobBenefitTypes.MEALS.key to Rules(
                strong = listOf(
                    "meals provided", "free meals", "free food", "meal allowance", "food allowance",
                    "canteen", "site canteen", "subsidised canteen", "three meals",
                ),
                hedged = listOf("meal allowance"),
                refusals = listOf("no meals", "meals not provided"),
            ),
            JobBenefitTypes.TRANSPORT.key to Rules(
                strong = listOf(
                    "transport provided", "company transport", "transport allowance",
                    "travel allowance", "shuttle service", "shuttle", "company car",
                    "company vehicle", "site transport", "transport to and from",
                ),
                hedged = listOf("travel reimbursement"),
                refusals = listOf("no transport", "transport not provided"),
            ),
            JobBenefitTypes.LANGUAGE_TRAINING.key to Rules(
                strong = listOf(
                    "language training", "language course", "language courses", "german course",
                    "language classes", "language lessons", "language programme", "language program",
                    "paid language", "language support", "language tuition",
                ),
                hedged = listOf("language training support"),
                refusals = listOf("no language training"),
            ),
            JobBenefitTypes.FAMILY_SUPPORT.key to Rules(
                strong = listOf(
                    "family visa", "family sponsorship", "spouse can work", "dependant visa",
                    "dependent visa", "spouse visa", "family relocation", "family support",
                    "dependants supported",
                ),
                hedged = listOf("dependant visa support"),
                refusals = listOf("no family support", "family not supported"),
            ),
        )

        val COMPILED: Map<String, CompiledRules> = RULES.mapValues { (_, rules) ->
            CompiledRules(
                strong = rules.strong.map(::phraseRegex),
                hedged = rules.hedged.map(::phraseRegex),
                refusals = rules.refusals.map(::phraseRegex),
            )
        }
    }
}
