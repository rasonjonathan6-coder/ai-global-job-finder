import { RoleFamily, SalaryPeriod } from "../../models";
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
import { defaultProviderCapabilities, type ProviderCapabilities } from "../search/search-strategy";
import { classifyRole } from "../search/role-relevance";

/**
 * Translates one source's feed into `RawJob`s. Mirrors the Kotlin `JobProvider`: it only
 * translates, never scores or enriches, so a field the source did not send stays null.
 *
 * `capabilities` records what the source's API actually supports. It is a fact about the
 * provider, established from its documentation and from what the feed does with a parameter,
 * and it drives the search strategy: a provider that cannot filter server-side is fetched
 * once and its results are filtered locally, so the app never claims a targeted search it
 * did not run.
 */
export interface JobProvider {
  readonly id: string;
  readonly name: string;
  readonly homepage: string;
  readonly kind: ProviderKind;
  readonly documentationUrl: string;
  readonly capabilities: ProviderCapabilities;
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
  // Remotive documents a `search` parameter, but a live check on the production feed returns
  // the same newest-first list with or without it, so it is recorded as not effective rather
  // than claimed as a working filter.
  readonly capabilities = defaultProviderCapabilities();

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
 *
 * The board is paginated (`?page=N`) and its newest-first order is dominated by software and
 * office roles, so a single page rarely carries the industrial trades the candidate targets.
 * The provider therefore reads several pages and pools them: the app is fetching a broad
 * sample either way, and widening the sample is what lets the local relevance filter find the
 * maintenance roles that were sitting a few pages down. `maxPages` bounds the work.
 */
export class ArbeitnowProvider implements JobProvider {
  readonly id = "arbeitnow";
  readonly name = "Arbeitnow";
  readonly homepage = "https://www.arbeitnow.com";
  readonly kind = ProviderKind.AGGREGATOR;
  readonly documentationUrl = "https://www.arbeitnow.com/blog/arbeitnow-api";
  readonly capabilities = defaultProviderCapabilities({ pagination: true });

  constructor(
    private readonly feed: JsonFeed = HttpJsonFeed,
    private readonly maxPages = ARBEITNOW_MAX_PAGES,
  ) {}

