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
  readonly capabilities = defaultProviderCapabilities({
    serverSideKeyword: true,
    pagination: true,
    // The feed reads only the first keyword but each call sweeps every configured country for
    // every board, so a six-role plan would multiply that country sweep by six. Two sets add a
    // second angle at bounded cost without re-sweeping six times. Mirrors the Kotlin provider.
    keywordSweepBudget: 2,
  });

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

/** One employer careers site hosted on Greenhouse, addressed by its board token. */
export interface GreenhouseBoard {
  /** Greenhouse board token, e.g. "redwoodmaterials". */
  token: string;
  /** Employer name shown on the card. */
  label: string;
}

/** Curated Greenhouse boards of industrial and energy employers, verified to answer. */
export const GREENHOUSE_BOARDS: GreenhouseBoard[] = [
  { token: "redwoodmaterials", label: "Redwood Materials" },
  { token: "lucidmotors", label: "Lucid Motors" },
  { token: "koboldmetals", label: "KoBold Metals" },
];

/**
 * Greenhouse careers sites of industrial and energy employers. Mirrors the Kotlin
 * `GreenhouseProvider`. The public board API has no server-side keyword search: the list is
 * fetched and filtered locally. The list row omits the description, so a bounded, concurrent
 * set of per-posting detail calls reads the country and the job text the matcher needs; trade
 * titles are read first so an employer's maintenance vacancies are not crowded out.
 */
export class GreenhouseProvider implements JobProvider {
  readonly id = "greenhouse";
  readonly name = "Industrial career sites";
  readonly homepage = "https://boards.greenhouse.io";
  readonly kind = ProviderKind.EMPLOYER_BOARD;
  readonly documentationUrl = "https://developers.greenhouse.io/job-board.html";
  readonly capabilities = defaultProviderCapabilities();

  constructor(
    private readonly feed: JsonFeed = HttpJsonFeed,
    private readonly boards: GreenhouseBoard[] = GREENHOUSE_BOARDS,
  ) {}

  async fetch(_keywords: string[] = [], limit = 60): Promise<RawJob[]> {
    const collected = new Map<string, RawJob>();
    let lastError: unknown = null;
    let anyAnswered = false;

    for (const board of this.boards) {
      let rows: unknown[];
      try {
        const body = await this.feed.objectAt(
          `https://boards-api.greenhouse.io/v1/boards/${board.token}/jobs`,
        );
        rows = requireArray(body, "jobs", this.id, "Industrial career sites");
        anyAnswered = true;
      } catch (cause) {
        lastError = cause;
        continue;
      }
      for (const job of await this.detailsFor(board, this.pickDetailIds(rows))) {
        if (!collected.has(job.externalId)) collected.set(job.externalId, job);
      }
      if (collected.size >= limit) break;
    }

    if (!anyAnswered && lastError != null) {
      if (lastError instanceof JobProviderError) throw lastError;
      throw wrap(this.id, "The industrial career sites did not respond", lastError);
    }
    return [...collected.values()].slice(0, limit);
  }

  /** Trade titles first, then the newest remaining rows, inside the bounded detail budget. */
  private pickDetailIds(rows: unknown[]): string[] {
    const titled = rows
      .filter(isObject)
      .map((row) => ({ id: optString(row.id), title: optString(row.title) }))
      .filter((r) => r.id.length > 0);
    const relevant = titled.filter((r) => isTradeTitle(r.title));
    const rest = titled.filter((r) => !isTradeTitle(r.title));
    return [...relevant, ...rest].slice(0, GREENHOUSE_DETAILS_PER_BOARD).map((r) => r.id);
  }

