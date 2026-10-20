import {
  BenefitStatus,
  BlockerKind,
  JobBenefitTypes,
  MadagascarEligibility,
  ScamRisk,
  VisaSupport,
  applicationReadinessFromScore,
  expatAssessmentHasEvidence,
  jobIsMaintenanceRelevant,
  type CandidateProfile,
  type ExpatPreferences,
  type Job,
  type RankComponent,
  type ReadinessBlocker,
  type ShortlistEntry,
  type ShortlistState,
} from "../../models";
import { assessExpatBenefits } from "../../expat/expat-benefits-engine";

/**
 * Builds the expat shortlist: the postings a candidate currently in Madagascar can
 * realistically act on, ranked by how ready they are to apply for — not by how well the
 * CV fits alone. Mirrors the Kotlin `ShortlistEngine`.
 *
 * The readiness score answers a question the match score cannot: "can I apply for this, and
 * what is stopping me today?". It is a transparent weighted sum of four things the app
 * already knows, so every point can be traced:
 *
 *   eligibility route  0..45  — the employer's stated route in, or its absence
 *   CV match           0..30  — the existing match score, rescaled
 *   expat benefits     0..15  — documented benefits vs. the user's priorities
 *   profile readiness  0..10  — how complete the CV-derived profile is
 *
 * Two rules keep it honest: silence is never treated as a refusal, and every blocker names
 * where it came from (a quoted posting fact, an app inference, or the user's own profile).
 * Postings the employer restricts to local applicants are excluded outright.
 */

const ELIGIBILITY_MAX = 45;
const MATCH_WEIGHT = 30;
const EXPAT_WEIGHT = 15;
const PROFILE_WEIGHT = 10;
const STRONG_MATCH = 70;
const BENEFIT_FLOOR = 50;
const PROFILE_TARGET = 80;
const DEFAULT_LIMIT = 40;

const ROUTE_LABELS = ["visa sponsorship", "relocation", "open to international", "remote or worldwide"];

export interface ShortlistEngineOptions {
  clock?: () => number;
}

export class ShortlistEngine {
  private readonly clock: () => number;

  constructor(options?: ShortlistEngineOptions) {
    this.clock = options?.clock ?? Date.now;
  }

  build(
    jobs: Job[],
    profile: CandidateProfile,
    preferences: ExpatPreferences,
    profileCompletenessPercent: number,
    salaryEurPerYear: (job: Job) => number | null,
    limit: number = DEFAULT_LIMIT,
  ): ShortlistState {
    const entries = jobs
      // Only postings the candidate can actually act on: the employer has not restricted them
      // to local applicants, and the role is the candidate's own trade rather than an unrelated
      // profession the broad remote boards happened to return. Mirrors the Kotlin ShortlistEngine.
      .filter((job) => job.eligibility !== MadagascarEligibility.RESTRICTED && jobIsMaintenanceRelevant(job))
      .map((job) =>
        this.assess(job, profile, preferences, profileCompletenessPercent, salaryEurPerYear(job)),
      )
      .sort((a, b) => b.readinessScore - a.readinessScore || b.matchScore - a.matchScore)
      .slice(0, limit);

    return {
      isLoading: false,
      entries,
      totalJobs: jobs.length,
      isLive: false,
      sourceNote: "",
      generatedAtEpochMillis: this.clock(),
    };
  }

  /** Scores one posting. Public so it can be exercised directly in tests. */
  assess(
    job: Job,
    profile: CandidateProfile,
    preferences: ExpatPreferences,
    profileCompletenessPercent: number,
    salaryEurPerYear: number | null,
  ): ShortlistEntry {
    const assessment = assessExpatBenefits(job.benefitEvidence, preferences, salaryEurPerYear);
    const expatHasEvidence = expatAssessmentHasEvidence(assessment);
    const expatScore = expatHasEvidence ? assessment.score : 0;

    const eligibilityPoints = this.eligibilityPoints(job);
    const matchPoints = Math.trunc((clamp(job.matchScore, 0, 100) * MATCH_WEIGHT) / 100);
    const expatPoints = Math.trunc((clamp(expatScore, 0, 100) * EXPAT_WEIGHT) / 100);
    const profilePoints = Math.trunc((clamp(profileCompletenessPercent, 0, 100) * PROFILE_WEIGHT) / 100);

    const score = clamp(eligibilityPoints + matchPoints + expatPoints + profilePoints, 0, 100);

    const documentedBenefits = assessment.components.filter((c) => c.status === BenefitStatus.CONFIRMED).length;
    const consideredBenefits = assessment.components.filter((c) => c.priority > 0).length;

    return {
      job,
      matchScore: job.matchScore,
      expatScore,
      readinessScore: score,
      readiness: applicationReadinessFromScore(score),
      blockers: this.buildBlockers(
        job,
        profile,
        profileCompletenessPercent,
        eligibilityPoints,
        matchPoints,
        expatHasEvidence,
        expatScore,
      ),
      highlights: this.buildHighlights(job, expatHasEvidence, documentedBenefits),
      generatedAtEpochMillis: this.clock(),
      rankComponents: this.buildRankComponents(
        job,
        eligibilityPoints,
        matchPoints,
        expatPoints,
        expatScore,
        expatHasEvidence,
        profilePoints,
        profileCompletenessPercent,
      ),
      expatEvidenceCoverage: assessment.evidenceCoverage,
      benefitsMatched: documentedBenefits,
      benefitsConsidered: consideredBenefits,
    };
  }

