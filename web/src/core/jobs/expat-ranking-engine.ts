import {
  ApplicationReadiness,
  EvidenceStrength,
  JobBenefitTypes,
  MadagascarEligibility,
  ScamRisk,
  VerificationState,
  VisaSupport,
  benefitTypeByKey,
  orderedBlockers,
  type ExpatPreferences,
  type ShortlistEntry,
  type ShortlistState,
} from "../models";
import {
  BenefitFinding,
  ExpatOpportunityBand,
  OpportunityDimension,
  opportunityConfidenceIsEnough,
  type ExpatOpportunity,
} from "../expat/opportunity";
import { ExpatOpportunityEngine } from "../expat/expat-opportunity-engine";
import { RankingLabel, type ExpatRankedJob, type ExpatRankingState } from "./expat-ranking";

/**
 * Ranks the shortlisted postings for an applicant in Madagascar and explains every place.
 * Mirrors the Kotlin `ExpatRankingEngine`.
 *
 * The rank is a weighted blend of two numbers the app already derives and keeps separate:
 *
 *   readiness  0..60 — how ready the posting is to apply for (`ShortlistEntry.readinessScore`):
 *                      the route in, the CV match, the documented benefits and the profile.
 *   opportunity 0..40 — how good the documented expatriate package is (`ExpatOpportunity.score`):
 *                      the money, housing, flights and insurance the employer states.
 *
 * Both are read from the shortlist, so the ranking never re-derives eligibility or benefits
 * and can never disagree with the screens that show them. Three rules keep it honest:
 *
 *  1. A disqualifier cannot be outvoted. A posting that is blocked for the candidate, or whose
 *     package is disqualified (ineligible, scam-flagged, dead link), is placed below every
 *     posting that is not, regardless of score.
 *  2. Labels need evidence. A label such as "visa sponsorship" is only attached when the
 *     employer's stated sponsorship (or documented benefit) exists; silence produces the
 *     caution label "benefits not stated", never a positive one.
 *  3. Ranking is deterministic. Ties are broken by final score, then readiness, then the job
 *     id, so the same feed always yields the same order.
 */

const READINESS_WEIGHT = 60;
const OPPORTUNITY_WEIGHT = 40;
const DISQUALIFIED_CAP = 35;
const DEFAULT_LIMIT = 60;

export interface ExpatRankingEngineOptions {
  opportunityEngine?: ExpatOpportunityEngine;
  clock?: () => number;
}

export class ExpatRankingEngine {
  private readonly opportunityEngine: ExpatOpportunityEngine;
  private readonly clock: () => number;

  constructor(options?: ExpatRankingEngineOptions) {
    this.opportunityEngine = options?.opportunityEngine ?? new ExpatOpportunityEngine();
    this.clock = options?.clock ?? Date.now;
  }

  rank(
    shortlist: ShortlistState,
    preferences: ExpatPreferences,
    limit: number = DEFAULT_LIMIT,
  ): ExpatRankingState {
    if (shortlist.entries.length === 0) {
      return {
        isLoading: shortlist.isLoading,
        ranked: [],
        totalJobs: shortlist.totalJobs,
        generatedAtEpochMillis: this.clock(),
      };
    }

    const ranked = shortlist.entries
      .map((entry) => this.scored(entry, preferences))
      .sort((a, b) => {
        const aDisq = isDisqualified(a);
        const bDisq = isDisqualified(b);
        if (aDisq !== bDisq) return aDisq ? 1 : -1;
        if (a.finalScore !== b.finalScore) return b.finalScore - a.finalScore;
        if (a.readinessScore !== b.readinessScore) return b.readinessScore - a.readinessScore;
        return a.job.id < b.job.id ? -1 : a.job.id > b.job.id ? 1 : 0;
      })
      .slice(0, limit)
      .map((item, index) => ({ ...item, rank: index + 1 }));

    return {
      isLoading: false,
      ranked,
      totalJobs: shortlist.totalJobs,
      generatedAtEpochMillis: this.clock(),
    };
  }

  private scored(entry: ShortlistEntry, preferences: ExpatPreferences): ExpatRankedJob {
    const opportunity = this.opportunityEngine.assess(entry.job, preferences);
    const raw =
      Math.trunc((entry.readinessScore * READINESS_WEIGHT) / 100) +
      Math.trunc((opportunity.score * OPPORTUNITY_WEIGHT) / 100);
    const disqualified = entry.readiness === ApplicationReadiness.BLOCKED || opportunity.disqualified;
    const finalScore = disqualified ? Math.min(raw, DISQUALIFIED_CAP) : clamp(raw, 0, 100);

    return {
      job: entry.job,
      rank: 0,
      finalScore,
      readinessScore: entry.readinessScore,
      readiness: entry.readiness,
      opportunity,
      labels: labelsFor(entry, opportunity, disqualified),
      reasons: reasonsFor(entry, opportunity),
    };
  }
}

