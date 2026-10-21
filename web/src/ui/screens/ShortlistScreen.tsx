import { useEffect, useMemo, useState } from "react";
import {
  APPLICATION_READINESS_META,
  ApplicationReadiness,
  BlockerKind,
  NextStepAction,
  ShortlistQualityBand,
  blockerSummary,
  orderedBlockers,
  shortlistBreakdown,
  shortlistHasOfficialRoute,
  shortlistNextStep,
  shortlistQuality,
  type NextStep,
  type RankComponent,
  type ReadinessBlocker,
  type ShortlistEntry,
} from "../../core/models";
import { formatSalaryRange } from "../lib/format";
import { Badge, type BadgeTone } from "../components/Badge";
import { Button } from "../components/Button";
import { EmptyState } from "../components/EmptyState";
import { ReadinessRing } from "../components/ReadinessRing";
import { Skeleton } from "../components/Skeleton";
import { cx } from "../lib/cx";
import { useAppStore } from "../../state/app-store";
import {
  ArrowLeftIcon,
  BuildIcon,
  CheckCircleIcon,
  CheckIcon,
  ChevronRightIcon,
  CloseIcon,
  GiftIcon,
  GavelIcon,
  GlobeIcon,
  HelpIcon,
  RouteIcon,
  ShieldIcon,
  SlidersIcon,
  SparkIcon,
  TrophyIcon,
  TrendIcon,
  UserIcon,
  WarningIcon,
} from "../components/icons";

interface ShortlistScreenProps {
  onNavigate: (route: string) => void;
}

/** Where each readiness band maps in the palette, shared by the badge and the ring. */
const READINESS_BADGE_TONE: Record<ApplicationReadiness, BadgeTone> = {
  [ApplicationReadiness.PRIORITY]: "success",
  [ApplicationReadiness.READY]: "info",
  [ApplicationReadiness.PREPARE_FIRST]: "warning",
  [ApplicationReadiness.NEEDS_REVIEW]: "neutral",
  [ApplicationReadiness.BLOCKED]: "danger",
};

const READINESS_ICON: Record<ApplicationReadiness, React.ReactNode> = {
  [ApplicationReadiness.PRIORITY]: <TrophyIcon width={12} height={12} />,
  [ApplicationReadiness.READY]: <CheckCircleIcon width={12} height={12} />,
  [ApplicationReadiness.PREPARE_FIRST]: <BuildIcon width={12} height={12} />,
  [ApplicationReadiness.NEEDS_REVIEW]: <HelpIcon width={12} height={12} />,
  [ApplicationReadiness.BLOCKED]: <WarningIcon width={12} height={12} />,
};

const NEXT_STEP_STYLE: Record<NextStepAction, { className: string; icon: React.ReactNode }> = {
  [NextStepAction.APPLY]: { className: "bg-success-container text-success", icon: <CheckCircleIcon width={16} height={16} /> },
  [NextStepAction.RUN_SCAM_CHECK]: { className: "bg-danger-container text-danger", icon: <GavelIcon width={16} height={16} /> },
  [NextStepAction.REVIEW_ELIGIBILITY]: { className: "bg-warning-container text-warning", icon: <GlobeIcon width={16} height={16} /> },
  [NextStepAction.CONFIRM_BENEFITS]: { className: "bg-warning-container text-warning", icon: <GiftIcon width={16} height={16} /> },
  [NextStepAction.COMPLETE_PROFILE]: { className: "bg-info-container text-info", icon: <UserIcon width={16} height={16} /> },
  [NextStepAction.TAILOR_CV]: { className: "bg-info-container text-info", icon: <RouteIcon width={16} height={16} /> },
  [NextStepAction.OPEN_JOB]: { className: "bg-surface-variant text-ink-secondary", icon: <SlidersIcon width={16} height={16} /> },
};

const QUALITY_TONE: Record<ShortlistQualityBand, { text: string; container: string; label: string }> = {
  [ShortlistQualityBand.STRONG]: { text: "text-success", container: "bg-success-container", label: "Strong" },
  [ShortlistQualityBand.BUILDING]: { text: "text-info", container: "bg-info-container", label: "Building" },
  [ShortlistQualityBand.THIN]: { text: "text-warning", container: "bg-warning-container", label: "Thin" },
  [ShortlistQualityBand.NONE]: { text: "text-ink-secondary", container: "bg-surface-variant", label: "No data" },
};

