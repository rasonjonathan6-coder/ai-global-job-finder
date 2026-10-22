import { useEffect, useMemo, useState } from "react";
import { MadagascarEligibility, WorkMode, type Job } from "../../core/models";
import { EmptyState } from "../components/EmptyState";
import { JobCard } from "../components/JobCard";
import { ScreenHeader } from "../components/ScreenHeader";
import { JobListSkeleton } from "../components/Skeleton";
import { useAppStore } from "../../state/app-store";
import { isStrongMatch } from "../lib/job-stats";
import { cx } from "../lib/cx";
import { FilterIcon, GlobeIcon, SearchIcon } from "../components/icons";
import { coverageHasTargetedSource, coverageSummary } from "../../core/jobs/search/search-strategy";
import { queryMatches } from "../../core/jobs/search/query-expansion";
import {
  DEFAULT_FRESHNESS_WINDOW,
  FreshnessWindow,
  freshnessWindowDays,
  withinFreshnessWindow,
} from "../../core/jobs/freshness/freshness-window";

type FilterKey = "all" | "eligible" | "sponsored" | "remote" | "strong";

const FILTERS: { key: FilterKey; label: string }[] = [
  { key: "all", label: "All" },
  { key: "eligible", label: "Open to me" },
  { key: "sponsored", label: "Visa sponsorship" },
  { key: "remote", label: "Remote" },
  { key: "strong", label: "Strong match" },
];

const WINDOWS: { key: FreshnessWindow; label: string }[] = [
  { key: FreshnessWindow.SEVEN_DAYS, label: "7 derniers jours" },
  { key: FreshnessWindow.FOURTEEN_DAYS, label: "14 derniers jours" },
  { key: FreshnessWindow.THIRTY_DAYS, label: "30 derniers jours" },
];

/**
 * The jobs list: a search field, eligibility filters and the full ranked catalogue built
 * from the live key-free sources. The skeleton and error states reflect the real refresh.
 */
