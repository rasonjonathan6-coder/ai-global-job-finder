import { useEffect } from "react";
import {
  ScamSignalTone,
  ScamVerdict,
  scamSafetyScore,
  type ScamReport,
  type ScamSignal,
} from "../../core/models";
import { Button } from "../components/Button";
import { EmptyState } from "../components/EmptyState";
import { SecondaryHeader } from "../components/SecondaryHeader";
import { useAppStore } from "../../state/app-store";
import { cx } from "../lib/cx";
import {
  CheckCircleIcon,
  ExternalLinkIcon,
  GavelIcon,
  ShieldIcon,
  WarningIcon,
} from "../components/icons";

/** The rules the detector applies, shown verbatim so the advice is auditable. */
const RULES = [
  "A real employer never charges a registration, placement or training fee.",
  "You should never pay a third party for a work visa or permit.",
  "Payment by wire, money transfer or crypto to an employer is a scam pattern.",
  "Interviews arranged only on WhatsApp or Telegram deserve scrutiny.",
  "A company contact should use the company's own domain, not a free inbox.",
  "A posting without a named employer, a link or a description cannot be verified.",
];

const VERDICT_GRADIENT: Record<ScamVerdict, string> = {
  [ScamVerdict.HIGH_RISK]: "from-[#7A1B15] to-[#3A0D0A]",
  [ScamVerdict.NEEDS_CAUTION]: "from-[#5C3A08] to-[#2A1B05]",
  [ScamVerdict.LIKELY_SAFE]: "from-[#0C4A34] to-[#06251A]",
};

const VERDICT_META: Record<ScamVerdict, { label: string; emoji: string }> = {
  [ScamVerdict.HIGH_RISK]: { label: "High scam risk", emoji: "⛔" },
  [ScamVerdict.NEEDS_CAUTION]: { label: "Check before applying", emoji: "⚠️" },
  [ScamVerdict.LIKELY_SAFE]: { label: "Likely safe", emoji: "✅" },
};

function verdictAdvice(verdict: ScamVerdict): string {
  switch (verdict) {
    case ScamVerdict.HIGH_RISK:
      return "Do not send money, documents or personal details. Real employers never ask for a fee. Verify the company independently before replying.";
    case ScamVerdict.NEEDS_CAUTION:
      return "This posting is not proven bad, but something is missing. Confirm the employer's official domain and the visa route before you invest time.";
    default:
      return "Nothing alarming found. Still apply through the employer's own site and keep a copy of every message.";
  }
}

/**
 * The scam shield for one posting. Every signal is a rule the ported detector fired, quoted
 * from the posting where possible, so the user can judge for themselves — the app advises,
 * it never blocks.
 */
