import type { Job } from "../models";

/**
 * Identity of an application, used to stop the same opening being applied to twice. Mirrors
 * the Kotlin `SubmissionKey`.
 *
 * The key is deliberately provider-agnostic and independent of the app's own job id: the
 * same posting reaches the app from several boards, each with its own id and URL, and those
 * ids change between releases. Employer + normalised title + country is the strongest stable
 * signal available offline.
 */
export class SubmissionKey {
  constructor(
    readonly normalizedCompany: string,
    readonly normalizedTitle: string,
    readonly normalizedCountry: string,
  ) {}

  /** Canonical value stored in the registry and used as the dedup index. */
  get value(): string {
    return `${this.normalizedCompany}|${this.normalizedTitle}|${this.normalizedCountry}`;
  }

  /** Normalises one component: case-folded, punctuation stripped, single spaces. */
  static normalise(value: string): string {
    return value
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, " ")
      .trim();
  }

  /** Derives the key for a posting. Falls back to the title when the employer is blank. */
  static of(job: Job): SubmissionKey;
  static of(company: string, title: string, country: string): SubmissionKey;
  static of(jobOrCompany: Job | string, title?: string, country?: string): SubmissionKey {
    if (typeof jobOrCompany === "string") {
      return new SubmissionKey(
        SubmissionKey.normalise(jobOrCompany),
        SubmissionKey.normalise(title ?? ""),
        SubmissionKey.normalise(country ?? ""),
      );
    }
    const job = jobOrCompany;
    return SubmissionKey.of(job.company.trim() === "" ? job.title : job.company, job.title, job.country);
  }
}
