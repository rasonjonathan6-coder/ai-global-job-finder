import {
  MadagascarEligibility,
  ScamRisk,
  VisaSupport,
  type Job,
} from "../../core/models";
import { countryLine, formatSalaryRange, relativeTimeFrom } from "../lib/format";
import { Badge, type BadgeTone } from "./Badge";
import { Button } from "./Button";
import { MatchRing } from "./MatchRing";
import {
  AlertIcon,
  CheckIcon,
  ClockIcon,
  ExternalLinkIcon,
  GlobeIcon,
  InfoIcon,
  MoneyIcon,
  PinIcon,
  ShieldIcon,
} from "./icons";

export type EligibilityTone = BadgeTone;

/** Maps the model's eligibility verdict to a badge label + tone, shared by card and detail. */
export function eligibilityBadge(job: Job): { label: string; tone: EligibilityTone } {
  switch (job.eligibility) {
    case MadagascarEligibility.ELIGIBLE:
      return { label: "Open to Madagascar applicants", tone: "success" };
    case MadagascarEligibility.VISA_SPONSORED:
      return { label: "Visa sponsorship route", tone: "success" };
    case MadagascarEligibility.UNCLEAR:
      return { label: "Eligibility needs checking", tone: "warning" };
    case MadagascarEligibility.RESTRICTED:
    default:
      return { label: "Local applicants only", tone: "danger" };
  }
}

export function visaBadge(job: Job): { label: string; tone: EligibilityTone } | null {
  switch (job.visaSupport) {
    case VisaSupport.FULL_SPONSORSHIP:
      return { label: "Full sponsorship", tone: "success" };
    case VisaSupport.RELOCATION_PACKAGE:
      return { label: "Relocation package", tone: "success" };
    case VisaSupport.SPONSORSHIP_AVAILABLE:
      return { label: "Sponsorship available", tone: "info" };
    case VisaSupport.LOCAL_ONLY:
      return { label: "Local only", tone: "danger" };
    case VisaSupport.NOT_MENTIONED:
    default:
      return null;
  }
}

interface JobCardProps {
  job: Job;
  /** Index within its list, used to stagger the entrance animation. */
  index?: number;
  onView?: (job: Job) => void;
  onApply?: (job: Job) => void;
}

/**
 * The premium job card: a scan-friendly summary that leads with the match score, then the
 * facts a Madagascar-based applicant needs before deciding to spend time — eligibility,
 * location, pay and visa posture. Nothing is colour-only; every signal carries a label.
 */
export function JobCard({ job, index = 0, onView, onApply }: JobCardProps) {
  const eligibility = eligibilityBadge(job);
  const visa = visaBadge(job);
  const salary = formatSalaryRange(
    job.salaryMin,
    job.salaryMax,
    job.currencyCode,
    job.salaryPeriod,
    job.salaryIsEstimated,
  );
  const salaryKnown = job.salaryMin > 0;

  return (
    <article
      className="card-surface animate-fade-up p-4"
      style={{ animationDelay: `${Math.min(index, 8) * 55}ms` }}
      aria-label={`${job.title} at ${job.company}`}
    >
      <div className="flex items-start gap-3.5">
        <MatchRing score={job.matchScore} size={54} />
        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-2">
            <h3 className="line-clamp-2 text-[15.5px] font-bold leading-snug text-ink-primary">
              {job.title}
            </h3>
            {job.isNew && (
              <Badge tone="accent" className="mt-0.5 shrink-0">
                New
              </Badge>
            )}
          </div>
          <p className="mt-0.5 truncate text-[13px] font-semibold text-ink-secondary">
            {job.company}
          </p>
          <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-[12px] text-ink-tertiary">
            <span className="inline-flex min-w-0 max-w-full items-center gap-1">
              <PinIcon width={13} height={13} className="shrink-0" />
              <span className="truncate">
                {job.countryFlag ? `${job.countryFlag} ` : ""}
                {countryLine(job.country, job.city)}
              </span>
            </span>
            <span className="inline-flex min-w-0 items-center gap-1">
              <MoneyIcon width={13} height={13} className="shrink-0" />
              <span className={salaryKnown ? "truncate text-ink-secondary" : "truncate"}>{salary}</span>
            </span>
          </div>
        </div>
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-1.5">
        <Badge tone={eligibility.tone} icon={<GlobeIcon width={12} height={12} />}>
          {eligibility.label}
        </Badge>
        {visa && <Badge tone={visa.tone}>{visa.label}</Badge>}
        {job.scamRisk === ScamRisk.HIGH && (
          <Badge tone="danger" icon={<ShieldIcon width={12} height={12} />}>
            Scam risk flagged
          </Badge>
        )}
        {job.scamRisk === ScamRisk.MEDIUM && (
          <Badge tone="warning" icon={<ShieldIcon width={12} height={12} />}>
            Check the details
          </Badge>
        )}
      </div>

      {job.aiInsight && (
        <p className="mt-3 flex items-start gap-1.5 rounded-xl bg-accent-500/[0.06] px-3 py-2 text-[12px] leading-snug text-ink-secondary">
          <InfoIcon width={14} height={14} className="mt-0.5 shrink-0 text-accent-ink" />
          <span className="line-clamp-2">{job.aiInsight}</span>
        </p>
      )}

      <div className="mt-3.5 flex items-center justify-between gap-3">
        <span className="inline-flex items-center gap-1 text-[11.5px] text-ink-tertiary">
          <ClockIcon width={13} height={13} />
          {relativeTimeFrom(job.postedAtEpochMillis)}
          {job.openToApplicants ? (
            <span className="ml-1 inline-flex items-center gap-0.5 text-success">
              <CheckIcon width={12} height={12} /> Open
            </span>
          ) : job.openToApplicants === false ? (
            <span className="ml-1 inline-flex items-center gap-0.5 text-danger">
              <AlertIcon width={12} height={12} /> Closed
            </span>
          ) : null}
        </span>
        <div className="flex items-center gap-2">
          <Button variant="secondary" size="sm" onClick={() => onView?.(job)}>
            View Job
          </Button>
          <Button
            variant="primary"
            size="sm"
            onClick={() => onApply?.(job)}
            trailingIcon={<ExternalLinkIcon width={14} height={14} />}
          >
            Apply
          </Button>
        </div>
      </div>
    </article>
  );
}
