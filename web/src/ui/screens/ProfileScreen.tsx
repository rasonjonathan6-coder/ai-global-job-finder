import { useRef, useState } from "react";
import { Badge } from "../components/Badge";
import { ScreenHeader } from "../components/ScreenHeader";
import { useTheme } from "../theme/ThemeProvider";
import type { ThemeMode } from "../theme/theme-mode";
import { useAppStore } from "../../state/app-store";
import { profileCompletion } from "../../state/demo-profile";
import { cx } from "../lib/cx";
import {
  CheckIcon,
  ChevronRightIcon,
  FileTextIcon,
  GlobeIcon,
  MailIcon,
  MoonIcon,
  PhoneIcon,
  PinIcon,
  ShieldIcon,
  SunIcon,
} from "../components/icons";

const THEME_OPTIONS: { mode: ThemeMode; label: string; icon: React.ReactNode }[] = [
  { mode: "light", label: "Light", icon: <SunIcon width={16} height={16} /> },
  { mode: "dark", label: "Dark", icon: <MoonIcon width={16} height={16} /> },
  { mode: "system", label: "System", icon: <GlobeIcon width={16} height={16} /> },
];

/**
 * Profile and preferences, bound to the live profile so the CV summary, contact details and
 * completion ring always match what the matching engines actually read.
 */
