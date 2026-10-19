import {
  ALL_BENEFIT_TYPES,
  BenefitPriority,
  BenefitStatus,
  JobBenefitTypes,
  benefitPriorityOf,
  type BenefitEvidence,
  type BenefitScoreComponent,
  type BenefitType,
  type ExpatBenefitsAssessment,
  type ExpatPreferences,
} from "../models";

/**
 * Scores how well a posting's *documented* expat benefits match the user's priorities.
 * Mirrors the Kotlin `ExpatBenefitsEngine`.
 *
 * This is intentionally a different number from the CV match score and the eligibility
 * verdict, and it is never allowed to imply either of them. Two rules keep it honest:
 *
 *  1. Only documented benefits earn points. A benefit the listing never mentions scores
 *     nothing, but it is reported as "not stated" rather than "refused", and the separate
 *     `evidenceCoverage` number says how much of the listing could actually be read.
 *  2. A low score is not a statement about the employer. When coverage is thin the score
 *     is flagged as unreliable and the explanation says the listing simply lacks detail.
 */

/** Below this share of documented benefits the score is reported as unreliable. */
const LOW_EVIDENCE_THRESHOLD = 40;

/**
 * @param benefits the extracted findings for the posting.
 * @param preferences the user's priorities and gates.
 * @param salaryEurPerYear the advertised salary normalised to EUR/year, or null when the
 *   source published none (never invented).
 */
export function assessExpatBenefits(
  benefits: BenefitEvidence[],
  preferences: ExpatPreferences,
  salaryEurPerYear: number | null,
): ExpatBenefitsAssessment {
  const byType = new Map<string, BenefitEvidence>();
  for (const b of benefits) byType.set(b.type.key, b);

  const components: BenefitScoreComponent[] = [];

  for (const type of ALL_BENEFIT_TYPES) {
    const priority = benefitPriorityOf(preferences, type);
    const evidence = byType.get(type.key);
    components.push(component(type, priority, evidence?.status ?? BenefitStatus.UNKNOWN, evidence?.evidence ?? ""));
  }

  // Minimum salary is a preference, not a phrase in the listing, so it is scored separately:
  // it only counts once the user has actually set a target.
  const salaryType = JobBenefitTypes.MINIMUM_SALARY;
  const salaryPriority = benefitPriorityOf(preferences, salaryType);
  const minimum = preferences.minimumSalaryEur;
  if (minimum != null && salaryPriority > 0) {
    const status =
      salaryEurPerYear == null
        ? BenefitStatus.UNKNOWN
        : salaryEurPerYear >= minimum
          ? BenefitStatus.CONFIRMED
          : BenefitStatus.NOT_OFFERED;
    const evidence =
      salaryEurPerYear == null
        ? "The source did not publish a salary."
        : salaryEurPerYear >= minimum
          ? `Advertised pay is at or above your €${minimum} minimum.`
          : `Advertised pay is below your €${minimum} minimum.`;
    components.push(component(salaryType, salaryPriority, status, evidence));
  }

  const weighted = components.filter((c) => c.priority > 0);
  const totalPotential = weighted.reduce((sum, c) => sum + c.potentialPoints, 0);
  const earned = weighted.reduce((sum, c) => sum + c.earnedPoints, 0);
  const score = totalPotential === 0 ? 0 : Math.round((100 * earned) / totalPotential);

  const documented = weighted.filter((c) => c.status !== BenefitStatus.UNKNOWN).length;
  const coverage = weighted.length === 0 ? 0 : Math.round((100 * documented) / weighted.length);
  const lowEvidence = weighted.length > 0 && coverage < LOW_EVIDENCE_THRESHOLD;

  const unmetEssentials = weighted
    .filter((c) => c.priority === BenefitPriority.ESSENTIAL && c.status !== BenefitStatus.CONFIRMED)
    .map((c) => c.type);

  return {
    score,
    evidenceCoverage: coverage,
    components,
    explanation: explain(score, coverage, lowEvidence, weighted, unmetEssentials),
    unmetEssentials,
    lowEvidence,
  };
}

function component(
  type: BenefitType,
  priority: BenefitPriority,
  status: BenefitStatus,
  evidence: string,
): BenefitScoreComponent {
  const credit =
    status === BenefitStatus.CONFIRMED
      ? 1
      : status === BenefitStatus.NEEDS_VERIFICATION
        ? 0.5
        : 0; // NOT_OFFERED and UNKNOWN both earn nothing.
  return {
    type,
    priority,
    status,
    evidence,
    credit,
    potentialPoints: priority,
    earnedPoints: Math.round(priority * credit),
  };
}

function explain(
  score: number,
  coverage: number,
  lowEvidence: boolean,
  weighted: BenefitScoreComponent[],
  unmetEssentials: BenefitType[],
): string {
  if (weighted.length === 0) {
    return (
      "You have not set any benefit priorities yet. Choose the benefits that matter to you " +
      "to score postings on them."
    );
  }
  const documented = weighted.filter((c) => c.status !== BenefitStatus.UNKNOWN).length;
  const base =
    `${score}% of the benefits you marked as important are documented in this posting ` +
    `(${documented} of ${weighted.length} benefits addressed).`;

  if (lowEvidence) {
    return (
      `${base} Coverage is only ${coverage}%, so this score mainly reflects missing information — ` +
      "not a shortcoming of the employer. Confirm the package with the employer before deciding."
    );
  }

  const refused = unmetEssentials.filter((type) =>
    weighted.some((c) => c.type.key === type.key && c.status === BenefitStatus.NOT_OFFERED),
  );
  const unstated = unmetEssentials.filter((type) =>
    weighted.some((c) => c.type.key === type.key && c.status === BenefitStatus.UNKNOWN),
  );
  let out = base;
  if (refused.length > 0) {
    out += ` The listing states it does not offer ${refused.map((t) => t.shortLabel.toLowerCase()).join(", ")}.`;
  }
  if (unstated.length > 0) {
    out += ` It does not say whether it offers ${unstated.map((t) => t.shortLabel.toLowerCase()).join(", ")}.`;
  }
  if (refused.length === 0 && unstated.length === 0) {
    out += " Every benefit you marked as essential is documented.";
  }
  return out;
}
