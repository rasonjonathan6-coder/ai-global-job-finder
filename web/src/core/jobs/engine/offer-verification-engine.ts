import {
  ApplicationDestination,
  BenefitStatus,
  EvidenceStrength,
  JobBenefitTypes,
  MadagascarEligibility,
  VerificationAspect,
  VerificationState,
  VisaSupport,
  type Job,
  type OfferVerification,
  type VerificationFact,
} from "../../models";
import { ApplicationUrlInspector } from "../../apply/application-url-inspector";

/**
 * Builds the transparent verification read for one posting. Mirrors the Kotlin
 * `OfferVerificationEngine`.
 *
 * It answers four separate questions, each with its own state, because conflating them is
 * how a candidate ends up trusting a dead listing:
 *  - Is the vacancy still taking applicants? Only the source's own flag can confirm this.
 *  - Is the application link usable? Delegated to `ApplicationUrlInspector`.
 *  - Is there a route in for a Madagascar applicant? Read from documented visa/relocation
 *    evidence; silence stays `UNKNOWN`, only an explicit refusal becomes `NOT_OFFERED`.
 *  - How recent is this check? The retrieval timestamp is classified so a stale read is
 *    never presented as fresh.
 *
 * It reads recorded facts, never invents them: a field the source did not send stays unknown.
 */

const DAY_MS = 86_400_000;
export const DEFAULT_RECENT_DAYS = 3;
export const DEFAULT_STALE_DAYS = 21;

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

export class OfferVerificationEngine {
  private readonly linkInspector: ApplicationUrlInspector;
  private readonly recentDays: number;
  private readonly staleDays: number;

  constructor(options?: {
    linkInspector?: ApplicationUrlInspector;
    recentDays?: number;
    staleDays?: number;
  }) {
    this.linkInspector = options?.linkInspector ?? new ApplicationUrlInspector();
    this.recentDays = options?.recentDays ?? DEFAULT_RECENT_DAYS;
    this.staleDays = options?.staleDays ?? DEFAULT_STALE_DAYS;
  }

  assess(job: Job, now: number = Date.now()): OfferVerification {
    const activity = activityState(job);
    const deadline = deadlineState(job, now);
    const link = this.linkState(job);
    const visa = visaState(job);
    const relocation = relocationState(job);
    const freshness = this.freshnessState(job, now);

    const facts: VerificationFact[] = [
      sourceFact(job),
      activityFact(job, activity),
      deadlineFact(job, deadline),
      this.linkFact(job, link),
      visaFact(job, visa),
      relocationFact(job, relocation),
      this.freshnessFact(job, freshness, now),
    ];

    const caveats = this.caveatsFor(job, activity, deadline, link, visa, freshness);

    return {
      activity,
      deadline,
      link,
      visa,
      relocation,
      lastRetrievedAtEpochMillis: job.retrievedAtEpochMillis,
      freshness,
      facts,
      caveats,
      summary: summaryFor(activity, deadline, link, visa, freshness),
    };
  }

  // ---- Activity: only the source's own flag can confirm or deny it -----------

  private linkState(job: Job): VerificationState {
    const inspection = this.linkInspector.inspect(job);
    if (!inspection.canOpen || inspection.destination === ApplicationDestination.INVALID) {
      return VerificationState.INACCESSIBLE;
    }
    return inspection.warnings.length > 0 ? VerificationState.POSSIBLE : VerificationState.CONFIRMED;
  }

  // ---- Freshness: how much the last retrieval can be trusted -----------------

  private freshnessState(job: Job, now: number): VerificationState {
    const retrieved = job.retrievedAtEpochMillis;
    if (retrieved == null || retrieved > now) return VerificationState.UNKNOWN;
    const ageDays = Math.trunc((now - retrieved) / DAY_MS);
    if (ageDays <= this.recentDays) return VerificationState.CONFIRMED;
    if (ageDays <= this.staleDays) return VerificationState.POSSIBLE;
    // An aged-out check is not proof the offer is dead, only that we cannot rely on it.
    return VerificationState.UNKNOWN;
  }

