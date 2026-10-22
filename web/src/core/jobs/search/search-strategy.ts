import { keywordPlan } from "./target-search";

/**
 * How one source is searched. `serverSideSearch` is true only when the source's API really
 * narrows the feed by the keyword, so the app can be honest that the other sources return a
 * broad sample that is filtered locally. Mirrors the Kotlin `SearchStrategy.kt`.
 */

/**
 * What a source's API actually supports, ported from the Kotlin `ProviderCapabilities`. These
 * are facts about the provider, established from its documentation and from what the feed does
 * with a parameter; they drive the search strategy, so a provider that cannot filter
 * server-side is fetched once and its results are filtered locally.
 */
export interface ProviderCapabilities {
  serverSideKeyword: boolean;
  countryFilter: boolean;
  pagination: boolean;
  employmentTypeFilter: boolean;
  applicationLinks: boolean;
  submissionEndpoint: boolean;
  realSubmissionSupported: boolean;
  /**
   * How many keyword sets this provider should be handed in one refresh. A provider whose feed
   * reads only the first keyword (and therefore re-runs its whole multi-site sweep for every set
   * it is given) declares a small budget so the keyword plan cannot multiply its request volume.
   * A provider that reads every keyword keeps the default and receives the whole plan. Mirrors
   * the Kotlin `ProviderCapabilities.keywordSweepBudget`.
   */
  keywordSweepBudget: number;
}

/** The default capabilities for a source that can only return a broad sample. */
export function defaultProviderCapabilities(overrides?: Partial<ProviderCapabilities>): ProviderCapabilities {
  return {
    serverSideKeyword: false,
    countryFilter: false,
    pagination: false,
    employmentTypeFilter: false,
    applicationLinks: true,
    submissionEndpoint: false,
    realSubmissionSupported: false,
    keywordSweepBudget: Number.MAX_SAFE_INTEGER,
    ...overrides,
  };
}

/** The subset of a provider the search strategy reads. Mirrors `JobProvider` + its capabilities. */
export interface SearchProvider {
  readonly id: string;
  readonly name: string;
  readonly capabilities: ProviderCapabilities;
}

/** One source's search plan. */
export interface ProviderSearchPlan {
  providerId: string;
  providerName: string;
  keywordSets: string[][];
  serverSideSearch: boolean;
}

/** True when the source is fetched once and filtered locally against the target roles. */
export function providerPlanIsBroadSample(plan: ProviderSearchPlan): boolean {
  return !plan.serverSideSearch;
}

/** One row of the coverage report: what a source did for this search. */
export interface SearchCoverageSource {
  providerId: string;
  providerName: string;
  targeted: boolean;
  /** Postings this source returned that matched a target role. */
  relevantCount: number;
  /** Postings this source returned in total. */
  totalCount: number;
}

/**
 * An honest account of the search that was just run: which sources can narrow by keyword,
 * which only return a broad sample, and how many of the postings actually matched a target
 * role. The UI reads this so it never presents a broad remote feed as a targeted search.
 */
export interface SearchCoverage {
  sources: SearchCoverageSource[];
  totalRelevant: number;
  totalRetrieved: number;
}

export function targetedSourceCount(coverage: SearchCoverage): number {
  return coverage.sources.filter((s) => s.targeted).length;
}

export function broadSampleSourceCount(coverage: SearchCoverage): number {
  return coverage.sources.filter((s) => !s.targeted).length;
}

/** True when at least one source could actually narrow the search by keyword. */
export function coverageHasTargetedSource(coverage: SearchCoverage): boolean {
  return targetedSourceCount(coverage) > 0;
}

/** True when at least one relevant posting was found. */
export function coverageHasRelevantResults(coverage: SearchCoverage): boolean {
  return coverage.totalRelevant > 0;
}

/** Share of retrieved postings that matched the candidate's trade, 0..100. */
export function coverageRelevantSharePercent(coverage: SearchCoverage): number {
  if (coverage.totalRetrieved === 0) return 0;
  return Math.trunc((100 * coverage.totalRelevant) / coverage.totalRetrieved);
}

/**
 * A short, plain statement of what this search was and how much of it landed. It says
 * "broad sample" rather than "search" when no source supports keyword narrowing, and it
 * always states how many of the retrieved postings actually matched the trade, so a large
 * feed of unrelated roles cannot read as a good result set.
 */
export function coverageSummary(coverage: SearchCoverage): string {
  const targeted = targetedSourceCount(coverage);
  const broad = broadSampleSourceCount(coverage);
  const { totalRelevant, totalRetrieved, sources } = coverage;
  if (totalRetrieved === 0) {
    return "No postings were retrieved.";
  }
  if (targeted === 0 && totalRelevant === 0) {
    return `Broad sample from ${sources.length} sources; none of the ${totalRetrieved} postings matched your trade.`;
  }
  if (targeted === 0) {
    return `Broad sample from ${sources.length} sources; ${totalRelevant} of ${totalRetrieved} postings matched your trade (${coverageRelevantSharePercent(coverage)}%).`;
  }
  if (broad === 0) {
    return `Targeted search across ${targeted} sources; ${totalRelevant} relevant.`;
  }
  return `Targeted search on ${targeted} source(s), broad sample from ${broad}; ${totalRelevant} of ${totalRetrieved} postings matched your trade.`;
}

/** The diagnostic fields the coverage report reads, mirroring the Kotlin `ProviderDiagnostic`. */
export interface CoverageDiagnostic {
  providerId: string;
  name: string;
  serverSideSearch: boolean;
  validJobCount: number;
}

/**
 * Turns the candidate's target roles and each provider's real capabilities into a concrete
 * fetch plan. A provider that can filter server-side is asked for the target roles; one that
 * cannot is fetched once and its results are filtered locally by the role classifier.
 */
export const SearchStrategy = {
  /** The keyword plan for a provider, or a single empty set when it cannot search. */
  planFor(provider: SearchProvider, profileTitles: string[]): ProviderSearchPlan {
    const serverSideSearch = provider.capabilities.serverSideKeyword;
    const keywordSets = serverSideSearch ? keywordPlan(profileTitles).map((k) => [k]) : [[]];
    return {
      providerId: provider.id,
      providerName: provider.name,
      keywordSets,
      serverSideSearch,
    };
  },

  /**
   * Builds the coverage report from the diagnostics of the last refresh plus, per source,
   * how many of the returned postings matched a target role.
   */
  coverageFrom(
    diagnostics: CoverageDiagnostic[],
    relevantByProvider: Map<string, number>,
    totalByProvider: Map<string, number>,
  ): SearchCoverage {
    const rows = diagnostics.map((diag) => ({
      providerId: diag.providerId,
      providerName: diag.name,
      targeted: diag.serverSideSearch,
      relevantCount: relevantByProvider.get(diag.providerId) ?? 0,
      totalCount: totalByProvider.get(diag.providerId) ?? diag.validJobCount,
    }));
    return {
      sources: rows,
      totalRelevant: rows.reduce((sum, r) => sum + r.relevantCount, 0),
      totalRetrieved: rows.reduce((sum, r) => sum + r.totalCount, 0),
    };
  },
};
