import { SalaryPeriod } from "../../models";
import type { RawJob } from "../provider/raw-job";

/**
 * Why a retrieved record was not allowed into the feed. Mirrors the Kotlin
 * `RecordRejectionReason`. Each value is a decision the app can defend: the record was
 * missing something the UI or an engine needs, or it was a repeat of a posting already kept.
 */
export enum RecordRejectionReason {
  MISSING_TITLE = "MISSING_TITLE",
  MISSING_EMPLOYER = "MISSING_EMPLOYER",
  MISSING_LOCATION = "MISSING_LOCATION",
  MISSING_DESCRIPTION = "MISSING_DESCRIPTION",
  MISSING_URL = "MISSING_URL",
  INVALID_URL = "INVALID_URL",
  STALE_POSTING = "STALE_POSTING",
  IMPLAUSIBLE_SALARY = "IMPLAUSIBLE_SALARY",
  INCOMPLETE_SALARY = "INCOMPLETE_SALARY",
  DUPLICATE = "DUPLICATE",
  CLOSED_TO_APPLICANTS = "CLOSED_TO_APPLICANTS",
  EXPIRED = "EXPIRED",
}

/** The outcome of validating one record. */
export interface RecordValidation {
  accepted: boolean;
  reasons: RecordRejectionReason[];
  /** Stable id, identical to the id the UI uses, so counters and cards agree. */
  recordId: string;
  /** Identity used for deduplication: provider-agnostic, so cross-source repeats collapse. */
  dedupKey: string;
}

/** Per-provider outcome of a validation + dedup pass. */
export interface ValidationSummary {
  providerId: string;
  receivedCount: number;
  acceptedCount: number;
  duplicateCount: number;
  rejectedByReason: Map<RecordRejectionReason, number>;
}

export function summaryRejectedCount(summary: ValidationSummary): number {
  let total = 0;
  for (const value of summary.rejectedByReason.values()) total += value;
  return total;
}

/** The whole pass: what survived, what was dropped, and why, grouped by source. */
export interface DeduplicationResult {
  kept: RawJob[];
  summaries: ValidationSummary[];
  totalReceived: number;
  totalKept: number;
}

const DAY_MS = 86_400_000;

/** The id the UI keys a record by. Kept here so validation and rendering cannot drift. */
export function recordId(raw: RawJob): string {
  const external = firstNonBlank(raw.externalId, raw.url, raw.title);
  return `${raw.providerId}:${external}`;
}

/**
 * Provider-agnostic identity for deduplication. Employer and title are normalised so
 * "Northline Industrial" and "northline industrial " collapse, while genuinely different
 * roles at the same employer stay separate.
 */
export function dedupKey(raw: RawJob): string {
  const company = normalise(raw.company.trim().length === 0 ? raw.title : raw.company);
  const title = normalise(raw.title);
  return `${company}|${title}`;
}

function firstNonBlank(...values: string[]): string {
  for (const value of values) {
    const trimmed = value.trim();
    if (trimmed.length > 0) return trimmed;
  }
  return "";
}

function normalise(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}

/**
 * Decides which retrieved records are real enough to show and collapses repeats. Mirrors
 * the Kotlin `RecordValidationEngine`.
 *
 * Two rules keep this honest:
 *  - A record is rejected only for a field the app cannot work without. Anything merely
 *    absent is left absent; it is never replaced with an invented value.
 *  - Two postings are duplicates only when the employer and the title agree after
 *    normalisation. Salary, location and source are deliberately excluded, because the
 *    same role legitimately appears at different salaries and in different markets.
 *
 * Ordering is stable: input order is preserved for the records that are kept, so the feed
 * does not reshuffle between refreshes.
 */
export class RecordValidationEngine {
  private readonly maxPostedAgeDays: number | null;
  private readonly minPlausibleAnnual: number;
  private readonly maxPlausibleAnnual: number;

  constructor(options?: {
    /** Postings older than this are treated as filled. Null disables the age check. */
    maxPostedAgeDays?: number | null;
    /** Below this a stated annual salary is treated as a typo rather than a real figure. */
    minPlausibleAnnual?: number;
    /** Above this a stated annual salary is treated as a typo rather than a real figure. */
    maxPlausibleAnnual?: number;
  }) {
    this.maxPostedAgeDays = options?.maxPostedAgeDays === undefined ? 120 : options.maxPostedAgeDays;
    this.minPlausibleAnnual = options?.minPlausibleAnnual ?? 1_000;
    this.maxPlausibleAnnual = options?.maxPlausibleAnnual ?? 2_000_000;
  }

