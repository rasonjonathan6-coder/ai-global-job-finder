import type { CandidateProfile, Job } from "../models";
import { RoleFamily } from "../models";
import { JobMapper } from "../jobs/job-mapper";
import { RecordValidationEngine } from "../jobs/engine/record-validation-engine";
import { defaultProviders, providerFailureLine, type JobProvider } from "../jobs/provider/providers";
import type { RawJob } from "../jobs/provider/raw-job";
import { SearchStrategy, type SearchCoverage } from "../jobs/search/search-strategy";
import { classifyRole } from "../jobs/search/role-relevance";
import { relevantJobs } from "../jobs/search/relevance-feed";
import { HtmlText } from "../jobs/provider/text-parsing";

/**
 * Resolves with `promise`, or rejects with `new Error(message)` if it has not settled within
 * `timeoutMillis`. A non-positive timeout rejects immediately, so a spent provider budget stops
 * the next keyword fetch rather than starting one with no time left. Mirrors the Kotlin
 * `withTimeoutOrNull` + `ProviderTimeoutException` pair in `LiveJobRepository`.
 */
function withDeadline<T>(promise: Promise<T>, timeoutMillis: number, message: string): Promise<T> {
  if (timeoutMillis <= 0) return Promise.reject(new Error(message));
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(message)), timeoutMillis);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error) => {
        clearTimeout(timer);
        reject(error);
      },
    );
  });
}

/** One source's outcome for the diagnostics strip, in plain words. */
export interface ProviderReport {
  providerId: string;
  providerName: string;
  ok: boolean;
  fetched: number;
  accepted: number;
  message: string | null;
  /** True when this source's API narrowed the feed by keyword on this attempt. */
  serverSideSearch: boolean;
}

export interface PipelineResult {
  jobs: Job[];
  reports: ProviderReport[];
  /** True when every source failed, so the UI can distinguish "no matches" from "no network". */
  allFailed: boolean;
  /** An honest account of the search: which sources narrowed by keyword, and how much landed. */
  coverage: SearchCoverage;
}

export interface PipelineOptions {
  providers?: JobProvider[];
  mapper?: JobMapper;
  validation?: RecordValidationEngine;
  clock?: () => number;
  limitPerProvider?: number;
  /**
   * Hard ceiling on a source across all of its keyword fetches in one refresh, in ms. The
   * per-request bound alone lets a keyword-capable source pay its budget once per target role,
   * so a six-role plan could hold the whole refresh for minutes even though every provider runs
   * concurrently. Mirrors the Kotlin `LiveJobRepository.perProviderTotalTimeoutMillis`.
   */
  perProviderTotalTimeoutMillis?: number;
}

/**
 * The read pipeline: query every key-free source, drop unusable records and duplicates, then
 * map what survives into scored `Job`s. Mirrors the Android `LiveJobRepository` refresh path
 * so both editions apply the same validation, deduplication and scoring.
 *
 * Each source is queried independently: one board being down never hides the postings the
 * others returned. A source's failure is reported rather than thrown.
 */
export class JobPipeline {
  private readonly providers: JobProvider[];
  private readonly mapper: JobMapper;
  private readonly validation: RecordValidationEngine;
  private readonly clock: () => number;
  private readonly limit: number;
  private readonly perProviderTotalTimeoutMillis: number;

  constructor(options?: PipelineOptions) {
    this.providers = options?.providers ?? defaultProviders();
    this.clock = options?.clock ?? Date.now;
    this.mapper = options?.mapper ?? new JobMapper({ clock: this.clock });
    this.validation = options?.validation ?? new RecordValidationEngine();
    this.limit = options?.limitPerProvider ?? 60;
    this.perProviderTotalTimeoutMillis = options?.perProviderTotalTimeoutMillis ?? 60_000;
  }

  /** Wall-clock now, so a fake clock can drive the provider budget deterministically. */
  private now(): number {
    return this.clock();
  }

  /** Milliseconds left on a provider's budget, floored at zero. */
  private remainingBudget(deadline: number): number {
    return Math.max(0, deadline - this.now());
  }

  /**
   * Normalised EUR/year salary for a mapped job, or null when the source did not publish a
   * trustworthy figure. Exposed so the shortlist engine can weigh documented pay with the
   * exact same guards (uncertain unit, inferred period) the mapper applied at map time.
   */
  eurPerYear(job: Job): number | null {
    return this.mapper.eurPerYear(job);
  }

