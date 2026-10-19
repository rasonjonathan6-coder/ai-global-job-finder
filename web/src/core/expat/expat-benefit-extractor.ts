import {
  ALL_BENEFIT_TYPES,
  BenefitStatus,
  JobBenefitTypes,
  type BenefitEvidence,
  type BenefitType,
} from "../models";

/**
 * Reads the expat benefits a posting documents, together with the sentence that proves it.
 * Mirrors the Kotlin `ExpatBenefitExtractor`. This is a pure function of the posting text:
 * it never guesses, and it never treats silence as a refusal.
 *
 * Rules, in priority order for each benefit:
 *  1. A phrase that explicitly refuses the benefit → `NOT_OFFERED`.
 *  2. A phrase that states it, split into strong and hedged wording. Strong wording is the
 *     employer committing; hedged wording only hints at it, so it is `NEEDS_VERIFICATION`.
 *  3. Nothing at all → `UNKNOWN`. This is the common case and must not be read as a
 *     negative statement about the employer.
 */

const SENTENCE_SPLIT = /[.\n;!?•]+/;

/**
 * A sentence that contains a strong phrase but also negates it is a refusal, not an offer.
 * Kept to unambiguous negations so that an offer phrased with "no cost" or "no fee" is not
 * misread as a refusal.
 */
const NEGATION =
  /\b(cannot|can not|can't|do not|don't|does not|doesn't|will not|won't|unable to|not available|not offered|not provided|not eligible|not able to|isn't|aren't|no longer|no sponsorship)\b/i;

