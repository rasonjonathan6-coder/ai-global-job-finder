import {
  applicationReadinessIsActionable,
  jobApplyableFromMadagascar,
  recurringProfileGaps,
  shortlistHasOfficialRoute,
  type CountryOpportunity,
  type ExpatIntelligence,
  type ShortlistState,
} from "../models";

/**
 * Rolls the per-posting shortlist up into a whole-feed digest: which countries actually
 * have routes in for an applicant in Madagascar, which profile gaps keep recurring, and
 * which few postings are worth acting on first. Mirrors the Kotlin `ExpatIntelligenceEngine`.
 *
 * Like the rest of the pipeline, it only counts what it can see. If the feed holds no
 * postings open to Madagascar, the summary says so rather than implying opportunity.
 */

const DEFAULT_COUNTRY_LIMIT = 8;
const TOP_LIMIT = 3;
const GAP_LIMIT = 3;

export interface ExpatIntelligenceEngineOptions {
  clock?: () => number;
}

export class ExpatIntelligenceEngine {
  private readonly clock: () => number;

  constructor(options?: ExpatIntelligenceEngineOptions) {
    this.clock = options?.clock ?? Date.now;
  }

  build(shortlist: ShortlistState, limit: number = DEFAULT_COUNTRY_LIMIT): ExpatIntelligence {
    const entries = shortlist.entries;
    const actionable = entries.filter((entry) => jobApplyableFromMadagascar(entry.job));

    const countries = [...groupBy(actionable, (entry) => entry.job.country).entries()]
      .map(([country, group]) => {
        const totalCount = entries.filter((entry) => entry.job.country === country).length;
        const countryOpportunity: CountryOpportunity = {
          country,
          countryFlag: group[0].job.countryFlag,
          actionableCount: group.length,
          totalCount,
          bestReadiness: Math.max(...group.map((entry) => entry.readinessScore)),
          hasOfficialRoute: group.some((entry) => shortlistHasOfficialRoute(entry)),
        };
        return countryOpportunity;
      })
      .sort(
        (a, b) =>
          b.bestReadiness - a.bestReadiness ||
          b.actionableCount - a.actionableCount ||
          compareNatural(a.country, b.country),
      )
      .slice(0, limit);

    const topOpportunities = [...entries].sort((a, b) => b.readinessScore - a.readinessScore).slice(0, TOP_LIMIT);

    return {
      totalJobs: shortlist.totalJobs > 0 ? shortlist.totalJobs : entries.length,
      actionableCount: actionable.length,
      priorityCount: entries.filter((entry) => applicationReadinessIsActionable(entry.readiness)).length,
      officialRouteCount: entries.filter((entry) => shortlistHasOfficialRoute(entry)).length,
      countries,
      recurringProfileGaps: recurringProfileGaps(entries).slice(0, GAP_LIMIT),
      topOpportunities,
      summary: this.summaryFor(actionable.length, countries, shortlist),
      generatedAtEpochMillis: this.clock(),
    };
  }

  private summaryFor(actionableCount: number, countries: CountryOpportunity[], shortlist: ShortlistState): string {
    if (shortlist.entries.length === 0) {
      return "No postings are loaded yet. Refresh the feed to see where you can apply from Madagascar.";
    }
    if (actionableCount === 0) {
      return (
        `None of the ${shortlist.entries.length} postings seen so far states a route in from Madagascar. ` +
        "That is a gap in the listings, not a verdict on you — refresh for new ones."
      );
    }
    if (countries.length === 0) {
      return `${actionableCount} postings look open to you right now.`;
    }
    const top = countries[0];
    const official = countries.filter((c) => c.hasOfficialRoute).length;
    let summary =
      `${actionableCount} postings look open to you from Madagascar, across ` +
      `${countries.length} ${countries.length === 1 ? "country" : "countries"}. ` +
      `${top.countryFlag} ${top.country} leads with a best readiness of ${top.bestReadiness}%.`;
    if (official > 0) {
      summary += ` ${official} ${official === 1 ? "country states" : "countries state"} an official route in.`;
    }
    return summary;
  }
}

function groupBy<T, K>(items: T[], key: (item: T) => K): Map<K, T[]> {
  const map = new Map<K, T[]>();
  for (const item of items) {
    const k = key(item);
    const list = map.get(k) ?? [];
    list.push(item);
    map.set(k, list);
  }
  return map;
}

/**
 * Kotlin's `String.compareTo` orders by UTF-16 code unit. `localeCompare` does not — it
 * applies locale collation, which reorders accented country names and would make the port
 * disagree with the engine it mirrors. Compare code units directly.
 */
function compareNatural(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}
