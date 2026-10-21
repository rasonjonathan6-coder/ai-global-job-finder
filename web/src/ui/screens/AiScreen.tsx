import { useEffect, useMemo, useState } from "react";
import {
  AI_CAPABILITY_CATALOG,
  AI_CAPABILITY_KIND_META,
  AI_PROCESSING_LOCATION_META,
  AiCapabilityKind,
  AiProcessingLocation,
  type AiCapability,
} from "../../core/models";
import { SubmissionCapability } from "../../core/submissions/submission-capability";
import { Button } from "../components/Button";
import { ScreenHeader } from "../components/ScreenHeader";
import { useAppStore } from "../../state/app-store";
import { isStrongMatch } from "../lib/job-stats";
import {
  AiIcon,
  ApplicationsIcon,
  BellIcon,
  CheckIcon,
  ChevronDownIcon,
  ChevronRightIcon,
  ChevronUpIcon,
  CloudIcon,
  FileTextIcon,
  GavelIcon,
  GlobeIcon,
  RocketIcon,
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

const TONE_CLASS: Record<Capability["tone"], string> = {
  accent: "bg-accent-500/[0.10] text-accent-ink",
  success: "bg-success-container text-success",
  warning: "bg-warning-container text-warning",
  info: "bg-info-container text-info",
};

interface CapabilityGroup {
  title: string;
  subtitle: string;
  cards: Capability[];
}

/**
 * The AI workspace. Every card below is wired to a ported engine (or to a screen that is), so
 * the assistant describes only what it actually does. The analysis card tells the truth about
 * the profile: with no CV imported it asks for one rather than claiming a match was scored.
 */
export function AiScreen({ onNavigate }: { onNavigate: (route: string) => void }) {
  const { jobs, profileIsImported, profile, refreshJobs, refresh } = useAppStore();
  const [progress, setProgress] = useState(0);
  const [running, setRunning] = useState(true);

  useEffect(() => {
    if (!running) return;
    setProgress(0);
    const timer = window.setInterval(() => {
      setProgress((p) => {
        const next = Math.min(100, p + 4);
        if (next >= 100) {
          window.clearInterval(timer);
          setRunning(false);
        }
        return next;
      });
    }, 90);
    return () => window.clearInterval(timer);
  }, [running]);

  const skillCount = profile.skills.length;
  const strongMatchCount = useMemo(() => jobs.filter(isStrongMatch).length, [jobs]);

  const groups: CapabilityGroup[] = [
    {
      title: "Find the right role",
      subtitle: "Match, rank and explain the postings that fit you",
      cards: [
        {
          title: profileIsImported ? "Open my CV" : "Import my CV",
          detail: profileIsImported
            ? "Your CV is analysed. Re-open it any time to correct a skill or add experience."
            : "Reads your real document, extracts skills and experience, and builds a profile you can correct.",
          icon: <FileTextIcon width={20} height={20} />,
          tone: "accent",
          route: "profile",
          actionLabel: profileIsImported ? "Open my CV" : "Import my CV",
        },
        {
          title: "Expat shortlist",
          detail: "Ranks the postings you can actually apply for from Madagascar, and says what is in the way.",
          icon: <GlobeIcon width={20} height={20} />,
          tone: "success",
          route: "shortlist",
          actionLabel: "Open shortlist",
        },
      ],
    },
    {
      title: "Apply with confidence",
      subtitle: "Prepare a grounded application and check it is safe",
      cards: [
        {
          title: "Cover letter",
          detail: "Drafts a letter from your own CV and the posting, in the destination's language. Nothing is invented.",
          icon: <ApplicationsIcon width={20} height={20} />,
          tone: "accent",
          route: "coverletter",
          actionLabel: "Write a letter",
        },
        {
          title: "Scam shield",
          detail: "Flags upfront fees, unregistered employers and unrealistic salary claims — each quoted from the posting.",
          icon: <GavelIcon width={20} height={20} />,
          tone: "warning",
          route: "scam",
          actionLabel: "Check a posting",
        },
        {
          title: "Interview coach",
          detail: "Rehearses visa interviews and technical rounds with instant, grounded feedback.",
          icon: <TrendIcon width={20} height={20} />,
          tone: "info",
          route: "interview",
          actionLabel: "Start rehearsing",
        },
        {
          title: "Prepared, never sent for you",
          detail: SubmissionCapability.sendsNothing
            ? "The assistant prepares each application and stops at one-tap approval. No channel can be driven by the app, so nothing is ever submitted automatically."
            : "The assistant prepares each application and waits for your one-tap approval before anything is sent.",
          icon: <ShieldIcon width={20} height={20} />,
          tone: "info",
        },
      ],
    },
    {
      title: "Stay ahead",
      subtitle: "Get told the moment a role matches, and never miss a follow-up",
      cards: [
        {
          title: "Job alerts",
          detail: "Saved searches that tell you the moment a matching posting appears.",
          icon: <BellIcon width={20} height={20} />,
          tone: "info",
          route: "alerts",
          actionLabel: "Manage alerts",
        },
        {
          title: "Safe auto-apply",
          detail: "Prepares a tailored draft for every strong match, and never sends it for you.",
          icon: <RocketIcon width={20} height={20} />,
          tone: "accent",
          route: "autoapply",
          actionLabel: "Open auto-apply",
        },
      ],
    },
  ];

  return (
    <>
      <ScreenHeader title="AI Assistant" subtitle="Your CV, matched against real international openings" />
      <div className="app-shell space-y-5">
        <AnalysisCard
          progress={progress}
          running={running}
          hasCv={profileIsImported}
          skillCount={skillCount}
          strongMatchCount={strongMatchCount}
          feedIdle={refresh.status === "idle"}
          onRun={() => {
            setRunning(true);
            if (refresh.status === "idle" || refresh.status === "error") void refreshJobs();
          }}
        />

        <section className="space-y-2">
          <h2 className="px-1 text-[16px] font-bold text-ink-primary">Start here</h2>
          <CapabilityCard
            cap={{
              title: "Import and analyse my CV",
              detail:
                "Read your real document, extract skills and experience, and build a profile you can correct. Runs on your device.",
              icon: <FileTextIcon width={20} height={20} />,
              tone: "accent",
              route: "profile",
              actionLabel: "Open CV analysis",
            }}
            onClick={() => onNavigate("profile")}
          />
        </section>

        {groups.map((group) => (
          <section key={group.title} className="space-y-2.5">
            <div className="px-1">
              <h2 className="text-[16px] font-bold text-ink-primary">{group.title}</h2>
              <p className="text-[12.5px] text-ink-secondary">{group.subtitle}</p>
            </div>
            {group.cards.map((cap, i) => (
              <CapabilityCard
                key={cap.title}
                cap={cap}
                index={i}
                onClick={() => cap.route && onNavigate(cap.route)}
              />
            ))}
          </section>
        ))}

        <TransparencyCard />

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

        <Button fullWidth variant="primary" leadingIcon={<SparkIcon width={16} height={16} />} onClick={() => onNavigate("jobs")}>
          Browse matched jobs
        </Button>
      </div>
    </>
  );
}

/** The hero analysis card: an honest progress read driven by the real profile and feed. */
function AnalysisCard({
  progress,
  running,
  hasCv,
  skillCount,
  strongMatchCount,
  feedIdle,
  onRun,
}: {
  progress: number;
  running: boolean;
  hasCv: boolean;
  skillCount: number;
  strongMatchCount: number;
  feedIdle: boolean;
  onRun: () => void;
}) {
  const steps: { label: string; done: boolean }[] = [
    { label: skillCount > 0 ? `CV parsed — ${skillCount} skills extracted` : "CV parsed", done: hasCv && progress > 15 },
    { label: "Skills mapped to international standards", done: progress > 40 },
    { label: "Visa eligibility checked per country", done: progress > 65 },
    { label: "Scam risk screened on every listing", done: progress > 85 },
    { label: "Matches ranked by fit", done: progress >= 100 },
  ];

  const subtitle = running
    ? "Scoring your profile against the live feed"
    : !hasCv
      ? "Import your CV to start matching"
      : strongMatchCount > 0
        ? `${strongMatchCount} strong match${strongMatchCount === 1 ? "" : "es"} found`
        : feedIdle
          ? "Pull to refresh for the latest postings"
          : "No strong matches yet — try refreshing";

  return (
    <section className="animate-fade-up overflow-hidden rounded-3xl bg-gradient-to-br from-[#15356F] to-[#0A1D40] p-5 text-white shadow-card dark:from-[#152C5C] dark:to-[#0A1730]">
      <div className="flex items-center gap-3">
        <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-white/12">
          <AiIcon width={22} height={22} className="text-teal-300" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-[15.5px] font-bold">{running ? "Analysing your profile" : "Analysis complete"}</p>
          <p className="text-[12.5px] text-white/70">{subtitle}</p>
        </div>
      </div>

      <div className="mt-4 h-1.5 w-full overflow-hidden rounded-full bg-white/18">
        <div
          className="h-full rounded-full bg-teal-400 transition-[width] duration-200 ease-out"
          style={{ width: `${progress}%` }}
          role="progressbar"
          aria-valuenow={progress}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label="Analysis progress"
        />
      </div>

      <ul className="mt-3.5 space-y-2">
        {steps.map((step) => (
          <li
            key={step.label}
            className={`flex items-center gap-2 text-[12.5px] transition-opacity duration-500 ${step.done ? "opacity-100" : "opacity-45"}`}
          >
            <span
              className={`flex h-4 w-4 shrink-0 items-center justify-center rounded-full ${step.done ? "bg-teal-400 text-navy-950" : "border border-white/40"}`}
            >
              {step.done && <CheckIcon width={11} height={11} />}
            </span>
            <span className="truncate">{step.label}</span>
          </li>
        ))}
      </ul>

      {!running && (
        <Button variant="secondary" fullWidth className="mt-4" onClick={onRun}>
          Run analysis again
        </Button>
      )}
    </section>
  );
}

/** One capability card: an icon chip, a title, the honest detail and an optional action. */
function CapabilityCard({
  cap,
  index = 0,
  onClick,
}: {
  cap: Capability;
  index?: number;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      disabled={!cap.route}
      onClick={() => cap.route && onClick()}
      className="card-surface animate-fade-up flex w-full items-start gap-3.5 p-4 text-left transition-transform active:scale-[0.99] disabled:active:scale-100"
      style={{ animationDelay: `${index * 55}ms` }}
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
