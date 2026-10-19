import { useMemo, useState } from "react";
import { MadagascarEligibility, type Job } from "../../core/models";
import { EmptyState } from "../components/EmptyState";
import { JobCard } from "../components/JobCard";
import { ScreenHeader } from "../components/ScreenHeader";
import { SAMPLE_JOBS } from "../lib/sample-jobs";
import { cx } from "../lib/cx";
import { FilterIcon, SearchIcon } from "../components/icons";

type FilterKey = "all" | "eligible" | "sponsored" | "remote";

const FILTERS: { key: FilterKey; label: string }[] = [
  { key: "all", label: "All" },
  { key: "eligible", label: "Open to me" },
  { key: "sponsored", label: "Visa sponsorship" },
  { key: "remote", label: "Remote" },
];

/**
 * The jobs list: a search field, eligibility filters and the full ranked catalogue. This is
 * the Stage-1 shell; live provider data and infinite scroll land in a later stage.
 */
export function JobsScreen({ onNavigate }: { onNavigate: (route: string) => void }) {
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<FilterKey>("all");

  const results = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return SAMPLE_JOBS.filter((job) => {
      if (filter === "eligible" && job.eligibility === MadagascarEligibility.RESTRICTED) return false;
      if (filter === "sponsored" && job.eligibility !== MadagascarEligibility.VISA_SPONSORED) return false;
      if (filter === "remote" && job.workMode !== "REMOTE") return false;
      if (!needle) return true;
      return (
        job.title.toLowerCase().includes(needle) ||
        job.company.toLowerCase().includes(needle) ||
        job.country.toLowerCase().includes(needle) ||
        job.matchedSkills.some((s) => s.toLowerCase().includes(needle))
      );
    });
  }, [query, filter]);

  const handleView = (job: Job) => window.open(job.sourceUrl, "_blank", "noopener,noreferrer");

  return (
    <>
      <ScreenHeader title="Jobs" subtitle={`${SAMPLE_JOBS.length} international opportunities`} />

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

        {results.length === 0 ? (
          <EmptyState
            title="No jobs match those filters"
            message="Try a different search term or clear the filters to see every opportunity."
            actionLabel="Clear filters"
            onAction={() => {
              setQuery("");
              setFilter("all");
            }}
          />
        ) : (
          <div className="space-y-3">
            {results.map((job, i) => (
              <JobCard key={job.id} job={job} index={i} onView={handleView} onApply={handleView} />
            ))}
          </div>
        )}

        <p className="flex flex-wrap items-center justify-center gap-1 pt-2 text-center text-[11.5px] text-ink-tertiary">
          Showing demo postings. Live sources connect in a later stage.
          <button
            type="button"
            onClick={() => onNavigate("ai")}
            className="rounded-lg px-2 py-2 font-semibold text-accent-ink"
          >
            How matching works
          </button>
        </p>
      </div>
    </>
  );
}
