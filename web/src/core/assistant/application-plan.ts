import { ApplicationReadiness, type ScamVerdict } from "../models";

/**
 * The per-posting application plan vocabulary, ported from the Kotlin
 * `data/model/ApplicationPlan.kt`. It is a self-contained sub-language: the ordered
 * checklist, the CV tailoring, the documents to prepare and the one next action. It is a
 * pure derivation of evidence the app already holds, so it can be rebuilt on every change.
 */

/** One kind of work in a per-posting application plan, in the order it is presented. */
export enum PlanStepKind {
  PROFILE = "PROFILE",
  VISA_ROUTE = "VISA_ROUTE",
  TAILOR_CV = "TAILOR_CV",
  COVER_LETTER = "COVER_LETTER",
  SCAM_CHECK = "SCAM_CHECK",
  INTERVIEW_PREP = "INTERVIEW_PREP",
  SUBMIT = "SUBMIT",
  FOLLOW_UP = "FOLLOW_UP",
}

export const PLAN_STEP_KIND_META: Record<PlanStepKind, { label: string; emoji: string }> = {
  [PlanStepKind.PROFILE]: { label: "Your profile", emoji: "📄" },
  [PlanStepKind.VISA_ROUTE]: { label: "Route in from Madagascar", emoji: "🛂" },
  [PlanStepKind.TAILOR_CV]: { label: "Tailor your CV", emoji: "✂️" },
  [PlanStepKind.COVER_LETTER]: { label: "Cover letter", emoji: "✉️" },
  [PlanStepKind.SCAM_CHECK]: { label: "Safety check", emoji: "🛡️" },
  [PlanStepKind.INTERVIEW_PREP]: { label: "Interview prep", emoji: "🎤" },
  [PlanStepKind.SUBMIT]: { label: "Submit the application", emoji: "🚀" },
  [PlanStepKind.FOLLOW_UP]: { label: "Follow up", emoji: "⏰" },
};

/** The display order of the plan, matching the Kotlin enum ordinal. */
export const PLAN_STEP_ORDER: PlanStepKind[] = [
  PlanStepKind.PROFILE,
  PlanStepKind.VISA_ROUTE,
  PlanStepKind.TAILOR_CV,
  PlanStepKind.COVER_LETTER,
  PlanStepKind.SCAM_CHECK,
  PlanStepKind.INTERVIEW_PREP,
  PlanStepKind.SUBMIT,
  PlanStepKind.FOLLOW_UP,
];

/** Where one plan step stands right now. */
export enum PlanStepStatus {
  DONE = "DONE",
  TO_DO = "TO_DO",
  BLOCKED = "BLOCKED",
  NOT_NEEDED = "NOT_NEEDED",
}

export const PLAN_STEP_STATUS_META: Record<PlanStepStatus, { label: string }> = {
  [PlanStepStatus.DONE]: { label: "Done" },
  [PlanStepStatus.TO_DO]: { label: "To do" },
  [PlanStepStatus.BLOCKED]: { label: "Blocked" },
  [PlanStepStatus.NOT_NEEDED]: { label: "Not needed yet" },
};

/** One actionable step for a single posting. */
export interface PlanStep {
  kind: PlanStepKind;
  status: PlanStepStatus;
  title: string;
  detail: string;
  /** The single thing to do next, in plain language. Empty when nothing is required. */
  action: string;
  /** Readiness points this step is currently withholding; 0 when it is already satisfied. */
  weight: number;
  /** True when the step rests on a fact quoted from the posting rather than an inference. */
  official: boolean;
}

export function planStepIsDone(step: PlanStep): boolean {
  return step.status === PlanStepStatus.DONE;
}

export function planStepIsBlocked(step: PlanStep): boolean {
  return step.status === PlanStepStatus.BLOCKED;
}

export function planStepIsActionable(step: PlanStep): boolean {
  return step.status === PlanStepStatus.TO_DO || step.status === PlanStepStatus.BLOCKED;
}

/** A skill the CV already lists that this posting rewards, shown as something to lead with. */
export interface TailoringHighlight {
  skill: string;
  detail: string;
}

/** Per-posting CV tailoring. It never rewrites history. */
export interface CvTailoring {
  jobTitle: string;
  leadSkills: TailoringHighlight[];
  addressSkills: string[];
  keywords: string[];
  note: string;
}

export function cvTailoringHasContent(tailoring: CvTailoring): boolean {
  return tailoring.leadSkills.length > 0 || tailoring.addressSkills.length > 0;
}

/** A document to have ready before applying, with why it matters and whether it is expected. */
export interface DocumentRequirement {
  label: string;
  detail: string;
  required: boolean;
}

/** The whole per-posting application plan. */
export interface ApplicationPlan {
  jobId: string;
  jobTitle: string;
  company: string;
  country: string;
  countryFlag: string;
  matchScore: number;
  readinessScore: number;
  readiness: ApplicationReadiness;
  steps: PlanStep[];
  tailoring: CvTailoring;
  documents: DocumentRequirement[];
  routeSummary: string;
  nextAction: string;
  hasCoverLetter: boolean;
  scamVerdict: ScamVerdict | null;
  generatedAtEpochMillis: number;
}

/** Steps that count towards progress; "not needed yet" steps are excluded. */
export function planCountableSteps(plan: ApplicationPlan): PlanStep[] {
  return plan.steps.filter((step) => step.status !== PlanStepStatus.NOT_NEEDED);
}

export function planDoneCount(plan: ApplicationPlan): number {
  return planCountableSteps(plan).filter(planStepIsDone).length;
}

export function planTotalCount(plan: ApplicationPlan): number {
  return planCountableSteps(plan).length;
}

export function planProgressPercent(plan: ApplicationPlan): number {
  const total = planTotalCount(plan);
  return total === 0 ? 100 : Math.trunc((planDoneCount(plan) * 100) / total);
}

/** Everything still standing between the user and a sent application, hardest-hitting first. */
export function planOpenSteps(plan: ApplicationPlan): PlanStep[] {
  return plan.steps.filter(planStepIsActionable).sort((a, b) => b.weight - a.weight);
}

/** A hard stop, quoted from the posting or the profile, that must be cleared first. */
export function planBlockers(plan: ApplicationPlan): PlanStep[] {
  return plan.steps.filter(planStepIsBlocked);
}

/**
 * True when nothing is blocked and the application essentials (CV, route, safety, a cover
 * letter) are in place. It is a readiness statement, not a promise of an offer.
 */
export function planIsReadyToApply(plan: ApplicationPlan): boolean {
  return planBlockers(plan).length === 0 && plan.hasCoverLetter && plan.tailoring.note.trim().length > 0;
}

/** The single next step to take, or null when the plan is fully done. */
export function planNextStep(plan: ApplicationPlan): PlanStep | null {
  return plan.steps.find(planStepIsBlocked) ?? plan.steps.find((step) => step.status === PlanStepStatus.TO_DO) ?? null;
}

export function planHasProfile(plan: ApplicationPlan): boolean {
  return plan.tailoring.jobTitle.trim().length > 0;
}
