import { useEffect, type ReactNode } from "react";
import { AutoApplyStatus, type AutoApplyItem } from "../../core/models";
import { useAppStore } from "../../state/app-store";
import { Badge } from "../components/Badge";
import { Button } from "../components/Button";
import { Chip } from "../components/Chip";
import { EmptyState } from "../components/EmptyState";
import { SecondaryHeader } from "../components/SecondaryHeader";
import { Skeleton } from "../components/Skeleton";
import { Switch } from "../components/Switch";
import { cx } from "../lib/cx";
import {
  CheckCircleIcon,
  FileTextIcon,
  InfoIcon,
  LockIcon,
  ShieldIcon,
  SkipIcon,
  SendIcon,
  SparkIcon,
  BlockIcon,
} from "../components/icons";

const THRESHOLD_OPTIONS = [50, 60, 70, 80, 90];

/**
 * Safe auto-apply. Mirrors the Android `AutoApplyScreen`: the mode prepares a CV and cover
 * letter for every posting above the threshold and never submits. Approval is always a
 * deliberate tap, and the send happens on the posting itself.
 */
export function AutoApplyScreen({ onNavigate }: { onNavigate: (route: string) => void }) {
  const {
    refresh,
    refreshJobs,
    autoApplyEnabled,
    setAutoApplyEnabled,
    autoApplyItems,
    autoApplyPreparing,
    autoApplyThreshold,
    setAutoApplyThreshold,
    prepareAutoApply,
    approveAutoApply,
    skipAutoApply,
  } = useAppStore();

  // Deep-linking straight to `#/autoapply` must still evaluate against a real feed; the
  // store then rebuilds the queue once the postings land.
  useEffect(() => {
    if (refresh.status === "idle") void refreshJobs();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const activeItems = autoApplyItems.filter((item) => item.status !== AutoApplyStatus.SKIPPED);
  const readyCount = autoApplyItems.filter((i) => i.status === AutoApplyStatus.DRAFT_READY).length;
  const blockedCount = autoApplyItems.filter((i) => i.status === AutoApplyStatus.BLOCKED).length;

  return (
    <>
      <SecondaryHeader
        title="Safe auto-apply"
        subtitle="Prepare drafts, you approve every send"
        onBack={() => onNavigate("ai")}
      />

      <div className="app-shell space-y-4">
        <section className="card-surface flex items-center gap-3 p-4">
          <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-accent-500/[0.14] text-accent-ink">
            <SparkIcon width={22} height={22} />
          </span>
          <div className="min-w-0 flex-1">
            <p className="text-[14px] font-bold text-ink-primary">
              Prepare applications automatically
            </p>
            <p className="text-[12.5px] leading-relaxed text-ink-secondary">
              Drafts only. The app never submits on your behalf.
            </p>
          </div>
          <Switch
            checked={autoApplyEnabled}
            onChange={setAutoApplyEnabled}
            label="Auto-apply mode"
          />
        </section>

        <SafetyCard />

        {autoApplyEnabled ? (
          <>
            <section className="card-surface space-y-3 p-4">
              <div>
                <h2 className="text-[15px] font-bold tracking-tight text-ink-primary">Match threshold</h2>
                <p className="text-[12px] text-ink-secondary">
                  Only prepare jobs at or above {autoApplyThreshold}%
                </p>
              </div>
              <div className="flex flex-wrap gap-1.5">
                {THRESHOLD_OPTIONS.map((value) => (
                  <Chip
                    key={value}
                    label={`${value}%`}
                    selected={autoApplyThreshold === value}
                    onClick={() => setAutoApplyThreshold(value)}
                  />
                ))}
              </div>
            </section>

            <div className="flex items-center gap-3">
              <div className="min-w-0 flex-1">
                <h2 className="text-[15px] font-bold tracking-tight text-ink-primary">
                  Prepared applications
                </h2>
                <p className="text-[12px] text-ink-secondary">
                  {readyCount} ready · {blockedCount} blocked
                </p>
              </div>
              <Button
                variant="secondary"
                size="sm"
                loading={autoApplyPreparing}
                leadingIcon={<SparkIcon width={15} height={15} />}
                onClick={() => prepareAutoApply()}
              >
                Prepare
              </Button>
            </div>

            {autoApplyPreparing && autoApplyItems.length === 0 ? (
              <div className="space-y-2.5" role="status" aria-label="Preparing drafts">
                <Skeleton className="h-[132px] rounded-2xl" />
                <Skeleton className="h-[132px] rounded-2xl" />
              </div>
            ) : activeItems.length === 0 ? (
              <EmptyState
                icon={<SparkIcon width={26} height={26} />}
                title="Nothing to prepare"
                message={`No posting clears your ${autoApplyThreshold}% threshold right now. Lower it or widen your target roles.`}
              />
            ) : (
              <div className="space-y-2.5">
                {activeItems.map((item) => (
                  <AutoApplyCard
                    key={item.jobId}
                    item={item}
                    onApprove={() => approveAutoApply(item.jobId)}
                    onSkip={() => skipAutoApply(item.jobId)}
                    onOpenJob={() => onNavigate(`job/${item.jobId}`)}
                  />
                ))}
              </div>
            )}
          </>
        ) : (
          <EmptyState
            icon={<ShieldIcon width={26} height={26} />}
            title="Auto-apply is off"
            message="Turn it on and the app prepares a tailored CV and cover letter for every job above your threshold. Nothing is sent until you approve it."
          />
        )}
      </div>
    </>
  );
}

function SafetyCard() {
  return (
    <section className="card-surface space-y-2 border border-success/30 bg-success-container p-4">
      <div className="flex items-center gap-2">
        <LockIcon width={18} height={18} className="text-success" />
        <h2 className="text-[14px] font-bold text-ink-primary">
          What safe auto-apply will and will not do
        </h2>
      </div>
      <SafetyLine tone="success" text="Prepares a CV and cover letter for each job above your threshold." />
      <SafetyLine
        tone="success"
        text="Blocks anything the scam shield flags, or that is not open to Madagascar."
      />
      <SafetyLine tone="danger" text="Never sends an application, email or message without your tap." />
      <SafetyLine tone="danger" text="Never pays a fee, shares documents, or signs anything for you." />
    </section>
  );
}

function SafetyLine({ tone, text }: { tone: "success" | "danger"; text: string }) {
  const isSuccess = tone === "success";
  return (
    <div className="flex items-start gap-2">
      {isSuccess ? (
        <CheckCircleIcon width={14} height={14} className="mt-0.5 shrink-0 text-success" />
      ) : (
        <BlockIcon width={14} height={14} className="mt-0.5 shrink-0 text-danger" />
      )}
      <p className="text-[12.5px] leading-relaxed text-ink-secondary">{text}</p>
    </div>
  );
}

function AutoApplyCard({
  item,
  onApprove,
  onSkip,
  onOpenJob,
}: {
  item: AutoApplyItem;
  onApprove: () => void;
  onSkip: () => void;
  onOpenJob: () => void;
}) {
  const meta = STATUS_META[item.status];
  const actionable =
    item.status === AutoApplyStatus.DRAFT_READY || item.status === AutoApplyStatus.QUEUED;

  return (
    <section className="card-surface animate-fade-up p-4">
      <div className="flex items-center gap-3">
        <span className="text-[26px] leading-none" aria-hidden="true">
          {item.countryFlag || "🌍"}
        </span>
        <div className="min-w-0 flex-1">
          <p className="truncate text-[13.5px] font-semibold text-ink-primary">{item.jobTitle}</p>
          <p className="truncate text-[12px] text-ink-secondary">
            {item.company} · {item.country}
          </p>
        </div>
        <Badge tone="accent">{item.matchScore}%</Badge>
      </div>

      <div className="mt-2.5 flex items-start gap-1.5">
        <span className={cx("mt-0.5 shrink-0", meta.tone)} aria-hidden="true">
          {meta.icon}
        </span>
        <div className="min-w-0">
          <p className={cx("text-[12px] font-semibold", meta.tone)}>{meta.label}</p>
          <p className="text-[12.5px] leading-relaxed text-ink-secondary">{item.reason}</p>
        </div>
      </div>

      {actionable && (
        <div className="mt-3 flex items-center gap-2">
          <Button
            size="sm"
            className="flex-1"
            leadingIcon={<CheckCircleIcon width={15} height={15} />}
            onClick={onApprove}
          >
            Review &amp; approve
          </Button>
          <Button
            variant="secondary"
            size="sm"
            leadingIcon={<SkipIcon width={15} height={15} />}
            onClick={onSkip}
          >
            Skip
          </Button>
        </div>
      )}

      {item.status === AutoApplyStatus.APPROVED && (
        <div className="mt-3">
          <Button
            variant="secondary"
            fullWidth
            size="sm"
            leadingIcon={<SendIcon width={15} height={15} />}
            onClick={onOpenJob}
          >
            Open posting to send
          </Button>
        </div>
      )}
    </section>
  );
}

const STATUS_META: Record<AutoApplyStatus, { label: string; tone: string; icon: ReactNode }> = {
  [AutoApplyStatus.DRAFT_READY]: {
    label: "Draft ready",
    tone: "text-accent-ink",
    icon: <FileTextIcon width={15} height={15} />,
  },
  [AutoApplyStatus.APPROVED]: {
    label: "Approved",
    tone: "text-success",
    icon: <CheckCircleIcon width={15} height={15} />,
  },
  [AutoApplyStatus.BLOCKED]: {
    label: "Blocked",
    tone: "text-danger",
    icon: <BlockIcon width={15} height={15} />,
  },
  [AutoApplyStatus.QUEUED]: {
    label: "Queued",
    tone: "text-info",
    icon: <InfoIcon width={15} height={15} />,
  },
  [AutoApplyStatus.SKIPPED]: {
    label: "Skipped",
    tone: "text-ink-tertiary",
    icon: <SkipIcon width={15} height={15} />,
  },
};
