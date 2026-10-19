import {
  BenefitStatus,
  JobBenefitTypes,
  MadagascarEligibility,
  ScamRisk,
  ScamVerdict,
  LETTER_LANGUAGE_META,
  VisaSupport,
  letterLanguageForCountry,
  readinessBlockerIsProfileGap,
  type Job,
  type ReadinessBlocker,
  type ShortlistEntry,
} from "../models";
import {
  PLAN_STEP_ORDER,
  PlanStepKind,
  PlanStepStatus,
  type ApplicationPlan,
  type CvTailoring,
  type DocumentRequirement,
  type PlanStep,
  type TailoringHighlight,
} from "./application-plan";

/**
 * Turns the app's separate findings about one posting into a single, ordered application
 * plan. Mirrors the Kotlin `ApplicationPlanEngine`.
 *
 * The engine invents nothing. Every step is derived from evidence the app already holds —
 * the posting's own text, the readiness blockers from `ShortlistEngine`, the candidate's CV
 * and the state of the other tools. Where a fact is unknown, the step says so and asks the
 * employer rather than assuming a refusal.
 */

const PROFILE_TARGET = 80;
const NAME_GAP_POINTS = 6;
const MIN_BLOCK_WEIGHT = 45;
const VISA_CONFIRM_WEIGHT = 8;
const ELIGIBILITY_UNKNOWN_WEIGHT = 27;
const COVER_LETTER_WEIGHT = 10;
const SCAM_BLOCK_WEIGHT = 20;
const SCAM_CAUTION_WEIGHT = 8;
const INTERVIEW_WEIGHT = 5;
const MAX_LEAD_SKILLS = 6;
const MAX_ADDRESS_SKILLS = 8;
const MAX_KEYWORDS = 12;

export interface ApplicationPlanEngineOptions {
  clock?: () => number;
}

export class ApplicationPlanEngine {
  private readonly clock: () => number;

  constructor(options?: ApplicationPlanEngineOptions) {
    this.clock = options?.clock ?? Date.now;
  }

  build(
    job: Job,
    entry: ShortlistEntry,
    profileCompletenessPercent: number,
    hasCoverLetter: boolean,
    interviewAttempted = 0,
    interviewTotal = 0,
    scamVerdict: ScamVerdict | null = null,
    hasApplied = false,
  ): ApplicationPlan {
    const tailoring = this.tailoringFor(job);
    const routeSummary = this.routeSummaryFor(job);

    const steps = [
      this.profileStep(entry, profileCompletenessPercent),
      this.visaStep(job, entry),
      this.tailorStep(job, tailoring, entry),
      this.coverLetterStep(job, hasCoverLetter),
      this.scamStep(job, scamVerdict, entry),
      this.interviewStep(interviewAttempted, interviewTotal),
      this.submitStep(job, hasApplied),
      this.followUpStep(hasApplied),
    ].sort((a, b) => PLAN_STEP_ORDER.indexOf(a.kind) - PLAN_STEP_ORDER.indexOf(b.kind));

    const nextAction =
      steps.find((step) => step.status === PlanStepStatus.BLOCKED && step.action.trim().length > 0)?.action ??
      steps.find((step) => step.status === PlanStepStatus.TO_DO && step.action.trim().length > 0)?.action ??
      "Everything on the checklist is done — submit the application and follow up.";

    return {
      jobId: job.id,
      jobTitle: job.title,
      company: job.company,
      country: job.country,
      countryFlag: job.countryFlag,
      matchScore: job.matchScore,
      readinessScore: entry.readinessScore,
      readiness: entry.readiness,
      steps,
      tailoring,
      documents: this.documentsFor(job),
      routeSummary,
      nextAction,
      hasCoverLetter,
      scamVerdict,
      generatedAtEpochMillis: this.clock(),
    };
  }

  // ------------------------------------------------------------------ steps

  private profileStep(entry: ShortlistEntry, completeness: number): PlanStep {
    const done =
      completeness >= PROFILE_TARGET &&
      !entry.blockers.some((blocker) => readinessBlockerIsProfileGap(blocker) && blocker.points >= NAME_GAP_POINTS);
    return {
      kind: PlanStepKind.PROFILE,
      status: done ? PlanStepStatus.DONE : PlanStepStatus.TO_DO,
      title: done ? `Profile ${completeness}% complete` : `Finish your profile (${completeness}%)`,
      detail: done
        ? "Your CV carries enough detail for the app to tailor this application accurately."
        : "A fuller profile makes the match and the CV tailoring more trustworthy. " +
          "Add the missing CV sections before you rely on the score.",
      action: done ? "" : "Import or complete your CV in Profile.",
      weight: this.blockersFor(entry, (blocker) => readinessBlockerIsProfileGap(blocker)),
      official: false,
    };
  }