  private linkFact(job: Job, state: VerificationState): VerificationFact {
    const inspection = this.linkInspector.inspect(job);
    let detail: string;
    if (state === VerificationState.CONFIRMED) {
      detail = `The application link is present and well-formed: ${inspection.host ?? job.sourceUrl}.`;
    } else if (state === VerificationState.POSSIBLE) {
      detail = inspection.warnings.join(" ").trim();
      if (detail.length === 0) detail = "The application link is present but has something worth checking.";
    } else {
      detail = inspection.message;
    }
    return fact(VerificationAspect.APPLICATION_LINK, state, "Application link", detail, job.sourceUrl);
  }

  private freshnessFact(job: Job, state: VerificationState, now: number): VerificationFact {
    const retrieved = job.retrievedAtEpochMillis;
    let detail: string;
    if (retrieved == null) {
      detail = "This posting has not been retrieved from its source yet.";
    } else if (state === VerificationState.CONFIRMED) {
      detail = `Retrieved from the source ${relativeDays(retrieved, now)}.`;
    } else if (state === VerificationState.POSSIBLE) {
      detail = `Retrieved from the source ${relativeDays(retrieved, now)}. Confirm it is still current.`;
    } else {
      detail =
        `Last retrieved from the source ${relativeDays(retrieved, now)}. ` +
        "The check is old, so re-open the listing before relying on it.";
    }
    return fact(VerificationAspect.FRESHNESS, state, "Verification age", detail, job.sourceUrl);
  }

  // ---- Caveats and summary ---------------------------------------------------

  private caveatsFor(
    job: Job,
    activity: VerificationState,
    deadline: VerificationState,
    link: VerificationState,
    visa: VerificationState,
    freshness: VerificationState,
  ): string[] {
    const out: string[] = [];
    if (link === VerificationState.INACCESSIBLE) {
      out.push("This posting has no usable application link. Search the employer's own careers page instead.");
    }
    if (activity === VerificationState.EXPIRED) {
      out.push("The source reports this posting is no longer accepting applicants.");
    }
    if (deadline === VerificationState.EXPIRED) {
      out.push("The application deadline the employer stated has passed, so an application would be too late.");
    }
    if (job.eligibility === MadagascarEligibility.RESTRICTED) {
      out.push("The posting is limited to local applicants, so a Madagascar application is unlikely to be considered.");
    }
    if (visa === VerificationState.UNKNOWN) {
      out.push("The posting does not say whether it sponsors a visa. Confirm with the employer before relying on it.");
    } else if (visa === VerificationState.NOT_OFFERED) {
      out.push("The employer states it does not sponsor a visa for this role.");
    }
    if (freshness === VerificationState.UNKNOWN && job.retrievedAtEpochMillis != null) {
      out.push("This listing was last retrieved a while ago. Re-open it to confirm it is still current.");
    }
    return out;
  }
}

// ---- Activity: only the source's own flag can confirm or deny it -----------

function activityState(job: Job): VerificationState {
  if (job.openToApplicants === true) return VerificationState.CONFIRMED;
  if (job.openToApplicants === false) return VerificationState.EXPIRED;
  // No flag: age is not proof of expiry, so the honest answer is "unknown".
  return VerificationState.UNKNOWN;
}

/**
 * Reads the employer's own application deadline. A posting with no stated deadline is
 * `UNKNOWN`, never `EXPIRED`: silence about a deadline is not a claim that one passed.
 */
function deadlineState(job: Job, now: number): VerificationState {
  const expires = job.expiresAtEpochMillis;
  if (expires == null) return VerificationState.UNKNOWN;
  return expires <= now ? VerificationState.EXPIRED : VerificationState.CONFIRMED;
}

// ---- Visa: documented route in, never assumed from silence -----------------