  async fetch(_keywords: string[] = [], limit = 60): Promise<RawJob[]> {
    const collected: RawJob[] = [];
    let lastError: unknown = null;
    let anyAnswered = false;
    for (let page = 1; page <= this.maxPages; page++) {
      let body: Record<string, unknown>;
      try {
        body = await this.feed.objectAt(`${BASE_ARBEITNOW}?page=${page}`);
      } catch (cause) {
        // A page that fails after an earlier one succeeded is not a source failure: the
        // postings already read are real, so stop widening and return them rather than
        // discarding a good sample because a later page was slow or rate-limited.
        lastError = cause;
        break;
      }
      const data = requireArray(body, "data", this.id, "Arbeitnow");
      anyAnswered = true;
      if (data.length === 0) break;
      for (const item of data) if (isObject(item)) collected.push(this.toRawJob(item));
      if (collected.length >= limit) break;
    }
    if (!anyAnswered && lastError != null) {
      if (lastError instanceof JobProviderError) throw lastError;
      throw wrap(this.id, "Arbeitnow did not respond", lastError);
    }
    return collected.slice(0, limit);
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
  // Jobicy exposes `industry`/`tag`, but a live check shows they only loosely steer the feed
  // and never narrow it to the candidate's trade, so it is a broad sample, not a targeted
  // search.
  readonly capabilities = defaultProviderCapabilities();

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
  // Himalayas documents no keyword parameter on its public jobs endpoint; the feed is a
  // broad newest-first sample, so it is filtered locally.
  readonly capabilities = defaultProviderCapabilities();

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

/** One employer careers site hosted on SmartRecruiters, addressed by its company identifier. */
export interface SmartRecruitersBoard {
  /** SmartRecruiters company identifier, e.g. "SGS". */
  identifier: string;
  /** Employer name shown on the card. */
  label: string;
}

/**
 * Curated SmartRecruiters employers, each verified to answer the keyless public posting API.
 * The set is deliberately trade-led: inspection/testing and industrial-equipment employers
 * publish the maintenance, fitter, millwright and pump roles the candidate targets, and the
 * large ones reach Africa and the Gulf, not only North America and Europe. Mirrors the Kotlin
 * `SmartRecruitersBoards`.
 */
export const SMART_RECRUITERS_BOARDS: SmartRecruitersBoard[] = [
  { identifier: "SGS", label: "SGS" },
  { identifier: "Wabtec", label: "Wabtec" },
  { identifier: "Konecranes", label: "Konecranes" },
  { identifier: "BoschGroup", label: "Bosch" },
];

/**
 * SmartRecruiters careers sites of inspection, testing, industrial-service and equipment
 * employers — SGS, Wabtec, Konecranes and Bosch. Mirrors the Kotlin `SmartRecruitersProvider`.
 *
 * These are employer boards: the posting is the employer's own, so it is the most trustworthy
 * feed for expat industrial and mining trades and it applies directly with no middle party.
 * SmartRecruiters publishes a keyless public posting API that supports server-side keyword
 * search (`q=`) and, verified from the browser, sends `Access-Control-Allow-Origin: *` on both
 * the list and the detail endpoints — so unlike the broad remote boards, the web edition can
 * run a real targeted search that reaches the candidate's trades across Africa and the Gulf.
 *
 * The list row carries the title, employer and location but not the description, so a
 * bounded, concurrent set of per-posting detail calls reads the job text the matching and
 * eligibility engines need. Trade titles are read first (see `pickDetailIds`) so an employer's
 * maintenance vacancies are not crowded out by its latest internship.
 */
export class SmartRecruitersProvider implements JobProvider {
  readonly id = "smartrecruiters";
  readonly name = "Employer job boards";
  readonly homepage = "https://jobs.smartrecruiters.com";
  readonly kind = ProviderKind.EMPLOYER_BOARD;
  readonly documentationUrl = "https://developers.smartrecruiters.com/docs/job-postings-api";
  readonly capabilities = defaultProviderCapabilities({ serverSideKeyword: true, pagination: true });

  constructor(
    private readonly feed: JsonFeed = HttpJsonFeed,
    private readonly boards: SmartRecruitersBoard[] = SMART_RECRUITERS_BOARDS,
  ) {}

  async fetch(keywords: string[] = [], limit = 60): Promise<RawJob[]> {
    const keyword = keywords.find((k) => k.trim().length > 0)?.trim() || SMART_DEFAULT_KEYWORD;
    const collected = new Map<string, RawJob>();
    let lastError: unknown = null;
    let anyAnswered = false;

    for (const board of this.boards) {
      let rows: unknown[];
      try {
        const body = await this.feed.objectAt(
          `https://api.smartrecruiters.com/v1/companies/${board.identifier}/postings` +
            `?q=${encodeURIComponent(keyword)}&limit=${SMART_PAGE_LIMIT}`,
        );
        rows = requireArray(body, "content", this.id, "Employer job boards");
        anyAnswered = true;
      } catch (cause) {
        lastError = cause;
        continue;
      }
      for (const row of await this.detailRows(board, rows)) {
        if (!collected.has(row.externalId)) collected.set(row.externalId, row);
      }
      if (collected.size >= limit) break;
    }

    if (!anyAnswered && lastError != null) {
      if (lastError instanceof JobProviderError) throw lastError;
      throw wrap(this.id, "The employer job boards did not respond", lastError);
    }
    return [...collected.values()].slice(0, limit);
  }

  /**
   * Chooses which list rows get the bounded, expensive per-posting detail read. Trade titles
   * are read first, using the same `classifyRole` the rest of the pipeline uses to define
   * relevance, and the remaining rows fill the rest of the budget. This changes only the
   * order; the same number of requests are made and no posting is invented. When no title
   * reads as a trade role the result is the source's own order, unchanged.
   */
  private pickDetailIds(rows: unknown[]): string[] {
    const titled = rows
      .filter(isObject)
      .map((row) => ({ id: optString(row.id), title: optString(row.name) }))
      .filter((r) => r.id.length > 0);
    const relevant = titled.filter((r) => isTradeTitle(r.title));
    const rest = titled.filter((r) => !isTradeTitle(r.title));
    return [...relevant, ...rest].slice(0, SMART_DETAILS_PER_BOARD).map((r) => r.id);
  }

  /**
   * Fetches the detail rows concurrently, skipping any that fail so one bad row cannot drop
   * the rest of the employer's postings. This is the concurrent read the Kotlin `detailsFor`
   * performs with `async`/`awaitAll`.
   */
  private async detailRows(board: SmartRecruitersBoard, rows: unknown[]): Promise<RawJob[]> {
    const ids = this.pickDetailIds(rows);
    const settled = await Promise.allSettled(
      ids.map(async (id) => {
        const detail = await this.feed.objectAt(
          `https://api.smartrecruiters.com/v1/companies/${board.identifier}/postings/${id}`,
        );
        return this.toRawJob(board, detail);
      }),
    );
    return settled
      .filter((result): result is PromiseFulfilledResult<RawJob | null> => result.status === "fulfilled")
      .map((result) => result.value)
      .filter((job): job is RawJob => job != null);
  }

  private toRawJob(board: SmartRecruitersBoard, detail: Record<string, unknown>): RawJob | null {
    if (detail.active === false) return null;
    const title = optString(detail.name).trim();
    if (title.length === 0) return null;
    const description = this.descriptionText(detail);
    if (description.trim().length === 0) return null;
    const location = this.locationText(detail);
    if (location.trim().length === 0) return null;

    const postingId = optString(detail.id);
    const url = optString(detail.postingUrl) || `https://api.smartrecruiters.com/v1/companies/${board.identifier}/postings/${postingId}`;
    const employmentType = optString((detail.typeOfEmployment as Record<string, unknown>)?.label) || null;
    const company = optString((detail.company as Record<string, unknown>)?.name) || board.label;
    const releasedDate = optString(detail.releasedDate);
    const locationObj = (detail.location as Record<string, unknown>) ?? {};
    return rawJob({
      providerId: this.id,
      providerName: this.name,
      externalId: `${board.identifier}-${postingId}`,
      title,
      company,
      location,
      descriptionHtml: description,
      url,
      employmentType,
      postedAtEpochMillis: isoEpochMillis(releasedDate),
      isRemote: optBool(locationObj.remote, false),
    });
  }

  /** Joins the four description sections SmartRecruiters publishes, dropping empty ones. */
  private descriptionText(detail: Record<string, unknown>): string {
    const sections = ((detail.jobAd as Record<string, unknown>)?.sections as Record<string, unknown>) ?? null;
    if (!sections) return "";
    return SMART_SECTION_KEYS.map((key) => optString((sections[key] as Record<string, unknown>)?.text))
      .filter((text) => text.trim().length > 0)
      .join("\n");
  }

  /** "City, Region, Country" from the structured location, or the published free text. */
  private locationText(detail: Record<string, unknown>): string {
    const location = detail.location as Record<string, unknown> | undefined;
    if (!location) return "";
    const full = optString(location.fullLocation).trim();
    if (full.length > 0) return full;
    return ["city", "region", "country"]
      .map((key) => optString(location[key]).trim())
      .filter((part) => part.length > 0)
      .filter((part, index, all) => all.findIndex((other) => other.toLowerCase() === part.toLowerCase()) === index)
      .join(", ");
  }
}

/**
 * The sources the browser can query directly, in the order the app queries them. Remotive,
 * Arbeitnow, Jobicy and the SmartRecruiters employer boards all send
 * `Access-Control-Allow-Origin: *`, so a static SPA can read them without a server. Himalayas
 * is deliberately absent: its feed sends no CORS header, so a browser fetch is blocked.
 * `HimalayasProvider` is kept (and tested) for the optional proxy stage described in
 * docs/web-parity.md, where a server makes the request instead.
 */
export function defaultProviders(feed: JsonFeed = HttpJsonFeed): JobProvider[] {
  return [
    new RemotiveProvider(feed),
    new ArbeitnowProvider(feed),
    new JobicyProvider(feed),
    new SmartRecruitersProvider(feed),
  ];
}

/** True when a posting title reads as the candidate's trade, using the shared role verdict. */
function isTradeTitle(title: string): boolean {
  const family = classifyRole(title).family;
  return family === RoleFamily.TARGET || family === RoleFamily.MAINTENANCE_FAMILY;
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

/** How many pages of the broad Arbeitnow sample to pool before the local relevance filter runs. */
const ARBEITNOW_MAX_PAGES = 8;

/** SmartRecruiters: the public posting API caps a page at 100 and the detail budget is bounded. */
const SMART_PAGE_LIMIT = 100;
const SMART_DETAILS_PER_BOARD = 4;
const SMART_DEFAULT_KEYWORD = "maintenance";
const SMART_SECTION_KEYS = ["jobDescription", "qualifications", "additionalInformation", "companyDescription"];