  private visaStep(job: Job, entry: ShortlistEntry): PlanStep {
    const visaWeight = this.blockersFor(
      entry,
      (blocker) => !readinessBlockerIsProfileGap(blocker) && blocker.label.toLowerCase().includes("eligib"),
    );
    switch (job.eligibility) {
      case MadagascarEligibility.RESTRICTED:
        return {
          kind: PlanStepKind.VISA_ROUTE,
          status: PlanStepStatus.BLOCKED,
          title: "No route in from Madagascar",
          detail: "The posting is limited to local applicants, so there is no way to apply from Madagascar.",
          action: "Set this posting aside and look for one open to international applicants.",
          weight: Math.max(visaWeight, MIN_BLOCK_WEIGHT),
          official: true,
        };
      case MadagascarEligibility.VISA_SPONSORED: {
        const documented = this.documentsVisaSponsorship(job);
        return {
          kind: PlanStepKind.VISA_ROUTE,
          status: documented ? PlanStepStatus.DONE : PlanStepStatus.TO_DO,
          title: documented ? "Visa sponsorship stated" : "Confirm the visa terms",
          detail: documented
            ? "The employer states a route in (visa sponsorship or relocation), quoted from the posting."
            : "The posting mentions sponsorship but does not spell out the conditions. " +
              "Confirm who pays the fees and whether dependants are covered.",
          action: documented ? "" : "Ask the employer for the visa terms in writing.",
          weight: documented ? 0 : Math.max(visaWeight, VISA_CONFIRM_WEIGHT),
          official: true,
        };
      }
      case MadagascarEligibility.ELIGIBLE:
        return {
          kind: PlanStepKind.VISA_ROUTE,
          status: PlanStepStatus.DONE,
          title: "Open to applicants from Madagascar",
          detail: "The posting is open to applicants already in Madagascar.",
          action: "",
          weight: 0,
          official: true,
        };
      default:
        return {
          kind: PlanStepKind.VISA_ROUTE,
          status: PlanStepStatus.TO_DO,
          title: "Eligibility not stated",
          detail:
            "The posting does not say whether it accepts applicants from Madagascar. " +
            "That is missing information, not a refusal.",
          action: "Email the employer and ask before you invest time in the application.",
          weight: Math.max(visaWeight, ELIGIBILITY_UNKNOWN_WEIGHT),
          official: false,
        };
    }
  }

  private tailorStep(job: Job, tailoring: CvTailoring, entry: ShortlistEntry): PlanStep {
    const matchWeight = this.blockersFor(entry, (blocker) => blocker.label.toLowerCase().includes("cv match"));
    const needsWork = tailoring.addressSkills.length > 0;
    const parts: string[] = [];
    if (tailoring.leadSkills.length > 0) {
      parts.push(`Lead with ${tailoring.leadSkills.slice(0, 4).map((h) => h.skill).join(", ")}.`);
    }
    if (needsWork) {
      parts.push(
        `This posting also lists ${tailoring.addressSkills.slice(0, 4).join(", ")}, which your CV does not yet show.`,
      );
    }
    const detail = parts.length > 0 ? parts.join(" ") : "Your CV already covers everything this posting lists.";
    return {
      kind: PlanStepKind.TAILOR_CV,
      status: needsWork ? PlanStepStatus.TO_DO : PlanStepStatus.DONE,
      title: needsWork ? "Tailor your CV to this posting" : "CV already lines up",
      detail,
      action: needsWork ? `Adapt your CV for ${job.company} so it leads with the skills above.` : "",
      weight: matchWeight,
      official: false,
    };
  }

  private coverLetterStep(job: Job, hasCoverLetter: boolean): PlanStep {
    const language = letterLanguageForCountry(job.country);
    return {
      kind: PlanStepKind.COVER_LETTER,
      status: hasCoverLetter ? PlanStepStatus.DONE : PlanStepStatus.TO_DO,
      title: hasCoverLetter ? "Cover letter ready" : "Write a cover letter",
      detail: hasCoverLetter
        ? "A letter for this posting has been generated from your CV and is ready to review."
        : "A short, tailored letter raises reply rates and shows you read the posting.",
        action: hasCoverLetter ? "" : `Generate a ${LETTER_LANGUAGE_META[language].label} cover letter for this posting.`,
      weight: hasCoverLetter ? 0 : COVER_LETTER_WEIGHT,
      official: false,
    };
  }