  private buildRankComponents(
    job: Job,
    eligibilityPoints: number,
    matchPoints: number,
    expatPoints: number,
    expatScore: number,
    expatHasEvidence: boolean,
    profilePoints: number,
    profileCompletenessPercent: number,
  ): RankComponent[] {
    return [
      {
        label: "Route in from Madagascar",
        detail:
          job.eligibility === MadagascarEligibility.VISA_SPONSORED
            ? "The employer states a route in (visa sponsorship or relocation)."
            : job.eligibility === MadagascarEligibility.ELIGIBLE
              ? "The posting is open to applicants from Madagascar."
              : job.eligibility === MadagascarEligibility.UNCLEAR
                ? "The posting does not state whether it accepts applicants from Madagascar."
                : "The posting is limited to local applicants; there is no route in.",
        points: eligibilityPoints,
        maxPoints: ELIGIBILITY_MAX,
      },
      {
        label: "CV match",
        detail: `Your CV matches ${job.matchScore}% of this posting.`,
        points: matchPoints,
        maxPoints: MATCH_WEIGHT,
      },
      {
        label: "Documented benefits",
        detail: expatHasEvidence
          ? `Benefits you care about score ${expatScore}% on what the posting documents.`
          : "The posting does not document the benefits you care about.",
        points: expatPoints,
        maxPoints: EXPAT_WEIGHT,
      },
      {
        label: "Profile completeness",
        detail:
          `Your profile is ${profileCompletenessPercent}% complete, so the CV match is ` +
          (profileCompletenessPercent >= PROFILE_TARGET ? "fully trusted." : "only partly trusted."),
        points: profilePoints,
        maxPoints: PROFILE_WEIGHT,
      },
    ];
  }

  private eligibilityPoints(job: Job): number {
    if (job.eligibility === MadagascarEligibility.VISA_SPONSORED) return 45;
    if (job.eligibility === MadagascarEligibility.ELIGIBLE && this.hasOfficialRoute(job)) return 45;
    if (job.eligibility === MadagascarEligibility.ELIGIBLE) return 38;
    if (job.eligibility === MadagascarEligibility.UNCLEAR) return 18;
    return 0;
  }

  private hasOfficialRoute(job: Job): boolean {
    return job.eligibilityReasons.some(
      (reason) => reason.official && ROUTE_LABELS.some((label) => reason.label.toLowerCase().includes(label)),
    );
  }

