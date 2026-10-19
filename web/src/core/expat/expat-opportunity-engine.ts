import {
  ALL_BENEFIT_TYPES,
  BenefitStatus,
  EvidenceStrength,
  JobBenefitTypes,
  MadagascarEligibility,
  defaultExpatPreferences,
  RoleFamily,
  SELECTABLE_BENEFIT_TYPES,
  ScamRisk,
  VerificationState,
  VisaSupport,
  verificationIsNegative,
  type BenefitEvidence,
  type ExpatPreferences,
  type InternationalFitAssessment,
  type Job,
} from "../models";
import { rateAsOfEpochMillis, rateSource, toEurPerYear } from "../geo/currency-converter";
import {
  BenefitFinding,
  EXPAT_BAND_STRONG_MIN,
  ExpatOpportunityBand,
  OPPORTUNITY_DIMENSION_META,
  OpportunityConfidence,
  OpportunityDimension,
  expatOpportunityBandFromScore,
  type BenefitClaim,
  type ExpatOpportunity,
  type NormalisedSalary,
  type OpportunityDimensionScore,
} from "./opportunity";

/**
 * Builds the explainable Expatriate Opportunity Score for one posting. Mirrors the Kotlin
 * `ExpatOpportunityEngine`.
 *
 * The score is a fixed, weighted sum of seven dimensions. Every dimension reports the points
 * it earned, why, and how strong the evidence was, so the total can always be traced. Three
 * honesty rules run through the whole engine:
 *
 *  1. Silence is not a claim. A benefit the listing does not mention is `UNKNOWN` — it neither
 *     earns points nor is presented as a refusal. A vague phrase is never upgraded to
 *     `CONFIRMED`.
 *  2. A high total cannot hide a disqualifier. An ineligible posting, a high scam risk, or a
 *     dead application link caps the score and sets `disqualified`.
 *  3. Missing data lowers confidence, not the verdict. Too little documented → `INSUFFICIENT`,
 *     which says the evidence is thin — a different statement from "this is a poor offer".
 */

/** A disqualifying posting is never scored as a good opportunity. */
const DISQUALIFIED_CAP = 35;

export interface ExpatOpportunityEngineOptions {
  clock?: () => number;
}

export class ExpatOpportunityEngine {
  private readonly clock: () => number;

  constructor(options?: ExpatOpportunityEngineOptions) {
    this.clock = options?.clock ?? Date.now;
  }

  assess(
    job: Job,
    preferences: ExpatPreferences = defaultExpatPreferences(),
    internationalFit: InternationalFitAssessment | null = null,
  ): ExpatOpportunity {
    void preferences;
    const claims = this.claimsFor(job);
    const salary = this.salaryFor(job);

    const dimensions: OpportunityDimensionScore[] = [
      this.eligibilityDimension(job),
      this.roleFitDimension(job, internationalFit),
      this.compensationDimension(job, salary),
      this.accommodationRelocationDimension(claims),
      this.travelRotationDimension(job, claims),
      this.insuranceOtherDimension(claims),
      this.reliabilityDimension(job),
    ];

    const rawScore = Math.max(0, Math.min(100, dimensions.reduce((sum, d) => sum + d.points, 0)));

    const hardStops = this.hardStopsFor(job);
    const disqualified = hardStops.length > 0;
    const score = disqualified ? Math.min(rawScore, DISQUALIFIED_CAP) : rawScore;

    const confidence = this.confidenceFor(claims);
    const band = confidence === OpportunityConfidence.INSUFFICIENT ? ExpatOpportunityBand.INSUFFICIENT : expatOpportunityBandFromScore(score);

    return {
      score,
      band,
      dimensions,
      claims,
      salary,
      confidence,
      confidenceNote: this.confidenceNote(confidence, claims),
      hardStops,
      disqualified,
      labels: this.labelsFor(job, claims, salary, score),
      routeSummary: this.routeSummary(job),
      explanation: this.explanation(band, score, confidence, disqualified, hardStops),
    };
  }

  // ------------------------------------------------------------ dimensions