/**
 * Stage 11 web parity: the expat shortlist — the postings a candidate in Madagascar can
 * realistically apply for, ranked by how ready they are to act on rather than by CV fit
 * alone. The readiness number, the quality read, the next-step advice and the explanation
 * sheet all come from the same ported engine the Android screen uses.
 */
export function ShortlistScreen({ onNavigate }: ShortlistScreenProps) {
  const { shortlist, refresh, refreshJobs, jobs, markApplied } = useAppStore();
  const [explainEntry, setExplainEntry] = useState<ShortlistEntry | null>(null);
  const [preparationLimit, setPreparationLimit] = useState(3);

  // A deep link straight to the shortlist may arrive before the feed has loaded. Kick off the
  // same refresh the dashboard would, so the shortlist is never blank for lack of a fetch.
  useEffect(() => {
    if (refresh.status === "idle") void refreshJobs();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const breakdown = useMemo(() => shortlistBreakdown(shortlist), [shortlist]);
  const quality = useMemo(() => shortlistQuality(shortlist), [shortlist]);
  const loading = (shortlist.isLoading || refresh.status === "loading" || refresh.status === "idle") && breakdown.isEmpty;
  const empty = !loading && jobs.length > 0 && breakdown.isEmpty;
  const feedUnavailable = !loading && jobs.length === 0 && refresh.status === "error";

  const preparation = breakdown.needsPreparation;
  const shownPreparation = preparation.slice(0, preparationLimit);

  return (
    <>
      <ShortlistHeader
        onBack={() => onNavigate("home")}
        onRefresh={() => void refreshJobs()}
      />

      <div className="app-shell space-y-4">
        {loading ? (
          <ShortlistSkeleton />
        ) : feedUnavailable ? (
          <EmptyState
            tone="error"
            title="Could not reach the job sources"
            message={refresh.error ?? "Check your connection and try again."}
            actionLabel="Try again"
            onAction={() => void refreshJobs()}
          />
        ) : empty ? (
          <EmptyState
            icon={<GlobeIcon width={26} height={26} />}
            title="No shortlist yet"
            message="Nothing in your feed is open to applicants in Madagascar right now. Refresh to pull the latest postings, or widen your filters."
            actionLabel="Refresh"
            onAction={() => void refreshJobs()}
          />
        ) : (
          <>
            <ShortlistSummary
              breakdown={breakdown}
              totalJobs={shortlist.totalJobs}
              onOpenJobs={() => onNavigate("jobs")}
            />

            <QualityDashboard quality={quality} />

            {breakdown.recurringProfileGaps.length > 0 && (
              <ProfileGapCard gaps={breakdown.recurringProfileGaps} />
            )}

            <SectionHeader
              title="Ready to act on"
              subtitle={`${breakdown.actionableCount} of ${shortlist.entries.length} postings are open to you now`}
            />

            {breakdown.rankedActionable.length === 0 ? (
              <PreparationPrompt
                count={preparation.length}
                onOpenJobs={() => onNavigate("jobs")}
              />
            ) : (
              <div className="space-y-3">
                {breakdown.rankedActionable.map((entry, index) => (
                  <ShortlistEntryCard
                    key={entry.job.id}
                    entry={entry}
                    index={index}
                    showNextStep={false}
                    onOpenJob={() => openJob(entry)}
                    onApply={() => apply(entry)}
                    onExplain={() => setExplainEntry(entry)}
                  />
                ))}
              </div>
            )}

            {preparation.length > 0 && (
              <>
                <SectionHeader
                  title="Worth preparing for"
                  subtitle="Close the gap and these move up your list"
                />
                <div className="space-y-3">
                  {shownPreparation.map((entry, index) => (
                    <ShortlistEntryCard
                      key={entry.job.id}
                      entry={entry}
                      index={index}
                      showNextStep
                      onOpenJob={() => openJob(entry)}
                      onApply={() => apply(entry)}
                      onExplain={() => setExplainEntry(entry)}
                    />
                  ))}
                </div>
                {preparation.length > shownPreparation.length && (
                  <Button
                    variant="secondary"
                    fullWidth
                    onClick={() => setPreparationLimit((n) => n + 3)}
                  >
                    Show {preparation.length - shownPreparation.length} more
                  </Button>
                )}
              </>
            )}

            <SourceFootnote note={shortlist.sourceNote} />
          </>
        )}
      </div>

      {explainEntry && (
        <ReadinessExplanationSheet
          entry={explainEntry}
          onDismiss={() => setExplainEntry(null)}
          onOpenJob={() => {
            openJob(explainEntry);
            setExplainEntry(null);
          }}
        />
      )}
    </>
  );

  function openJob(entry: ShortlistEntry) {
    window.open(entry.job.sourceUrl, "_blank", "noopener,noreferrer");
  }

  function apply(entry: ShortlistEntry) {
    markApplied(entry.job);
    window.open(entry.job.sourceUrl, "_blank", "noopener,noreferrer");
    onNavigate("applications");
  }
}

/** Sticky header with the back and refresh actions, matching the other secondary screens. */
function ShortlistHeader({ onBack, onRefresh }: { onBack: () => void; onRefresh: () => void }) {
  return (
    <header className="safe-top sticky top-0 z-30 border-b border-card-stroke bg-background/80 backdrop-blur-xl">
      <div className="mx-auto flex w-full max-w-screen-sm items-center gap-3 px-4 pb-3 pt-3">
        <button
          type="button"
          onClick={onBack}
          aria-label="Back"
          className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full border border-card-stroke bg-surface text-ink-secondary transition-colors hover:text-accent-ink active:scale-95"
        >
          <ArrowLeftIcon width={20} height={20} />
        </button>
        <div className="min-w-0 flex-1">
          <h1 className="truncate text-[18px] font-extrabold tracking-tight text-ink-primary">
            Expat shortlist
          </h1>
          <p className="truncate text-[12.5px] text-ink-secondary">
            Roles open to applicants in Madagascar
          </p>
        </div>
        <button
          type="button"
          onClick={onRefresh}
          aria-label="Refresh shortlist"
          className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full border border-card-stroke bg-surface text-ink-secondary transition-colors hover:text-accent-ink active:scale-95"
        >
          <SparkIcon width={20} height={20} />
        </button>
      </div>
    </header>
  );
}

function SectionHeader({ title, subtitle }: { title: string; subtitle?: string }) {
  return (
    <div className="pt-2">
      <h2 className="text-[16px] font-bold text-ink-primary">{title}</h2>
      {subtitle && <p className="text-[12.5px] text-ink-secondary">{subtitle}</p>}
    </div>
  );
}

/** Hero summary: the one number that matters, then the breakdown behind it. */
function ShortlistSummary({
  breakdown,
  totalJobs,
  onOpenJobs,
}: {
  breakdown: ReturnType<typeof shortlistBreakdown>;
  totalJobs: number;
  onOpenJobs: () => void;
}) {
  const headline =
    breakdown.priorityCount > 0
      ? `${breakdown.priorityCount} top-priority role${breakdown.priorityCount === 1 ? "" : "s"} for you`
      : breakdown.readyCount > 0
        ? `${breakdown.readyCount} role${breakdown.readyCount === 1 ? "" : "s"} ready to apply for`
        : "Build up your shortlist";
  const sub =
    totalJobs > 0
      ? `Screened ${totalJobs} postings · ${breakdown.officialRouteCount} with a stated route in`
      : "Refresh to screen the latest postings";

  return (
    <section className="animate-fade-up overflow-hidden rounded-3xl bg-gradient-to-br from-[#15356F] to-[#0A1D40] p-5 text-white shadow-card dark:from-[#152C5C] dark:to-[#0A1730]">
      <div className="flex items-center gap-3">
        <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-white/12">
          <SparkIcon width={22} height={22} className="text-teal-300" />
        </span>
        <div className="min-w-0">
          <p className="text-[15.5px] font-bold">{headline}</p>
          <p className="text-[12.5px] text-white/70">{sub}</p>
        </div>
      </div>

      <div className="mt-4 flex flex-wrap gap-2">
        <SummaryStat label="Priority" value={breakdown.priorityCount} accent="text-emerald-300" />
        <SummaryStat label="Ready" value={breakdown.readyCount} accent="text-blue-300" />
        <SummaryStat label="Verify" value={breakdown.verifyCount} accent="text-amber-300" />
        <SummaryStat label="Official route" value={breakdown.officialRouteCount} accent="text-white" />
      </div>

      <Button
        variant="secondary"
        fullWidth
        className="mt-4"
        leadingIcon={<SlidersIcon width={16} height={16} />}
        onClick={onOpenJobs}
      >
        Browse all matches
      </Button>
    </section>
  );
}

function SummaryStat({ label, value, accent }: { label: string; value: number; accent: string }) {
  return (
    <span className="inline-flex items-center gap-1.5 rounded-2xl bg-white/10 px-3 py-2">
      <span className={cx("text-[15px] font-extrabold tabular-nums", accent)}>{value}</span>
      <span className="text-[11px] font-semibold text-white/80">{label}</span>
    </span>
  );
}

/**
 * The shortlist-quality read: how strong the list is, how many postings are open to the user,
 * what share that is, and where the routes in lead. It reports only what the listings state,
 * and never dresses a thin list up as an opportunity.
 */
function QualityDashboard({ quality }: { quality: ReturnType<typeof shortlistQuality> }) {
  const tone = QUALITY_TONE[quality.band];
  return (
    <section className="card-surface p-4">
      <div className="flex items-center gap-3">
        <span className={cx("flex h-9 w-9 shrink-0 items-center justify-center rounded-xl", tone.container, tone.text)}>
          <TrendIcon width={18} height={18} />
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-[14px] font-bold text-ink-primary">Shortlist quality</p>
          <p className="text-[12.5px] text-ink-secondary">{quality.headline}</p>
        </div>
        <span className={cx("rounded-full px-2.5 py-1 text-[11px] font-bold", tone.container, tone.text)}>
          {tone.label}
        </span>
      </div>

      <div className="mt-3 h-2 w-full overflow-hidden rounded-full bg-surface-variant">
        <div
          className={cx("h-full rounded-full bg-current transition-[width] duration-700 ease-out", tone.text)}
          style={{ width: `${Math.max(2, quality.actionableShare)}%` }}
          role="progressbar"
          aria-valuenow={quality.actionableShare}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label="Share open to you"
        />
      </div>

      <div className="mt-3 flex items-center justify-between">
        <StatBlock value={`${quality.actionableShare}%`} label="Open to you" tone={tone.text} />
        <StatBlock value={quality.strongestReadiness.toString()} label="Best readiness" />
        <StatBlock value={quality.countriesRepresented.toString()} label="Countries" />
      </div>

      <p className="mt-3 text-[12px] leading-relaxed text-ink-secondary">{quality.detail}</p>
    </section>
  );
}

function StatBlock({ value, label, tone }: { value: string; label: string; tone?: string }) {
  return (
    <div className="flex flex-1 flex-col items-center">
      <span className={cx("text-[18px] font-extrabold tabular-nums", tone ?? "text-ink-primary")}>
        {value}
      </span>
      <span className="text-[11px] text-ink-secondary">{label}</span>
    </div>
  );
}

/**
 * The gaps that recur across postings. Fixing one of these improves every match at once,
 * so it is worth surfacing above the list.
 */
function ProfileGapCard({ gaps }: { gaps: ReadinessBlocker[] }) {
  return (
    <section className="card-surface p-4">
      <div className="flex items-center gap-3">
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-warning-container text-warning">
          <WarningIcon width={18} height={18} />
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-[14px] font-bold text-ink-primary">Fix once, help every match</p>
          <p className="text-[12.5px] text-ink-secondary">
            These gaps hold back several of your shortlisted roles.
          </p>
        </div>
      </div>
      <ul className="mt-3 space-y-1.5">
        {gaps.slice(0, 3).map((gap) => (
          <li key={gap.label} className="flex items-start gap-2 text-[13px] text-ink-primary">
            <WarningIcon width={15} height={15} className="mt-0.5 shrink-0 text-warning" />
            {gap.label}
          </li>
        ))}
      </ul>
    </section>
  );
}

/**
 * Shown when nothing is ready yet. It says plainly that the gap is in the listings, points at
 * the postings that are close, and offers the one action that can change it: browse/refresh.
 */
function PreparationPrompt({ count, onOpenJobs }: { count: number; onOpenJobs: () => void }) {
  return (
    <section className="card-surface p-4">
      <div className="flex items-center gap-3">
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-info-container text-info">
          <HelpIcon width={18} height={18} />
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-[14px] font-bold text-ink-primary">Nothing is ready yet</p>
          <p className="text-[12.5px] text-ink-secondary">
            {count > 0
              ? `${count} posting${count === 1 ? "" : "s"} below are close — close the gap and they move up.`
              : "No posting on your shortlist states a route in from Madagascar yet."}
          </p>
        </div>
      </div>
      <Button
        variant="secondary"
        fullWidth
        className="mt-3"
        leadingIcon={<SlidersIcon width={16} height={16} />}
        onClick={onOpenJobs}
      >
        Browse all matches
      </Button>
    </section>
  );
}

/** The one action that moves a posting forward, with its own icon and tone. */
function NextStepRow({ step }: { step: NextStep }) {
  const style = NEXT_STEP_STYLE[step.action];
  return (
    <div className={cx("flex items-start gap-2 rounded-2xl px-3 py-2", style.className)}>
      <span className="mt-0.5 shrink-0">{style.icon}</span>
      <div className="min-w-0">
        <p className="text-[12.5px] font-bold">Next: {step.label}</p>
        <p className="text-[12px] leading-snug opacity-90">{step.detail}</p>
      </div>
    </div>
  );
}

interface EntryCardProps {
  entry: ShortlistEntry;
  index: number;
  showNextStep: boolean;
  onOpenJob: () => void;
  onApply: () => void;
  onExplain: () => void;
}

/** A shortlist card: readiness gauge, the facts, the highlights, then the two actions. */
function ShortlistEntryCard({
  entry,
  index,
  showNextStep,
  onOpenJob,
  onApply,
  onExplain,
}: EntryCardProps) {
  const job = entry.job;
  const readinessMeta = APPLICATION_READINESS_META[entry.readiness];
  const salary = formatSalaryRange(
    job.salaryMin,
    job.salaryMax,
    job.currencyCode,
    job.salaryPeriod,
    job.salaryIsEstimated,
  );
  const lowEvidence = entry.expatEvidenceCoverage < 40;

  return (
    <article
      className="card-surface animate-fade-up p-4"
      style={{ animationDelay: `${Math.min(index, 8) * 55}ms` }}
      aria-label={`${job.title} at ${job.company}`}
    >
      <div className="flex items-start gap-3.5">
        <div className="min-w-0 flex-1">
          <h3 className="line-clamp-2 text-[15.5px] font-bold leading-snug text-ink-primary">
            {job.title}
          </h3>
          <p className="mt-0.5 truncate text-[13px] font-semibold text-ink-secondary">{job.company}</p>
          <p className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-[12px] text-ink-tertiary">
            <span className="truncate">
              {job.countryFlag ? `${job.countryFlag} ` : ""}
              {job.country}
            </span>
            <span aria-hidden="true">·</span>
            <span className="truncate">{salary}</span>
          </p>
        </div>
        <button
          type="button"
          onClick={onExplain}
          aria-label={`Why ${job.title} ranks here`}
          className="shrink-0 rounded-full transition-transform active:scale-95"
        >
          <ReadinessRing score={entry.readinessScore} readiness={entry.readiness} />
        </button>
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-1.5">
        <Badge tone={READINESS_BADGE_TONE[entry.readiness]} icon={READINESS_ICON[entry.readiness]}>
          {readinessMeta.label}
        </Badge>
        <Badge tone="accent">{entry.matchScore}% Match</Badge>
        {entry.expatScore > 0 && (
          <Badge tone={lowEvidence ? "warning" : "info"} icon={<GiftIcon width={12} height={12} />}>
            {lowEvidence
              ? `Expat ${entry.expatScore}% · ${entry.expatEvidenceCoverage}% documented`
              : `Expat benefits ${entry.expatScore}%`}
          </Badge>
        )}
        {shortlistHasOfficialRoute(entry) && (
          <Badge tone="success" icon={<ShieldIcon width={12} height={12} />}>
            Stated route in
          </Badge>
        )}
      </div>

      {entry.highlights.slice(0, 3).map((highlight) => (
        <p key={highlight} className="mt-2 flex items-center gap-1.5 text-[12.5px] text-ink-primary">
          <CheckIcon width={14} height={14} className="shrink-0 text-success" />
          <span className="truncate">{highlight}</span>
        </p>
      ))}

      {showNextStep && (
        <div className="mt-3">
          <NextStepRow step={shortlistNextStep(entry)} />
        </div>
      )}

      <p className="mt-3 flex items-start gap-1.5 text-[12px] text-ink-secondary">
        {entry.blockers.length === 0 ? (
          <CheckIcon width={15} height={15} className="mt-0.5 shrink-0 text-success" />
        ) : (
          <WarningIcon width={15} height={15} className="mt-0.5 shrink-0 text-warning" />
        )}
        <span className="line-clamp-2">{blockerSummary(entry)}</span>
      </p>

      <div className="mt-3.5 flex items-center gap-2">
        <Button variant="secondary" size="sm" fullWidth onClick={onOpenJob} className="flex-1">
          View job
        </Button>
        <Button
          variant="primary"
          size="sm"
          fullWidth
          onClick={onApply}
          className="flex-1"
          trailingIcon={<ChevronRightIcon width={14} height={14} />}
        >
          Apply
        </Button>
      </div>
    </article>
  );
}

/**
 * The explanation sheet: opens up the readiness rank so nothing is a black box. It shows why
 * the posting ranks where it does, the four weighted ingredients, and what is in the way —
 * each labelled by whether the finding came from the posting, the app or the user's profile.
 */
function ReadinessExplanationSheet({
  entry,
  onDismiss,
  onOpenJob,
}: {
  entry: ShortlistEntry;
  onDismiss: () => void;
  onOpenJob: () => void;
}) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onDismiss();
    };
    window.addEventListener("keydown", onKey);
    const previous = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      window.removeEventListener("keydown", onKey);
      document.body.style.overflow = previous;
    };
  }, [onDismiss]);

  const job = entry.job;
  const readinessMeta = APPLICATION_READINESS_META[entry.readiness];

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center" role="presentation">
      <button
        type="button"
        aria-label="Close"
        onClick={onDismiss}
        className="absolute inset-0 bg-navy-950/50 backdrop-blur-sm animate-fade-in"
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label={`Why ${job.title} ranks here`}
        className="relative w-full max-w-screen-sm animate-sheet-up rounded-t-3xl border-t border-card-stroke bg-surface shadow-card-hover"
        style={{ maxHeight: "88dvh" }}
      >
        <div className="mx-auto mt-2.5 h-1.5 w-10 rounded-full bg-outline" aria-hidden="true" />
        <div className="flex items-start justify-between gap-3 px-5 pt-3">
          <div className="min-w-0">
            <h2 className="text-[17px] font-extrabold leading-snug text-ink-primary">{job.title}</h2>
            <p className="mt-0.5 truncate text-[12.5px] text-ink-secondary">
              {job.company} · {job.countryFlag} {job.country}
            </p>
          </div>
          <button
            type="button"
            onClick={onDismiss}
            aria-label="Close"
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full border border-card-stroke bg-surface text-ink-secondary active:scale-95"
          >
            <CloseIcon width={18} height={18} />
          </button>
        </div>

        <div className="overflow-y-auto px-5 pb-6" style={{ maxHeight: "calc(88dvh - 96px)" }}>
          <div className="mt-3 flex flex-wrap items-center gap-1.5">
            <Badge tone={READINESS_BADGE_TONE[entry.readiness]} icon={READINESS_ICON[entry.readiness]}>
              {readinessMeta.label}
            </Badge>
            <Badge tone="accent">{entry.matchScore}% Match</Badge>
          </div>

          <div className="mt-3">
            <NextStepRow step={shortlistNextStep(entry)} />
          </div>

          <SheetHeading>Why this rank</SheetHeading>
          {entry.highlights.length === 0 ? (
            <p className="text-[13px] text-ink-secondary">
              Nothing in the posting stood out as a strong positive signal yet.
            </p>
          ) : (
            <ul className="space-y-1.5">
              {entry.highlights.map((highlight) => (
                <li key={highlight} className="flex items-start gap-2 text-[13px] text-ink-primary">
                  <CheckIcon width={15} height={15} className="mt-0.5 shrink-0 text-success" />
                  {highlight}
                </li>
              ))}
            </ul>
          )}

          {entry.rankComponents.length > 0 && (
            <>
              <SheetHeading>How this ranks</SheetHeading>
              <p className="mb-2 text-[12px] text-ink-secondary">
                Readiness {entry.readinessScore}% is these four ingredients added up.
              </p>
              <div className="space-y-2">
                {entry.rankComponents.map((component) => (
                  <RankComponentRow key={component.label} component={component} />
                ))}
              </div>
            </>
          )}

          {entry.blockers.length > 0 && (
            <>
              <SheetHeading>What is in the way</SheetHeading>
              <div className="space-y-2">
                {orderedBlockers(entry).map((blocker) => (
                  <BlockerRow key={blocker.label} blocker={blocker} />
                ))}
              </div>
            </>
          )}

          <p className="mt-4 text-[12px] leading-relaxed text-ink-secondary">
            Readiness blends the employer's stated route in, your CV match, the documented benefits
            and how complete your profile is. It is a guide, not a decision.
          </p>

          <Button
            variant="primary"
            fullWidth
            className="mt-4"
            leadingIcon={<GlobeIcon width={16} height={16} />}
            onClick={onOpenJob}
          >
            Open job
          </Button>
        </div>
      </div>
    </div>
  );
}

