import {
  AutoApplyStatus,
  ScamRisk,
  jobApplyableFromMadagascar,
  type AutoApplyItem,
  type Job,
} from "../models";

/**
 * Decides which postings the safe auto-apply flow should prepare, and why. Mirrors the
 * Kotlin `AutoApplyEngine`.
 *
 * The engine refuses to prepare anything it cannot defend: a posting below the user's
 * match threshold, one that is not open to a Madagascar-based applicant, or one the scam
 * detector flagged. Refusals are surfaced as [AutoApplyStatus.BLOCKED] with the reason,
 * never silently dropped.
 *
 * It prepares drafts only. Submission is always a separate, explicit user action.
 */
export class AutoApplyEngine {
  static readonly DEFAULT_THRESHOLD = 70;
  static readonly DEFAULT_LIMIT = 12;

  constructor(
    readonly threshold: number = AutoApplyEngine.DEFAULT_THRESHOLD,
    private readonly clock: () => number = () => Date.now(),
  ) {}

  /** One decision about one posting. */
  decide(job: Job): AutoApplyDecision {
    if (job.scamRisk === ScamRisk.HIGH) {
      return {
        status: AutoApplyStatus.BLOCKED,
        reason: "The scam check flagged this posting. Review it by hand before doing anything.",
        eligible: false,
      };
    }
    if (!jobApplyableFromMadagascar(job)) {
      return {
        status: AutoApplyStatus.BLOCKED,
        reason: "This posting is not open to applicants from Madagascar.",
        eligible: false,
      };
    }
    if (job.matchScore < this.threshold) {
      return {
        status: AutoApplyStatus.SKIPPED,
        reason: `Your match is ${job.matchScore}%, below your ${this.threshold}% threshold.`,
        eligible: false,
      };
    }
    if (job.sourceUrl.trim() === "") {
      return {
        status: AutoApplyStatus.BLOCKED,
        reason: "There is no application link, so nothing can be prepared.",
        eligible: false,
      };
    }
    return {
      status: AutoApplyStatus.DRAFT_READY,
      reason: `Match ${job.matchScore}% and open to Madagascar applicants. Draft ready for your approval.`,
      eligible: true,
    };
  }

  /**
   * Builds the queue for a feed, best match first. Jobs already tracked keep their
   * existing entry so approving one is never undone by a refresh.
   */
  buildQueue(
    jobs: Job[],
    existing: AutoApplyItem[],
    limit: number = AutoApplyEngine.DEFAULT_LIMIT,
  ): AutoApplyItem[] {
    const existingByJob = new Map(existing.map((item) => [item.jobId, item]));
    const queue: AutoApplyItem[] = [];
    for (const job of [...jobs].sort((a, b) => b.matchScore - a.matchScore)) {
      const decision = this.decide(job);
      if (decision.status === AutoApplyStatus.SKIPPED) continue;
      const previous = existingByJob.get(job.id);
      let status: AutoApplyStatus = decision.status;
      if (previous != null) {
        if (previous.status === AutoApplyStatus.APPROVED) status = AutoApplyStatus.APPROVED;
        else if (previous.status === AutoApplyStatus.SKIPPED) status = AutoApplyStatus.SKIPPED;
      }
      queue.push({
        jobId: job.id,
        jobTitle: job.title,
        company: job.company,
        country: job.country,
        countryFlag: job.countryFlag,
        matchScore: job.matchScore,
        sourceUrl: job.sourceUrl,
        status,
        coverLetterId: previous?.coverLetterId ?? null,
        reason:
          status === AutoApplyStatus.APPROVED
            ? "You approved this draft. Send it from the posting."
            : decision.reason,
        preparedAtEpochMillis: previous?.preparedAtEpochMillis ?? this.clock(),
      });
      if (queue.length >= limit) break;
    }
    return queue;
  }
}

/** One decision about one posting. */
export interface AutoApplyDecision {
  status: AutoApplyStatus;
  reason: string;
  eligible: boolean;
}