  private eligibilityDimension(job: Job): OpportunityDimensionScore {
    const max = OPPORTUNITY_DIMENSION_META[OpportunityDimension.INTERNATIONAL_ELIGIBILITY].weightPercent;
    let points: number;
    let summary: string;
    let strength: EvidenceStrength;
    switch (job.eligibility) {
      case MadagascarEligibility.ELIGIBLE:
        points = max;
        summary = "The listing accepts international applicants.";
        strength = EvidenceStrength.DOCUMENTED;
        break;
      case MadagascarEligibility.VISA_SPONSORED:
        switch (job.visaSupport) {
          case VisaSupport.FULL_SPONSORSHIP:
            points = max;
            summary = "The listing states full visa sponsorship.";
            strength = EvidenceStrength.DOCUMENTED;
            break;
          case VisaSupport.RELOCATION_PACKAGE:
            points = Math.trunc(max * 0.9);
            summary = "The listing states a relocation package with sponsorship.";
            strength = EvidenceStrength.DOCUMENTED;
            break;
          case VisaSupport.SPONSORSHIP_AVAILABLE:
            points = Math.trunc(max * 0.75);
            summary = "Sponsorship is mentioned as available — confirm the terms.";
            strength = EvidenceStrength.NEEDS_VERIFICATION;
            break;
          default:
            points = Math.trunc(max * 0.7);
            summary = "The listing points to a sponsored route — confirm it in writing.";
            strength = EvidenceStrength.NEEDS_VERIFICATION;
            break;
        }
        break;
      case MadagascarEligibility.UNCLEAR:
        points = Math.trunc(max * 0.4);
        summary = "The listing does not say whether applicants from Madagascar are accepted.";
        strength = EvidenceStrength.UNKNOWN;
        break;
      default:
        points = 0;
        summary = "The listing appears restricted to local applicants.";
        strength = EvidenceStrength.DOCUMENTED;
        break;
    }
    return {
      dimension: OpportunityDimension.INTERNATIONAL_ELIGIBILITY,
      points,
      maxPoints: max,
      summary,
      strength,
    };
  }

  private roleFitDimension(job: Job, fit: InternationalFitAssessment | null): OpportunityDimensionScore {
    const max = OPPORTUNITY_DIMENSION_META[OpportunityDimension.ROLE_FIT].weightPercent;
    // The CV match is the backbone; the role family nudges it so an unrelated title cannot
    // score as well as a target trade even at the same match percentage.
    const familyFactor =
      job.roleFamily === RoleFamily.TARGET
        ? 1.0
        : job.roleFamily === RoleFamily.MAINTENANCE_FAMILY
          ? 0.85
          : job.roleFamily === RoleFamily.UNKNOWN
            ? 0.6
            : 0.25;
    const fitBonus = fit ? Math.max(-10, Math.min(10, fit.score - 50)) : 0;
    const points = Math.max(0, Math.min(100, Math.trunc(job.matchScore * familyFactor) + fitBonus));
    const scaled = Math.max(0, Math.min(max, Math.trunc((points * max) / 100)));
    const summary =
      job.roleFamily === RoleFamily.TARGET
        ? `A target role for your trade (CV match ${job.matchScore}%).`
        : job.roleFamily === RoleFamily.MAINTENANCE_FAMILY
          ? `In your wider maintenance family (CV match ${job.matchScore}%).`
          : job.roleFamily === RoleFamily.UNRELATED
            ? "A different profession from your trade."
            : "The role family could not be confirmed from the title.";
    return {
      dimension: OpportunityDimension.ROLE_FIT,
      points: scaled,
      maxPoints: max,
      summary,
      strength: job.roleFamily === RoleFamily.UNRELATED ? EvidenceStrength.UNKNOWN : EvidenceStrength.INFERRED,
    };
  }

