import type { CandidateProfile, Job } from "../models";
import { RoleFamily } from "../models";
import { JobMapper } from "../jobs/job-mapper";
import { RecordValidationEngine } from "../jobs/engine/record-validation-engine";
import { defaultProviders, providerFailureLine, type JobProvider } from "../jobs/provider/providers";
import type { RawJob } from "../jobs/provider/raw-job";
import { SearchStrategy, type SearchCoverage } from "../jobs/search/search-strategy";
import { classifyRole } from "../jobs/search/role-relevance";
import { HtmlText } from "../jobs/provider/text-parsing";

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

  constructor(options?: PipelineOptions) {
    this.providers = options?.providers ?? defaultProviders();
    this.clock = options?.clock ?? Date.now;
    this.mapper = options?.mapper ?? new JobMapper({ clock: this.clock });
    this.validation = options?.validation ?? new RecordValidationEngine();
    this.limit = options?.limitPerProvider ?? 60;
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
        // cannot is fetched once with no keywords and filtered locally afterwards. This mirrors
        // the Android `LiveJobRepository` fetch loop.
        const plan = SearchStrategy.planFor(provider, titles);
        try {
          const fetched: RawJob[] = [];
          for (const keywordSet of plan.keywordSets) {
            const batch = await provider.fetch(keywordSet, this.limit);
            fetched.push(...batch);
          }
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
        } catch (error) {
          reports.push({
            providerId: provider.id,
            providerName: provider.name,
            ok: false,
            fetched: 0,
            accepted: 0,
            message: providerFailureLine(provider.name, error),
            serverSideSearch: plan.serverSideSearch,
          });
        }
      }),
    );

    // The per-source lists were deduplicated in isolation; collapse cross-source duplicates
    // once more so the same posting syndicated to two boards appears a single time.
    const { kept } = this.validation.deduplicate(raws, now);
    const jobs = kept.map((raw) => this.mapper.toJob(raw, profile));

    // Rank by match score, then by how recent the posting is, so the freshest strong matches
    // lead the list. Ties keep a stable order.
    jobs.sort((a, b) => {
      if (b.matchScore !== a.matchScore) return b.matchScore - a.matchScore;
      return (b.postedAtEpochMillis ?? 0) - (a.postedAtEpochMillis ?? 0);
    });

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