function isDisqualified(item: ExpatRankedJob): boolean {
  return item.opportunity.disqualified || item.readiness === ApplicationReadiness.BLOCKED;
}

function labelsFor(
  entry: ShortlistEntry,
  opportunity: ExpatOpportunity,
  disqualified: boolean,
): RankingLabel[] {
  const labels: RankingLabel[] = [];
  const job = entry.job;
  if (disqualified) {
    labels.push(RankingLabel.NOT_OPEN_TO_YOU);
  } else {
    if (entry.readiness === ApplicationReadiness.PRIORITY) labels.push(RankingLabel.TOP_PRIORITY);
    else if (entry.readiness === ApplicationReadiness.READY) labels.push(RankingLabel.READY_TO_APPLY);
    if (job.visaSupport === VisaSupport.FULL_SPONSORSHIP) labels.push(RankingLabel.VISA_SPONSORSHIP);
    if (hasConfirmedBenefit(opportunity, JobBenefitTypes.RELOCATION_SUPPORT.key)) {
      labels.push(RankingLabel.RELOCATION_CONFIRMED);
    }
    if (hasConfirmedBenefit(opportunity, JobBenefitTypes.ACCOMMODATION.key)) {
      labels.push(RankingLabel.ACCOMMODATION_CONFIRMED);
    }
    if (hasConfirmedBenefit(opportunity, JobBenefitTypes.FLIGHTS_HOME.key)) {
      labels.push(RankingLabel.FLIGHTS_CONFIRMED);
    }
    if (opportunity.salary !== null && !job.salaryIsEstimated) labels.push(RankingLabel.SALARY_DISCLOSED);
    if (isStrongOrBetter(opportunity.band)) labels.push(RankingLabel.STRONG_PACKAGE);
  }
  // Cautions are always surfaced, so a strong score cannot hide an unresolved risk.
  if (!disqualified && job.eligibility === MadagascarEligibility.UNCLEAR) {
    labels.push(RankingLabel.VERIFY_ELIGIBILITY);
  }
  if (!opportunity.claims.some((c) => c.finding === BenefitFinding.CONFIRMED)) {
    labels.push(RankingLabel.BENEFITS_NOT_DISCLOSED);
  }
  if (job.verification.link !== VerificationState.CONFIRMED && job.verification.link !== VerificationState.POSSIBLE) {
    labels.push(RankingLabel.LINK_TO_CHECK);
  }
  if (job.scamRisk === ScamRisk.MEDIUM) labels.push(RankingLabel.SCAM_CAUTION);
  return labels;
}

function reasonsFor(entry: ShortlistEntry, opportunity: ExpatOpportunity): string[] {
  const reasons: string[] = [];
  // The strongest documented benefit, quoted from the employer's own words.
  const confirmed = opportunity.claims.find((c) => c.finding === BenefitFinding.CONFIRMED && c.evidence.trim().length > 0);
  if (confirmed) reasons.push(`${confirmed.label}: “${confirmed.evidence.trim().slice(0, 120)}”`);
  // The single biggest thing holding the posting back, if anything is.
  const hardest = orderedBlockers(entry)[0];
  if (hardest) reasons.push(`${hardest.label} — ${hardest.detail}`);
  // The reliability read, stated plainly rather than as a number.
  const reliabilityUnknown = opportunity.dimensions.some(
    (d) => d.dimension === OpportunityDimension.RELIABILITY && d.strength === EvidenceStrength.UNKNOWN,
  );
  if (reliabilityUnknown) reasons.push("The posting's reliability could not be confirmed yet.");
  // Confidence in the expatriate package, so a thin listing is not read as a verdict.
  if (!opportunityConfidenceIsEnough(opportunity.confidence)) reasons.push(opportunity.confidenceNote);
  return reasons;
}

/** True when the posting documents this benefit, keyed by the stable benefit key. */
function hasConfirmedBenefit(opportunity: ExpatOpportunity, key: string): boolean {
  const type = benefitTypeByKey(key);
  if (!type) return false;
  return opportunity.claims.some((c) => c.finding === BenefitFinding.CONFIRMED && c.label === type.label);
}

function isStrongOrBetter(band: ExpatOpportunityBand): boolean {
  return band === ExpatOpportunityBand.STRONG || band === ExpatOpportunityBand.EXCELLENT;
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}