  private compensationDimension(job: Job, salary: NormalisedSalary | null): OpportunityDimensionScore {
    const max = OPPORTUNITY_DIMENSION_META[OpportunityDimension.COMPENSATION].weightPercent;
    const disclosed = job.salaryMin > 0 && !job.salaryIsEstimated && !job.salaryUnitUncertain;
    const conflicting = job.salaryMin > 0 && job.salaryMax > 0 && job.salaryMin > job.salaryMax;
    let points: number;
    let summary: string;
    let strength: EvidenceStrength;
    if (job.salaryUnitUncertain) {
      points = Math.trunc(max * 0.3);
      summary = "A salary is shown, but the source's pay unit cannot be trusted — verify it with the employer.";
      strength = EvidenceStrength.NEEDS_VERIFICATION;
    } else if (conflicting) {
      points = Math.trunc(max * 0.3);
      summary = "The advertised salary range is internally inconsistent — verify it with the employer.";
      strength = EvidenceStrength.NEEDS_VERIFICATION;
    } else if (disclosed && salary?.converted === true) {
      points = max;
      summary =
        `Salary disclosed (${salary.originalCurrency} ${salary.originalAmount}/${salary.originalPeriod.toLowerCase()}); ` +
        `about €${salary.eurPerYear}/year (estimated).`;
      strength = EvidenceStrength.DOCUMENTED;
    } else if (disclosed) {
      points = Math.trunc(max * 0.8);
      summary = "Salary disclosed, but no exchange rate is available to normalise it.";
      strength = EvidenceStrength.DOCUMENTED;
    } else {
      points = 0;
      summary = "The listing does not state a salary.";
      strength = EvidenceStrength.UNKNOWN;
    }
    return {
      dimension: OpportunityDimension.COMPENSATION,
      points,
      maxPoints: max,
      summary,
      strength,
    };
  }

  private accommodationRelocationDimension(claims: BenefitClaim[]): OpportunityDimensionScore {
    const max = OPPORTUNITY_DIMENSION_META[OpportunityDimension.ACCOMMODATION_RELOCATION].weightPercent;
    const accommodation = this.claim(claims, JobBenefitTypes.ACCOMMODATION.key);
    const relocation = this.claim(claims, JobBenefitTypes.RELOCATION_SUPPORT.key);
    let points = 0;
    const parts: string[] = [];
    if (accommodation?.finding === BenefitFinding.CONFIRMED) {
      points += Math.trunc(max * 0.6);
      parts.push("Accommodation confirmed");
    } else if (accommodation?.finding === BenefitFinding.CONFLICTING_EVIDENCE) {
      points += Math.trunc(max * 0.2);
      parts.push("Accommodation evidence conflicts");
    }
    if (relocation?.finding === BenefitFinding.CONFIRMED) {
      points += Math.trunc(max * 0.4);
      parts.push("relocation support confirmed");
    } else if (relocation?.finding === BenefitFinding.CONFLICTING_EVIDENCE) {
      points += Math.trunc(max * 0.15);
      parts.push("relocation evidence conflicts");
    }
    const summary =
      parts.length === 0
        ? "Neither accommodation nor relocation support is stated."
        : capitalize(parts.join(", ")) + ".";
    return {
      dimension: OpportunityDimension.ACCOMMODATION_RELOCATION,
      points: Math.max(0, Math.min(max, points)),
      maxPoints: max,
      summary,
      strength: this.strengthOf(accommodation, relocation),
    };
  }

  private travelRotationDimension(job: Job, claims: BenefitClaim[]): OpportunityDimensionScore {
    void job;
    const max = OPPORTUNITY_DIMENSION_META[OpportunityDimension.TRAVEL_ROTATION].weightPercent;
    const flights = this.claim(claims, JobBenefitTypes.FLIGHTS_HOME.key);
    const transport = this.claim(claims, JobBenefitTypes.TRANSPORT.key);
    let points = 0;
    const parts: string[] = [];
    if (flights?.finding === BenefitFinding.CONFIRMED) {
      points += Math.trunc(max * 0.7);
      parts.push("return flights stated");
    } else if (flights?.finding === BenefitFinding.CONFLICTING_EVIDENCE) {
      points += Math.trunc(max * 0.2);
      parts.push("flight evidence conflicts");
    }
    if (transport?.finding === BenefitFinding.CONFIRMED) {
      points += Math.trunc(max * 0.3);
      parts.push("transport allowance stated");
    }
    const summary =
      parts.length === 0
        ? "Return flights, rotation or transport are not stated."
        : capitalize(parts.join(", ")) + ".";
    return {
      dimension: OpportunityDimension.TRAVEL_ROTATION,
      points: Math.max(0, Math.min(max, points)),
      maxPoints: max,
      summary,
      strength: this.strengthOf(flights, transport),
    };
  }