  private async detailsFor(board: GreenhouseBoard, ids: string[]): Promise<RawJob[]> {
    const settled = await Promise.allSettled(
      ids.map(async (id) => {
        const info = await this.feed.objectAt(
          `https://boards-api.greenhouse.io/v1/boards/${board.token}/jobs/${id}`,
        );
        return this.toRawJob(board, info);
      }),
    );
    return settled
      .filter((result): result is PromiseFulfilledResult<RawJob | null> => result.status === "fulfilled")
      .map((result) => result.value)
      .filter((job): job is RawJob => job != null);
  }

  private toRawJob(board: GreenhouseBoard, info: Record<string, unknown>): RawJob | null {
    const title = optString(info.title).trim();
    if (title.length === 0) return null;
    const description = optString(info.content);
    if (description.trim().length === 0) return null;
    const location = optString((info.location as Record<string, unknown>)?.name).trim();
    if (location.length === 0) return null;
    const company = optString(info.company_name).trim() || board.label;
    const posted = isoEpochMillis(optString(info.first_published)) ?? isoEpochMillis(optString(info.updated_at));
    return rawJob({
      providerId: this.id,
      providerName: this.name,
      externalId: `${board.token}-${optString(info.id)}`,
      title,
      company,
      location,
      descriptionHtml: description,
      url: optString(info.absolute_url),
      postedAtEpochMillis: posted,
      isRemote: /remote/i.test(location),
    });
  }
}

/** One employer careers site hosted on Lever, addressed by its board token. */
export interface LeverBoard {
  /** Lever postings token, e.g. "shieldai". */
  token: string;
  /** Employer name shown on the card. */
  label: string;
}

/** Curated Lever boards of large industrial and defence-tech employers. */
export const LEVER_BOARDS: LeverBoard[] = [
  { token: "shieldai", label: "Shield AI" },
  { token: "sila", label: "Sila" },
];

/**
 * Lever careers sites of large industrial and defence-tech employers. Mirrors the Kotlin
 * `LeverProvider`. The endpoint returns the full board inline with no server-side search, and
 * a board can be very large, so the feed is read a bounded number of pages and each page is
 * filtered locally against the candidate's target titles.
 */
export class LeverProvider implements JobProvider {
  readonly id = "lever";
  readonly name = "Industrial employer boards";
  readonly homepage = "https://www.lever.co";
  readonly kind = ProviderKind.EMPLOYER_BOARD;
  readonly documentationUrl = "https://github.com/lever/postings-api";
  readonly capabilities = defaultProviderCapabilities({ pagination: true });

  constructor(
    private readonly feed: JsonFeed = HttpJsonFeed,
    private readonly boards: LeverBoard[] = LEVER_BOARDS,
  ) {}

  async fetch(keywords: string[] = [], limit = 60): Promise<RawJob[]> {
    const plan = [
      ...new Set(
        (keywords.length > 0 ? keywords : LEVER_DEFAULT_KEYWORDS)
          .map((k) => k.trim().toLowerCase())
          .filter((k) => k.length > 0),
      ),
    ];

    const collected = new Map<string, RawJob>();
    let lastError: unknown = null;
    let anyAnswered = false;

    for (const board of this.boards) {
      let rows: Record<string, unknown>[];
      try {
        rows = await this.list(board);
        anyAnswered = true;
      } catch (cause) {
        lastError = cause;
        continue;
      }
      for (const item of rows) {
        const job = this.toRawJob(board, item, plan);
        if (job && !collected.has(job.externalId)) collected.set(job.externalId, job);
      }
      if (collected.size >= limit) break;
    }

    if (!anyAnswered && lastError != null) {
      if (lastError instanceof JobProviderError) throw lastError;
      throw wrap(this.id, "The employer boards did not respond", lastError);
    }
    return [...collected.values()].slice(0, limit);
  }

  private async list(board: LeverBoard): Promise<Record<string, unknown>[]> {
    const jobs: Record<string, unknown>[] = [];
    for (let page = 0; page < LEVER_MAX_PAGES; page++) {
      const array = await this.feed.arrayAt(
        `https://api.lever.co/v0/postings/${board.token}?mode=json&limit=${LEVER_PAGE_SIZE}&skip=${page * LEVER_PAGE_SIZE}`,
      );
      if (array.length === 0) break;
      jobs.push(...array.filter(isObject));
      if (array.length < LEVER_PAGE_SIZE) break;
    }
    return jobs;
  }

