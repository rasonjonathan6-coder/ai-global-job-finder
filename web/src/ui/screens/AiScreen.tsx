import { useEffect, useState } from "react";
import {
  AI_CAPABILITY_CATALOG,
  AI_CAPABILITY_KIND_META,
  AI_PROCESSING_LOCATION_META,
  AiCapabilityKind,
  AiProcessingLocation,
  type AiCapability,
} from "../../core/models";
import { SubmissionCapability } from "../../core/submissions/submission-capability";
import { Badge } from "../components/Badge";
import { Button } from "../components/Button";
import { ScreenHeader } from "../components/ScreenHeader";
import {
  AiIcon,
  ApplicationsIcon,
  CheckIcon,
  ChevronDownIcon,
  ChevronRightIcon,
  ChevronUpIcon,
  CloudIcon,
  FileTextIcon,
  GlobeIcon,
  ShieldIcon,
  SmartphoneIcon,
  SparkIcon,
  TrendIcon,
  VerifiedUserIcon,
} from "../components/icons";

interface Capability {
  title: string;
  detail: string;
  icon: React.ReactNode;
  tone: "accent" | "success" | "warning" | "info";
  /** When set, the card opens this destination instead of being a static explainer. */
  route?: string;
  actionLabel?: string;
}

const CAPABILITIES: Capability[] = [
  {
    title: "CV analysis",
    detail: "Reads your skills, experience and education, then matches them against each posting.",
    icon: <FileTextIcon width={20} height={20} />,
    tone: "accent",
  },
  {
    title: "Expat shortlist",
    detail: "Ranks the postings you can actually apply for from Madagascar, and says what is in the way.",
    icon: <GlobeIcon width={20} height={20} />,
    tone: "success",
    route: "shortlist",
    actionLabel: "Open shortlist",
  },
  {
    title: "Eligibility check",
    detail: "Checks whether a role accepts applicants from Madagascar, and whether a visa route is stated.",
    icon: <ShieldIcon width={20} height={20} />,
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
  {
    title: "Prepared, never sent for you",
    detail: SubmissionCapability.sendsNothing
      ? "The assistant prepares each application and stops at one-tap approval. No channel can be driven by the app, so nothing is ever submitted automatically."
      : "The assistant prepares each application and waits for your one-tap approval before anything is sent.",
    icon: <ShieldIcon width={20} height={20} />,
    tone: "info",
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
export function AiScreen({ onNavigate }: { onNavigate: (route: string) => void }) {
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
      <ScreenHeader title="AI Assistant" subtitle="Your CV, matched against real international openings" />
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
            <button
              key={cap.title}
              type="button"
              disabled={!cap.route}
              onClick={() => cap.route && onNavigate(cap.route)}
              className="card-surface animate-fade-up flex w-full items-start gap-3.5 p-4 text-left transition-transform active:scale-[0.99] disabled:active:scale-100"
              style={{ animationDelay: `${i * 55}ms` }}
            >
              <span className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-xl ${TONE_CLASS[cap.tone]}`}>
                {cap.icon}
              </span>
              <div className="min-w-0 flex-1">
                <p className="text-[14px] font-bold text-ink-primary">{cap.title}</p>
                <p className="mt-0.5 text-[12.5px] leading-relaxed text-ink-secondary">{cap.detail}</p>
                {cap.route && (
                  <span className="mt-1.5 inline-flex items-center gap-1 text-[12.5px] font-semibold text-accent-ink">
                    {cap.actionLabel}
                    <ChevronRightIcon width={13} height={13} />
                  </span>
                )}
              </div>
            </button>
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
              SubmissionCapability.sendsNothing
                ? `Prepared, not sent: ${SubmissionCapability.supportedChannelCount} application routes can be driven by the app, so every application stops at your approval.`
                : `Prepared, not sent without you: the app can submit on ${SubmissionCapability.supportedChannelCount} verified route(s).`,
            ].map((line) => (
              <li key={line} className="flex items-start gap-2 text-[12.5px] leading-relaxed text-ink-secondary">
                <CheckIcon width={15} height={15} className="mt-0.5 shrink-0 text-success" />
                <span>{line}</span>
              </li>
            ))}
          </ul>
        </section>

        <TransparencyCard />

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

/** Pill styling per capability kind, matching the Kotlin `AiCapabilityRow` accents. */
const KIND_PILL: Record<AiCapabilityKind, string> = {
  [AiCapabilityKind.REMOTE_MODEL]: "bg-danger-container text-danger",
  [AiCapabilityKind.ON_DEVICE_MODEL]: "bg-accent-500/[0.12] text-accent-ink",
  [AiCapabilityKind.RULE_BASED]: "bg-info-container text-info",
  [AiCapabilityKind.DOCUMENT_PARSER]: "bg-info-container text-info",
  [AiCapabilityKind.TEMPLATE]: "bg-info-container text-info",
};

function CapabilityRow({ capability }: { capability: AiCapability }) {
  const remote = capability.location === AiProcessingLocation.REMOTE;
  return (
    <li className="space-y-1">
      <div className="flex items-center gap-2">
        <p className="min-w-0 flex-1 text-[13px] font-semibold text-ink-primary">{capability.name}</p>
        <span
          className={`shrink-0 rounded-full px-2.5 py-1 text-[11px] font-semibold leading-none ${KIND_PILL[capability.kind]}`}
        >
          {AI_CAPABILITY_KIND_META[capability.kind].label}
        </span>
      </div>
      <p className="text-[12px] leading-relaxed text-ink-secondary">{capability.description}</p>
      <div className="flex items-center gap-1.5 text-ink-tertiary">
        {remote ? <CloudIcon width={13} height={13} /> : <SmartphoneIcon width={13} height={13} />}
        <span className="text-[11px] font-medium">{AI_PROCESSING_LOCATION_META[capability.location].label}</span>
      </div>
      <p className="text-[11px] leading-relaxed text-ink-tertiary">{capability.note}</p>
    </li>
  );
}

/**
 * The honest inventory of what the app's "AI" actually does, ported from the Kotlin
 * `AiTransparencyCard`. The list is collapsed by default and expands to the full breakdown
 * so the screen states plainly which parts run on-device and which one is an opt-in remote model.
 */
function TransparencyCard() {
  const [expanded, setExpanded] = useState(false);
  return (
    <section className="card-surface space-y-3 p-4">
      <div className="flex items-center gap-3">
        <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-info-container text-info">
          <VerifiedUserIcon width={20} height={20} />
        </span>
        <div className="min-w-0">
          <p className="text-[13.5px] font-semibold text-ink-primary">How this app&apos;s AI works</p>
          <p className="text-[12px] text-ink-secondary">
            Rules and parsers on-device, plus one optional NVIDIA model
          </p>
        </div>
      </div>

      <p className="text-[12px] leading-relaxed text-ink-secondary">{AI_CAPABILITY_CATALOG.summary}</p>

      {expanded && (
        <ul className="space-y-3 pt-1">
          {AI_CAPABILITY_CATALOG.capabilities.map((capability) => (
            <CapabilityRow key={capability.name} capability={capability} />
          ))}
        </ul>
      )}

      <button
        type="button"
        onClick={() => setExpanded((v) => !v)}
        aria-expanded={expanded}
        className="flex w-full items-center justify-between rounded-xl py-1.5 text-left"
      >
        <span className="text-[12.5px] font-semibold text-accent-ink">
          {expanded ? "Hide the full breakdown" : "See the full breakdown"}
        </span>
        {expanded ? <ChevronUpIcon width={18} height={18} className="text-accent-ink" /> : <ChevronDownIcon width={18} height={18} className="text-accent-ink" />}
      </button>
    </section>
  );
}
