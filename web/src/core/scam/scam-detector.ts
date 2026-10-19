import {
  MadagascarEligibility,
  SalaryPeriod,
  ScamRisk,
  ScamSignalTone,
  ScamVerdict,
  VisaSupport,
  type Job,
  type ScamReport,
  type ScamSignal,
} from "../models";

/**
 * Scans a posting for the patterns that separate a real international job from a scam.
 * Mirrors the Kotlin `ScamDetector`.
 *
 * The detector only ever reports what it can quote. Each danger signal carries the exact
 * phrase from the posting that triggered it, so the user can read the original and judge
 * for themselves. It advises; it never blocks an application, because a false positive
 * that hides a real job is as harmful as a missed scam.
 */

interface Pattern {
  label: string;
  detail: string;
  regex: RegExp;
}

/** Anything that asks the applicant to send money is a scam, with no exceptions. */
const MONEY_PATTERNS: Pattern[] = [
  {
    label: "Asks you to pay a fee",
    detail: "A legitimate employer never charges you to apply, to be placed, or to be trained.",
    regex: /(registration|processing|application|placement|agency|training|admin(istration)?)\s+fee/,
  },
  {
    label: "Asks for a deposit or payment",
    detail: "No real job requires you to deposit money or pay for your own paperwork up front.",
    regex: /(pay|payment|deposit|transfer)\s+(a\s+)?(small\s+)?(deposit|amount|fee|money|sum)/,
  },
  {
    label: "Mentions Western Union or money transfer",
    detail: "Payment by wire or money transfer to an employer is a hallmark of advance-fee fraud.",
    regex: /(western union|moneygram|money\s*gram|bitcoin|usdt|crypto\s*wallet)/,
  },
  {
    label: "Charges for the visa",
    detail:
      "You should never pay a third party for a work visa; only official government fees apply, paid directly.",
    regex: /(visa|work permit|permit)\s+(fee|processing|payment)/,
  },
];

/** Channels that real recruiters do not use for first contact. */
const MESSAGING_PATTERNS: Pattern[] = [
  {
    label: "Contact only by chat app",
    detail:
      "Interviews arranged only on WhatsApp or Telegram, with no company channel, are a common scam pattern.",
    regex: /(whatsapp|telegram|signal)\s+(only|us|number|contact)/,
  },
  {
    label: "Personal email address",
    detail: "A real employer writes from a company domain, not a free personal inbox.",
    regex: /(gmail|yahoo|hotmail|outlook|mail)\.com/,
  },
];

const REPUTABLE_HOSTS = [
  "arbeitnow.com", "remotive.com", "jobicy.com", "linkedin.com", "indeed.com",
  "eures.europa.eu", "europa.eu", "un.org", "reliefweb.int", "jobbank.gc.ca",
  "make-it-in-germany.com", "gulftalent.com", "bayt.com", "glassdoor.com",
];

/** Monthly pay above this in a strong currency is treated as a lure, not an offer. */
const UNREALISTIC_MONTHLY = 60_000;

const HIGH_VALUE_CURRENCIES = new Set(["USD", "EUR", "GBP", "CAD", "AUD", "CHF"]);

