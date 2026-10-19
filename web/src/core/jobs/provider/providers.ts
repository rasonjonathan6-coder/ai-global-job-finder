import { SalaryPeriod } from "../../models";
import { rawJob, ProviderKind, type RawJob } from "./raw-job";
import {
  HttpJsonFeed,
  JobProviderError,
  ProviderErrorCategory,
  classifyProviderError,
  describeCategory,
  optBool,
  optInt,
  optString,
  toStringList,
  type JsonFeed,
} from "./feed";
import { isoEpochMillis, SalaryParser } from "./text-parsing";

/**
 * Translates one source's feed into `RawJob`s. Mirrors the Kotlin `JobProvider`: it only
 * translates, never scores or enriches, so a field the source did not send stays null.
 */
export interface JobProvider {
  readonly id: string;
  readonly name: string;
  readonly homepage: string;
  readonly kind: ProviderKind;
  readonly documentationUrl: string;
  fetch(keywords?: string[], limit?: number): Promise<RawJob[]>;
}

function wrap(providerId: string, message: string, cause: unknown): JobProviderError {
  return new JobProviderError(providerId, message, classifyProviderError(message, cause), cause);
}

function requireArray(body: Record<string, unknown>, key: string, providerId: string, label: string): unknown[] {
  const value = body[key];
  if (!Array.isArray(value)) {
    throw new JobProviderError(
      providerId,
      `${label} returned an unexpected response shape`,
      ProviderErrorCategory.INVALID_RESPONSE,
    );
  }
  return value;
}

function periodOf(raw: string): SalaryPeriod | null {
  switch (raw.toLowerCase()) {
    case "yearly":
    case "year":
    case "annual":
    case "annually":
      return SalaryPeriod.YEAR;
    case "monthly":
    case "month":
      return SalaryPeriod.MONTH;
    case "daily":
    case "day":
      return SalaryPeriod.DAY;
    case "hourly":
    case "hour":
      return SalaryPeriod.HOUR;
    default:
      return null;
  }
}

/**
 * Remotive — a curated remote-jobs board. Its terms require linking back to the original
 * posting and naming Remotive as the source, and ask for at most a few requests per day; the
 * card always links out to the Remotive URL, so both conditions are met.
 */
export class RemotiveProvider implements JobProvider {
  readonly id = "remotive";
  readonly name = "Remotive";
  readonly homepage = "https://remotive.com";
  readonly kind = ProviderKind.REMOTE_BOARD;
  readonly documentationUrl = "https://remotive.com/remote-jobs/api";

  constructor(private readonly feed: JsonFeed = HttpJsonFeed) {}

  async fetch(keywords: string[] = [], limit = 60): Promise<RawJob[]> {
    const query = keywords.find((k) => k.trim().length > 0);
    const url = query
      ? `${BASE_REMOTIVE}?limit=${limit}&search=${encodeURIComponent(query)}`
      : `${BASE_REMOTIVE}?limit=${limit}`;
    let body: Record<string, unknown>;
    try {
      body = await this.feed.objectAt(url);
    } catch (cause) {
      if (cause instanceof JobProviderError) throw cause;
      throw wrap(this.id, "Remotive did not respond", cause);
    }
    const jobs = requireArray(body, "jobs", this.id, "Remotive");
    return jobs.filter(isObject).map((item) => this.toRawJob(item));
  }

  private toRawJob(item: Record<string, unknown>): RawJob {
    const parsed = SalaryParser.parse(optString(item.salary));
    return rawJob({
      providerId: this.id,
      providerName: this.name,
      externalId: optString(item.id) || optString(item.url),
      title: optString(item.title),
      company: optString(item.company_name),
      location: optString(item.candidate_required_location) || "Worldwide",
      descriptionHtml: optString(item.description),
      url: optString(item.url),
      tags: toStringList(item.tags),
      employmentType: optString(item.job_type) || null,
      postedAtEpochMillis: isoEpochMillis(optString(item.publication_date)),
      isRemote: true,
      salaryMin: parsed.min,
      salaryMax: parsed.max,
      salaryCurrency: parsed.currency,
      salaryPeriod: parsed.period ?? (parsed.min != null && !parsed.unitUncertain ? SalaryPeriod.YEAR : null),
      salaryPeriodInferred: parsed.periodInferred,
      salaryUnitUncertain: parsed.unitUncertain,
    });
  }
}

