import type { EvidenceStrength, SalaryPeriod } from "../models";

/**
 * The Expatriate Opportunity Score vocabulary, ported from the Kotlin
 * `data/model/ExpatOpportunity.kt`. Kept in its own module because it is a self-contained
 * sub-language (the expat package speaks it) that the rest of the model file does not need.
 *
 * The score is a fixed weighted sum of the seven `OpportunityDimension`s, and every claim
 * keeps the employer's own words. Two honesty rules run through it: silence is `UNKNOWN`,
 * never a refusal; and a disqualifier caps the total so a high number can never read as a
 * good opportunity when the candidate cannot actually take it.
 */

/** One dimension of the Expatriate Opportunity Score. The weights sum to 100. */
export enum OpportunityDimension {
  INTERNATIONAL_ELIGIBILITY = "INTERNATIONAL_ELIGIBILITY",
  ROLE_FIT = "ROLE_FIT",
  COMPENSATION = "COMPENSATION",
  ACCOMMODATION_RELOCATION = "ACCOMMODATION_RELOCATION",
  TRAVEL_ROTATION = "TRAVEL_ROTATION",
  INSURANCE_OTHER = "INSURANCE_OTHER",
  RELIABILITY = "RELIABILITY",
}

export const OPPORTUNITY_DIMENSION_META: Record<OpportunityDimension, { label: string; weightPercent: number }> = {
  [OpportunityDimension.INTERNATIONAL_ELIGIBILITY]: { label: "International eligibility & work permit", weightPercent: 25 },
  [OpportunityDimension.ROLE_FIT]: { label: "Role and skills alignment", weightPercent: 20 },
  [OpportunityDimension.COMPENSATION]: { label: "Salary and compensation transparency", weightPercent: 15 },
  [OpportunityDimension.ACCOMMODATION_RELOCATION]: { label: "Accommodation and relocation", weightPercent: 15 },
  [OpportunityDimension.TRAVEL_ROTATION]: { label: "Travel, rotation and return flights", weightPercent: 10 },
  [OpportunityDimension.INSURANCE_OTHER]: { label: "Insurance and other benefits", weightPercent: 5 },
  [OpportunityDimension.RELIABILITY]: { label: "Offer reliability and application availability", weightPercent: 10 },
};

/**
 * The honest finding for one benefit. Adds `CONFLICTING_EVIDENCE`, which the app must never
 * paper over, and keeps `UNKNOWN` ("the listing did not say") strictly separate from
 * `NOT_OFFERED` ("the employer stated it is not provided").
 */
export enum BenefitFinding {
  CONFIRMED = "CONFIRMED",
  NOT_OFFERED = "NOT_OFFERED",
  CONFLICTING_EVIDENCE = "CONFLICTING_EVIDENCE",
  UNKNOWN = "UNKNOWN",
}

export const BENEFIT_FINDING_META: Record<BenefitFinding, { label: string; emoji: string }> = {
  [BenefitFinding.CONFIRMED]: { label: "Confirmed", emoji: "✅" },
  [BenefitFinding.NOT_OFFERED]: { label: "Stated as not offered", emoji: "🚫" },
  [BenefitFinding.CONFLICTING_EVIDENCE]: { label: "Conflicting evidence", emoji: "⚠️" },
  [BenefitFinding.UNKNOWN]: { label: "Not stated", emoji: "❔" },
};

/** True when the listing actually told us something, one way or the other. */
export function benefitFindingIsKnown(finding: BenefitFinding): boolean {
  return finding !== BenefitFinding.UNKNOWN;
}

/** One benefit finding with the evidence behind it. */
export interface BenefitClaim {
  label: string;
  finding: BenefitFinding;
  evidence: string;
  sourceUrl: string;
  sourceType: string;
  checkedAtEpochMillis: number | null;
  /** True when the statement is explicit in the listing rather than inferred. */
  explicit: boolean;
  limitations: string;
}

/** True when the claim rests on the employer's own text. */
export function benefitClaimIsDocumented(claim: BenefitClaim): boolean {
  return claim.finding === BenefitFinding.CONFIRMED && claim.explicit;
}

/** An advertised salary, normalised without ever discarding the original. */
export interface NormalisedSalary {
  originalAmount: number;
  originalCurrency: string;
  originalPeriod: SalaryPeriod;
  /** The estimate in EUR/year, or null when no rate exists for the currency. */
  eurPerYear: number | null;
  rateSource: string | null;
  rateAsOfEpochMillis: number | null;
  converted: boolean;
  note: string;
}

/**
 * How much of the posting actually told us something. `INSUFFICIENT` means "we cannot
 * judge", which is not the same as "this is poor".
 */