function escapeRegex(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/**
 * Matches a phrase only on whole words. A plain substring search would read "family" out of
 * "family-owned company" and report a family-support benefit that was never offered.
 */
function phraseRegex(phrase: string): RegExp {
  return new RegExp(`\\b${escapeRegex(phrase)}\\b`, "i");
}

interface Rules {
  strong: string[];
  hedged?: string[];
  refusals?: string[];
}

const RULES: Record<string, Rules> = {
  [JobBenefitTypes.VISA_SPONSORSHIP.key]: {
    strong: [
      "visa sponsorship", "sponsorship available", "sponsorship is available",
      "visa sponsorship available", "visa sponsorship provided",
      "work visa sponsorship", "immigration support",
      "we sponsor visas", "we will sponsor", "we can sponsor", "sponsor a work visa",
      "work permit sponsorship", "tier 2 sponsorship", "skilled worker visa",
      "visa and relocation", "employer-sponsored visa", "sponsor your work visa",
      "we provide visa", "visa support provided",
    ],
    hedged: ["visa support", "sponsorship considered", "may sponsor"],
    refusals: [
      "no visa sponsorship", "we do not sponsor", "we are unable to sponsor",
      "unable to offer sponsorship", "not able to sponsor", "cannot sponsor",
      "without sponsorship", "no sponsorship",
    ],
  },
  [JobBenefitTypes.WORK_PERMIT.key]: {
    strong: [
      "work permit", "work permits", "work permit support", "work permit provided",
      "work permit assistance", "we obtain your work permit", "work authorisation",
      "work authorization", "right to work", "work visa", "employment permit",
      "residence permit", "we handle the work permit", "permit sponsorship",
    ],
    hedged: ["permit assistance", "work permit support available"],
    refusals: [
      "no work permit", "work permit not provided", "must have the right to work",
      "must already have the right to work", "no work authorisation", "no work authorization",
    ],
  },
  [JobBenefitTypes.RELOCATION_SUPPORT.key]: {
    strong: [
      "relocation package", "relocation support", "relocation assistance",
      "relocation assistance is available", "relocation benefits", "relocation program",
      "relocation programme", "relocation reimbursement", "relocation expenses",
      "relocation costs", "we offer relocation", "paid relocation",
      "help with relocation", "support with relocation", "assistance with relocation",
      "relocation allowance", "relocation bonus", "we help you relocate",
      "we will help you relocate", "relocation covered", "settling-in allowance",
    ],
    hedged: ["relocation", "help with moving"],
    refusals: ["no relocation", "relocation is not provided", "no relocation support"],
  },
  [JobBenefitTypes.ACCOMMODATION.key]: {
    strong: [
      "accommodation provided", "company accommodation", "housing provided",
      "provided accommodation", "free accommodation", "staff accommodation",
      "company housing", "staff housing", "camp accommodation", "site accommodation",
      "subsidised accommodation", "subsidized accommodation", "living allowance",
      "accommodation is provided", "furnished accommodation",
    ],
    hedged: ["housing allowance", "accommodation allowance", "accommodation support", "lodging"],
    refusals: ["no accommodation", "accommodation not provided"],
  },
  [JobBenefitTypes.FLIGHTS_HOME.key]: {
    strong: [
      "annual flight", "annual flights", "annual return flight", "flight home",
      "flights home", "return flight", "return flights", "rotation flights",
      "airfare", "air ticket", "air tickets", "airfare provided", "flight allowance",
      "repatriation flight",
    ],
    hedged: ["flights provided"],
    refusals: ["no flights", "flight not provided"],
  },
  [JobBenefitTypes.HEALTH_INSURANCE.key]: {
    strong: [
      "health insurance", "medical insurance", "health cover", "healthcare provided",
      "private medical", "medical cover", "health insurance provided",
      "private health insurance", "medical aid", "medical scheme", "health benefits",
      "medical benefits",
    ],
    hedged: ["health care"],
    refusals: ["no health insurance", "no medical insurance"],
  },
  [JobBenefitTypes.PAID_LEAVE.key]: {
    strong: [
      "paid leave", "paid annual leave", "annual leave", "paid vacation",
      "paid holiday", "holiday entitlement", "leave entitlement", "paid time off",
      "annual leave allowance", "vacation allowance", "rotation leave",
    ],
    hedged: ["leave allowance", "days leave"],
    refusals: ["no paid leave", "unpaid leave only"],
  },
  [JobBenefitTypes.MEALS.key]: {
    strong: [
      "meals provided", "free meals", "free food", "meal allowance", "food allowance",
      "canteen", "site canteen", "subsidised canteen", "three meals",
    ],
    hedged: ["meal allowance"],
    refusals: ["no meals", "meals not provided"],
  },
  [JobBenefitTypes.TRANSPORT.key]: {
    strong: [
      "transport provided", "company transport", "transport allowance",
      "travel allowance", "shuttle service", "shuttle", "company car",
      "company vehicle", "site transport", "transport to and from",
    ],
    hedged: ["travel reimbursement"],
    refusals: ["no transport", "transport not provided"],
  },
  [JobBenefitTypes.LANGUAGE_TRAINING.key]: {
    strong: [
      "language training", "language course", "language courses", "german course",
      "language classes", "language lessons", "language programme", "language program",
      "paid language", "language support", "language tuition",
    ],
    hedged: ["language training support"],
    refusals: ["no language training"],
  },
  [JobBenefitTypes.FAMILY_SUPPORT.key]: {
    strong: [
      "family visa", "family sponsorship", "spouse can work", "dependant visa",
      "dependent visa", "spouse visa", "family relocation", "family support",
      "dependants supported",
    ],
    hedged: ["dependant visa support"],
    refusals: ["no family support", "family not supported"],
  },
};

interface CompiledRules {
  strong: RegExp[];
  hedged: RegExp[];
  refusals: RegExp[];
}

const COMPILED: Record<string, CompiledRules> = Object.fromEntries(
  Object.entries(RULES).map(([key, rules]) => [
    key,
    {
      strong: rules.strong.map(phraseRegex),
      hedged: (rules.hedged ?? []).map(phraseRegex),
      refusals: (rules.refusals ?? []).map(phraseRegex),
    },
  ]),
);

/** Splits text into trimmed sentences, dropping empties. Casing is preserved. */
function sentencesOf(text: string): string[] {
  return text
    .split(SENTENCE_SPLIT)
    .map((s) => s.trim())
    .filter((s) => s.length >= 3);
}

function firstMatch(sentences: string[], regexes: RegExp[]): [RegExp, string] | null {
  for (const regex of regexes) {
    const sentence = sentences.find((s) => regex.test(s));
    if (sentence != null) return [regex, sentence];
  }
  return null;
}

function assess(type: BenefitType, sentences: string[]): BenefitEvidence {
  const rules = COMPILED[type.key];

  const refusal = firstMatch(sentences, rules.refusals);
  if (refusal) return { type, status: BenefitStatus.NOT_OFFERED, evidence: refusal[1].trim(), source: "", verifiedAtEpochMillis: null };

  const strong = firstMatch(sentences, rules.strong);
  if (strong) {
    // "Visa sponsorship ... is not available" contains a strong phrase, but the sentence
    // refuses it. Reading that as a confirmed benefit would tell the user they are
    // sponsored for a job that has just said the opposite.
    const status = NEGATION.test(strong[1]) ? BenefitStatus.NOT_OFFERED : BenefitStatus.CONFIRMED;
    return { type, status, evidence: strong[1].trim(), source: "", verifiedAtEpochMillis: null };
  }

  const hedged = firstMatch(sentences, rules.hedged);
  if (hedged) {
    const status = NEGATION.test(hedged[1]) ? BenefitStatus.NOT_OFFERED : BenefitStatus.NEEDS_VERIFICATION;
    return { type, status, evidence: hedged[1].trim(), source: "", verifiedAtEpochMillis: null };
  }

  return { type, status: BenefitStatus.UNKNOWN, evidence: "", source: "", verifiedAtEpochMillis: null };
}

/**
 * Reads the documented benefits from a posting.
 *
 * @param source the source name the text came from, recorded on each finding so the claim
 *   can be traced back to where it was read.
 * @param verifiedAtEpochMillis when the text was read, recorded so a later reader can tell
 *   a fresh claim from a stale one. Null means "not recorded", left null rather than filled.
 */
export function extractBenefits(
  title: string,
  description: string,
  tags: string[] = [],
  source = "",
  verifiedAtEpochMillis: number | null = null,
): BenefitEvidence[] {
  const sentences = sentencesOf([title, tags.join(" "), description].join(". "));
  return ALL_BENEFIT_TYPES.map((type) => {
    const finding = assess(type, sentences);
    return { ...finding, source, verifiedAtEpochMillis };
  });
}
