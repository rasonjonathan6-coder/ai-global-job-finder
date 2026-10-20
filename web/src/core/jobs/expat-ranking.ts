import { ApplicationReadiness, applicationReadinessIsActionable, type Job } from "../models";
import { type ExpatOpportunity } from "../expat/opportunity";

/**
 * The ranked-expatriate sub-language, ported from the Kotlin `data/model/ExpatRanking.kt`.
 * Kept in its own module for the same reason as `opportunity.ts`: it is a self-contained
 * vocabulary the ranking engine speaks that the rest of the model file does not need.
 */

/**
 * The labels a ranked posting can carry. Each one is only emitted when the evidence behind
 * it exists — a "visa sponsorship" label needs the employer's stated sponsorship, a
 * "salary disclosed" label needs a real figure. `positive` drives the colour in the UI so a
 * caution never looks like a selling point.
 */
export enum RankingLabel {
  TOP_PRIORITY = "TOP_PRIORITY",
  READY_TO_APPLY = "READY_TO_APPLY",
  VISA_SPONSORSHIP = "VISA_SPONSORSHIP",
  RELOCATION_CONFIRMED = "RELOCATION_CONFIRMED",
  ACCOMMODATION_CONFIRMED = "ACCOMMODATION_CONFIRMED",
  FLIGHTS_CONFIRMED = "FLIGHTS_CONFIRMED",
  SALARY_DISCLOSED = "SALARY_DISCLOSED",
  STRONG_PACKAGE = "STRONG_PACKAGE",
  VERIFY_ELIGIBILITY = "VERIFY_ELIGIBILITY",
  BENEFITS_NOT_DISCLOSED = "BENEFITS_NOT_DISCLOSED",
  LINK_TO_CHECK = "LINK_TO_CHECK",
  SCAM_CAUTION = "SCAM_CAUTION",
  NOT_OPEN_TO_YOU = "NOT_OPEN_TO_YOU",
}

export const RANKING_LABEL_META: Record<RankingLabel, { label: string; emoji: string; positive: boolean }> = {
  [RankingLabel.TOP_PRIORITY]: { label: "Top priority", emoji: "🏆", positive: true },
  [RankingLabel.READY_TO_APPLY]: { label: "Ready to apply", emoji: "✅", positive: true },
  [RankingLabel.VISA_SPONSORSHIP]: { label: "Visa sponsorship", emoji: "🛂", positive: true },
  [RankingLabel.RELOCATION_CONFIRMED]: { label: "Relocation support", emoji: "📦", positive: true },
  [RankingLabel.ACCOMMODATION_CONFIRMED]: { label: "Accommodation", emoji: "🏠", positive: true },
  [RankingLabel.FLIGHTS_CONFIRMED]: { label: "Return flights", emoji: "✈️", positive: true },
  [RankingLabel.SALARY_DISCLOSED]: { label: "Salary disclosed", emoji: "💰", positive: true },
  [RankingLabel.STRONG_PACKAGE]: { label: "Strong package", emoji: "⭐", positive: true },
  [RankingLabel.VERIFY_ELIGIBILITY]: { label: "Verify eligibility", emoji: "⚠️", positive: false },
  [RankingLabel.BENEFITS_NOT_DISCLOSED]: { label: "Benefits not stated", emoji: "❔", positive: false },
  [RankingLabel.LINK_TO_CHECK]: { label: "Check the link", emoji: "🔗", positive: false },
  [RankingLabel.SCAM_CAUTION]: { label: "Scam caution", emoji: "🛡️", positive: false },
  [RankingLabel.NOT_OPEN_TO_YOU]: { label: "Not open to you", emoji: "⛔", positive: false },
};

/**
 * One posting placed in the explainable expatriate ranking.
 *
 * `finalScore` folds together how ready the posting is to apply for (`readinessScore`, from
 * the shortlist) and how good its documented expatriate package is (`opportunity`). The two
 * ingredients stay on the record beside the total, so the rank can always be traced. A
 * disqualified posting — ineligible, scam-flagged or with a dead link — is capped and can
 * never sit at the top, however attractive its package looks.
 */
export interface ExpatRankedJob {
  job: Job;
  /** 1-based position in the ranking, strongest first. */
  rank: number;
  finalScore: number;
  readinessScore: number;
  readiness: ApplicationReadiness;
  opportunity: ExpatOpportunity;
  labels: RankingLabel[];
  /** Short, evidence-backed reasons for the rank, strongest first. */
  reasons: string[];
}

/** The whole ranked feed plus the counts the UI summarises, as one immutable snapshot. */
export interface ExpatRankingState {
  isLoading: boolean;
  ranked: ExpatRankedJob[];
  totalJobs: number;
  generatedAtEpochMillis: number;
}

export const EMPTY_EXPAT_RANKING_STATE: ExpatRankingState = {
  isLoading: true,
  ranked: [],
  totalJobs: 0,
  generatedAtEpochMillis: 0,
};

export function expatRankingActionableCount(state: ExpatRankingState): number {
  return state.ranked.filter((it) => applicationReadinessIsActionable(it.readiness)).length;
}

export function expatRankingDisqualifiedCount(state: ExpatRankingState): number {
  return state.ranked.filter((it) => it.opportunity.disqualified).length;
}

export function expatRankingTopOpportunities(state: ExpatRankingState): ExpatRankedJob[] {
  return state.ranked.slice(0, 3);
}