export enum OpportunityConfidence {
  INSUFFICIENT = "INSUFFICIENT",
  LOW = "LOW",
  MEDIUM = "MEDIUM",
  HIGH = "HIGH",
}

export const OPPORTUNITY_CONFIDENCE_META: Record<OpportunityConfidence, { label: string }> = {
  [OpportunityConfidence.INSUFFICIENT]: { label: "Not enough evidence to judge" },
  [OpportunityConfidence.LOW]: { label: "Low — most benefits not stated" },
  [OpportunityConfidence.MEDIUM]: { label: "Medium — some benefits documented" },
  [OpportunityConfidence.HIGH]: { label: "High — most benefits documented" },
};

export function opportunityConfidenceIsEnough(confidence: OpportunityConfidence): boolean {
  return confidence !== OpportunityConfidence.INSUFFICIENT;
}

/** The band a score falls into. */
export enum ExpatOpportunityBand {
  INSUFFICIENT = "INSUFFICIENT",
  WEAK = "WEAK",
  POSSIBLE = "POSSIBLE",
  STRONG = "STRONG",
  EXCELLENT = "EXCELLENT",
}

export const EXPAT_OPPORTUNITY_BAND_META: Record<ExpatOpportunityBand, { label: string; shortLabel: string; emoji: string }> = {
  [ExpatOpportunityBand.INSUFFICIENT]: { label: "Not enough evidence to judge", shortLabel: "Insufficient", emoji: "❔" },
  [ExpatOpportunityBand.WEAK]: { label: "Weak expatriate offer", shortLabel: "Weak", emoji: "⚠️" },
  [ExpatOpportunityBand.POSSIBLE]: { label: "Worth a look", shortLabel: "Possible", emoji: "🔎" },
  [ExpatOpportunityBand.STRONG]: { label: "A strong expatriate offer", shortLabel: "Strong", emoji: "✅" },
  [ExpatOpportunityBand.EXCELLENT]: { label: "An outstanding expatriate offer", shortLabel: "Excellent", emoji: "🏆" },
};

export const EXPAT_BAND_EXCELLENT_MIN = 80;
export const EXPAT_BAND_STRONG_MIN = 65;
export const EXPAT_BAND_POSSIBLE_MIN = 45;

export function expatOpportunityBandFromScore(score: number): ExpatOpportunityBand {
  if (score >= EXPAT_BAND_EXCELLENT_MIN) return ExpatOpportunityBand.EXCELLENT;
  if (score >= EXPAT_BAND_STRONG_MIN) return ExpatOpportunityBand.STRONG;
  if (score >= EXPAT_BAND_POSSIBLE_MIN) return ExpatOpportunityBand.POSSIBLE;
  return ExpatOpportunityBand.WEAK;
}

/** What one dimension earned, and on what basis. */
export interface OpportunityDimensionScore {
  dimension: OpportunityDimension;
  points: number;
  maxPoints: number;
  summary: string;
  strength: EvidenceStrength;
}

export function opportunityDimensionEarnedShare(score: OpportunityDimensionScore): number {
  return score.maxPoints <= 0 ? 0 : score.points / score.maxPoints;
}

/** The complete, explainable expatriate assessment for one posting. */
export interface ExpatOpportunity {
  score: number;
  band: ExpatOpportunityBand;
  dimensions: OpportunityDimensionScore[];
  claims: BenefitClaim[];
  salary: NormalisedSalary | null;
  confidence: OpportunityConfidence;
  confidenceNote: string;
  hardStops: string[];
  disqualified: boolean;
  labels: string[];
  routeSummary: string;
  explanation: string;
}

export function expatOpportunityIsInsufficient(opportunity: ExpatOpportunity): boolean {
  return opportunity.band === ExpatOpportunityBand.INSUFFICIENT;
}

/** Dimensions strongest first, so a panel can lead with what carries the score. */
export function rankedDimensions(opportunity: ExpatOpportunity): OpportunityDimensionScore[] {
  return [...opportunity.dimensions].sort((a, b) => b.points - a.points);
}

/** The honest empty read, used before a posting has been assessed. */
export const EMPTY_EXPAT_OPPORTUNITY: ExpatOpportunity = {
  score: 0,
  band: ExpatOpportunityBand.INSUFFICIENT,
  dimensions: [],
  claims: [],
  salary: null,
  confidence: OpportunityConfidence.INSUFFICIENT,
  confidenceNote: "Import and analyse your CV, then open a posting to assess its expatriate package.",
  hardStops: [],
  disqualified: false,
  labels: [],
  routeSummary: "Open a posting to see the route in from Madagascar.",
  explanation: "There is not enough on the posting yet to judge its expatriate offer.",
};