function SheetHeading({ children }: { children: React.ReactNode }) {
  return <h3 className="mb-2 mt-5 text-[14px] font-bold text-ink-primary">{children}</h3>;
}

function BlockerRow({ blocker }: { blocker: ReadinessBlocker }) {
  const tone =
    blocker.kind === BlockerKind.OFFICIAL
      ? { container: "bg-danger-container", text: "text-danger", label: "From the posting" }
      : blocker.kind === BlockerKind.HEURISTIC
        ? { container: "bg-warning-container", text: "text-warning", label: "App assessment" }
        : { container: "bg-info-container", text: "text-info", label: "Your profile" };
  return (
    <div className="flex items-start gap-2.5">
      <span className={cx("flex h-7 w-7 shrink-0 items-center justify-center rounded-lg", tone.container, tone.text)}>
        <WarningIcon width={15} height={15} />
      </span>
      <div className="min-w-0 flex-1">
        <p className="text-[13px] font-semibold text-ink-primary">{blocker.label}</p>
        <p className="text-[12px] text-ink-secondary">{blocker.detail}</p>
        <p className={cx("mt-0.5 text-[11px] font-semibold", tone.text)}>{tone.label}</p>
      </div>
    </div>
  );
}

function RankComponentRow({ component }: { component: RankComponent }) {
  const share = component.maxPoints === 0 ? 0 : component.points / component.maxPoints;
  const tone = share >= 0.75 ? "text-success" : share >= 0.4 ? "text-info" : "text-ink-secondary";
  const container =
    share >= 0.75 ? "bg-success-container" : share >= 0.4 ? "bg-info-container" : "bg-surface-variant";
  return (
    <div className="flex items-start gap-2.5">
      <span className={cx("shrink-0 rounded-full px-2 py-1 text-[11px] font-bold tabular-nums", container, tone)}>
        {component.points}/{component.maxPoints}
      </span>
      <div className="min-w-0 flex-1">
        <p className="text-[13px] font-semibold text-ink-primary">{component.label}</p>
        <p className="text-[12px] text-ink-secondary">{component.detail}</p>
      </div>
    </div>
  );
}

function ShortlistSkeleton() {
  return (
    <div className="space-y-3" role="status" aria-label="Loading shortlist">
      <Skeleton className="h-[150px] w-full rounded-3xl" />
      {Array.from({ length: 3 }).map((_, i) => (
        <Skeleton key={i} className="h-[196px] w-full rounded-3xl" />
      ))}
    </div>
  );
}

/** Where the postings came from, so a cached list is never passed off as a live run. */
function SourceFootnote({ note }: { note: string }) {
  if (!note) return null;
  return (
    <p className="flex items-center gap-1.5 pb-1 pt-1 text-[11.5px] text-ink-tertiary">
      <GlobeIcon width={14} height={14} className="shrink-0" />
      <span>{note}</span>
    </p>
  );
}