  private scamStep(job: Job, verdict: ScamVerdict | null, entry: ShortlistEntry): PlanStep {
    const effective: ScamVerdict =
      verdict ??
      (job.scamRisk === ScamRisk.HIGH
        ? ScamVerdict.HIGH_RISK
        : job.scamRisk === ScamRisk.MEDIUM
          ? ScamVerdict.NEEDS_CAUTION
          : ScamVerdict.LIKELY_SAFE);
    const weight = this.blockersFor(
      entry,
      (blocker) => blocker.label.toLowerCase().includes("scam") || blocker.label.toLowerCase().includes("checking"),
    );
    switch (effective) {
      case ScamVerdict.HIGH_RISK:
        return {
          kind: PlanStepKind.SCAM_CHECK,
          status: PlanStepStatus.BLOCKED,
          title: "Scam shield flagged this posting",
          detail: "Several risk signals were found. Do not share personal details or pay any fee.",
          action: "Open the scam check and read every signal before you continue.",
          weight: Math.max(weight, SCAM_BLOCK_WEIGHT),
          official: false,
        };
      case ScamVerdict.NEEDS_CAUTION:
        return {
          kind: PlanStepKind.SCAM_CHECK,
          status: PlanStepStatus.TO_DO,
          title: "Some details need checking",
          detail: "The scam shield found minor caution signals. Verify the employer independently.",
          action: "Confirm the employer is real before you send documents.",
          weight: Math.max(weight, SCAM_CAUTION_WEIGHT),
          official: false,
        };
      default:
        return {
          kind: PlanStepKind.SCAM_CHECK,
          status: PlanStepStatus.DONE,
          title: "No scam signals found",
          detail: "The scam shield found no risk signals. It advises, it never guarantees.",
          action: "",
          weight: 0,
          official: false,
        };
    }
  }

  private interviewStep(attempted: number, total: number): PlanStep {
    if (total === 0) {
      return {
        kind: PlanStepKind.INTERVIEW_PREP,
        status: PlanStepStatus.NOT_NEEDED,
        title: "Interview prep",
        detail: "Open the interview coach to build a rehearsal set for this posting.",
        action: "",
        weight: 0,
        official: false,
      };
    }
    const done = attempted >= total;
    return {
      kind: PlanStepKind.INTERVIEW_PREP,
      status: done ? PlanStepStatus.DONE : PlanStepStatus.TO_DO,
      title: done ? "Interview rehearsed" : "Rehearse for the interview",
      detail: `${attempted} of ${total} rehearsal questions practised for this posting.`,
      action: done ? "" : "Practise the remaining interview questions.",
      weight: done ? 0 : INTERVIEW_WEIGHT,
      official: false,
    };
  }

  private submitStep(job: Job, hasApplied: boolean): PlanStep {
    return {
      kind: PlanStepKind.SUBMIT,
      status: hasApplied ? PlanStepStatus.DONE : PlanStepStatus.TO_DO,
      title: hasApplied ? "Application submitted" : "Submit the application",
      detail: hasApplied
        ? "This application is marked as sent in your pipeline."
        : "Apply on the employer's own site or portal. The app never submits for you.",
      action: hasApplied ? "" : "Open the posting and send your application.",
      weight: 0,
      official: job.sourceUrl.trim().length > 0,
    };
  }

  private followUpStep(hasApplied: boolean): PlanStep {
    return {
      kind: PlanStepKind.FOLLOW_UP,
      status: hasApplied ? PlanStepStatus.TO_DO : PlanStepStatus.NOT_NEEDED,
      title: "Follow up",
      detail: hasApplied
        ? "Send a short follow-up about a week after applying if you have not heard back."
        : "The follow-up reminder appears once you have applied.",
      action: hasApplied ? "Set a follow-up reminder in Applications." : "",
      weight: 0,
      official: false,
    };
  }

  // ------------------------------------------------------------------ tailoring & documents