export function scanForScams(job: Job, clock: () => number = Date.now): ScamReport {
  const haystack = [
    job.title,
    job.company,
    job.summary,
    job.responsibilities.join("\n"),
    job.benefits.join("\n"),
    job.aiInsight,
  ].join("\n");
  const lower = haystack.toLowerCase();
  const signals: ScamSignal[] = [];

  // ---- Money flowing the wrong way ------------------------------------
  for (const pattern of MONEY_PATTERNS) {
    const match = pattern.regex.exec(lower);
    if (match) {
      signals.push({
        label: pattern.label,
        detail: pattern.detail,
        evidence: match[0].trim(),
        tone: ScamSignalTone.DANGER,
      });
    }
  }

  // ---- Unrealistic pay -------------------------------------------------
  // Skipped when the source's own unit is untrustworthy: judging plausibility from a figure
  // whose period we could not believe would be a second wrong reading.
  const salaryPerMonth = job.salaryUnitUncertain
    ? null
    : job.salaryMin > 0
      ? salaryPerMonthOf(job.salaryMin, job.salaryPeriod)
      : null;
  const currencyUpper = job.currencyCode.toUpperCase();
  if (salaryPerMonth != null && HIGH_VALUE_CURRENCIES.has(currencyUpper) && salaryPerMonth > UNREALISTIC_MONTHLY) {
    signals.push({
      label: "Unrealistic salary",
      detail: "The advertised pay is far above the normal range for this kind of role, which is a common lure.",
      evidence: `${job.currencyCode} ${job.salaryMin} per ${job.salaryPeriod.toLowerCase()}`,
      tone: ScamSignalTone.DANGER,
    });
  }

  // ---- Communication channel ------------------------------------------
  for (const pattern of MESSAGING_PATTERNS) {
    const match = pattern.regex.exec(lower);
    if (match) {
      signals.push({
        label: pattern.label,
        detail: pattern.detail,
        evidence: match[0].trim(),
        tone: ScamSignalTone.CAUTION,
      });
    }
  }

  // ---- Structural checks ----------------------------------------------
  if (job.company.trim().length === 0 || job.company.toLowerCase() === "unknown employer") {
    signals.push({
      label: "No named employer",
      detail: "The posting does not name a real company, so there is nothing to verify.",
      evidence: "",
      tone: ScamSignalTone.DANGER,
    });
  }
  if (job.sourceUrl.trim().length === 0 || !job.sourceUrl.startsWith("http")) {
    signals.push({
      label: "No original posting link",
      detail: "There is no link back to the employer's own advert, so you cannot confirm it exists.",
      evidence: "",
      tone: ScamSignalTone.CAUTION,
    });
  } else if (!isReputableHost(job.sourceUrl)) {
    signals.push({
      label: "Unfamiliar application site",
      detail: "The link does not point to a well-known job board or the employer's own domain.",
      evidence: hostOf(job.sourceUrl),
      tone: ScamSignalTone.CAUTION,
    });
  }
  if (job.title.trim().length === 0 || job.title.toLowerCase() === "untitled role") {
    signals.push({
      label: "Vague job title",
      detail: "The title is too generic to tell what the work actually is.",
      evidence: "",
      tone: ScamSignalTone.CAUTION,
    });
  }
  if (job.summary.trim().length === 0 && job.responsibilities.length === 0) {
    signals.push({
      label: "No job description",
      detail: "A real posting describes the day-to-day work. This one says almost nothing.",
      evidence: "",
      tone: ScamSignalTone.CAUTION,
    });
  }

  // ---- Reassurances ----------------------------------------------------
  if (job.visaSupport === VisaSupport.FULL_SPONSORSHIP || job.visaSupport === VisaSupport.RELOCATION_PACKAGE) {
    signals.push({
      label: "States sponsorship in writing",
      detail: "The employer put visa or relocation support in the advert itself, which is a good sign.",
      evidence: "",
      tone: ScamSignalTone.REASSURANCE,
    });
  }
  if (job.source.trim().length > 0 && job.sourceUrl.startsWith("http")) {
    signals.push({
      label: "Published on a job board",
      detail: `${job.source} published this posting with a link back to the original advert.`,
      evidence: "",
      tone: ScamSignalTone.REASSURANCE,
    });
  }
  if (job.eligibility === MadagascarEligibility.RESTRICTED) {
    signals.push({
      label: "Employer states local-only",
      detail: "The posting is explicit about who it is open to, which real employers usually are.",
      evidence: "",
      tone: ScamSignalTone.REASSURANCE,
    });
  }

  const dangers = signals.filter((s) => s.tone === ScamSignalTone.DANGER).length;
  const cautions = signals.filter((s) => s.tone === ScamSignalTone.CAUTION).length;
  const verdict = dangers > 0 ? ScamVerdict.HIGH_RISK : cautions >= 1 ? ScamVerdict.NEEDS_CAUTION : ScamVerdict.LIKELY_SAFE;
  const risk =
    verdict === ScamVerdict.HIGH_RISK
      ? ScamRisk.HIGH
      : verdict === ScamVerdict.NEEDS_CAUTION
        ? ScamRisk.MEDIUM
        : ScamRisk.LOW;

  return {
    jobId: job.id,
    jobTitle: job.title,
    company: job.company,
    risk,
    verdict,
    signals,
    checkedAtEpochMillis: clock(),
  };
}

function salaryPerMonthOf(min: number, period: SalaryPeriod): number {
  switch (period) {
    case SalaryPeriod.YEAR:
      return Math.trunc(min / 12);
    case SalaryPeriod.MONTH:
      return min;
    case SalaryPeriod.DAY:
      return min * 21;
    case SalaryPeriod.HOUR:
      return min * 160;
  }
}

function hostOf(url: string): string {
  try {
    return new URL(url).hostname;
  } catch {
    return "";
  }
}

function isReputableHost(url: string): boolean {
  const host = hostOf(url).toLowerCase();
  if (host.length === 0) return false;
  return REPUTABLE_HOSTS.some((it) => host === it || host.endsWith(`.${it}`));
}