  private insuranceOtherDimension(claims: BenefitClaim[]): OpportunityDimensionScore {
    const max = OPPORTUNITY_DIMENSION_META[OpportunityDimension.INSURANCE_OTHER].weightPercent;
    const insurance = this.claim(claims, JobBenefitTypes.HEALTH_INSURANCE.key);
    const meals = this.claim(claims, JobBenefitTypes.MEALS.key);
    const family = this.claim(claims, JobBenefitTypes.FAMILY_SUPPORT.key);
    let points = 0;
    const parts: string[] = [];
    if (insurance?.finding === BenefitFinding.CONFIRMED) {
      points += Math.trunc(max * 0.6);
      parts.push("health insurance confirmed");
    }
    if (meals?.finding === BenefitFinding.CONFIRMED) {
      points += Math.trunc(max * 0.2);
      parts.push("meals stated");
    }
    if (family?.finding === BenefitFinding.CONFIRMED) {
      points += Math.trunc(max * 0.2);
      parts.push("family support stated");
    }
    const summary =
      parts.length === 0 ? "Insurance and other benefits are not stated." : capitalize(parts.join(", ")) + ".";
    return {
      dimension: OpportunityDimension.INSURANCE_OTHER,
      points: Math.max(0, Math.min(max, points)),
      maxPoints: max,
      summary,
      strength: this.strengthOf(insurance, meals, family),
    };
  }

  private reliabilityDimension(job: Job): OpportunityDimensionScore {
    const max = OPPORTUNITY_DIMENSION_META[OpportunityDimension.RELIABILITY].weightPercent;
    const verification = job.verification;
    let points = 0;
    const notes: string[] = [];
    if (verification.activity === VerificationState.CONFIRMED) {
      points += Math.trunc(max * 0.35);
      notes.push("vacancy confirmed open");
    } else if (verification.activity === VerificationState.POSSIBLE) {
      points += Math.trunc(max * 0.2);
      notes.push("vacancy looks open");
    }
    if (verification.link === VerificationState.CONFIRMED) {
      points += Math.trunc(max * 0.25);
      notes.push("link works");
    } else if (verification.link === VerificationState.POSSIBLE) {
      points += Math.trunc(max * 0.12);
      notes.push("link reachable");
    }
    if (job.scamRisk === ScamRisk.LOW) {
      points += Math.trunc(max * 0.2);
      notes.push("no scam signals");
    }
    if (job.openToApplicants === true) {
      points += Math.trunc(max * 0.2);
      notes.push("source says still open");
    }
    const summary = notes.length === 0 ? "The posting's reliability could not be confirmed yet." : capitalize(notes.join(", ")) + ".";
    return {
      dimension: OpportunityDimension.RELIABILITY,
      points: Math.max(0, Math.min(max, points)),
      maxPoints: max,
      summary,
      strength: notes.length === 0 ? EvidenceStrength.UNKNOWN : EvidenceStrength.INFERRED,
    };
  }

  // ------------------------------------------------------------ claims

  private claimsFor(job: Job): BenefitClaim[] {
    const now = this.clock();
    const byType = new Map<string, BenefitEvidence[]>();
    for (const evidence of job.benefitEvidence) {
      const list = byType.get(evidence.type.key) ?? [];
      list.push(evidence);
      byType.set(evidence.type.key, list);
    }
    return ALL_BENEFIT_TYPES.map((type) => this.claimFrom(type.label, byType.get(type.key) ?? [], job, now));
  }