  private toRawJob(board: LeverBoard, item: Record<string, unknown>, plan: string[]): RawJob | null {
    // Unlisted postings are internal/closed and must never surface.
    if ("isListed" in item && !optBool(item.isListed, true)) return null;

    const title = optString(item.text).trim();
    if (title.length === 0) return null;

    // Lever has no server-side search, so the board is filtered locally to target titles.
    if (plan.length > 0 && !plan.some((k) => title.toLowerCase().includes(k))) return null;

    const description = optString(item.descriptionPlain) || optString(item.description);
    if (description.trim().length === 0) return null;

    const categories = (item.categories as Record<string, unknown>) ?? {};
    const location = this.locationText(categories);
    if (location.trim().length === 0) return null;

    const workplace = optString(item.workplaceType);
    const remote =
      optBool(item.isRemote, false) ||
      workplace.toLowerCase() === "remote" ||
      /remote/i.test(location);

    const commitment = optString(categories.commitment) || null;
    const tags = [optString(categories.department), optString(categories.team)].filter((t) => t.length > 0);
    const createdAt = optInt(item.createdAt);

    return rawJob({
      providerId: this.id,
      providerName: this.name,
      externalId: `${board.token}-${optString(item.id)}`,
      title,
      company: board.label,
      location,
      descriptionHtml: description,
      url: optString(item.hostedUrl) || optString(item.applyUrl),
      tags,
      employmentType: commitment,
      postedAtEpochMillis: createdAt != null && createdAt > 0 ? createdAt : null,
      isRemote: remote,
    });
  }

  /** Joins the primary location with every secondary location into one label. */
  private locationText(categories: Record<string, unknown>): string {
    const primary = optString(categories.location).trim();
    const all = toStringList(categories.allLocations);
    return [...new Set([primary, ...all].filter((part) => part.length > 0))].join("; ");
  }
}

/** One employer careers site hosted on Ashby, addressed by its board name. */
export interface AshbyBoard {
  /** Ashby job-board name, e.g. "formenergy". */
  board: string;
  /** Employer name shown on the card. */
  label: string;
}

/** Curated Ashby boards of industrial and advanced-manufacturing employers. */
export const ASHBY_BOARDS: AshbyBoard[] = [
  { board: "formenergy", label: "Form Energy" },
  { board: "twelve", label: "Twelve" },
];

/**
 * Ashby careers sites of industrial and energy employers. Mirrors the Kotlin `AshbyProvider`.
 * The public posting API returns the full description and every location inline, so a single
 * request per employer is enough — no per-posting detail call is needed.
 */
export class AshbyProvider implements JobProvider {
  readonly id = "ashby";
  readonly name = "Advanced manufacturing sites";
  readonly homepage = "https://www.ashbyhq.com";
  readonly kind = ProviderKind.EMPLOYER_BOARD;
  readonly documentationUrl = "https://developers.ashbyhq.com/docs/public-job-posting-api";
  readonly capabilities = defaultProviderCapabilities();

  constructor(
    private readonly feed: JsonFeed = HttpJsonFeed,
    private readonly boards: AshbyBoard[] = ASHBY_BOARDS,
  ) {}

