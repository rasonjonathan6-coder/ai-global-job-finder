import { useEffect, useState } from "react";
import { Badge } from "../components/Badge";
import { Button } from "../components/Button";
import { ScreenHeader } from "../components/ScreenHeader";
import {
  AiIcon,
  ApplicationsIcon,
  CheckIcon,
  FileTextIcon,
  GlobeIcon,
  ShieldIcon,
  SparkIcon,
  TrendIcon,
} from "../components/icons";

interface Capability {
  title: string;
  detail: string;
  icon: React.ReactNode;
  tone: "accent" | "success" | "warning" | "info";
}

const CAPABILITIES: Capability[] = [
  {
    title: "CV analysis",
    detail: "Reads your skills, experience and education, then matches them against each posting.",
    icon: <FileTextIcon width={20} height={20} />,
    tone: "accent",
  },
  {
    title: "Eligibility check",
    detail: "Checks whether a role accepts applicants from Madagascar, and whether a visa route is stated.",
    icon: <GlobeIcon width={20} height={20} />,
    tone: "info",
  },
  {
    title: "Scam shield",
    detail: "Flags postings with the warning signs that typically precede recruitment fraud.",
    icon: <ShieldIcon width={20} height={20} />,
    tone: "warning",
  },
  {
    title: "Application help",
    detail: "Adapts your CV and drafts a cover letter for the posting you choose.",
    icon: <ApplicationsIcon width={20} height={20} />,
    tone: "success",
  },
];

const TONE_CLASS: Record<Capability["tone"], string> = {
  accent: "bg-accent-500/[0.10] text-accent-ink",
  success: "bg-success-container text-success",
  warning: "bg-warning-container text-warning",
  info: "bg-info-container text-info",
};

/**
 * The AI workspace. Stage 1 presents the assistant's capabilities and an honest, animated
 * "analysis" progress example; the live pipeline connects in a later stage.
 */
export function AiScreen() {
  const [progress, setProgress] = useState(0);

  useEffect(() => {
    const timer = window.setInterval(() => {
      setProgress((p) => (p >= 100 ? 0 : Math.min(100, p + 4)));
    }, 90);
    return () => window.clearInterval(timer);
  }, []);

  const analysing = progress < 100;

  return (
    <>
      <ScreenHeader title="AI Assistant" subtitle="How your matches are worked out" />
      <div className="app-shell space-y-5">
        <section className="card-surface overflow-hidden p-0">
          <div className="ai-wash p-4">
            <div className="flex items-center gap-3">
              <span className="flex h-11 w-11 items-center justify-center rounded-2xl bg-gradient-to-br from-accent-500 to-teal-600 text-white">
                <AiIcon width={22} height={22} />
              </span>
              <div className="min-w-0">
                <p className="text-[15px] font-bold text-ink-primary">Analysing your profile</p>
                <p className="text-[12.5px] text-ink-secondary">
                  {analysing ? "Reading your CV and checking eligibility…" : "Analysis complete"}
                </p>
              </div>
            </div>

            <div className="mt-4 h-2 w-full overflow-hidden rounded-full bg-surface-variant">
              <div
                className="h-full rounded-full bg-gradient-to-r from-accent-500 to-teal-500 transition-[width] duration-200 ease-out"
                style={{ width: `${progress}%` }}
                role="progressbar"
                aria-valuenow={progress}
                aria-valuemin={0}
                aria-valuemax={100}
                aria-label="Analysis progress"
              />
            </div>
            <div className="mt-2 flex items-center justify-between text-[11.5px] text-ink-tertiary">
              <span>{analysing ? "Working…" : "Up to date"}</span>
              <span className="tabular-nums">{progress}%</span>
            </div>
          </div>
        </section>

        <section aria-label="What the assistant does" className="space-y-3">
          {CAPABILITIES.map((cap, i) => (
            <div
              key={cap.title}
              className="card-surface animate-fade-up flex items-start gap-3.5 p-4"
              style={{ animationDelay: `${i * 55}ms` }}
            >
              <span className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-xl ${TONE_CLASS[cap.tone]}`}>
                {cap.icon}
              </span>
              <div className="min-w-0">
                <p className="text-[14px] font-bold text-ink-primary">{cap.title}</p>
                <p className="mt-0.5 text-[12.5px] leading-relaxed text-ink-secondary">{cap.detail}</p>
              </div>
            </div>
          ))}
        </section>

        <section className="card-surface p-4">
          <div className="flex items-center gap-2">
            <SparkIcon width={18} height={18} className="text-accent-ink" />
            <p className="text-[14px] font-bold text-ink-primary">Honest by design</p>
          </div>
          <ul className="mt-3 space-y-2.5">
            {[
              "Silence is never a claim — an unstated benefit is shown as unknown, not refused.",
              "A high score cannot hide a disqualifier: ineligible or scam-flagged postings are capped.",
              "Every point is traceable to a sentence in the posting or your own profile.",
            ].map((line) => (
              <li key={line} className="flex items-start gap-2 text-[12.5px] leading-relaxed text-ink-secondary">
                <CheckIcon width={15} height={15} className="mt-0.5 shrink-0 text-success" />
                <span>{line}</span>
              </li>
            ))}
          </ul>
        </section>

        <section className="flex items-center gap-3 rounded-2xl border border-card-stroke bg-surface p-4">
          <TrendIcon width={20} height={20} className="shrink-0 text-accent-ink" />
          <div className="min-w-0 flex-1">
            <p className="text-[13px] font-semibold text-ink-primary">Interview coach</p>
            <p className="text-[12px] text-ink-secondary">Practice answers tailored to each posting.</p>
          </div>
          <Badge tone="accent">Soon</Badge>
        </section>

        <Button fullWidth variant="secondary">
          Import or update your CV
        </Button>
      </div>
    </>
  );
}
