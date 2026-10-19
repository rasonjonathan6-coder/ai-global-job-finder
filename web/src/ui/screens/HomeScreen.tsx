import { useMemo, useState } from "react";
import type { Job } from "../../core/models";
import { JobCard } from "../components/JobCard";
import { ScreenHeader } from "../components/ScreenHeader";
import { StatTile } from "../components/StatTile";
import { JobListSkeleton } from "../components/Skeleton";
import { EmptyState } from "../components/EmptyState";
import { Button } from "../components/Button";
import { SAMPLE_JOBS } from "../lib/sample-jobs";
import {
  ApplicationsIcon,
  ChevronRightIcon,
  ClockIcon,
  SparkIcon,
  TrendIcon,
  JobsIcon,
} from "../components/icons";

interface HomeScreenProps {
  onNavigate: (route: string) => void;
}

/**
 * The premium dashboard. It leads with a personalised greeting, then quick statistics, then
 * the highest-scoring opportunities. A short simulated "analysis" delay drives the skeleton
 * state so the loading treatment is exercised on every visit.
 */
export function HomeScreen({ onNavigate }: HomeScreenProps) {
  const [loading] = useState(false);
  const jobs = SAMPLE_JOBS;

  const stats = useMemo(
    () => ({
      matchingJobs: jobs.filter((j) => j.matchScore >= 70).length,
      newOpportunities: jobs.filter((j) => j.isNew).length,
      applications: 3,
      interviews: 1,
    }),
    [jobs],
  );

  const best = useMemo(
    () => [...jobs].sort((a, b) => b.matchScore - a.matchScore).slice(0, 4),
    [jobs],
  );

  const handleView = (job: Job) => {
    window.open(job.sourceUrl, "_blank", "noopener,noreferrer");
  };

  return (
    <>
      <ScreenHeader title="AI Global Job Finder" subtitle="Your AI-powered search from Madagascar">
        <div className="ai-wash -mx-4 rounded-b-2xl px-4 pb-1 pt-1">
          <p className="text-[22px] font-extrabold leading-tight text-ink-primary">
            Hello, Jonathan <span aria-hidden="true">👋</span>
          </p>
          <p className="mt-1 text-[13px] leading-relaxed text-ink-secondary">
            Your CV is analysed. Here are the international roles you can realistically apply for.
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
                <JobCard key={job.id} job={job} index={i} onView={handleView} onApply={handleView} />
              ))}
            </div>
          )}

          {!loading && best.length > 0 && (
            <Button variant="ghost" fullWidth className="mt-3" onClick={() => onNavigate("jobs")}>
              Browse all opportunities
            </Button>
          )}
        </section>

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