function visaState(job: Job): VerificationState {
  const sponsorship = job.benefitEvidence.find((it) => it.type.key === JobBenefitTypes.VISA_SPONSORSHIP.key);
  switch (sponsorship?.status) {
    case BenefitStatus.CONFIRMED:
      return VerificationState.CONFIRMED;
    case BenefitStatus.NEEDS_VERIFICATION:
      return VerificationState.POSSIBLE;
    case BenefitStatus.NOT_OFFERED:
      return VerificationState.NOT_OFFERED;
    default:
      if (job.eligibility === MadagascarEligibility.RESTRICTED) return VerificationState.NOT_OFFERED;
      if (job.eligibility === MadagascarEligibility.ELIGIBLE) return VerificationState.POSSIBLE;
      if (job.eligibility === MadagascarEligibility.VISA_SPONSORED) return VerificationState.CONFIRMED;
      return VerificationState.UNKNOWN;
  }
}

// ---- Relocation: a separate axis from the visa -----------------------------

function relocationState(job: Job): VerificationState {
  const relocation = job.benefitEvidence.find((it) => it.type.key === JobBenefitTypes.RELOCATION_SUPPORT.key);
  switch (relocation?.status) {
    case BenefitStatus.CONFIRMED:
      return VerificationState.CONFIRMED;
    case BenefitStatus.NEEDS_VERIFICATION:
      return VerificationState.POSSIBLE;
    case BenefitStatus.NOT_OFFERED:
      return VerificationState.NOT_OFFERED;
    default:
      return job.visaSupport === VisaSupport.RELOCATION_PACKAGE
        ? VerificationState.POSSIBLE
        : VerificationState.UNKNOWN;
  }
}

// ---- Facts, one per axis, official ones first ------------------------------

function fact(
  aspect: VerificationAspect,
  state: VerificationState,
  label: string,
  detail: string,
  sourceUrl: string,
): VerificationFact {
  return { aspect, strength: strengthFor(state), label, detail, sourceUrl };
}

function sourceFact(job: Job): VerificationFact {
  const detail =
    job.source.trim().length === 0
      ? "The posting did not name the source it came from."
      : `Supplied by ${job.source}. The listing is the employer's or the board's own record.`;
  return {
    aspect: VerificationAspect.SOURCE,
    strength: EvidenceStrength.DOCUMENTED,
    label: "Source",
    detail,
    sourceUrl: job.sourceUrl,
  };
}

function activityFact(job: Job, state: VerificationState): VerificationFact {
  let detail: string;
  if (state === VerificationState.CONFIRMED) {
    detail = "The source itself reports this posting is still accepting applicants.";
  } else if (state === VerificationState.EXPIRED) {
    detail = "The source itself reports this posting is no longer accepting applicants.";
  } else {
    detail =
      "The source does not state whether this posting is still accepting applicants. " +
      "Open it to confirm before you invest time.";
  }
  return fact(VerificationAspect.VACANCY_ACTIVITY, state, "Vacancy activity", detail, job.sourceUrl);
}

function deadlineFact(job: Job, state: VerificationState): VerificationFact {
  let detail: string;
  if (state === VerificationState.EXPIRED) {
    detail = `The employer stated an application deadline of ${dateOf(job.expiresAtEpochMillis)}, which has passed.`;
  } else if (state === VerificationState.CONFIRMED) {
    detail = `The employer states applications close ${dateOf(job.expiresAtEpochMillis)}.`;
  } else {
    detail = "The posting does not state an application deadline. Confirm the closing date before you rely on it.";
  }
  return fact(VerificationAspect.APPLICATION_DEADLINE, state, "Application deadline", detail, job.sourceUrl);
}

