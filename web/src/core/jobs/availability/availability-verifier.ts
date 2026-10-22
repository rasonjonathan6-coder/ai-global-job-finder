import {
  AvailabilitySignalType,
  AvailabilityStatus,
  AvailabilityCache,
  type AvailabilityEvidence,
  type AvailabilityResult,
  type Job,
} from "../../models";
import { OfferAvailabilityEngine } from "./offer-availability-engine";

/**
 * The outcome of probing one application link. Mirrors the Kotlin `LinkProbeOutcome`. A probe
 * can fail for reasons that say nothing about the vacancy, so the caller must be able to tell
 * a "closed" answer from "could not reach".
 */
export type LinkProbeOutcome =
  | { kind: "open"; detail: string }
  | { kind: "closed"; detail: string }
  | { kind: "unknown"; detail: string };

/** Reaches one application link and reports what it observed. Injected so tests use no network. */
export type AvailabilityLinkProbe = (url: string) => Promise<LinkProbeOutcome>;

/**
 * Turns what a link probe observed into a verdict, using only markers that reliably mean
 * "still open" or "no longer available". Mirrors the Kotlin `LinkProbeClassifier`.
 *
 * This is the rule that stops a plain HTTP 200 from being read as proof of openness: a 200 is
 * not in either marker list, so on its own it stays `unknown`. Only a body that positively
 * advertises an application action promotes a posting to open, and only a body that positively
 * says the vacancy is gone demotes it to expired. Pure, so every rule is unit-tested.
 */
export class LinkProbeClassifier {
  private static readonly OPEN_MARKERS = [
    "apply for this job",
    "apply now",
    "apply for this position",
    "submit your application",
    "start your application",
    "we are hiring",
    "we're hiring",
  ];

  private static readonly CLOSED_MARKERS = [
    "no longer accepting applications",
    "no longer available",
    "this position has been filled",
    "this job has been filled",
    "position closed",
    "job posting has expired",
    "this job is closed",
    "applications are closed",
    "this role is no longer",
    "vacancy has been filled",
    "job not found",
    "this posting is no longer",
  ];

  /**
   * Classifies a probe result. A "closed" marker is checked before an "open" marker, because a
   * page can carry both a stale "Apply" button and an explicit "position filled" notice; when
   * the two conflict, the safer reading is the closed one.
   */
  static classify(statusCode: number, body: string): LinkProbeOutcome {
    const haystack = body.toLowerCase();

    if (statusCode === 404 || statusCode === 410) {
      return { kind: "closed", detail: `The application link answered HTTP ${statusCode}: the posting is gone.` };
    }
    if (statusCode === 401 || statusCode === 403 || statusCode === 429) {
      return {
        kind: "unknown",
        detail: `The application link answered HTTP ${statusCode}, so this app could not read it (blocked or rate-limited).`,
      };
    }
    if (statusCode < 200 || statusCode > 299) {
      return {
        kind: "unknown",
        detail: `The application link answered HTTP ${statusCode}, which does not confirm whether the vacancy is open.`,
      };
    }
    if (LinkProbeClassifier.CLOSED_MARKERS.some((m) => haystack.includes(m))) {
      return { kind: "closed", detail: "The application page states the vacancy is no longer available." };
    }
    if (LinkProbeClassifier.OPEN_MARKERS.some((m) => haystack.includes(m))) {
      return { kind: "open", detail: "The application page still offers an active application action." };
    }
    return {
      kind: "unknown",
      detail: "The application link is reachable but does not state whether the vacancy is still open.",
    };
  }
}

