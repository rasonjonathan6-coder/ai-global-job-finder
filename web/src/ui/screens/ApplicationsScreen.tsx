import { useState } from "react";
import { Badge } from "../components/Badge";
import { Card } from "../components/Card";
import { EmptyState } from "../components/EmptyState";
import { ScreenHeader } from "../components/ScreenHeader";
import { ApplicationsIcon, ChevronRightIcon } from "../components/icons";
import { cx } from "../lib/cx";

type Stage = "Saved" | "Applied" | "Interview" | "Offer";

interface TrackedApplication {
  id: string;
  title: string;
  company: string;
  country: string;
  stage: Stage;
  updated: string;
}

const STAGES: Stage[] = ["Saved", "Applied", "Interview", "Offer"];

const DEMO: TrackedApplication[] = [
  {
    id: "a1",
    title: "Maintenance Technician",
    company: "Northline Industrial",
    country: "🇨🇦 Canada",
    stage: "Applied",
    updated: "2 days ago",
  },
  {
    id: "a2",
    title: "Heavy Diesel Mechanic",
    company: "Outback Fleet Services",
    country: "🇦🇺 Australia",
    stage: "Interview",
    updated: "5 hours ago",
  },
];

const STAGE_TONE: Record<Stage, "neutral" | "info" | "warning" | "success"> = {
  Saved: "neutral",
  Applied: "info",
  Interview: "warning",
  Offer: "success",
};

/**
 * Application tracker. Stage 1 shows a small demo set and the empty state; persistence and
 * reminders arrive with the application engine in a later stage.
 */
export function ApplicationsScreen() {
  const [apps] = useState<TrackedApplication[]>(DEMO);

  return (
    <>
      <ScreenHeader title="Applications" subtitle="Track every application in one place" />
      <div className="app-shell space-y-4">
        {apps.length === 0 ? (
          <EmptyState
            title="No applications yet"
            message="When you apply for a job it will appear here, so you always know what you are waiting on."
            icon={<ApplicationsIcon width={26} height={26} />}
          />
        ) : (
          <>
            <div className="grid grid-cols-4 gap-2">
              {STAGES.map((stage) => {
                const count = apps.filter((a) => a.stage === stage).length;
                return (
                  <div key={stage} className="card-surface flex flex-col items-center gap-1 py-3">
                    <span className="text-[18px] font-extrabold tabular-nums text-ink-primary">{count}</span>
                    <span className="text-[10.5px] font-medium text-ink-secondary">{stage}</span>
                  </div>
                );
              })}
            </div>

            <div className="space-y-3">
              {apps.map((app, i) => {
                const stageIndex = STAGES.indexOf(app.stage);
                return (
                  <Card key={app.id} interactive className="animate-fade-up" style={{ animationDelay: `${i * 60}ms` }}>
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <h3 className="truncate text-[15px] font-bold text-ink-primary">{app.title}</h3>
                        <p className="mt-0.5 truncate text-[12.5px] text-ink-secondary">
                          {app.company} · {app.country}
                        </p>
                      </div>
                      <Badge tone={STAGE_TONE[app.stage]}>{app.stage}</Badge>
                    </div>

                    <div className="mt-3.5 flex items-center gap-1" aria-hidden="true">
                      {STAGES.map((stage, idx) => (
                        <span
                          key={stage}
                          className={cx(
                            "h-1.5 flex-1 rounded-full transition-colors",
                            idx <= stageIndex ? "bg-accent-500" : "bg-surface-variant",
                          )}
                        />
                      ))}
                    </div>

                    <div className="mt-3 flex items-center justify-between">
                      <span className="text-[11.5px] text-ink-tertiary">Updated {app.updated}</span>
                      <span className="inline-flex items-center gap-0.5 text-[12.5px] font-semibold text-accent-ink">
                        Open
                        <ChevronRightIcon width={15} height={15} />
                      </span>
                    </div>
                  </Card>
                );
              })}
            </div>
          </>
        )}
      </div>
    </>
  );
}