function visaFact(job: Job, state: VerificationState): VerificationFact {
  const evidence = job.benefitEvidence.find((it) => it.type.key === JobBenefitTypes.VISA_SPONSORSHIP.key);
  const quoted = evidence?.evidence.trim();
  let detail: string;
  if (state === VerificationState.CONFIRMED) {
    detail = quoted && quoted.length > 0 ? quoted : "The posting states the employer sponsors a work visa.";
  } else if (state === VerificationState.POSSIBLE) {
    detail =
      quoted && quoted.length > 0
        ? quoted
        : "The posting suggests a route in for international applicants, but does not promise sponsorship.";
  } else if (state === VerificationState.NOT_OFFERED) {
    detail =
      quoted && quoted.length > 0
        ? quoted
        : "The posting states it is not open to applicants who need sponsorship.";
  } else {
    detail = "The posting does not say whether it sponsors a work visa. Do not assume sponsorship.";
  }
  return fact(VerificationAspect.VISA, state, "Visa / sponsorship", detail, job.sourceUrl);
}

function relocationFact(job: Job, state: VerificationState): VerificationFact {
  const evidence = job.benefitEvidence.find((it) => it.type.key === JobBenefitTypes.RELOCATION_SUPPORT.key);
  const quoted = evidence?.evidence.trim();
  let detail: string;
  if (state === VerificationState.CONFIRMED) {
    detail = quoted && quoted.length > 0 ? quoted : "The posting states the employer supports relocation.";
  } else if (state === VerificationState.POSSIBLE) {
    detail = quoted && quoted.length > 0 ? quoted : "The posting hints at relocation support without committing to it.";
  } else if (state === VerificationState.NOT_OFFERED) {
    detail = quoted && quoted.length > 0 ? quoted : "The posting states relocation is not provided.";
  } else {
    detail = "The posting does not mention relocation support.";
  }
  return fact(VerificationAspect.RELOCATION, state, "Relocation", detail, job.sourceUrl);
}

function relativeDays(retrieved: number, now: number): string {
  const ageDays = Math.trunc(Math.max(0, now - retrieved) / DAY_MS);
  if (ageDays === 0) return "today";
  if (ageDays === 1) return "yesterday";
  return `${ageDays} days ago`;
}

/** A stated calendar date, rendered in UTC so the same instant reads the same everywhere. */
function dateOf(epochMillis: number | null): string {
  if (epochMillis == null) return "a date the posting did not show";
  const d = new Date(epochMillis);
  return `${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]} ${d.getUTCFullYear()}`;
}

function summaryFor(
  activity: VerificationState,
  deadline: VerificationState,
  link: VerificationState,
  visa: VerificationState,
  freshness: VerificationState,
): string {
  let out = "";
  if (link === VerificationState.INACCESSIBLE) {
    out += "No usable application link.";
  } else if (deadline === VerificationState.EXPIRED) {
    out += "The stated application deadline has passed.";
  } else if (activity === VerificationState.EXPIRED) {
    out += "The source reports this posting is closed.";
  } else if (activity === VerificationState.CONFIRMED) {
    out += "The source reports this posting is open.";
  } else {
    out += "The app cannot confirm whether this posting is still open.";
  }

  if (visa === VerificationState.CONFIRMED) {
    out += " Visa sponsorship is documented.";
  } else if (visa === VerificationState.POSSIBLE) {
    out += " There is a possible route in for international applicants.";
  } else if (visa === VerificationState.NOT_OFFERED) {
    out += " No visa route is offered.";
  } else {
    out += " Visa sponsorship is not stated.";
  }

  if (freshness === VerificationState.UNKNOWN && activity !== VerificationState.EXPIRED) {
    out += " Re-check the listing before applying.";
  }
  return out;
}

/** Maps a verification state to how strongly the underlying fact is established. */
function strengthFor(state: VerificationState): EvidenceStrength {
  switch (state) {
    case VerificationState.CONFIRMED:
      return EvidenceStrength.DOCUMENTED;
    case VerificationState.POSSIBLE:
      return EvidenceStrength.INFERRED;
    case VerificationState.NOT_OFFERED:
      return EvidenceStrength.REFUSED;
    case VerificationState.EXPIRED:
      return EvidenceStrength.REFUSED;
    case VerificationState.INACCESSIBLE:
      return EvidenceStrength.DOCUMENTED;
    case VerificationState.UNKNOWN:
      return EvidenceStrength.UNKNOWN;
  }
}