  async refresh(profile: CandidateProfile): Promise<PipelineResult> {
    const now = this.clock();
    const titles = [profile.currentTitle, ...profile.previousTitles, ...profile.preferredRoles].filter(
      (t) => t.trim().length > 0,
    );

    const reports: ProviderReport[] = [];
    const raws: RawJob[] = [];

    await Promise.all(
      this.providers.map(async (provider) => {
        // The plan is derived from the provider's real capabilities: a source that can filter
        // server-side is asked for each target role in turn and the results pooled; one that
        // cannot is fetched once with no keywords and filtered locally afterwards. A provider
        // whose feed reads only the first keyword and re-walks its whole site list on every call
        // declares a small sweep budget, so the plan cannot multiply its request volume. This
        // mirrors the Android `LiveJobRepository` fetch loop.
        const plan = SearchStrategy.planFor(provider, titles);
        const keywordSets = plan.keywordSets.slice(0, provider.capabilities.keywordSweepBudget);
        const deadline = this.now() + this.perProviderTotalTimeoutMillis;
        const fetched: RawJob[] = [];
        let failure: unknown = null;
        for (const keywordSet of keywordSets) {
          try {
            const batch = await withDeadline(
              provider.fetch(keywordSet, this.limit),
              this.remainingBudget(deadline),
              `The source ${provider.name} did not answer in time`,
            );
            fetched.push(...batch);
          } catch (error) {
            failure = failure ?? error;
            // The provider-wide budget is spent; stop asking it for more keyword sets. The rows
            // already returned are kept, exactly as the Android repository keeps them.
            if (this.remainingBudget(deadline) <= 0) break;
          }
        }
        if (fetched.length > 0 || failure === null) {
          const { kept } = this.validation.deduplicate(fetched, now);
          raws.push(...kept);
          reports.push({
            providerId: provider.id,
            providerName: provider.name,
            ok: true,
            fetched: fetched.length,
            accepted: kept.length,
            message: null,
            serverSideSearch: plan.serverSideSearch,
          });
        } else {
          reports.push({
            providerId: provider.id,
            providerName: provider.name,
            ok: false,
            fetched: 0,
            accepted: 0,
            message: providerFailureLine(provider.name, failure),
            serverSideSearch: plan.serverSideSearch,
          });
        }
      }),
    );

    // The per-source lists were deduplicated in isolation; collapse cross-source duplicates
    // once more so the same posting syndicated to two boards appears a single time.
    const { kept } = this.validation.deduplicate(raws, now);
    const scored = kept.map((raw) => this.mapper.toJob(raw, profile));

    // Rank by match score, then by how recent the posting is, so the freshest strong matches
    // lead the list. Ties keep a stable order.
    scored.sort((a, b) => {
      if (b.matchScore !== a.matchScore) return b.matchScore - a.matchScore;
      return (b.postedAtEpochMillis ?? 0) - (a.postedAtEpochMillis ?? 0);
    });

    // Stage 70: the key-free remote boards return a broad, newest-first global sample, so the
    // ranked feed is narrowed to the postings whose role is actually the candidate's trade
    // before it reaches any screen. The coverage report below still counts the whole sample,
    // so the app can state how many unrelated postings were set aside rather than hiding the
    // breadth of what the sources returned. Mirrors the Android `LiveJobRepository.jobs` flow.
    const jobs = relevantJobs(scored);

    reports.sort((a, b) => a.providerName.localeCompare(b.providerName));

    // Count how many of the postings that actually arrived matched a target role, per source.
    // This is what lets the coverage report describe the search honestly: a source that
    // returned forty software jobs and no maintenance role is recorded as
    // retrieved-but-not-relevant, not hidden.
    const relevantByProvider = new Map<string, number>();
    const totalByProvider = new Map<string, number>();
    for (const raw of kept) {
      totalByProvider.set(raw.providerId, (totalByProvider.get(raw.providerId) ?? 0) + 1);
      const relevance = classifyRole(raw.title, HtmlText.toPlainText(raw.descriptionHtml).slice(0, 400));
      if (relevance.family === RoleFamily.TARGET || relevance.family === RoleFamily.MAINTENANCE_FAMILY) {
        relevantByProvider.set(raw.providerId, (relevantByProvider.get(raw.providerId) ?? 0) + 1);
      }
    }
    const coverage = SearchStrategy.coverageFrom(
      reports.map((r) => ({
        providerId: r.providerId,
        name: r.providerName,
        serverSideSearch: r.serverSideSearch,
        validJobCount: r.accepted,
      })),
      relevantByProvider,
      totalByProvider,
    );

    return { jobs, reports, allFailed: reports.length > 0 && reports.every((r) => !r.ok), coverage };
  }
}