  private claimFrom(
    label: string,
    findings: BenefitEvidence[],
    job: Job,
    now: number,
  ): BenefitClaim {
    if (findings.length === 0) {
      return {
        label,
        finding: BenefitFinding.UNKNOWN,
        evidence: "",
        sourceUrl: job.sourceUrl,
        sourceType: "Posting text",
        checkedAtEpochMillis: now,
        explicit: false,
        limitations: "The listing does not mention this.",
      };
    }
    const statuses = new Set(findings.map((f) => f.status));
    const evidence = findings.find((f) => f.evidence.trim().length > 0)?.evidence ?? "";
    let finding: BenefitFinding;
    if (statuses.has(BenefitStatus.CONFIRMED) && statuses.has(BenefitStatus.NOT_OFFERED)) {
      finding = BenefitFinding.CONFLICTING_EVIDENCE;
    } else if (statuses.size === 1 && statuses.has(BenefitStatus.CONFIRMED)) {
      finding = BenefitFinding.CONFIRMED;
    } else if (statuses.size === 1 && statuses.has(BenefitStatus.NOT_OFFERED)) {
      finding = BenefitFinding.NOT_OFFERED;
    } else if (statuses.has(BenefitStatus.NEEDS_VERIFICATION)) {
      // A mention that needs checking is never presented as confirmed.
      finding = BenefitFinding.UNKNOWN;
    } else {
      finding = BenefitFinding.UNKNOWN;
    }
    const explicit = finding === BenefitFinding.CONFIRMED && statuses.size === 1 && statuses.has(BenefitStatus.CONFIRMED);
    const limitations =
      finding === BenefitFinding.CONFLICTING_EVIDENCE
        ? "The listing states this more than once, with conflicting wording."
        : finding === BenefitFinding.UNKNOWN
          ? "Mentioned in the listing, but not stated clearly enough to confirm."
          : finding === BenefitFinding.NOT_OFFERED
            ? "The listing states this is not provided."
            : "";
    return {
      label,
      finding,
      evidence,
      sourceUrl: job.sourceUrl,
      sourceType: "Posting text",
      checkedAtEpochMillis: now,
      explicit,
      limitations,
    };
  }

  private salaryFor(job: Job): NormalisedSalary | null {
    if (job.salaryMin <= 0) return null;
    // A figure whose unit is untrustworthy, or whose unit we had to infer, is not converted:
    // presenting a guessed yearly figure as the candidate's reference pay would read as a
    // confirmed number. The raw figure still shows on the card.
    if (job.salaryUnitUncertain || job.salaryPeriodInferred) return null;
    const eur = toEurPerYear(job.salaryMin, job.currencyCode, job.salaryPeriod);
    const converted = eur != null;
    return {
      originalAmount: job.salaryMin,
      originalCurrency: job.currencyCode.trim().length > 0 ? job.currencyCode : "—",
      originalPeriod: job.salaryPeriod,
      eurPerYear: eur,
      rateSource: converted ? rateSource : null,
      rateAsOfEpochMillis: converted ? rateAsOfEpochMillis : null,
      converted,
      note: converted
        ? "Converted to EUR/year using a coarse on-device rate table; treat it as an estimate, not a quote."
        : "No exchange rate is available on device, so only the original amount is shown.",
    };
  }

  // ------------------------------------------------------------ hard stops, confidence, labels

  private hardStopsFor(job: Job): string[] {
    const stops: string[] = [];
    if (job.eligibility === MadagascarEligibility.RESTRICTED) {
      stops.push("The listing appears restricted to local applicants.");
    }
    if (job.scamRisk === ScamRisk.HIGH) {
      stops.push("The scam check flagged this posting.");
    }
    if (verificationIsNegative(job.verification.link)) {
      stops.push("The application link looks broken or inaccessible.");
    }
    return stops;
  }

  private confidenceFor(claims: BenefitClaim[]): OpportunityConfidence {
    const known = claims.filter((c) => c.finding !== BenefitFinding.UNKNOWN).length;
    const total = Math.max(1, claims.length);
    const coverage = Math.trunc((known * 100) / total);
    if (known === 0) return OpportunityConfidence.INSUFFICIENT;
    if (coverage >= 60) return OpportunityConfidence.HIGH;
    if (coverage >= 30) return OpportunityConfidence.MEDIUM;
    return OpportunityConfidence.LOW;
  }

  private confidenceNote(confidence: OpportunityConfidence, claims: BenefitClaim[]): string {
    const known = claims.filter((c) => c.finding !== BenefitFinding.UNKNOWN).length;
    switch (confidence) {
      case OpportunityConfidence.INSUFFICIENT:
        return "None of the expatriate benefits are stated in the listing, so the score cannot be trusted.";
      case OpportunityConfidence.LOW:
        return `Only ${known} of ${claims.length} benefits are documented — read this as a rough signal.`;
      case OpportunityConfidence.MEDIUM:
        return `${known} of ${claims.length} benefits are documented — a partial but useful picture.`;
      default:
        return `${known} of ${claims.length} benefits are documented — a well-evidenced picture.`;
    }
  }

