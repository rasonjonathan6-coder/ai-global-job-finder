import {
  AvailabilitySignalType,
  AvailabilityStatus,
  type AvailabilityEvidence,
  type AvailabilityResult,
  type Job,
} from "../../models";

/**
 * Reads the two availability indicators that need no network: the source's own "still
 * accepting applicants" flag and any employer deadline the source published. Mirrors the
 * Kotlin `OfferAvailabilityEngine`. Everything else — reaching the application link — is
 * optional and lives in `AvailabilityVerifier`.
 *
 * The rules that keep this honest, in order of importance:
 *  - A source flag of `false` is `EXPIRED`; the source itself said so.
 *  - A source flag of `true` is `OPEN`; the source itself said so.
 *  - A published deadline already behind us is `EXPIRED`, even when the flag is silent.
 *  - Silence is `UNCONFIRMED`. Being young is not proof it is open; being old is not proof
 *    it is closed.
 *
 * Pure and clock-injected, so it runs in a test with no network and never writes a date.
 */
export class OfferAvailabilityEngine {
  assess(job: Job, now: number = Date.now()): AvailabilityResult {
    const evidence: AvailabilityEvidence[] = [];
    if (job.openToApplicants != null) evidence.push(sourceFlagEvidence(job, job.openToApplicants));
    const deadline = deadlineEvidence(job, now);
    if (deadline) evidence.push(deadline);

    const status = decideStatus(job, now);

    return { status, evidence, lastCheckedAtEpochMillis: now };
  }
}

/** The verdict, with the source's own flag outranking everything else. */
function decideStatus(job: Job, now: number): AvailabilityStatus {
  if (job.openToApplicants === true) return AvailabilityStatus.OPEN;
  if (job.openToApplicants === false) return AvailabilityStatus.EXPIRED;
  if (job.expiresAtEpochMillis != null && job.expiresAtEpochMillis <= now) return AvailabilityStatus.EXPIRED;
  return AvailabilityStatus.UNCONFIRMED;
}

/** The source's own flag, carried as `SOURCE_FLAG`. */
function sourceFlagEvidence(job: Job, open: boolean): AvailabilityEvidence {
  return {
    type: AvailabilitySignalType.SOURCE_FLAG,
    detail: open
      ? "The source itself reports this posting is still accepting applicants."
      : "The source itself reports this posting is no longer accepting applicants.",
    sourceUrl: job.sourceUrl,
  };
}

/**
 * The employer's own deadline, carried as `EMPLOYER_DEADLINE`. A future deadline is recorded
 * as a fact but does not, on its own, confirm the posting is open — a stated closing date says
 * nothing about whether it opened for applicants — so only a past deadline changes the verdict.
 */
function deadlineEvidence(job: Job, now: number): AvailabilityEvidence | null {
  const expires = job.expiresAtEpochMillis;
  if (expires == null) return null;
  if (expires <= now) {
    return {
      type: AvailabilitySignalType.EMPLOYER_DEADLINE,
      detail: `The employer stated applications close ${dateOf(expires)}, which has passed.`,
      sourceUrl: job.sourceUrl,
    };
  }
  return {
    type: AvailabilitySignalType.EMPLOYER_DEADLINE,
    detail: `The employer states applications close ${dateOf(expires)}.`,
    sourceUrl: job.sourceUrl,
  };
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** A stated calendar date in UTC, matching the Kotlin formatter ("d MMM yyyy"). */
function dateOf(epochMillis: number): string {
  const d = new Date(epochMillis);
  return `${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]} ${d.getUTCFullYear()}`;
}
