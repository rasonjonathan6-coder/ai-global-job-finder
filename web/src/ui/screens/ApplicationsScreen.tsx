import { useMemo } from "react";
import { Badge } from "../components/Badge";
import { Card } from "../components/Card";
import { EmptyState } from "../components/EmptyState";
import { ScreenHeader } from "../components/ScreenHeader";
import { ApplicationsIcon, ChevronRightIcon, GlobeIcon, PinIcon } from "../components/icons";
import { cx } from "../lib/cx";
import { relativeTimeFrom } from "../lib/format";
import {
  APPLICATION_STATUS_LABEL,
  ApplicationStatus,
  TRACKED_STAGES,
  useAppStore,
  type ApplicationRecord,
} from "../../state/app-store";

const STAGE_TONE: Record<ApplicationStatus, "neutral" | "info" | "warning" | "success" | "danger"> = {
  [ApplicationStatus.SAVED]: "neutral",
  [ApplicationStatus.PREPARING]: "info",
  [ApplicationStatus.APPLIED]: "info",
  [ApplicationStatus.INTERVIEW]: "warning",
  [ApplicationStatus.OFFER]: "success",
  [ApplicationStatus.REJECTED]: "danger",
  [ApplicationStatus.CLOSED]: "neutral",
};

/**
 * The application tracker, now backed by the live store: saving a job or tapping Apply on a
 * card writes here and survives a reload. Counts, stage progress and the empty state all
 * reflect the user's real rows.
 */
export function ApplicationsScreen({ onNavigate }: { onNavigate: (route: string) => void }) {
  const { applications, setApplicationStatus, removeApplication } = useAppStore();

  const counts = useMemo(() => {
    const map = new Map<ApplicationStatus, number>();
    for (const stage of TRACKED_STAGES) map.set(stage, 0);
    for (const app of applications) map.set(app.status, (map.get(app.status) ?? 0) + 1);
    return map;
  }, [applications]);

  const ordered = useMemo(
    () => [...applications].sort((a, b) => b.updatedAtEpochMillis - a.updatedAtEpochMillis),
    [applications],
  );

  return (
    <>
      <ScreenHeader title="Applications" subtitle="Track every application in one place" />
      <div className="app-shell space-y-4">
        {applications.length === 0 ? (
          <EmptyState
            title="No applications yet"
            message="Save a job or tap Apply on any card and it will appear here, so you always know what you are waiting on."
            icon={<ApplicationsIcon width={26} height={26} />}
            actionLabel="Browse jobs"
            onAction={() => onNavigate("jobs")}
          />
        ) : (
          <>
            <div className="grid grid-cols-5 gap-2">
              {TRACKED_STAGES.map((stage) => (
                <div key={stage} className="card-surface flex flex-col items-center gap-1 px-1 py-3">
                  <span className="text-[18px] font-extrabold tabular-nums text-ink-primary">
                    {counts.get(stage) ?? 0}
                  </span>
                  <span className="truncate text-[10px] font-medium text-ink-secondary">
                    {APPLICATION_STATUS_LABEL[stage]}
                  </span>
                </div>
              ))}
            </div>

            <div className="space-y-3">
              {ordered.map((app, i) => (
                <ApplicationCard
                  key={app.id}
                  app={app}
                  index={i}
                  onAdvance={(status) => setApplicationStatus(app.id, status)}
                  onRemove={() => removeApplication(app.id)}
                />
              ))}
            </div>
          </>
        )}
      </div>
    </>
  );
}

const FLOW: ApplicationStatus[] = [
  ApplicationStatus.SAVED,
  ApplicationStatus.PREPARING,
  ApplicationStatus.APPLIED,
  ApplicationStatus.INTERVIEW,
  ApplicationStatus.OFFER,
];

function nextStage(status: ApplicationStatus): ApplicationStatus | null {
  const index = FLOW.indexOf(status);
  if (index < 0) return null;
  return index < FLOW.length - 1 ? FLOW[index + 1] : null;
}

function ApplicationCard({
  app,
  index,
  onAdvance,
  onRemove,
}: {
  app: ApplicationRecord;
  index: number;
  onAdvance: (status: ApplicationStatus) => void;
  onRemove: () => void;
}) {
  const flowIndex = FLOW.indexOf(app.status);
  const next = nextStage(app.status);
  const open = () => window.open(app.sourceUrl, "_blank", "noopener,noreferrer");

  return (
    <Card interactive className="animate-fade-up" style={{ animationDelay: `${Math.min(index, 8) * 55}ms` }}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="truncate text-[15px] font-bold text-ink-primary">{app.jobTitle}</h3>
          <p className="mt-0.5 flex items-center gap-1 truncate text-[12.5px] text-ink-secondary">
            <span className="truncate">{app.company}</span>
            <span aria-hidden="true">·</span>
            <span className="inline-flex shrink-0 items-center gap-0.5">
              <PinIcon width={12} height={12} />
              {app.countryFlag ? `${app.countryFlag} ` : ""}
              {app.country}
            </span>
          </p>
        </div>
        <Badge tone={STAGE_TONE[app.status]}>{APPLICATION_STATUS_LABEL[app.status]}</Badge>
      </div>

      <div className="mt-3.5 flex items-center gap-1" aria-hidden="true">
        {FLOW.map((stage, idx) => (
          <span
            key={stage}
            className={cx(
              "h-1.5 flex-1 rounded-full transition-colors",
              flowIndex >= 0 && idx <= flowIndex ? "bg-accent-500" : "bg-surface-variant",
            )}
          />
        ))}
      </div>

      <div className="mt-3 flex items-center justify-between gap-2">
        <span className="text-[11.5px] text-ink-tertiary">
          {app.matchScore}% match · updated {relativeTimeFrom(app.updatedAtEpochMillis)}
        </span>
        <div className="flex items-center gap-1">
          {next && (
            <button
              type="button"
              onClick={() => onAdvance(next)}
              className="rounded-xl px-2.5 py-1.5 text-[12px] font-semibold text-accent-ink transition-colors hover:bg-accent-500/[0.08]"
            >
              Mark {APPLICATION_STATUS_LABEL[next].toLowerCase()}
            </button>
          )}
          <button
            type="button"
            onClick={open}
            aria-label={`Open ${app.jobTitle} posting`}
            className="inline-flex items-center gap-0.5 rounded-xl px-2 py-1.5 text-[12.5px] font-semibold text-ink-secondary transition-colors hover:bg-surface-variant"
          >
            <GlobeIcon width={14} height={14} />
            Posting
          </button>
          <button
            type="button"
            onClick={onRemove}
            aria-label={`Remove ${app.jobTitle}`}
            className="inline-flex items-center rounded-xl p-1.5 text-ink-tertiary transition-colors hover:bg-surface-variant hover:text-danger"
          >
            <ChevronRightIcon width={15} height={15} className="rotate-90" />
          </button>
        </div>
      </div>
    </Card>
  );
}