  private labelsFor(job: Job, claims: BenefitClaim[], salary: NormalisedSalary | null, score: number): string[] {
    const labels: string[] = [];
    if (job.matchScore >= 75 && job.roleFamily !== RoleFamily.UNRELATED) labels.push("Strong technical match");
    if (job.visaSupport === VisaSupport.FULL_SPONSORSHIP) labels.push("Visa sponsorship explicitly offered");
    if (this.claim(claims, JobBenefitTypes.ACCOMMODATION.key)?.finding === BenefitFinding.CONFIRMED) {
      labels.push("Accommodation confirmed");
    }
    if (this.claim(claims, JobBenefitTypes.FLIGHTS_HOME.key)?.finding === BenefitFinding.CONFIRMED) {
      labels.push("Return flights explicitly included");
    }
    if (salary != null && !job.salaryIsEstimated) labels.push("Salary disclosed");
    if (job.eligibility === MadagascarEligibility.UNCLEAR) labels.push("International eligibility needs verification");
    if (!claims.some((c) => c.finding === BenefitFinding.CONFIRMED)) labels.push("Expatriate benefits not disclosed");
    if (job.verification.link !== VerificationState.CONFIRMED && job.verification.link !== VerificationState.POSSIBLE) {
      labels.push("Application link needs checking");
    }
    if (job.roleFamily === RoleFamily.UNRELATED) labels.push("Outside your trade");
    if (score >= EXPAT_BAND_STRONG_MIN) labels.push("Expatriate package worth pursuing");
    return labels;
  }

  private routeSummary(job: Job): string {
    switch (job.eligibility) {
      case MadagascarEligibility.ELIGIBLE:
        return "The listing accepts international applicants; no sponsorship is mentioned.";
      case MadagascarEligibility.VISA_SPONSORED:
        return job.visaSupport === VisaSupport.FULL_SPONSORSHIP
          ? "The listing states visa sponsorship for international hires."
          : "The listing points to a sponsored route — confirm the terms with the employer.";
      case MadagascarEligibility.UNCLEAR:
        return "The listing does not say whether applicants from Madagascar are accepted.";
      default:
        return "The listing appears restricted to local applicants.";
    }
  }

  private explanation(
    band: ExpatOpportunityBand,
    score: number,
    confidence: OpportunityConfidence,
    disqualified: boolean,
    hardStops: string[],
  ): string {
    if (disqualified) {
      return `Capped at ${score}% — ${hardStops[0].toLowerCase()} A strong package cannot outweigh this.`;
    }
    if (confidence === OpportunityConfidence.INSUFFICIENT) {
      return "The listing states too little about its expatriate package to judge. That is a gap in the listing, not a verdict.";
    }
    if (band === ExpatOpportunityBand.EXCELLENT) {
      return `An outstanding expatriate package (${score}%), with most benefits documented.`;
    }
    if (band === ExpatOpportunityBand.STRONG) {
      return `A strong expatriate package (${score}%). Check the unstated items before applying.`;
    }
    if (band === ExpatOpportunityBand.POSSIBLE) {
      return `A reasonable package (${score}%), but several benefits are not stated.`;
    }
    return `A limited package (${score}%) — few expatriate benefits are documented.`;
  }

  private claim(claims: BenefitClaim[], key: string): BenefitClaim | null {
    const type = SELECTABLE_BENEFIT_TYPES.find((t) => t.key === key);
    if (!type) return null;
    return claims.find((c) => c.label === type.label) ?? null;
  }

  private strengthOf(...claims: (BenefitClaim | null | undefined)[]): EvidenceStrength {
    const present = claims.filter((c): c is BenefitClaim => c != null);
    if (present.some((c) => c.finding === BenefitFinding.CONFLICTING_EVIDENCE)) return EvidenceStrength.NEEDS_VERIFICATION;
    if (present.some((c) => c.finding === BenefitFinding.CONFIRMED && c.explicit)) return EvidenceStrength.DOCUMENTED;
    if (present.some((c) => c.finding === BenefitFinding.CONFIRMED)) return EvidenceStrength.INFERRED;
    if (present.some((c) => c.finding === BenefitFinding.NOT_OFFERED)) return EvidenceStrength.REFUSED;
    return EvidenceStrength.UNKNOWN;
  }
}

function capitalize(text: string): string {
  return text.length === 0 ? text : text[0].toUpperCase() + text.slice(1);
}