  async fetch(_keywords: string[] = [], limit = 60): Promise<RawJob[]> {
    const collected = new Map<string, RawJob>();
    let lastError: unknown = null;
    let anyAnswered = false;

    for (const board of this.boards) {
      let rows: unknown[];
      try {
        const body = await this.feed.objectAt(
          `https://api.ashbyhq.com/posting-api/job-board/${board.board}`,
        );
        rows = requireArray(body, "jobs", this.id, "The manufacturing career sites");
        anyAnswered = true;
      } catch (cause) {
        lastError = cause;
        continue;
      }
      for (const item of rows.filter(isObject)) {
        const job = this.toRawJob(board, item);
        if (job && !collected.has(job.externalId)) collected.set(job.externalId, job);
      }
      if (collected.size >= limit) break;
    }

    if (!anyAnswered && lastError != null) {
      if (lastError instanceof JobProviderError) throw lastError;
      throw wrap(this.id, "The manufacturing career sites did not respond", lastError);
    }
    return [...collected.values()].slice(0, limit);
  }

  private toRawJob(board: AshbyBoard, item: Record<string, unknown>): RawJob | null {
    // "Don't see the job you're looking for?" placeholders are not real postings.
    if (!optBool(item.isListed, true)) return null;

    const title = optString(item.title).trim();
    if (title.length === 0) return null;

    const description = optString(item.descriptionPlain) || optString(item.descriptionHtml);
    if (description.trim().length === 0) return null;

    const location = this.locationText(item);
    if (location.trim().length === 0) return null;

    const employmentType = optString(item.employmentType) || null;
    const remote =
      optBool(item.isRemote, false) || optString(item.workplaceType).toLowerCase() === "remote";

    const department = optString(item.department);
    const team = optString(item.team);
    const tags = [
      department.length > 0 && department !== "No Department" ? department : null,
      team.length > 0 ? team : null,
    ].filter((tag): tag is string => tag != null);

    return rawJob({
      providerId: this.id,
      providerName: this.name,
      externalId: `${board.board}-${optString(item.id)}`,
      title,
      company: board.label,
      location,
      descriptionHtml: description,
      url: optString(item.jobUrl) || optString(item.applyUrl),
      tags,
      employmentType,
      postedAtEpochMillis: isoEpochMillis(optString(item.publishedAt)),
      isRemote: remote,
    });
  }

  /** Joins the primary location with any secondary locations into one label. */
  private locationText(item: Record<string, unknown>): string {
    const primary = optString(item.location).trim();
    const secondary = Array.isArray(item.secondaryLocations) ? item.secondaryLocations : [];
    const extras = secondary
      .filter(isObject)
      .map((entry) => optString(entry.location).trim())
      .filter((loc) => loc.length > 0);
    return [...new Set([primary, ...extras].filter((part) => part.length > 0))].join("; ");
  }
}

/**
 * The sources the browser can query directly, in the order the app queries them. Remotive,
 * Arbeitnow, Jobicy, the Greenhouse/Lever/Ashby employer boards and the SmartRecruiters boards
 * all send `Access-Control-Allow-Origin: *`, so a static SPA can read them without a server.
 * Himalayas, Eures, Workday, Adzuna and Jooble are deliberately absent: Himalayas/Workday/Eures
 * send no CORS header (or are unreachable) and Adzuna/Jooble need a key that must not ship.
 * Those providers are kept (and tested) for the optional proxy stage in docs/web-parity.md.
 */
export function defaultProviders(feed: JsonFeed = HttpJsonFeed): JobProvider[] {
  return [
    new RemotiveProvider(feed),
    new ArbeitnowProvider(feed),
    new JobicyProvider(feed),
    new GreenhouseProvider(feed),
    new AshbyProvider(feed),
    new LeverProvider(feed),
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

/** Greenhouse omits the description from the list, so a bounded per-board detail read is needed. */
const GREENHOUSE_DETAILS_PER_BOARD = 6;

/** Lever pages the board; 100 is its default page size and keeps each request small. */
const LEVER_PAGE_SIZE = 100;
const LEVER_MAX_PAGES = 3;
/** Target roles, used to keep only the board rows worth showing when no keywords are given. */
const LEVER_DEFAULT_KEYWORDS = [
  "maintenance", "mechanic", "mechanical", "fitter", "millwright",
  "technician", "operator", "electrician", "welder",
];
