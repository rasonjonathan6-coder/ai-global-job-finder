import { SalaryPeriod } from "../../models";

/**
 * One posting exactly as a source reported it, before any matching or eligibility work.
 * Mirrors the Kotlin `RawJob` field for field. Providers only translate a feed into this
 * shape; they never score, guess or enrich, so a field the source did not send stays null
 * rather than being filled in.
 */
export interface RawJob {
  providerId: string;
  providerName: string;
  externalId: string;
  title: string;
  company: string;
  /** The location exactly as published, e.g. "Berlin", "France", "Worldwide". */
  location: string;
  descriptionHtml: string;
  url: string;
  tags: string[];
  employmentType: string | null;
  postedAtEpochMillis: number | null;
  /**
   * The application deadline the source itself published, when it published one. Null means
   * the source stated no deadline — which is not the same as "no deadline exists".
   */
  expiresAtEpochMillis: number | null;
  /** When the app fetched this posting, so an offline cache can report its real age. */
  retrievedAtEpochMillis: number | null;
  isRemote: boolean;
  salaryMin: number | null;
  salaryMax: number | null;
  salaryCurrency: string | null;
  salaryPeriod: SalaryPeriod | null;
  /** True when the source gave no pay unit and it was inferred from the amount. */
  salaryPeriodInferred: boolean;
  /** True when the source's stated unit is impossible for the amount ("USD 106k/hr"). */
  salaryUnitUncertain: boolean;
  /** True only when the source itself says the posting is closed to new applicants. */
  closedToNewApplicants: boolean;
  /** The source's own statement that the posting is open, or null when it said nothing. */
  openToApplicants: boolean | null;
}

/** A `RawJob` with the optional fields defaulted, matching the Kotlin data-class defaults. */
export function rawJob(
  fields: Partial<RawJob> & Pick<RawJob, "providerId" | "providerName" | "externalId" | "title">,
): RawJob {
  return {
    company: "",
    location: "",
    descriptionHtml: "",
    url: "",
    tags: [],
    employmentType: null,
    postedAtEpochMillis: null,
    expiresAtEpochMillis: null,
    retrievedAtEpochMillis: null,
    isRemote: false,
    salaryMin: null,
    salaryMax: null,
    salaryCurrency: null,
    salaryPeriod: null,
    salaryPeriodInferred: false,
    salaryUnitUncertain: false,
    closedToNewApplicants: false,
    openToApplicants: null,
    ...fields,
  };
}

/**
 * Broad classification of a source, used for the "official source" trust signals.
 * Mirrors the Kotlin `ProviderKind`.
 */
export enum ProviderKind {
  REMOTE_BOARD = "REMOTE_BOARD",
  GOVERNMENT = "GOVERNMENT",
  AGGREGATOR = "AGGREGATOR",
  EMPLOYER_BOARD = "EMPLOYER_BOARD",
}