  private tailoringFor(job: Job): CvTailoring {
    const lead: TailoringHighlight[] = distinct(job.matchedSkills)
      .slice(0, MAX_LEAD_SKILLS)
      .map((skill) => ({ skill, detail: "Already on your CV and asked for by this posting." }));

    const address = distinct(job.missingSkills).slice(0, MAX_ADDRESS_SKILLS);

    const keywordPool: string[] = [
      ...job.matchedSkills,
      ...job.missingSkills,
      ...job.requirements.certifications.map((r) => r.label),
      ...job.requirements.licences.map((r) => r.label),
    ];
    if (job.matchedTargetRole) keywordPool.push(job.matchedTargetRole);
    const keywords = distinct(keywordPool.map((k) => k.trim()).filter((k) => k.length > 0)).slice(0, MAX_KEYWORDS);

    let note: string;
    if (lead.length > 0 && address.length > 0) {
      note =
        `Mirror the posting's own wording for ${lead.length} of your skills, and address ` +
        `${address.length} it lists that your CV does not yet show.`;
    } else if (lead.length > 0) {
      note = "Your CV already names the skills this posting asks for. Mirror the posting's wording.";
    } else if (address.length > 0) {
      note = "This posting lists skills your CV does not show yet. Add them only if you genuinely have them.";
    } else {
      note = "This posting does not list specific skills the app can compare against.";
    }

    return { jobTitle: job.title, leadSkills: lead, addressSkills: address, keywords, note };
  }

  private documentsFor(job: Job): DocumentRequirement[] {
    const documents: DocumentRequirement[] = [
      {
        label: "CV (updated for this posting)",
        detail: "Your analysed CV, adjusted to lead with the skills above.",
        required: true,
      },
      { label: "Cover letter", detail: `A short letter written for ${job.company}.`, required: true },
      {
        label: "Passport / travel document",
        detail: "Valid for at least six months beyond your intended start date.",
        required: true,
      },
    ];
    for (const requirement of job.requirements.certifications) {
      documents.push({
        label: requirement.label,
        detail: `Certification the posting lists: "${requirement.evidence}"`,
        required: true,
      });
    }
    for (const requirement of job.requirements.licences) {
      documents.push({
        label: requirement.label,
        detail: `Licence or permit the posting lists: "${requirement.evidence}"`,
        required: true,
      });
    }
    documents.push(
      {
        label: "Education certificates",
        detail: "Scans of your diplomas, translated to the employer's language if needed.",
        required: false,
      },
      {
        label: "References",
        detail: "Two people who can speak to your maintenance work.",
        required: false,
      },
    );
    return documents.filter((doc, index) => documents.findIndex((d) => d.label === doc.label) === index);
  }

  // ------------------------------------------------------------------ helpers

  private routeSummaryFor(job: Job): string {
    const official = job.eligibilityReasons.filter((reason) => reason.official);
    const stated = official.length > 0 ? ` "${official[0].detail}"` : "";
    const base =
      job.eligibility === MadagascarEligibility.VISA_SPONSORED
        ? `The employer states a route in for international applicants.${stated}`
        : job.eligibility === MadagascarEligibility.ELIGIBLE
          ? `This posting is open to applicants from Madagascar.${stated}`
          : job.eligibility === MadagascarEligibility.RESTRICTED
            ? "The posting is limited to local applicants, so there is no route in from Madagascar."
            : "The posting does not state whether it accepts applicants from Madagascar. " +
              "Treat this as unknown and ask the employer.";
    return base + this.visaSupportNote(job.visaSupport);
  }

  private visaSupportNote(support: VisaSupport): string {
    switch (support) {
      case VisaSupport.FULL_SPONSORSHIP:
        return " It advertises full sponsorship.";
      case VisaSupport.RELOCATION_PACKAGE:
        return " It advertises a relocation package.";
      case VisaSupport.SPONSORSHIP_AVAILABLE:
        return " It says sponsorship is available.";
      default:
        return "";
    }
  }

  private documentsVisaSponsorship(job: Job): boolean {
    return job.benefitEvidence.some(
      (evidence) => evidence.type.key === JobBenefitTypes.VISA_SPONSORSHIP.key && evidence.status === BenefitStatus.CONFIRMED,
    );
  }

  private blockersFor(entry: ShortlistEntry, predicate: (blocker: ReadinessBlocker) => boolean): number {
    return entry.blockers.filter(predicate).reduce((sum, blocker) => sum + blocker.points, 0);
  }
}

function distinct(values: string[]): string[] {
  return [...new Set(values)];
}