export function JobsScreen({ onNavigate }: { onNavigate: (route: string) => void }) {
  const { jobs, refresh, refreshJobs, markApplied } = useAppStore();
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<FilterKey>("all");
  const [freshnessWindow, setFreshnessWindow] = useState<FreshnessWindow>(DEFAULT_FRESHNESS_WINDOW);
  const [includeUndatedAndStale, setIncludeUndatedAndStale] = useState(false);

  useEffect(() => {
    if (refresh.status === "idle") void refreshJobs();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loading = refresh.status === "loading" || refresh.status === "idle";

  // The window is measured against one instant for the whole render, so the visible list and
  // the counts below can never disagree.
  const now = Date.now();

  const windowCounts = useMemo(() => {
    let within = 0;
    let undated = 0;
    let stale = 0;
    for (const job of jobs) {
      const posted = job.postedAtEpochMillis;
      if (posted == null || posted < 1 || posted > now) {
        undated += 1;
      } else if (withinFreshnessWindow(posted, now, freshnessWindow)) {
        within += 1;
      } else {
        stale += 1;
      }
    }
    return { within, undated, stale };
  }, [jobs, now, freshnessWindow]);

  const hiddenByWindow =
    !includeUndatedAndStale && windowCounts.within === 0 && windowCounts.undated + windowCounts.stale > 0;

  const results = useMemo(() => {
    const needle = query.trim();
    return jobs.filter((job) => {
      // Recent-and-dated first: unless the user opts into the wider view, a posting must carry
      // a real publication date inside the selected window. An undated or stale posting is
      // never counted as recent and is offered separately rather than silently dropped.
      if (!includeUndatedAndStale && !withinFreshnessWindow(job.postedAtEpochMillis, now, freshnessWindow)) return false;
      if (filter === "eligible" && job.eligibility === MadagascarEligibility.RESTRICTED) return false;
      if (filter === "sponsored" && job.eligibility !== MadagascarEligibility.VISA_SPONSORED) return false;
      if (filter === "remote" && job.workMode !== WorkMode.REMOTE) return false;
      if (filter === "strong" && !isStrongMatch(job)) return false;
      if (!needle) return true;
      // Trade-aware search, mirroring the Android `JobsViewModel`: a query matches when a
      // posting carries the query itself or a local synonym of the trade it names. The haystack
      // is the posting's own text; `matchedSkills` is deliberately excluded, because searching
      // this app's own tag let an unrelated title surface on a generic skill.
      const haystack = [job.title, job.company, job.country, job.city, job.summary].join(" ");
      return queryMatches(needle, haystack);
    });
  }, [jobs, query, filter, includeUndatedAndStale, now, freshnessWindow]);

  const handleView = (job: Job) => window.open(job.sourceUrl, "_blank", "noopener,noreferrer");
  const handleApply = (job: Job) => {
    markApplied(job);
    window.open(job.sourceUrl, "_blank", "noopener,noreferrer");
    onNavigate("applications");
  };
  const handleTool = (tool: "coverletter" | "scam" | "interview", job: Job) =>
    onNavigate(`${tool}/${job.id}`);

  return (
    <>
      <ScreenHeader title="Jobs" subtitle={`${jobs.length} international opportunities`} />

      <div className="app-shell space-y-4">
        <div className="relative">
          <SearchIcon
            width={18}
            height={18}
            className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-ink-tertiary"
          />
          <input
            type="search"
            inputMode="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search title, company or country"
            aria-label="Search jobs"
            className="h-12 w-full rounded-2xl border border-card-stroke bg-surface pl-11 pr-4 text-[14px] text-ink-primary placeholder:text-ink-tertiary focus-visible:border-accent-500"
          />
        </div>

        <div className="-mx-4 flex gap-2 overflow-x-auto px-4 pb-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
          <span className="inline-flex shrink-0 items-center gap-1 text-ink-tertiary">
            <FilterIcon width={16} height={16} />
          </span>
          {FILTERS.map((f) => {
            const active = f.key === filter;
            return (
              <button
                key={f.key}
                type="button"
                onClick={() => setFilter(f.key)}
                aria-pressed={active}
                className={cx(
                  "shrink-0 rounded-full border px-3.5 py-1.5 text-[12.5px] font-semibold transition-colors",
                  active
                    ? "border-transparent bg-accent-500 text-white"
                    : "border-card-stroke bg-surface text-ink-secondary hover:bg-surface-variant",
                )}
              >
                {f.label}
              </button>
            );
          })}
        </div>

        <div className="-mx-4 flex gap-2 overflow-x-auto px-4 pb-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
          {WINDOWS.map((w) => {
            const active = w.key === freshnessWindow;
            return (
              <button
                key={w.key}
                type="button"
                onClick={() => setFreshnessWindow(w.key)}
                aria-pressed={active}
                className={cx(
                  "shrink-0 rounded-full border px-3.5 py-1.5 text-[12.5px] font-semibold transition-colors",
                  active
                    ? "border-transparent bg-accent-500 text-white"
                    : "border-card-stroke bg-surface text-ink-secondary hover:bg-surface-variant",
                )}
              >
                {w.label}
              </button>
            );
          })}
          {windowCounts.undated + windowCounts.stale > 0 && (
            <button
              type="button"
              onClick={() => setIncludeUndatedAndStale((v) => !v)}
              aria-pressed={includeUndatedAndStale}
              className={cx(
                "shrink-0 rounded-full border px-3.5 py-1.5 text-[12.5px] font-semibold transition-colors",
                includeUndatedAndStale
                  ? "border-transparent bg-accent-500 text-white"
                  : "border-card-stroke bg-surface text-ink-secondary hover:bg-surface-variant",
              )}
            >
              Undated/older ({windowCounts.undated + windowCounts.stale})
            </button>
          )}
        </div>

        {!loading && refresh.coverage.totalRetrieved > 0 && (
          <div className="flex items-start gap-1.5">
            {coverageHasTargetedSource(refresh.coverage) ? (
              <SearchIcon width={15} height={15} className="mt-0.5 shrink-0 text-info" />
            ) : (
              <GlobeIcon width={15} height={15} className="mt-0.5 shrink-0 text-warning" />
            )}
            <p className="text-[12px] leading-relaxed text-ink-secondary">{coverageSummary(refresh.coverage)}</p>
          </div>
        )}

        {loading ? (
          <JobListSkeleton count={5} />
        ) : refresh.status === "error" && jobs.length === 0 ? (
          <EmptyState
            tone="error"
            title="Could not reach the job sources"
            message={refresh.error ?? "Check your connection and try again."}
            actionLabel="Try again"
            onAction={() => void refreshJobs()}
          />
        ) : results.length === 0 ? (
          hiddenByWindow ? (
            <EmptyState
              title="No recently published jobs"
              message={`None of the loaded postings state a publication date inside the ${freshnessWindowDays(
                freshnessWindow,
              )}-day window. This app never invents a date, so undated or older postings stay hidden until you ask for them.`}
              actionLabel={`Show undated/older (${windowCounts.undated + windowCounts.stale})`}
              onAction={() => setIncludeUndatedAndStale(true)}
            />
          ) : (
            <EmptyState
              title="No jobs match those filters"
              message="Try a different search term or clear the filters to see every opportunity."
              actionLabel="Clear filters"
              onAction={() => {
                setQuery("");
                setFilter("all");
              }}
            />
          )
        ) : (
          <div className="space-y-3">
            {results.map((job, i) => (
              <JobCard key={job.id} job={job} index={i} onView={handleView} onApply={handleApply} onOpenTool={handleTool} />
            ))}
          </div>
        )}

        {!loading && jobs.length > 0 && (
          <p className="flex flex-wrap items-center justify-center gap-1 pt-2 text-center text-[11.5px] text-ink-tertiary">
            Live postings from Remotive, Arbeitnow and Jobicy.
            <button
              type="button"
              onClick={() => onNavigate("ai")}
              className="rounded-lg px-2 py-2 font-semibold text-accent-ink"
            >
              How matching works
            </button>
          </p>
        )}
      </div>
    </>
  );
}