  private buildBlockers(
    job: Job,
    profile: CandidateProfile,
    profileCompletenessPercent: number,
    eligibilityPoints: number,
    matchPoints: number,
    expatHasEvidence: boolean,
    expatScore: number,
  ): ReadinessBlocker[] {
    void matchPoints;
    const blockers: ReadinessBlocker[] = [];

    if (job.eligibility === MadagascarEligibility.UNCLEAR) {
      blockers.push({
        label: "Eligibility not stated",
        detail:
          "The posting does not say whether it accepts applicants from Madagascar. " +
          "Ask the employer before you invest time in the application.",
        kind: BlockerKind.HEURISTIC,
        points: ELIGIBILITY_MAX - eligibilityPoints,
      });
    } else if (job.eligibility === MadagascarEligibility.RESTRICTED) {
      blockers.push({
        label: "Restricted to local applicants",
        detail: "The posting states a local-only requirement, so there is no route in from Madagascar.",
        kind: BlockerKind.OFFICIAL,
        points: ELIGIBILITY_MAX,
      });
    }

    // A stated visa route the employer has not pinned to a benefit sentence is worth
    // confirming, even when the verdict is VISA_SPONSORED.
    if (job.eligibility === MadagascarEligibility.VISA_SPONSORED && !this.documentsVisaSponsorship(job)) {
      blockers.push({
        label: "Confirm the visa terms",
        detail:
          "The posting mentions sponsorship but does not spell out the conditions. " +
          "Confirm who pays the fees and whether dependants are covered.",
        kind: BlockerKind.HEURISTIC,
        points: 8,
      });
    }

    if (job.matchScore < STRONG_MATCH) {
      const gap = STRONG_MATCH - job.matchScore;
      const missing = job.missingSkills.slice(0, 3);
      blockers.push({
        label: `CV match below ${STRONG_MATCH}%`,
        detail:
          missing.length > 0
            ? `Your CV is missing ${job.missingSkills.length} skill(s) this posting lists: ${missing.join(", ")}.`
            : "This posting fits your CV less closely than your strongest matches.",
        kind: BlockerKind.PROFILE,
        points: Math.trunc((gap * MATCH_WEIGHT) / 100),
      });
    }

    if (!expatHasEvidence) {
      blockers.push({
        label: "Benefits not stated",
        detail:
          "The posting does not document visa, relocation or housing support. " +
          "That is missing information, not a refusal — ask the employer.",
        kind: BlockerKind.HEURISTIC,
        points: EXPAT_WEIGHT,
      });
    } else if (expatScore < BENEFIT_FLOOR) {
      blockers.push({
        label: "Documented benefits fall short",
        detail:
          "The benefits the employer documents score below your priorities. " +
          "Review the evidence before deciding.",
        kind: BlockerKind.HEURISTIC,
        points: Math.trunc(((BENEFIT_FLOOR - expatScore) * EXPAT_WEIGHT) / 100),
      });
    }

    if (profileCompletenessPercent < PROFILE_TARGET) {
      blockers.push({
        label: `Profile ${profileCompletenessPercent}% complete`,
        detail:
          "A fuller profile scores more accurately. Add the missing CV sections so this match can be trusted.",
        kind: BlockerKind.PROFILE,
        points: Math.trunc(((PROFILE_TARGET - profileCompletenessPercent) * PROFILE_WEIGHT) / 100),
      });
    }

    if (job.scamRisk === ScamRisk.HIGH) {
      blockers.push({
        label: "Scam shield flagged this posting",
        detail: "Several risk signals were found. Open the scam check before you share any details.",
        kind: BlockerKind.HEURISTIC,
        points: 20,
      });
    } else if (job.scamRisk === ScamRisk.MEDIUM) {
      blockers.push({
        label: "Some details need checking",
        detail: "The scam shield found minor caution signals. Verify the employer independently.",
        kind: BlockerKind.HEURISTIC,
        points: 8,
      });
    }

    if (profile.fullName.trim().length === 0) {
      blockers.push({
        label: "No name on your profile",
        detail: "Import and analyse your CV so applications carry your real details.",
        kind: BlockerKind.PROFILE,
        points: 6,
      });
    }

    return blockers.filter((b) => b.points > 0);
  }

  private documentsVisaSponsorship(job: Job): boolean {
    return job.benefitEvidence.some(
      (evidence) =>
        evidence.type.key === JobBenefitTypes.VISA_SPONSORSHIP.key && evidence.status === BenefitStatus.CONFIRMED,
    );
  }

  private buildHighlights(job: Job, expatHasEvidence: boolean, confirmedBenefits: number): string[] {
    const highlights: string[] = [];

    for (const reason of job.eligibilityReasons) {
      if (!reason.official) continue;
      const label = reason.label.toLowerCase();
      if (label.includes("visa sponsorship")) highlights.push("Employer states visa sponsorship");
      else if (label.includes("open to international")) highlights.push("Open to international applicants");
      else if (label.includes("relocation")) highlights.push("Relocation support mentioned");
      else if (label.includes("remote or worldwide")) highlights.push("Listed as remote or worldwide");
    }

    if (job.matchScore >= STRONG_MATCH) highlights.push(`Strong CV match (${job.matchScore}%)`);
    if (expatHasEvidence && confirmedBenefits > 0) {
      highlights.push(`Documents ${confirmedBenefits} benefit${confirmedBenefits === 1 ? "" : "s"} you care about`);
    }
    if (job.visaSupport === VisaSupport.FULL_SPONSORSHIP) highlights.push("Full sponsorship advertised");
    if (job.isNew) highlights.push("Freshly posted");
    return distinct(highlights);
  }
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

function distinct(values: string[]): string[] {
  return [...new Set(values)];
}
