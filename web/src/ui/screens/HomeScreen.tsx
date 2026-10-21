import { useEffect, useMemo } from "react";
import { shortlistBreakdown, type Job } from "../../core/models";
import { JobCard } from "../components/JobCard";
import { ScreenHeader } from "../components/ScreenHeader";
import { StatTile } from "../components/StatTile";
import { JobListSkeleton } from "../components/Skeleton";
import { EmptyState } from "../components/EmptyState";
import { Button } from "../components/Button";
import { useAppStore } from "../../state/app-store";
import { dashboardStats } from "../lib/job-stats";
import {
  ApplicationsIcon,
  ChevronRightIcon,
  ClockIcon,
  GlobeIcon,
  JobsIcon,
  RefreshIcon,
  SparkIcon,
  TrendIcon,
} from "../components/icons";

interface HomeScreenProps {
  onNavigate: (route: string) => void;
}

/**
 * The premium dashboard. It leads with a personalised greeting, then the real quick
 * statistics, then the highest-scoring opportunities pulled from the live sources. The
 * skeleton state is driven by the actual refresh, not a simulated delay.
 */
export function HomeScreen({ onNavigate }: HomeScreenProps) {
  const { profile, jobs, refresh, refreshJobs, applications, markApplied } = useAppStore();

  // Refresh once when the dashboard first opens. The pipeline reports per-source failures
  // instead of throwing, so a single board being down never blanks the screen.
  useEffect(() => {
    if (refresh.status === "idle") void refreshJobs();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loading = refresh.status === "loading" || refresh.status === "idle";
  const stats = useMemo(() => dashboardStats(jobs, applications), [jobs, applications]);
  const best = useMemo(() => [...jobs].sort((a, b) => b.matchScore - a.matchScore).slice(0, 4), [jobs]);
  const firstName = profile.fullName.trim().split(/\s+/)[0] || "there";

  const open = (job: Job) => window.open(job.sourceUrl, "_blank", "noopener,noreferrer");
  const apply = (job: Job) => {
    markApplied(job);
    window.open(job.sourceUrl, "_blank", "noopener,noreferrer");
    onNavigate("applications");
  };
  const openTool = (tool: "coverletter" | "scam" | "interview", job: Job) => onNavigate(`${tool}/${job.id}`);

  return (
    <>
      <ScreenHeader title="AI Global Job Finder" subtitle="Your AI-powered search from Madagascar">
        <div className="ai-wash -mx-4 rounded-b-2xl px-4 pb-1 pt-1">
          <p className="text-[22px] font-extrabold leading-tight text-ink-primary">
            Hello, {firstName} <span aria-hidden="true">👋</span>
          </p>
          <p className="mt-1 text-[13px] leading-relaxed text-ink-secondary">
            {jobs.length > 0
              ? "Here are the international roles you can realistically apply for."
              : "Searching live sources for international roles you can apply for from Madagascar."}
          </p>
        </div>
      </ScreenHeader>

      <div className="app-shell space-y-6">
        <section aria-label="Quick statistics" className="grid grid-cols-2 gap-3">
          <StatTile
            index={0}
            label="Jobs matching you"
            value={stats.matchingJobs}
            tone="accent"
            icon={<JobsIcon width={18} height={18} />}
            onClick={() => onNavigate("jobs")}
          />
          <StatTile
            index={1}
            label="New opportunities"
            value={stats.newOpportunities}
            tone="info"
            icon={<SparkIcon width={18} height={18} />}
            onClick={() => onNavigate("jobs")}
          />
          <StatTile
            index={2}
            label="Applications"
            value={stats.applications}
            tone="warning"
            icon={<ApplicationsIcon width={18} height={18} />}
            onClick={() => onNavigate("applications")}
          />
          <StatTile
            index={3}
            label="Interviews"
            value={stats.interviews}
            tone="success"
            icon={<ClockIcon width={18} height={18} />}
            onClick={() => onNavigate("applications")}
          />
        </section>
        <p className="-mt-3 text-center text-[11.5px] text-ink-tertiary">
          Matching counts postings scoring 60% or more. New counts postings added in the last 3 days.
        </p>

        <ShortlistBanner onNavigate={onNavigate} />

        <section aria-label="Your best opportunities">
          <div className="mb-3 flex items-end justify-between gap-3">
            <div>
              <h2 className="text-[17px] font-bold text-ink-primary">Your best opportunities</h2>
              <p className="text-[12.5px] text-ink-secondary">
                Ranked by how ready you are to apply, not just CV fit.
              </p>
            </div>
            <button
              type="button"
              onClick={() => onNavigate("jobs")}
              className="-mr-2 inline-flex shrink-0 items-center gap-0.5 rounded-xl px-2 py-2.5 text-[13px] font-semibold text-accent-ink transition-colors hover:bg-accent-500/[0.08]"
            >
              See all
              <ChevronRightIcon width={16} height={16} />
            </button>
          </div>

          {loading ? (
            <JobListSkeleton count={3} />
          ) : refresh.status === "error" && jobs.length === 0 ? (
            <EmptyState
              tone="error"
              title="Could not reach the job sources"
              message={refresh.error ?? "Check your connection and try again."}
              actionLabel="Try again"
              onAction={() => void refreshJobs()}
            />
          ) : best.length === 0 ? (
            <EmptyState
              title="No opportunities yet"
              message="Import your CV to start finding international roles you can apply for from Madagascar."
              actionLabel="Import your CV"
              onAction={() => onNavigate("profile")}
            />
          ) : (
            <div className="space-y-3">
              {best.map((job, i) => (
                <JobCard key={job.id} job={job} index={i} onView={open} onApply={apply} onOpenTool={openTool} />
              ))}
            </div>
          )}

          {!loading && best.length > 0 && (
            <Button variant="ghost" fullWidth className="mt-3" onClick={() => onNavigate("jobs")}>
              Browse all opportunities
            </Button>
          )}
        </section>

        <SourceStatus onRefresh={() => void refreshJobs()} />

        <section aria-label="AI tip" className="card-surface overflow-hidden p-0">
          <div className="flex items-start gap-3 bg-gradient-to-br from-accent-500 to-teal-600 p-4 text-white">
            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-white/20">
              <TrendIcon width={20} height={20} />
            </span>
            <div className="min-w-0">
              <p className="text-[14px] font-bold">Trade roles travel well</p>
              <p className="mt-1 text-[12.5px] leading-relaxed text-white/90">
                Maintenance, electrical and HVAC trades appear on international shortage lists. Documented
                sponsorship is what turns a match into an offer you can accept from Madagascar.
              </p>
            </div>
          </div>
        </section>
      </div>
    </>
  );
}

/**
 * A compact entry point to the expat shortlist: the one number that matters on the home
 * dashboard, and the route into the full screen. It stays hidden until the engine has run.
 */
function ShortlistBanner({ onNavigate }: { onNavigate: (route: string) => void }) {
  const { shortlist } = useAppStore();
  const breakdown = useMemo(() => shortlistBreakdown(shortlist), [shortlist]);
  if (shortlist.entries.length === 0) return null;

  const actionable = breakdown.actionableCount;
  const headline =
    actionable > 0
      ? `${actionable} posting${actionable === 1 ? "" : "s"} you can act on now`
      : `${breakdown.verifyCount + breakdown.blockedCount} screened, none ready yet`;

  return (
    <button
      type="button"
      onClick={() => onNavigate("shortlist")}
      className="card-surface animate-fade-up flex w-full items-center gap-3.5 p-4 text-left transition-transform active:scale-[0.99]"
    >
      <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-success-container text-success">
        <GlobeIcon width={21} height={21} />
      </span>
      <div className="min-w-0 flex-1">
        <p className="text-[14.5px] font-bold text-ink-primary">Expat shortlist</p>
        <p className="mt-0.5 text-[12.5px] text-ink-secondary">{headline}</p>
        <p className="mt-1 text-[11.5px] text-ink-tertiary">
          {breakdown.officialRouteCount} with a stated route in · {shortlist.totalJobs} screened
        </p>
      </div>
      <ChevronRightIcon width={18} height={18} className="shrink-0 text-ink-tertiary" />
    </button>
  );
}

/** The honesty strip: which sources answered, and how many postings each returned. */
function SourceStatus({ onRefresh }: { onRefresh: () => void }) {
  const { refresh } = useAppStore();
  const reports = refresh.reports;
  const coverage = refresh.coverage;
  if (reports.length === 0) return null;

  const when = refresh.lastRefreshedAtEpochMillis
    ? new Date(refresh.lastRefreshedAtEpochMillis).toLocaleTimeString(undefined, {
        hour: "2-digit",
        minute: "2-digit",
      })
    : null;

  return (
    <section aria-label="Job sources" className="card-surface p-4">
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <GlobeIcon width={17} height={17} className="text-accent-ink" />
          <p className="text-[13.5px] font-bold text-ink-primary">Live sources</p>
        </div>
        <button
          type="button"
          onClick={onRefresh}
          disabled={refresh.status === "loading"}
          className="inline-flex items-center gap-1 rounded-xl px-2 py-1.5 text-[12.5px] font-semibold text-accent-ink transition-colors hover:bg-accent-500/[0.08] disabled:opacity-50"
        >
          <RefreshIcon width={14} height={14} />
          {when ? `Updated ${when}` : "Refresh"}
        </button>
      </div>
      <ul className="mt-3 space-y-2">
        {reports.map((report) => (
          <li key={report.providerId} className="flex items-center justify-between gap-3 text-[12.5px]">
            <span className="inline-flex min-w-0 items-center gap-1.5 text-ink-secondary">
              <span
                className={
                  report.ok
                    ? "h-1.5 w-1.5 shrink-0 rounded-full bg-success"
                    : "h-1.5 w-1.5 shrink-0 rounded-full bg-danger"
                }
                aria-hidden="true"
              />
              <span className="truncate">{report.providerName}</span>
            </span>
            <span className="shrink-0 tabular-nums text-ink-tertiary">
              {report.ok ? `${report.accepted} usable` : "unavailable"}
            </span>
          </li>
        ))}
      </ul>
      {/* Per-source coverage: which sources narrowed by keyword and which only returned a
          broad sample. Same wording the jobs screen uses, so the two never disagree. */}
      {coverage.sources.length > 0 && (
        <ul className="mt-2 space-y-1 border-t border-card-stroke pt-2">
          {coverage.sources.map((source) => (
            <li key={source.providerId} className="flex items-center justify-between gap-3 text-[11.5px] text-ink-tertiary">
              <span className="truncate">{source.providerName}</span>
              <span className="shrink-0 tabular-nums">
                {source.targeted ? "keyword search" : "broad sample"} · {source.relevantCount}/{source.totalCount}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