/**
 * Arbeitnow — a free, public European job board API. Its terms ask for attribution, which
 * the app provides through the source badge and the "official posting" link. Server-side
 * search is not supported, so the feed is fetched and filtered locally.
 */
export class ArbeitnowProvider implements JobProvider {
  readonly id = "arbeitnow";
  readonly name = "Arbeitnow";
  readonly homepage = "https://www.arbeitnow.com";
  readonly kind = ProviderKind.AGGREGATOR;
  readonly documentationUrl = "https://www.arbeitnow.com/blog/arbeitnow-api";

  constructor(private readonly feed: JsonFeed = HttpJsonFeed) {}

  async fetch(_keywords: string[] = [], limit = 60): Promise<RawJob[]> {
    let body: Record<string, unknown>;
    try {
      body = await this.feed.objectAt(`${BASE_ARBEITNOW}?page=1`);
    } catch (cause) {
      if (cause instanceof JobProviderError) throw cause;
      throw wrap(this.id, "Arbeitnow did not respond", cause);
    }
    const data = requireArray(body, "data", this.id, "Arbeitnow");
    return data.filter(isObject).map((item) => this.toRawJob(item)).slice(0, limit);
  }

  private toRawJob(item: Record<string, unknown>): RawJob {
    const slug = optString(item.slug);
    const jobTypes = toStringList(item.job_types);
    const remote = optBool(item.remote, false);
    const location = optString(item.location) || (remote ? "Remote" : "");
    const createdAt = optInt(item.created_at);
    return rawJob({
      providerId: this.id,
      providerName: this.name,
      externalId: slug || optString(item.url),
      title: optString(item.title),
      company: optString(item.company_name),
      location,
      descriptionHtml: optString(item.description),
      url: optString(item.url),
      tags: [...toStringList(item.tags), ...jobTypes],
      employmentType: jobTypes[0] ?? null,
      postedAtEpochMillis: createdAt != null && createdAt > 0 ? createdAt * 1000 : null,
      isRemote: remote,
    });
  }
}

/**
 * Jobicy — a remote-jobs API with structured salary fields and an explicit geo field, which
 * makes it the most reliable of the key-free sources for salary and location data.
 */
export class JobicyProvider implements JobProvider {
  readonly id = "jobicy";
  readonly name = "Jobicy";
  readonly homepage = "https://jobicy.com";
  readonly kind = ProviderKind.REMOTE_BOARD;
  readonly documentationUrl = "https://jobicy.com/jobs-rss-feed";

  constructor(private readonly feed: JsonFeed = HttpJsonFeed) {}

  async fetch(_keywords: string[] = [], limit = 60): Promise<RawJob[]> {
    const url = `${BASE_JOBICY}?count=${Math.min(Math.max(limit, 1), 50)}`;
    let body: Record<string, unknown>;
    try {
      body = await this.feed.objectAt(url);
    } catch (cause) {
      if (cause instanceof JobProviderError) throw cause;
      throw wrap(this.id, "Jobicy did not respond", cause);
    }
    const jobs = requireArray(body, "jobs", this.id, "Jobicy");
    return jobs.filter(isObject).map((item) => this.toRawJob(item));
  }

  private toRawJob(item: Record<string, unknown>): RawJob {
    const rawMin = optInt(item.salaryMin);
    const rawMax = optInt(item.salaryMax);
    const min = rawMin != null && rawMin > 0 ? rawMin : null;
    const max = rawMax != null && rawMax > 0 ? rawMax : null;
    const jobTypes = toStringList(item.jobType);
    return rawJob({
      providerId: this.id,
      providerName: this.name,
      externalId: optString(item.id) || optString(item.jobSlug),
      title: optString(item.jobTitle),
      company: optString(item.companyName),
      location: optString(item.jobGeo) || "Worldwide",
      descriptionHtml: optString(item.jobDescription) || optString(item.jobExcerpt),
      url: optString(item.url),
      tags: [...toStringList(item.jobIndustry), ...jobTypes],
      employmentType: jobTypes[0] ?? null,
      postedAtEpochMillis: isoEpochMillis(optString(item.pubDate)),
      isRemote: true,
      salaryMin: min,
      salaryMax: max ?? min,
      salaryCurrency: optString(item.salaryCurrency) || null,
      salaryPeriod: periodOf(optString(item.salaryPeriod)) ?? (min != null ? SalaryPeriod.YEAR : null),
    });
  }
}