  validate(raw: RawJob, now: number): RecordValidation {
    const reasons: RecordRejectionReason[] = [];

    const title = raw.title.trim();
    const company = raw.company.trim();

    if (title.length === 0) reasons.push(RecordRejectionReason.MISSING_TITLE);
    // A record needs some employer identity. A title is the minimum the card can carry; a
    // blank company with a title is kept and labelled "Unknown employer".
    if (company.length === 0 && title.length === 0) reasons.push(RecordRejectionReason.MISSING_EMPLOYER);
    if (raw.location.trim().length === 0) reasons.push(RecordRejectionReason.MISSING_LOCATION);
    if (raw.descriptionHtml.trim().length === 0) reasons.push(RecordRejectionReason.MISSING_DESCRIPTION);

    const url = raw.url.trim();
    if (url.length === 0) {
      reasons.push(RecordRejectionReason.MISSING_URL);
    } else if (!isHttpUrl(url)) {
      reasons.push(RecordRejectionReason.INVALID_URL);
    }

    reasons.push(...this.salaryIssues(raw));
    reasons.push(...this.ageIssues(raw, now));

    // A deadline the source stated that has already passed is positive evidence the posting
    // is closed, so it is dropped with its own reason. A posting with no stated deadline is
    // never rejected here: "unknown" is not "expired".
    const expires = raw.expiresAtEpochMillis;
    if (expires != null && expires <= now) reasons.push(RecordRejectionReason.EXPIRED);

    // A posting the source itself marks as closed is not shown as if it were open. This is a
    // stated fact, not an age guess, so it is surfaced with its own reason.
    if (raw.closedToNewApplicants) reasons.push(RecordRejectionReason.CLOSED_TO_APPLICANTS);

    return {
      accepted: reasons.length === 0,
      reasons,
      recordId: recordId(raw),
      dedupKey: dedupKey(raw),
    };
  }

  /** Validates every record, then collapses cross-source duplicates, counting each reason. */
  deduplicate(records: RawJob[], now: number): DeduplicationResult {
    const kept: RawJob[] = [];
    const seen = new Set<string>();
    const received = new Map<string, number>();
    const accepted = new Map<string, number>();
    const duplicates = new Map<string, number>();
    const reasons = new Map<string, Map<RecordRejectionReason, number>>();

    const bump = (map: Map<string, number>, id: string) => map.set(id, (map.get(id) ?? 0) + 1);
    const recordReason = (providerId: string, reason: RecordRejectionReason) => {
      let bucket = reasons.get(providerId);
      if (!bucket) {
        bucket = new Map();
        reasons.set(providerId, bucket);
      }
      bucket.set(reason, (bucket.get(reason) ?? 0) + 1);
    };

    for (const raw of records) {
      bump(received, raw.providerId);
      const validation = this.validate(raw, now);
      if (!validation.accepted) {
        for (const reason of validation.reasons) recordReason(raw.providerId, reason);
        continue;
      }
      if (seen.has(validation.dedupKey)) {
        bump(duplicates, raw.providerId);
        recordReason(raw.providerId, RecordRejectionReason.DUPLICATE);
        continue;
      }
      seen.add(validation.dedupKey);
      bump(accepted, raw.providerId);
      kept.push(raw);
    }

    const providerIds = Array.from(new Set([...received.keys(), ...reasons.keys()])).sort();
    const summaries = providerIds.map((id): ValidationSummary => {
      const rejectedByReason = new Map(reasons.get(id) ?? []);
      return {
        providerId: id,
        receivedCount: received.get(id) ?? 0,
        acceptedCount: accepted.get(id) ?? 0,
        duplicateCount: duplicates.get(id) ?? 0,
        rejectedByReason,
      };
    });

    return { kept, summaries, totalReceived: records.length, totalKept: kept.length };
  }

  private salaryIssues(raw: RawJob): RecordRejectionReason[] {
    const min = raw.salaryMin;
    const max = raw.salaryMax;
    const issues: RecordRejectionReason[] = [];

    if (min != null && max != null && min > max) issues.push(RecordRejectionReason.IMPLAUSIBLE_SALARY);
    const low = min ?? max;
    const high = max ?? min;
    if (low != null && low < 0) issues.push(RecordRejectionReason.IMPLAUSIBLE_SALARY);

    // Only annual figures have a stable enough meaning to sanity-check, and only when the
    // source's unit is trustworthy — a figure whose period we could not believe is left for
    // the UI to flag rather than rejected here.
    if (!raw.salaryUnitUncertain && (raw.salaryPeriod === SalaryPeriod.YEAR || raw.salaryPeriod === null)) {
      if (high != null && high > this.maxPlausibleAnnual) issues.push(RecordRejectionReason.IMPLAUSIBLE_SALARY);
      if (low != null && low >= 1 && low < this.minPlausibleAnnual) {
        issues.push(RecordRejectionReason.IMPLAUSIBLE_SALARY);
      }
    }
    return Array.from(new Set(issues));
  }

  private ageIssues(raw: RawJob, now: number): RecordRejectionReason[] {
    const posted = raw.postedAtEpochMillis;
    if (posted == null) return [];
    const limit = this.maxPostedAgeDays;
    if (limit == null) return [];
    // A future date is a source clock problem, not a reason to hide the posting.
    if (posted > now) return [];
    const ageDays = Math.trunc((now - posted) / DAY_MS);
    return ageDays > limit ? [RecordRejectionReason.STALE_POSTING] : [];
  }
}

function isHttpUrl(url: string): boolean {
  const lower = url.toLowerCase();
  if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
  const host = lower.substring(lower.indexOf("://") + 3).split("/")[0].split("?")[0];
  return host.length > 0 && host.includes(".");
}