export function ScamShieldScreen({ jobId, onBack }: { jobId: string; onBack: () => void }) {
  const { jobs, refresh, refreshJobs, scamReport } = useAppStore();

  // Populate the feed on first open, like the other job-scoped screens.
  useEffect(() => {
    if (refresh.status === "idle") void refreshJobs();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // A blank id means "scan my best current match", mirroring the Android `ScamShieldViewModel`.
  const job = jobId
    ? jobs.find((j) => j.id === jobId) ?? null
    : jobs.reduce<(typeof jobs)[number] | null>((best, j) => (!best || j.matchScore > best.matchScore ? j : best), null);

  if (!job) {
    return (
      <>
        <SecondaryHeader title="Scam shield" subtitle="Posting check" onBack={onBack} />
        <div className="app-shell">
          <EmptyState
            title="Nothing to check"
            message="This posting could not be scanned because it is no longer in your feed."
            actionLabel="Back to jobs"
            onAction={onBack}
          />
        </div>
      </>
    );
  }

  const report = scamReport(job);
  const dangers = report.signals.filter((s) => s.tone === ScamSignalTone.DANGER);
  const cautions = report.signals.filter((s) => s.tone === ScamSignalTone.CAUTION);
  const reassurances = report.signals.filter((s) => s.tone === ScamSignalTone.REASSURANCE);

  return (
    <>
      <SecondaryHeader title="Scam shield" subtitle={job.title} onBack={onBack} />

      <div className="app-shell space-y-4">
        <VerdictCard report={report} />

        {dangers.length > 0 && (
          <SignalSection title="Stop and read" subtitle="These are the patterns that end in lost money" signals={dangers} tone={ScamSignalTone.DANGER} />
        )}
        {cautions.length > 0 && (
          <SignalSection title="Check before you apply" subtitle="Not proof of a scam, but worth verifying" signals={cautions} tone={ScamSignalTone.CAUTION} />
        )}
        {reassurances.length > 0 && (
          <SignalSection title="Good signs" subtitle="What this posting gets right" signals={reassurances} tone={ScamSignalTone.REASSURANCE} />
        )}

        <section className="card-surface p-4">
          <h2 className="text-[14px] font-bold text-ink-primary">The rules behind this check</h2>
          <p className="text-[12px] text-ink-secondary">Applied to every posting in your feed</p>
          <ul className="mt-3 space-y-1.5">
            {RULES.map((rule) => (
              <li key={rule} className="flex items-start gap-2">
                <ShieldIcon width={14} height={14} className="mt-0.5 shrink-0 text-accent-ink" />
                <span className="text-[12.5px] leading-relaxed text-ink-secondary">{rule}</span>
              </li>
            ))}
          </ul>
        </section>

        {job.sourceUrl && (
          <a
            href={job.sourceUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="flex items-center gap-1.5 px-1 text-[12px] font-semibold text-accent-ink"
          >
            <ExternalLinkIcon width={14} height={14} />
            <span className="truncate">Read the original posting</span>
          </a>
        )}

        <Button fullWidth variant="secondary" onClick={onBack}>
          Back to jobs
        </Button>
      </div>
    </>
  );
}

function VerdictCard({ report }: { report: ScamReport }) {
  const meta = VERDICT_META[report.verdict];
  const score = scamSafetyScore(report);
  const dangers = report.signals.filter((s) => s.tone === ScamSignalTone.DANGER).length;
  const cautions = report.signals.filter((s) => s.tone === ScamSignalTone.CAUTION).length;
  const reassurances = report.signals.filter((s) => s.tone === ScamSignalTone.REASSURANCE).length;

  return (
    <section className={cx("animate-fade-up rounded-3xl bg-gradient-to-br p-5 text-white", VERDICT_GRADIENT[report.verdict])}>
      <div className="flex items-center gap-3">
        <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-white/14">
          <GavelIcon width={22} height={22} />
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-[15px] font-bold">{meta.emoji} {meta.label}</p>
          <p className="truncate text-[12.5px] text-white/78">{report.company || "Unknown employer"}</p>
        </div>
        <span className="shrink-0 rounded-full bg-white/16 px-2.5 py-1 text-[12px] font-bold">{score}%</span>
      </div>

      <p className="mt-3 text-[12.5px] leading-relaxed text-white/85">{verdictAdvice(report.verdict)}</p>
      <p className="mt-1.5 text-[11px] text-white/70">
        {dangers} danger · {cautions} caution · {reassurances} good signs
      </p>
      <p className="text-[11px] text-white/60">This is guidance, not a guarantee. You decide.</p>
    </section>
  );
}

function SignalSection({
  title,
  subtitle,
  signals,
  tone,
}: {
  title: string;
  subtitle: string;
  signals: ScamSignal[];
  tone: ScamSignalTone;
}) {
  return (
    <section className="space-y-2.5">
      <div className="px-1">
        <h2 className="text-[15px] font-bold text-ink-primary">{title}</h2>
        <p className="text-[12px] text-ink-secondary">{subtitle}</p>
      </div>
      {signals.map((signal) => (
        <SignalCard key={signal.label + signal.evidence} signal={signal} tone={tone} />
      ))}
    </section>
  );
}

function SignalCard({ signal, tone }: { signal: ScamSignal; tone: ScamSignalTone }) {
  const color =
    tone === ScamSignalTone.DANGER ? "text-danger" : tone === ScamSignalTone.CAUTION ? "text-warning" : "text-success";
  const border =
    tone === ScamSignalTone.DANGER ? "border-danger/35" : tone === ScamSignalTone.CAUTION ? "border-warning/35" : "border-success/35";
  const Icon = tone === ScamSignalTone.REASSURANCE ? CheckCircleIcon : WarningIcon;

  return (
    <article className={cx("card-surface flex items-start gap-3 p-4", border)}>
      <span className={cx("flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-surface-variant", color)}>
        <Icon width={17} height={17} />
      </span>
      <div className="min-w-0 flex-1">
        <p className="text-[13.5px] font-semibold text-ink-primary">{signal.label}</p>
        <p className="mt-0.5 text-[12.5px] leading-relaxed text-ink-secondary">{signal.detail}</p>
        {signal.evidence && (
          <p className={cx("mt-1.5 truncate rounded-lg bg-surface-variant px-2.5 py-1.5 text-[11.5px]", color)}>
            “{signal.evidence}”
          </p>
        )}
      </div>
    </article>
  );
}
