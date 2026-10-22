import type { Job } from "../../models";

/**
 * How current a posting is, derived only from evidence the source gave us. Mirrors the Kotlin
 * `JobFreshnessEngine`.
 *
 * The distinction that matters: `Job.postedAtEpochMillis` is the employer's publication date
 * and may be absent, while `Job.retrievedAtEpochMillis` is when this app last pulled the
 * listing. A posting with no publication date is `UNKNOWN`, never "fresh" — the app does not
 * invent a date to fill the gap. `STALE` is claimed only when a real publication date is older
 * than `staleAfterDays`; a listing the source keeps returning without a date is at worst
 * `AGING`, because we cannot prove it expired.
 */
export enum Freshness {
  FRESH = "FRESH",
  RECENT = "RECENT",
  AGING = "AGING",
  STALE = "STALE",
  UNKNOWN = "UNKNOWN",
}

/** The classification plus the human sentence explaining it, so the UI never re-derives it. */
export interface FreshnessAssessment {
  freshness: Freshness;
  label: string;
  detail: string;
}

const DAY_MS = 86_400_000;
/**
 * The window beyond which a dated posting is `STALE`. Set to 30 days to match the Kotlin
 * `JobFreshnessEngine`: the product favours postings published in the last month and treats
 * anything older as likely filled. The engine still takes an explicit window.
 */
export const DEFAULT_STALE_AFTER_DAYS = 30;

/**
 * Classifies postings by age. Pure and clock-injected so it runs anywhere and is testable
 * without a device. It reads dates, never writes them: a missing publication date stays
 * missing and is reported as `UNKNOWN`.
 */
export class JobFreshnessEngine {
  private readonly staleAfterDays: number;

  constructor(staleAfterDays: number = DEFAULT_STALE_AFTER_DAYS) {
    this.staleAfterDays = staleAfterDays;
  }

  assess(job: Job, now: number = Date.now()): FreshnessAssessment {
    const rawPosted = job.postedAtEpochMillis;
    // A future date is a source clock problem, not a fresh posting; treat it as no date.
    const posted = rawPosted != null && rawPosted >= 1 && rawPosted <= now ? rawPosted : null;

    if (posted == null) {
      return {
        freshness: Freshness.UNKNOWN,
        label: "Date not stated",
        detail:
          "The source did not provide a publication date, so this app cannot say how recent it is. " +
          "Open the listing to check.",
      };
    }

    const ageDays = Math.trunc((now - posted) / DAY_MS);
    const freshness =
      ageDays < 2
        ? Freshness.FRESH
        : ageDays < 14
          ? Freshness.RECENT
          : ageDays <= this.staleAfterDays
            ? Freshness.AGING
            : Freshness.STALE;

    const label =
      freshness === Freshness.FRESH
        ? "Published recently"
        : freshness === Freshness.RECENT
          ? "Published this fortnight"
          : freshness === Freshness.AGING
            ? "Published a while ago"
            : freshness === Freshness.STALE
              ? "Likely filled"
              : "Date not stated";

    const retrievedSuffix =
      job.retrievedAtEpochMillis != null
        ? ` Retrieved from the source ${relativeDays(job.retrievedAtEpochMillis, now)}.`
        : "";
    const detail =
      freshness === Freshness.FRESH
        ? `The source published this within the last two days.${retrievedSuffix}`
        : freshness === Freshness.RECENT
          ? `The source published this within the last two weeks.${retrievedSuffix}`
          : freshness === Freshness.AGING
            ? `The source published this ${ageDays} days ago. Confirm it is still open before investing time.${retrievedSuffix}`
            : freshness === Freshness.STALE
              ? `The source published this ${ageDays} days ago, older than the ${this.staleAfterDays}-day window. It may already be filled.${retrievedSuffix}`
              : "The source did not state a publication date.";

    return { freshness, label, detail };
  }
}

function relativeDays(epochMillis: number, now: number): string {
  const days = Math.trunc(Math.max(0, now - epochMillis) / DAY_MS);
  if (days <= 0) return "today";
  if (days === 1) return "yesterday";
  return `${days} days ago`;
}
