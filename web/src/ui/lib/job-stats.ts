import {
  MadagascarEligibility,
  matchTierFromScore,
  MatchTier,
  type DashboardStats,
  type Job,
} from "../../core/models";
import { ApplicationStatus, type ApplicationRecord } from "../../state/app-store";

/** The match score at which a posting counts as a match. Mirrors the Android `LiveJobRepository`. */
export const MATCHING_JOBS_MIN = 60;
/** The match score at which a posting is worth the user's time. Mirrors the Android threshold. */
export const STRONG_MATCH_MIN = 70;

/**
 * The four headline numbers on the dashboard. They count the real, ranked jobs and the real
 * tracker rows, so a quiet day shows zeros rather than invented activity.
 */
export function dashboardStats(jobs: Job[], applications: ApplicationRecord[]): DashboardStats {
  return {
    matchingJobs: jobs.filter((job) => job.matchScore >= MATCHING_JOBS_MIN).length,
    newOpportunities: jobs.filter((job) => job.isNew).length,
    applications: applications.filter((a) => a.status !== ApplicationStatus.SAVED).length,
    interviews: applications.filter((a) => a.status === ApplicationStatus.INTERVIEW).length,
  };
}

/** True when the match is strong enough to lead the list. */
export function isStrongMatch(job: Job): boolean {
  return matchTierFromScore(job.matchScore) !== MatchTier.LOW && job.matchScore >= STRONG_MATCH_MIN;
}

/** How many postings the user can realistically apply for from Madagascar. */
export function applicableCount(jobs: Job[]): number {
  return jobs.filter(
    (job) =>
      job.matchScore >= STRONG_MATCH_MIN &&
      (job.eligibility === MadagascarEligibility.ELIGIBLE ||
        job.eligibility === MadagascarEligibility.VISA_SPONSORED),
  ).length;
}