export function ProfileScreen() {
  const { mode, setMode } = useTheme();
  const {
    profile,
    profileIsImported,
    cvFileName,
    importCv,
    jobs,
    autoApplyEnabled,
    setAutoApplyEnabled,
    demoMode,
    setDemoMode,
    refreshJobs,
  } = useAppStore();
  const fileRef = useRef<HTMLInputElement>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [importError, setImportError] = useState<string | null>(null);

  const analysed = profileIsImported;
  const completion = profileCompletion(profile);
  const initials =
    profile.fullName
      .trim()
      .split(/\s+/)
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase() ?? "")
      .join("") || "AI";

  /**
   * Reads the chosen CV on-device and runs the analyser. Only text formats are parsed here;
   * a PDF/DOCX is accepted but reported as not yet readable rather than silently ignored, so
   * the user is never told their CV was read when it was not.
   */
  const onPickFile = async (file: File) => {
    setImportError(null);
    const readable = /\.(txt|md|csv|json)$/i.test(file.name) || file.type.startsWith("text/");
    if (!readable) {
      setImportError(
        `${file.name} is a ${file.name.split(".").pop()?.toUpperCase() ?? "binary"} file. Paste the text of your CV below for now — PDF and DOCX parsing is not wired into the web edition yet.`,
      );
      return;
    }
    try {
      const text = await file.text();
      const summary = importCv(text, file.name);
      if (!summary.extractedAnything) {
        setImportError(
          "That document did not contain a name plus a role or skills, so nothing was imported. Check you picked your CV, or paste the text below.",
        );
        return;
      }
      setNotice(
        `Imported ${file.name} · ${summary.confidence}% of your profile read` +
          (summary.warnings.length > 0 ? ` · ${summary.warnings.length} note(s) to review` : ""),
      );
    } catch {
      setImportError("That file could not be read. Try a different copy of your CV.");
    }
  };

  const onPasteText = () => {
    const text = window.prompt("Paste the text of your CV:");
    if (!text) return;
    setImportError(null);
    const summary = importCv(text, "Pasted CV");
    if (!summary.extractedAnything) {
      setImportError(
        "That text did not contain a name plus a role or skills, so nothing was imported.",
      );
      return;
    }
    setNotice(`Imported pasted text · ${summary.confidence}% of your profile read`);
  };

  const rows = [
    { icon: <MailIcon width={17} height={17} />, label: "Email", value: profile.email || "Not set" },
    { icon: <PhoneIcon width={17} height={17} />, label: "Phone", value: profile.phone || "Not set" },
    { icon: <PinIcon width={17} height={17} />, label: "Location", value: profile.location || "Not set" },
  ];

  const settings = [
    { icon: <FileTextIcon width={17} height={17} />, label: "Import or update CV", action: () => fileRef.current?.click() },
    { icon: <FileTextIcon width={17} height={17} />, label: "Paste CV text", action: onPasteText },
    { icon: <GlobeIcon width={17} height={17} />, label: "Preferred countries", action: () => setNotice("Preferred countries come from your CV in this edition.") },
    { icon: <ShieldIcon width={17} height={17} />, label: "Scam warning settings", action: () => setNotice("Scam warnings are on by default and cannot be switched off.") },
  ];

  return (
    <>
      <ScreenHeader title="Profile" subtitle="Your CV and preferences" />
      <div className="app-shell space-y-5">
        <section className="card-surface flex items-center gap-4 p-4">
          <span className="flex h-14 w-14 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-accent-500 to-teal-600 text-[18px] font-extrabold text-white">
            {initials}
          </span>
          <div className="min-w-0 flex-1">
            <p className="truncate text-[16px] font-bold text-ink-primary">
              {profile.fullName || "No CV imported yet"}
            </p>
            <p className="truncate text-[12.5px] text-ink-secondary">
              {analysed
                ? `${profile.currentTitle || "Title not stated"}${
                    profile.yearsOfExperience != null ? ` · ${profile.yearsOfExperience} years` : ""
                  }`
                : "Import your CV to build your profile"}
            </p>
            <div className="mt-2 flex flex-wrap gap-1.5">
              <Badge tone={analysed ? "success" : "warning"} icon={analysed ? <CheckIcon width={12} height={12} /> : undefined}>
                {analysed ? "CV analysed" : "No CV yet"}
              </Badge>
              <Badge tone="accent">{completion}% complete</Badge>
            </div>
          </div>
        </section>

        <section aria-label="CV file" className="card-surface p-4">
          <div className="flex items-center justify-between gap-3">
            <div className="min-w-0">
              <p className="text-[14px] font-bold text-ink-primary">{analysed ? "CV on file" : "No CV on file"}</p>
              <p className="mt-0.5 truncate text-[12.5px] text-ink-secondary">
                {cvFileName ?? "Import a TXT file or paste your CV text to unlock matching"}
              </p>
            </div>
            <button
              type="button"
              onClick={() => fileRef.current?.click()}
              className="shrink-0 rounded-xl bg-accent-500 px-3.5 py-2 text-[12.5px] font-semibold text-white transition-transform active:scale-[0.97]"
            >
              {analysed ? "Replace" : "Import CV"}
            </button>
          </div>
        </section>

        <section aria-label="Contact details" className="card-surface divide-y divide-card-stroke p-0">
          {rows.map((row) => (
            <div key={row.label} className="flex items-center gap-3 px-4 py-3.5">
              <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-surface-variant text-ink-secondary">
                {row.icon}
              </span>
              <div className="min-w-0 flex-1">
                <p className="text-[11.5px] font-medium text-ink-tertiary">{row.label}</p>
                <p className="truncate text-[13.5px] font-semibold text-ink-primary">{row.value}</p>
              </div>
            </div>
          ))}
        </section>

        <section aria-label="Skills" className="card-surface p-4">
          <div className="flex items-center justify-between">
            <p className="text-[14px] font-bold text-ink-primary">Skills the engine reads</p>
            <span className="text-[11.5px] text-ink-tertiary">{profile.skills.length} skills</span>
          </div>
          {profile.skills.length > 0 ? (
            <div className="mt-3 flex flex-wrap gap-1.5">
              {profile.skills.slice(0, 12).map((skill) => (
                <span
                  key={skill}
                  className="rounded-full bg-surface-variant px-2.5 py-1 text-[11.5px] font-medium text-ink-secondary"
                >
                  {skill}
                </span>
              ))}
            </div>
          ) : (
            <p className="mt-3 rounded-xl bg-surface-variant px-3.5 py-3 text-[12.5px] leading-relaxed text-ink-secondary">
              No skills yet. The engine only reads skills it can find in your CV — nothing is
              guessed — so import your CV to fill this in.
            </p>
          )}
        </section>

        <section aria-label="Appearance" className="card-surface p-4">
          <p className="text-[14px] font-bold text-ink-primary">Appearance</p>
          <p className="mt-0.5 text-[12.5px] text-ink-secondary">
            The dark theme is designed, not inverted — cards, text and icons stay polished.
          </p>
          <div className="mt-3 grid grid-cols-3 gap-2">
            {THEME_OPTIONS.map((option) => {
              const active = option.mode === mode;
              return (
                <button
                  key={option.mode}
                  type="button"
                  onClick={() => setMode(option.mode)}
                  aria-pressed={active}
                  className={cx(
                    "flex flex-col items-center gap-1.5 rounded-2xl border py-3 text-[12.5px] font-semibold transition-all active:scale-[0.97]",
                    active
                      ? "border-accent-500 bg-accent-500/[0.10] text-accent-ink"
                      : "border-card-stroke bg-surface text-ink-secondary hover:bg-surface-variant",
                  )}
                >
                  {option.icon}
                  {option.label}
                </button>
              );
            })}
          </div>
        </section>

        <section aria-label="Auto-apply" className="card-surface p-4">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <p className="text-[14px] font-bold text-ink-primary">Auto-apply mode</p>
              <p className="mt-0.5 text-[12.5px] leading-relaxed text-ink-secondary">
                Optional. When on, the assistant prepares applications for strong matches. Nothing is ever
                submitted without your confirmation.
              </p>
            </div>
            <button
              type="button"
              role="switch"
              aria-checked={autoApplyEnabled}
              aria-label="Auto-apply mode"
              onClick={() => setAutoApplyEnabled(!autoApplyEnabled)}
              className={cx(
                "relative mt-0.5 h-6 w-11 shrink-0 rounded-full transition-colors",
                autoApplyEnabled ? "bg-accent-500" : "bg-surface-variant",
              )}
            >
              <span
                className={cx(
                  "absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-all",
                  autoApplyEnabled ? "left-[22px]" : "left-0.5",
                )}
              />
            </button>
          </div>
        </section>

        <section aria-label="Data source" className="card-surface p-4">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <p className="text-[14px] font-bold text-ink-primary">Demo fixtures</p>
              <p className="mt-0.5 text-[12.5px] leading-relaxed text-ink-secondary">
                Show a curated set of postings instead of the live feeds. Useful offline; the source
                strip is labelled so demo data is never mistaken for a live result.
              </p>
            </div>
            <button
              type="button"
              role="switch"
              aria-checked={demoMode}
              aria-label="Demo fixtures"
              onClick={() => {
                const next = !demoMode;
                setDemoMode(next);
                void refreshJobs(next);
              }}
              className={cx(
                "relative mt-0.5 h-6 w-11 shrink-0 rounded-full transition-colors",
                demoMode ? "bg-accent-500" : "bg-surface-variant",
              )}
            >
              <span
                className={cx(
                  "absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-all",
                  demoMode ? "left-[22px]" : "left-0.5",
                )}
              />
            </button>
          </div>
        </section>

        <section aria-label="Settings" className="card-surface divide-y divide-card-stroke p-0">
          {settings.map((item) => (
            <button
              key={item.label}
              type="button"
              onClick={item.action}
              className="flex w-full items-center gap-3 px-4 py-3.5 text-left transition-colors hover:bg-surface-variant active:bg-surface-variant"
            >
              <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-surface-variant text-ink-secondary">
                {item.icon}
              </span>
              <span className="flex-1 text-[13.5px] font-semibold text-ink-primary">{item.label}</span>
              <ChevronRightIcon width={17} height={17} className="text-ink-tertiary" />
            </button>
          ))}
        </section>

        {importError && (
          <p className="rounded-2xl bg-warning-container px-4 py-3 text-[12.5px] leading-relaxed text-warning">
            {importError}
          </p>
        )}

        {notice && (
          <p className="rounded-2xl bg-info-container px-4 py-3 text-[12.5px] leading-relaxed text-info">
            {notice}
          </p>
        )}

        <p className="pb-2 text-center text-[11.5px] text-ink-tertiary">
          AI Global Job Finder · Web edition · {jobs.length} live opportunities indexed
        </p>
      </div>

      <input
        ref={fileRef}
        type="file"
        accept=".txt,.md,.csv,.json,.pdf,.doc,.docx"
        className="hidden"
        onChange={(event) => {
          const file = event.target.files?.[0];
          if (file) void onPickFile(file);
          if (fileRef.current) fileRef.current.value = "";
        }}
      />
    </>
  );
}
