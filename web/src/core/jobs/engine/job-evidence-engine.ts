import {
  BenefitStatus,
  EvidenceKind,
  EvidenceStrength,
  MadagascarEligibility,
  VisaSupport,
  type BenefitEvidence,
  type EligibilityReason,
  type Evidence,
  type QualificationGap,
} from "../../models";

/**
 * Builds the typed, source-linked evidence list shown on a posting. Mirrors the Kotlin
 * `JobEvidenceEngine`.
 *
 * The rules it enforces:
 *  - A claim read from the employer's words is `DOCUMENTED` and carries the quote. A
 *    heuristic the engines worked out is `INFERRED`. A fact the posting does not state is
 *    `UNKNOWN` — never quietly omitted and never presented as a positive.
 *  - Every documented item carries the posting URL, so the user can verify it.
 */
export function buildEvidence(
  eligibility: MadagascarEligibility,
  visaSupport: VisaSupport,
  eligibilityReasons: EligibilityReason[],
  benefits: BenefitEvidence[],
  gaps: QualificationGap[],
  sourceUrl: string,
): Evidence[] {
  return [
    ...eligibilityEvidence(eligibility, visaSupport, eligibilityReasons, sourceUrl),
    ...benefitEvidence(benefits, sourceUrl),
    ...requirementEvidence(gaps, sourceUrl),
  ];
}

function eligibilityEvidence(
  eligibility: MadagascarEligibility,
  visaSupport: VisaSupport,
  reasons: EligibilityReason[],
  sourceUrl: string,
): Evidence[] {
  const out: Evidence[] = [];
  const official = reasons.find((r) => r.official);

  switch (eligibility) {
    case MadagascarEligibility.VISA_SPONSORED:
      out.push({
        kind: EvidenceKind.VISA,
        strength: EvidenceStrength.DOCUMENTED,
        label: "Visa sponsorship",
        detail: official?.detail.trim() ? official.detail : "The posting states the employer sponsors a work visa.",
        sourceUrl,
      });
      break;
    case MadagascarEligibility.ELIGIBLE:
      out.push({
        kind: EvidenceKind.ELIGIBILITY,
        strength: EvidenceStrength.DOCUMENTED,
        label: "Open to international applicants",
        detail: official?.detail.trim() ? official.detail : "The posting does not restrict applicants by country.",
        sourceUrl,
      });
      break;
    case MadagascarEligibility.RESTRICTED:
      out.push({
        kind: EvidenceKind.ELIGIBILITY,
        strength: EvidenceStrength.DOCUMENTED,
        label: "Local applicants only",
        detail: official?.detail.trim() ? official.detail : "The posting restricts applications to local candidates.",
        sourceUrl,
      });
      break;
    case MadagascarEligibility.UNCLEAR:
      out.push({
        kind: EvidenceKind.ELIGIBILITY,
        strength: EvidenceStrength.UNKNOWN,
        label: "Eligibility not stated",
        detail:
          "The posting does not say whether it accepts applicants from Madagascar. Verify before applying.",
        sourceUrl,
      });
      break;
  }

  // A relocation hint the eligibility engine inferred but the employer never stated as
  // sponsorship. It is labelled INFERRED so it is never read as a documented offer.
  const relocationHint = reasons.find((r) => !r.official && r.label.toLowerCase().includes("reloc"));
  if (relocationHint != null && visaSupport !== VisaSupport.FULL_SPONSORSHIP) {
    out.push({
      kind: EvidenceKind.RELOCATION,
      strength: EvidenceStrength.INFERRED,
      label: "Relocation hinted",
      detail:
        relocationHint.detail.trim() ||
        "The posting mentions relocation without stating sponsorship. Confirm with the employer.",
      sourceUrl,
    });
  }
  return out;
}

function benefitEvidence(benefits: BenefitEvidence[], sourceUrl: string): Evidence[] {
  return benefits
    .filter((b) => b.status !== BenefitStatus.UNKNOWN)
    .map((finding) => ({
      kind: EvidenceKind.BENEFIT,
      strength:
        finding.status === BenefitStatus.CONFIRMED
          ? EvidenceStrength.DOCUMENTED
          : finding.status === BenefitStatus.NOT_OFFERED
            ? EvidenceStrength.REFUSED
            : finding.status === BenefitStatus.NEEDS_VERIFICATION
              ? EvidenceStrength.NEEDS_VERIFICATION
              : EvidenceStrength.UNKNOWN,
      label: finding.type.label,
      detail: finding.evidence.trim() ? finding.evidence : finding.type.label,
      sourceUrl,
    }));
}

function requirementEvidence(gaps: QualificationGap[], sourceUrl: string): Evidence[] {
  return gaps.map((gap) => ({
    kind: EvidenceKind.REQUIREMENT,
    strength: EvidenceStrength.DOCUMENTED,
    label: gap.label,
    detail: gap.evidence,
    sourceUrl,
  }));
}