/**
 * Verifies availability, using the cache to avoid needless requests and the engine for the
 * indicators that need no request at all. Mirrors the Kotlin `AvailabilityVerifier`.
 *
 * The order of decisions is the point of the class:
 *  1. A settled verdict younger than 24 h is served from cache — no request is made.
 *  2. Otherwise the source's own flag and the employer deadline decide. A flag is the source's
 *     own word and outranks everything, so if it is present the link is not probed at all.
 *  3. Only when no flag is present, a probe is configured, and the posting carries a usable
 *     link is the link reached — and then a plain HTTP 200 is *not* enough: only a positive
 *     marker turns `OPEN`. A definitive "gone" marker turns it `EXPIRED`. Anything else stays
 *     `UNCONFIRMED`.
 *
 * A settled result is cached for 24 h; an unconfirmed one never is.
 */
export class AvailabilityVerifier {
  private readonly engine: OfferAvailabilityEngine;
  private readonly cache: AvailabilityCache;
  private readonly probe: AvailabilityLinkProbe | null;
  private readonly linkIsUsable: (job: Job) => boolean;
  private readonly clock: () => number;

  constructor(options?: {
    engine?: OfferAvailabilityEngine;
    cache?: AvailabilityCache;
    probe?: AvailabilityLinkProbe | null;
    linkIsUsable?: (job: Job) => boolean;
    clock?: () => number;
  }) {
    this.engine = options?.engine ?? new OfferAvailabilityEngine();
    this.cache = options?.cache ?? new AvailabilityCache();
    this.probe = options?.probe ?? null;
    this.linkIsUsable = options?.linkIsUsable ?? ((job) => job.sourceUrl.trim().length > 0);
    this.clock = options?.clock ?? Date.now;
  }

  /** The fresh settled verdict already cached for `jobId`, or null. */
  cached(jobId: string): AvailabilityResult | null {
    return this.cache.get(jobId);
  }

  /**
   * True when this posting could be probed: a probe is configured, the source gave no flag,
   * and the posting carries a usable link. The caller uses this to spend its budget wisely.
   */
  canProbe(job: Job): boolean {
    return this.probe != null && job.openToApplicants == null && this.linkIsUsable(job);
  }

  /**
   * Verifies availability. `allowProbe` lets the caller hold back link requests to respect
   * source quotas: when false, only the source's own indicators are read.
   */
  async verify(job: Job, allowProbe = true): Promise<AvailabilityResult> {
    const now = this.clock();

    const cached = this.cache.get(job.id);
    if (cached) return cached;

    const base = this.engine.assess(job, now);

    if (job.openToApplicants != null) {
      this.cache.put(job.id, base);
      return base;
    }

    const outcome: LinkProbeOutcome =
      allowProbe && this.probe != null && this.linkIsUsable(job)
        ? await this.probe(job.sourceUrl)
        : {
            kind: "unknown",
            detail: "No application link was probed, so this app cannot confirm the vacancy is still open.",
          };

    const result = mergeProbe(job, base, outcome, now);
    this.cache.put(job.id, result);
    return result;
  }
}

function mergeProbe(
  job: Job,
  base: AvailabilityResult,
  outcome: LinkProbeOutcome,
  now: number,
): AvailabilityResult {
  const signal = (type: AvailabilitySignalType): AvailabilityEvidence => ({
    type,
    detail: outcome.detail,
    sourceUrl: job.sourceUrl,
  });
  switch (outcome.kind) {
    case "open":
      return {
        status: AvailabilityStatus.OPEN,
        evidence: [...base.evidence, signal(AvailabilitySignalType.LINK_PROBE)],
        lastCheckedAtEpochMillis: now,
      };
    case "closed":
      return {
        status: AvailabilityStatus.EXPIRED,
        evidence: [...base.evidence, signal(AvailabilitySignalType.LINK_PROBE)],
        lastCheckedAtEpochMillis: now,
      };
    case "unknown":
    default:
      // A probe that could not settle it adds a "we tried and could not confirm" fact, but it
      // must never change the verdict to open or expired.
      return {
        ...base,
        evidence: [...base.evidence, signal(AvailabilitySignalType.NONE)],
        lastCheckedAtEpochMillis: now,
      };
  }
}
