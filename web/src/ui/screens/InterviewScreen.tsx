import { useEffect, useMemo, useState } from "react";
import {
  ANSWER_CONFIDENCE_META,
  AnswerConfidence,
  INTERVIEW_CATEGORY_META,
  INTERVIEW_CATEGORY_ORDER,
  type AnswerFeedback,
  type InterviewQuestion,
} from "../../core/models";
import { Button } from "../components/Button";
import { EmptyState } from "../components/EmptyState";
import { SecondaryHeader } from "../components/SecondaryHeader";
import { Skeleton } from "../components/Skeleton";
import { useAppStore } from "../../state/app-store";
import { cx } from "../lib/cx";
import { CheckCircleIcon, ChevronDownIcon, SparkIcon, WarningIcon } from "../components/icons";

const CONFIDENCE_ORDER: AnswerConfidence[] = [
  AnswerConfidence.NOT_READY,
  AnswerConfidence.GETTING_THERE,
  AnswerConfidence.CONFIDENT,
];

/**
 * The interview coach for one posting. The rehearsal set, the feedback and the readiness score
 * all come from the ported `InterviewQuestionBank`, so the screen only presents what the engine
 * derived from the CV and the posting — it never invents a question or a grade.
 */
export function InterviewScreen({ jobId, onBack }: { jobId: string; onBack: () => void }) {
  const {
    jobs,
    refresh,
    refreshJobs,
    interviewPrep,
    gradeInterviewAnswer,
    recordInterviewAttempt,
    resetInterviewAttempts,
    interviewReadiness,
    interviewAttempts,
  } = useAppStore();

  // Populate the feed on first open, like the other job-scoped screens.
  useEffect(() => {
    if (refresh.status === "idle") void refreshJobs();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // A blank id means "coach me for my best current match", mirroring the Android
  // `InterviewViewModel` (the AI tab opens the coach with no posting chosen).
  const job = useMemo(
    () =>
      jobId
        ? jobs.find((j) => j.id === jobId) ?? null
        : jobs.reduce<(typeof jobs)[number] | null>((best, j) => (!best || j.matchScore > best.matchScore ? j : best), null),
    [jobs, jobId],
  );
  const resolvedId = job?.id ?? "";
  const prep = useMemo(() => (job ? interviewPrep(resolvedId) : null), [job, resolvedId, interviewPrep]);
  const attempts = useMemo(() => interviewAttempts(resolvedId), [resolvedId, interviewAttempts]);
  const readiness = useMemo(() => interviewReadiness(resolvedId), [resolvedId, interviewReadiness]);

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const [confidence, setConfidence] = useState<AnswerConfidence>(AnswerConfidence.GETTING_THERE);
  const [feedback, setFeedback] = useState<AnswerFeedback | null>(null);

  const grouped = useMemo(() => {
    if (!prep) return [];
    const byCategory = new Map<InterviewQuestion["category"], InterviewQuestion[]>();
    for (const q of prep.questions) {
      const list = byCategory.get(q.category) ?? [];
      list.push(q);
      byCategory.set(q.category, list);
    }
    return INTERVIEW_CATEGORY_ORDER.filter((c) => byCategory.has(c)).map((c) => ({
      category: c,
      questions: byCategory.get(c)!,
    }));
  }, [prep]);

  if (!job || !prep) {
    return (
      <>
        <SecondaryHeader title="Interview coach" subtitle="Rehearsal" onBack={onBack} />
        <div className="app-shell">
          <EmptyState
            title="No interview plan yet"
            message="This posting is no longer in your feed, so the coach has nothing to rehearse."
            onAction={onBack}
            actionLabel="Back to jobs"
          />
        </div>
      </>
    );
  }

  const toggle = (id: string) => {
    setSelectedId((current) => (current === id ? null : id));
    setDraft("");
    setFeedback(null);
  };

  const submit = (question: InterviewQuestion) => {
    setFeedback(gradeInterviewAnswer(question, draft));
    recordInterviewAttempt(resolvedId, question.id, confidence);
  };

  return (
    <>
      <SecondaryHeader
        title={prep.jobTitle}
        subtitle={prep.company}
        onBack={onBack}
        action={
          attempts.length > 0 ? (
            <button
              type="button"
              onClick={() => resetInterviewAttempts(resolvedId)}
              aria-label="Reset rehearsal progress"
              className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full border border-card-stroke bg-surface text-ink-secondary transition-colors hover:text-accent-ink active:scale-95"
            >
              <SparkIcon width={18} height={18} />
            </button>
          ) : undefined
        }
      />

      <div className="app-shell space-y-4">
        <ReadinessCard
          label={readiness.label}
          attempted={readiness.attempted}
          total={readiness.total}
          confident={readiness.confident}
          percent={readiness.percent}
        />

        {prep.focusAreas.length > 0 && (
          <section className="card-surface p-4">
            <h2 className="text-[14px] font-bold text-ink-primary">Focus on this</h2>
            <p className="text-[12px] text-ink-secondary">Priorities drawn from your CV and this posting</p>
            <ul className="mt-3 space-y-2">
              {prep.focusAreas.map((area) => (
                <li key={area} className="flex items-start gap-2">
                  <WarningIcon width={15} height={15} className="mt-0.5 shrink-0 text-warning" />
                  <span className="text-[12.5px] leading-relaxed text-ink-secondary">{area}</span>
                </li>
              ))}
            </ul>
          </section>
        )}

        <div className="px-1">
          <h2 className="text-[16px] font-bold text-ink-primary">Rehearsal questions</h2>
          <p className="text-[12.5px] text-ink-secondary">
            {prep.questions.length} questions, grounded in your CV
          </p>
        </div>

        {grouped.map(({ category, questions }) => (
          <section key={category} className="space-y-2.5">
            <h3 className="px-1 text-[13.5px] font-bold text-ink-primary">
              {INTERVIEW_CATEGORY_META[category].emoji} {INTERVIEW_CATEGORY_META[category].label}
            </h3>
            {questions.map((question) => (
              <QuestionCard
                key={question.id}
                question={question}
                expanded={selectedId === question.id}
                answered={attempts.some((a) => a.questionId === question.id)}
                draft={selectedId === question.id ? draft : ""}
                feedback={selectedId === question.id ? feedback : null}
                confidence={confidence}
                onToggle={() => toggle(question.id)}
                onDraftChange={setDraft}
                onConfidenceChange={setConfidence}
                onSubmit={() => submit(question)}
              />
            ))}
          </section>
        ))}

        {attempts.length > 0 && (
          <Button
            fullWidth
            variant="secondary"
            onClick={() => resetInterviewAttempts(resolvedId)}
          >
            Reset rehearsal progress
          </Button>
        )}
      </div>
    </>
  );
}

/** The readiness gauge: the label, the rehearsal counts and a progress bar, all derived. */
function ReadinessCard({
  label,
  attempted,
  total,
  confident,
  percent,
}: {
  label: string;
  attempted: number;
  total: number;
  confident: number;
  percent: number;
}) {
  return (
    <section className="card-surface animate-fade-up p-4">
      <div className="flex items-center gap-3">
        <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-accent-500/[0.14] text-accent-ink">
          <SparkIcon width={22} height={22} />
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-[15px] font-bold text-ink-primary">{label}</p>
          <p className="text-[12.5px] text-ink-secondary">
            {attempted} of {total} rehearsed · {confident} marked confident
          </p>
        </div>
      </div>
      <div className="mt-3 h-2 w-full overflow-hidden rounded-full bg-surface-variant">
        <div
          className="h-full rounded-full bg-accent-500 transition-[width] duration-500 ease-out"
          style={{ width: `${percent}%` }}
          role="progressbar"
          aria-valuenow={percent}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label="Rehearsal progress"
        />
      </div>
    </section>
  );
}

function QuestionCard({
  question,
  expanded,
  answered,
  draft,
  feedback,
  confidence,
  onToggle,
  onDraftChange,
  onConfidenceChange,
  onSubmit,
}: {
  question: InterviewQuestion;
  expanded: boolean;
  answered: boolean;
  draft: string;
  feedback: AnswerFeedback | null;
  confidence: AnswerConfidence;
  onToggle: () => void;
  onDraftChange: (value: string) => void;
  onConfidenceChange: (value: AnswerConfidence) => void;
  onSubmit: () => void;
}) {
  const grounding = question.grounding ?? [];
  const missing = question.missingFacts ?? [];

  return (
    <article className="card-surface overflow-hidden">
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={expanded}
        className="flex w-full items-start gap-2 p-4 text-left"
      >
        <div className="min-w-0 flex-1">
          <p className="text-[14px] font-semibold leading-snug text-ink-primary">{question.prompt}</p>
          <p className="mt-1 text-[12.5px] leading-relaxed text-ink-secondary">{question.whyItMatters}</p>
        </div>
        <span className="mt-0.5 flex shrink-0 items-center gap-1.5">
          {answered && <CheckCircleIcon width={18} height={18} className="text-success" aria-label="Rehearsed" />}
          <ChevronDownIcon
            width={18}
            height={18}
            className={cx("text-ink-tertiary transition-transform duration-200", expanded && "rotate-180")}
          />
        </span>
      </button>

      {expanded && (
        <div className="animate-fade-in space-y-3 px-4 pb-4">
          {grounding.length > 0 && (
            <div>
              <p className="text-[12px] font-semibold text-accent-ink">From your CV</p>
              <ul className="mt-1 space-y-0.5">
                {grounding.map((fact) => (
                  <li key={fact} className="text-[12.5px] text-ink-secondary">• {fact}</li>
                ))}
              </ul>
            </div>
          )}

          {missing.length > 0 && (
            <div className="flex items-start gap-2 rounded-xl bg-warning-container px-3 py-2">
              <WarningIcon width={15} height={15} className="mt-0.5 shrink-0 text-warning" />
              <div>
                {missing.map((fact) => (
                  <p key={fact} className="text-[12px] text-ink-secondary">{fact}</p>
                ))}
              </div>
            </div>
          )}

          <div>
            <p className="text-[12px] font-semibold text-ink-primary">How to answer</p>
            <ol className="mt-1 space-y-0.5">
              {question.structure.map((step, i) => (
                <li key={step} className="text-[12.5px] text-ink-secondary">{i + 1}. {step}</li>
              ))}
            </ol>
          </div>

          <textarea
            value={draft}
            onChange={(e) => onDraftChange(e.target.value)}
            placeholder="Write your answer here, then get instant feedback…"
            rows={4}
            className="w-full resize-y rounded-2xl border border-card-stroke bg-surface-variant p-3 text-[13px] text-ink-primary placeholder:text-ink-tertiary focus-visible:border-accent-500"
          />

          <div className="flex flex-wrap gap-1.5">
            {CONFIDENCE_ORDER.map((option) => {
              const active = option === confidence;
              return (
                <button
                  key={option}
                  type="button"
                  onClick={() => onConfidenceChange(option)}
                  aria-pressed={active}
                  className={cx(
                    "rounded-full border px-3 py-1.5 text-[12px] font-semibold transition-colors",
                    active
                      ? "border-transparent bg-accent-500 text-white"
                      : "border-card-stroke bg-surface text-ink-secondary hover:bg-surface-variant",
                  )}
                >
                  {ANSWER_CONFIDENCE_META[option].label}
                </button>
              );
            })}
          </div>

          <Button fullWidth leadingIcon={<SparkIcon width={16} height={16} />} onClick={onSubmit}>
            Get feedback
          </Button>

          {feedback && <FeedbackBlock feedback={feedback} />}
        </div>
      )}
    </article>
  );
}

/** The graded feedback block, tinted by score band so the colour and the number agree. */
function FeedbackBlock({ feedback }: { feedback: AnswerFeedback }) {
  const tone = feedback.score >= 75 ? "success" : feedback.score >= 45 ? "warning" : "danger";
  const toneText = tone === "success" ? "text-success" : tone === "warning" ? "text-warning" : "text-danger";
  const toneBg = tone === "success" ? "bg-success-container" : tone === "warning" ? "bg-warning-container" : "bg-danger-container";

  return (
    <div className={cx("animate-fade-in rounded-2xl p-3", toneBg)}>
      <div className="flex items-center gap-2">
        <span className={cx("rounded-full bg-surface px-2.5 py-1 text-[11px] font-bold", toneText)}>
          {feedback.score}/100
        </span>
        <span className="text-[13px] font-bold text-ink-primary">{feedback.headline}</span>
      </div>
      {feedback.strengths.length > 0 && (
        <ul className="mt-2 space-y-0.5">
          {feedback.strengths.map((line) => (
            <li key={line} className="text-[12.5px] text-ink-secondary">✓ {line}</li>
          ))}
        </ul>
      )}
      {feedback.improvements.length > 0 && (
        <ul className="mt-2 space-y-0.5">
          {feedback.improvements.map((line) => (
            <li key={line} className={cx("text-[12.5px]", toneText)}>→ {line}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** Skeleton mirroring the rehearsal body, used while a plan is being built. */
export function InterviewSkeleton() {
  return (
    <div className="app-shell space-y-4" role="status" aria-label="Loading rehearsal">
      <Skeleton className="h-[110px] rounded-3xl" />
      {[0, 1, 2].map((i) => (
        <Skeleton key={i} className="h-[88px] rounded-2xl" />
      ))}
    </div>
  );
}
