import { useEffect, useMemo, useState } from "react";
import {
  LETTER_LANGUAGE_META,
  LetterLanguage,
  coverLetterPlainText,
  coverLetterWordCount,
  letterLanguageForCountry,
  type CoverLetter,
} from "../../core/models";
import { Badge } from "../components/Badge";
import { Button } from "../components/Button";
import { EmptyState } from "../components/EmptyState";
import { SecondaryHeader } from "../components/SecondaryHeader";
import { useAppStore } from "../../state/app-store";
import { cx } from "../lib/cx";
import { relativeTimeFrom } from "../lib/format";
import { CheckIcon, FileTextIcon, SparkIcon, WarningIcon } from "../components/icons";

const LANGUAGES: LetterLanguage[] = [
  LetterLanguage.ENGLISH,
  LetterLanguage.FRENCH,
  LetterLanguage.GERMAN,
];

/**
 * The cover-letter writer for one posting. The letter is produced by the ported
 * `CoverLetterGenerator` from the user's own CV and the posting, and the grounding card shows
 * exactly which facts it used and which it could not find — so a missing detail is visible
 * instead of being papered over with an invented sentence.
 */
export function CoverLetterScreen({ jobId, onBack }: { jobId: string; onBack: () => void }) {
  const { jobs, refresh, refreshJobs, profile, generateCoverLetter, coverLetterFor } = useAppStore();

  // Populate the feed on first open, like the other job-scoped screens.
  useEffect(() => {
    if (refresh.status === "idle") void refreshJobs();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // A blank id means "write a letter for my best current match", mirroring the other job-scoped
  // screens (the AI tab opens the writer with no posting chosen).
  const job = useMemo(
    () =>
      jobId
        ? jobs.find((j) => j.id === jobId) ?? null
        : jobs.reduce<(typeof jobs)[number] | null>((best, j) => (!best || j.matchScore > best.matchScore ? j : best), null),
    [jobs, jobId],
  );
  const resolvedId = job?.id ?? "";

  const [language, setLanguage] = useState<LetterLanguage>(() =>
    job ? letterLanguageForCountry(job.country) : LetterLanguage.ENGLISH,
  );
  const [letter, setLetter] = useState<CoverLetter | null>(() => coverLetterFor(resolvedId));
  const [copied, setCopied] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  // Load any letter already stored for the resolved posting, mirroring the Android collector
  // that restores the latest letter for the job once the feed has loaded.
  useEffect(() => {
    if (!resolvedId) return;
    const stored = coverLetterFor(resolvedId);
    if (stored) setLetter(stored);
  }, [resolvedId, coverLetterFor]);

  if (!job) {
    return (
      <>
        <SecondaryHeader title="Cover letter" subtitle="Tailored letter" onBack={onBack} />
        <div className="app-shell">
          <EmptyState
            title="Posting unavailable"
            message="This posting is no longer in your feed, so there is nothing to write a letter for."
            actionLabel="Back to jobs"
            onAction={onBack}
          />
        </div>
      </>
    );
  }

  const generate = () => {
    // Mirror the Android `CoverLetterViewModel.generate`: refuse to draft from an empty CV so
    // the letter cannot be padded with invented sentences.
    if (profile.fullName.trim() === "" && profile.skills.length === 0) {
      setMessage("Import and analyse your CV first, so the letter can use your real details.");
      return;
    }
    setMessage(null);
    setLetter(generateCoverLetter(resolvedId, language));
  };

  const copy = async () => {
    if (!letter) return;
    try {
      await navigator.clipboard.writeText(coverLetterPlainText(letter));
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      setCopied(false);
    }
  };

  return (
    <>
      <SecondaryHeader title="Cover letter" subtitle={job.title} onBack={onBack} />

      <div className="app-shell space-y-4">
        {message && (
          <div className="animate-fade-in flex items-start gap-2 rounded-2xl bg-warning-container px-3.5 py-3">
            <WarningIcon width={16} height={16} className="mt-0.5 shrink-0 text-warning" />
            <p className="text-[12.5px] leading-relaxed text-ink-secondary">{message}</p>
          </div>
        )}

        <section className="card-surface p-4">
          <h2 className="text-[14px] font-bold text-ink-primary">Language</h2>
          <p className="text-[12px] text-ink-secondary">
            Picked for the employer&apos;s market — change it if you prefer
          </p>
          <div className="mt-3 grid grid-cols-3 gap-2">
            {LANGUAGES.map((lang) => {
              const active = lang === language;
              return (
                <button
                  key={lang}
                  type="button"
                  onClick={() => setLanguage(lang)}
                  aria-pressed={active}
                  className={cx(
                    "flex flex-col items-center gap-0.5 rounded-2xl border py-2.5 transition-colors",
                    active
                      ? "border-accent-500 bg-accent-500/[0.12] text-accent-ink"
                      : "border-card-stroke bg-surface-variant text-ink-secondary hover:bg-surface",
                  )}
                >
                  <span className="text-[18px]" aria-hidden="true">{LETTER_LANGUAGE_META[lang].flag}</span>
                  <span className="text-[12px] font-semibold">{LETTER_LANGUAGE_META[lang].label}</span>
                </button>
              );
            })}
          </div>
        </section>

        <Button fullWidth leadingIcon={<SparkIcon width={16} height={16} />} onClick={generate}>
          {letter ? "Generate again" : "Generate cover letter"}
        </Button>

        {letter ? (
          <>
            <LetterPreview letter={letter} />
            <Button fullWidth variant="secondary" leadingIcon={copied ? <CheckIcon width={16} height={16} /> : undefined} onClick={() => void copy()}>
              {copied ? "Copied" : "Copy to clipboard"}
            </Button>
            <GroundingCard letter={letter} />
          </>
        ) : (
          <EmptyState
            title="No letter yet"
            message={
              profile.fullName.trim() !== "" || profile.skills.length > 0
                ? "Generate a letter tailored to this role and language."
                : "Import and analyse your CV first, so the letter can use your real details."
            }
          />
        )}
      </div>
    </>
  );
}

function LetterPreview({ letter }: { letter: CoverLetter }) {
  return (
    <section className="card-surface animate-fade-up p-4">
      <div className="flex items-center gap-3">
        <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-accent-500/[0.14] text-accent-ink">
          <FileTextIcon width={19} height={19} />
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-[13.5px] font-bold text-ink-primary">{LETTER_LANGUAGE_META[letter.language].label}</p>
          <p className="text-[12px] text-ink-secondary">
            {coverLetterWordCount(letter)} words · generated {relativeTimeFrom(letter.generatedAtEpochMillis)}
          </p>
        </div>
        {letter.edited && <Badge tone="info">Edited</Badge>}
      </div>

      <div className="mt-4 space-y-2.5 text-[13px] leading-relaxed text-ink-secondary">
        <p className="font-semibold text-ink-primary">{letter.subject}</p>
        <p className="text-ink-primary">{letter.salutation}</p>
        {letter.paragraphs.map((paragraph) => (
          <p key={paragraph}>{paragraph}</p>
        ))}
        <p className="text-ink-primary">{letter.closing}</p>
        <p className="font-semibold text-ink-primary">{letter.signature}</p>
      </div>
    </section>
  );
}

function GroundingCard({ letter }: { letter: CoverLetter }) {
  return (
    <section className="card-surface p-4">
      <h2 className="text-[14px] font-bold text-ink-primary">Built from your CV</h2>
      <p className="text-[12px] text-ink-secondary">Facts this letter used, and what it could not find</p>

      <ul className="mt-3 space-y-1.5">
        {letter.usedFacts.map((fact) => (
          <li key={fact} className="flex items-start gap-2">
            <CheckIcon width={15} height={15} className="mt-0.5 shrink-0 text-success" />
            <span className="text-[12.5px] leading-relaxed text-ink-secondary">{fact}</span>
          </li>
        ))}
      </ul>

      {letter.missingFacts.length > 0 && (
        <>
          <p className="mt-3 text-[12px] font-semibold text-ink-secondary">
            Missing — add these to your CV to strengthen the letter
          </p>
          <ul className="mt-1.5 space-y-1.5">
            {letter.missingFacts.map((fact) => (
              <li key={fact} className="flex items-start gap-2">
                <WarningIcon width={15} height={15} className="mt-0.5 shrink-0 text-warning" />
                <span className="text-[12.5px] leading-relaxed text-ink-secondary">{fact}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