/**
 * Himalayas — a remote-jobs board with a public, key-free JSON feed and structured salary
 * fields. The feed carries `locationRestrictions`, exactly the signal the eligibility engine
 * needs: a posting restricted to one country is not a realistic target from Madagascar,
 * while an empty restriction list means the employer accepts remote applicants from anywhere.
 */
export class HimalayasProvider implements JobProvider {
  readonly id = "himalayas";
  readonly name = "Himalayas";
  readonly homepage = "https://himalayas.app";
  readonly kind = ProviderKind.REMOTE_BOARD;
  readonly documentationUrl = "https://himalayas.app/jobs/api";

  constructor(private readonly feed: JsonFeed = HttpJsonFeed) {}

  async fetch(_keywords: string[] = [], limit = 60): Promise<RawJob[]> {
    const url = `${BASE_HIMALAYAS}?limit=${Math.min(Math.max(limit, 1), 100)}`;
    let body: Record<string, unknown>;
    try {
      body = await this.feed.objectAt(url);
    } catch (cause) {
      if (cause instanceof JobProviderError) throw cause;
      throw wrap(this.id, "Himalayas did not respond", cause);
    }
    // The feed always carries a "jobs" array, even when empty. A body without one is an
    // unexpected shape, not an empty result, so the two are not confused.
    const jobs = requireArray(body, "jobs", this.id, "Himalayas");
    return jobs.filter(isObject).map((item) => this.toRawJob(item));
  }

  private toRawJob(item: Record<string, unknown>): RawJob {
    const restrictions = toStringList(item.locationRestrictions);
    const rawMin = optInt(item.minSalary);
    const rawMax = optInt(item.maxSalary);
    const min = rawMin != null && rawMin > 0 ? rawMin : null;
    const max = rawMax != null && rawMax > 0 ? rawMax : null;
    const pubDate = optInt(item.pubDate);
    return rawJob({
      providerId: this.id,
      providerName: this.name,
      externalId: optString(item.guid) || optString(item.applicationLink),
      title: optString(item.title),
      company: optString(item.companyName),
      location: restrictions.join(", ") || "Worldwide",
      descriptionHtml: optString(item.description) || optString(item.excerpt),
      url: optString(item.applicationLink) || optString(item.guid),
      tags: [
        ...toStringList(item.categories),
        ...toStringList(item.parentCategories),
        ...toStringList(item.seniority),
      ],
      employmentType: optString(item.employmentType) || null,
      postedAtEpochMillis: pubDate != null && pubDate > 0 ? pubDate * 1000 : null,
      isRemote: true,
      salaryMin: min,
      salaryMax: max ?? min,
      salaryCurrency: optString(item.currency) || null,
      salaryPeriod: periodOf(optString(item.salaryPeriod)) ?? (min != null ? SalaryPeriod.YEAR : null),
    });
  }
}

/**
 * The sources the browser can query directly, in the order the app queries them. Remotive,
 * Arbeitnow and Jobicy all send `Access-Control-Allow-Origin: *`, so a static SPA can read
 * them without a server. Himalayas is deliberately absent: its feed sends no CORS header, so
 * a browser fetch is blocked. `HimalayasProvider` is kept (and tested) for the optional
 * proxy stage described in docs/web-parity.md, where a server makes the request instead.
 */
export function defaultProviders(feed: JsonFeed = HttpJsonFeed): JobProvider[] {
  return [new RemotiveProvider(feed), new ArbeitnowProvider(feed), new JobicyProvider(feed)];
}

/** A one-line, plain-words description of a provider failure for the diagnostics strip. */
export function providerFailureLine(providerName: string, error: unknown): string {
  if (error instanceof JobProviderError) {
    const { label, explanation } = describeCategory(error.category);
    return `${providerName}: ${label.toLowerCase()} — ${explanation}`;
  }
  return `${providerName}: ${error instanceof Error ? error.message : "unknown failure"}`;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value != null && typeof value === "object" && !Array.isArray(value);
}

const BASE_REMOTIVE = "https://remotive.com/api/remote-jobs";
const BASE_ARBEITNOW = "https://www.arbeitnow.com/api/job-board-api";
const BASE_JOBICY = "https://jobicy.com/api/v2/remote-jobs";
const BASE_HIMALAYAS = "https://himalayas.app/jobs/api";
