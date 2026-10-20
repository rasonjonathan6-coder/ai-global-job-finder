// Domain model for the web edition. Mirrors the Kotlin data classes in
// `app/src/main/java/com/aiglobal/jobfinder/data/model/` field for field so the ported
// engines produce byte-identical results against the golden fixtures exported from Kotlin.
// The `@Immutable` Compose annotation has no meaning here and is intentionally dropped.

export enum SalaryPeriod {
  YEAR = "YEAR",
  MONTH = "MONTH",
  DAY = "DAY",
  HOUR = "HOUR",
}

/** How well the CV matches the posting, derived from `Job.matchScore`. */
export enum MatchTier {
  EXCELLENT = "EXCELLENT",
  STRONG = "STRONG",
  GOOD = "GOOD",
  FAIR = "FAIR",
  LOW = "LOW",
}

export function matchTierFromScore(score: number): MatchTier {
  if (score >= 90) return MatchTier.EXCELLENT;
  if (score >= 75) return MatchTier.STRONG;
  if (score >= 60) return MatchTier.GOOD;
  if (score >= 45) return MatchTier.FAIR;
  return MatchTier.LOW;
}

/** Whether a candidate currently in Madagascar can realistically apply. */
export enum MadagascarEligibility {
  ELIGIBLE = "ELIGIBLE",
  VISA_SPONSORED = "VISA_SPONSORED",
  UNCLEAR = "UNCLEAR",
  RESTRICTED = "RESTRICTED",
}

/** Ordering weight for "can I apply from here?". Mirrors `MadagascarEligibility.rank`. */
export function eligibilityRank(e: MadagascarEligibility): number {
  switch (e) {
    case MadagascarEligibility.ELIGIBLE:
      return 3;
    case MadagascarEligibility.VISA_SPONSORED:
      return 2;
    case MadagascarEligibility.UNCLEAR:
      return 1;
    case MadagascarEligibility.RESTRICTED:
      return 0;
  }
}

/** Relocation / visa posture advertised by the employer. */
export enum VisaSupport {
  FULL_SPONSORSHIP = "FULL_SPONSORSHIP",
  RELOCATION_PACKAGE = "RELOCATION_PACKAGE",
  SPONSORSHIP_AVAILABLE = "SPONSORSHIP_AVAILABLE",
  NOT_MENTIONED = "NOT_MENTIONED",
  LOCAL_ONLY = "LOCAL_ONLY",
}

export enum ScamRisk {
  LOW = "LOW",
  MEDIUM = "MEDIUM",
  HIGH = "HIGH",
}

/** How a scam signal reads: a danger, a caution, or a reassurance. */
export enum ScamSignalTone {
  DANGER = "DANGER",
  CAUTION = "CAUTION",
  REASSURANCE = "REASSURANCE",
}

/** How safe a posting looks after scanning. Ordered so it can be compared directly. */
export enum ScamVerdict {
  LIKELY_SAFE = "LIKELY_SAFE",
  NEEDS_CAUTION = "NEEDS_CAUTION",
  HIGH_RISK = "HIGH_RISK",
}

/** One concrete observation about a posting, always quotable and always explained. */
export interface ScamSignal {
  label: string;
  detail: string;
  /** The exact text in the posting that triggered this, or empty for structural checks. */
  evidence: string;
  tone: ScamSignalTone;
}

/**
 * The result of scanning one posting. `verdict` is derived from the signals, and the
 * signals are shown verbatim so the user can judge for themselves — the app advises, it
 * never blocks.
 */
export interface ScamReport {
  jobId: string;
  jobTitle: string;
  company: string;
  risk: ScamRisk;
  verdict: ScamVerdict;
  signals: ScamSignal[];
  checkedAtEpochMillis: number;
}

/** A 0–100 confidence that the posting is legitimate. Never shown as a guarantee. */
export function scamSafetyScore(report: ScamReport): number {
  const dangers = report.signals.filter((s) => s.tone === ScamSignalTone.DANGER).length;
  const cautions = report.signals.filter((s) => s.tone === ScamSignalTone.CAUTION).length;
  return Math.max(0, Math.min(100, 100 - dangers * 28 - cautions * 11));
}

export enum WorkMode {
  ONSITE = "ONSITE",
  HYBRID = "HYBRID",
  REMOTE = "REMOTE",
}

export enum EmploymentType {
  FULL_TIME = "FULL_TIME",
  CONTRACT = "CONTRACT",
  PART_TIME = "PART_TIME",
  TEMPORARY = "TEMPORARY",
  INTERNSHIP = "INTERNSHIP",
}

/** How a posting's title relates to what the candidate is looking for. */
export enum RoleFamily {
  TARGET = "TARGET",
  MAINTENANCE_FAMILY = "MAINTENANCE_FAMILY",
  UNRELATED = "UNRELATED",
  UNKNOWN = "UNKNOWN",
}

/** Direction of a single matching signal. */
export enum MatchSignal {
  POSITIVE = "POSITIVE",
  NEUTRAL = "NEUTRAL",
  NEGATIVE = "NEGATIVE",
}

/** Proficiency for a spoken language. */
export enum LanguageLevel {
  BEGINNER = "BEGINNER",
  INTERMEDIATE = "INTERMEDIATE",
  ADVANCED = "ADVANCED",
  FLUENT = "FLUENT",
  NATIVE = "NATIVE",
}

export const LANGUAGE_LEVEL_META: Record<LanguageLevel, { label: string }> = {
  [LanguageLevel.BEGINNER]: { label: "Beginner" },
  [LanguageLevel.INTERMEDIATE]: { label: "Intermediate" },
  [LanguageLevel.ADVANCED]: { label: "Advanced" },
  [LanguageLevel.FLUENT]: { label: "Fluent" },
  [LanguageLevel.NATIVE]: { label: "Native" },
};

/** How willing the candidate is to move country. Drives the eligibility filtering. */
export enum RelocationPreference {
  UNKNOWN = "UNKNOWN",
  PREFERS_RELOCATION = "PREFERS_RELOCATION",
  OPEN_TO_RELOCATION = "OPEN_TO_RELOCATION",
  REMOTE_ONLY = "REMOTE_ONLY",
  NO_RELOCATION = "NO_RELOCATION",
}

export const RELOCATION_PREFERENCE_META: Record<RelocationPreference, { label: string }> = {
  [RelocationPreference.UNKNOWN]: { label: "Not specified" },
  [RelocationPreference.PREFERS_RELOCATION]: { label: "Actively wants to relocate" },
  [RelocationPreference.OPEN_TO_RELOCATION]: { label: "Open to relocating" },
  [RelocationPreference.REMOTE_ONLY]: { label: "Remote only" },
  [RelocationPreference.NO_RELOCATION]: { label: "Not willing to relocate" },
};

/** What an evidence item is about. */
export enum EvidenceKind {
  ELIGIBILITY = "ELIGIBILITY",
  VISA = "VISA",
  RELOCATION = "RELOCATION",
  SALARY = "SALARY",
  REQUIREMENT = "REQUIREMENT",
  BENEFIT = "BENEFIT",
}

/**
 * How much weight an evidence item carries. DOCUMENTED vs INFERRED is the whole point:
 * a claim read from the employer's own words is shown differently from a heuristic.
 */
export enum EvidenceStrength {
  DOCUMENTED = "DOCUMENTED",
  REFUSED = "REFUSED",
  INFERRED = "INFERRED",
  NEEDS_VERIFICATION = "NEEDS_VERIFICATION",
  UNKNOWN = "UNKNOWN",
}

/** What a posting actually says about one benefit. */
export enum BenefitStatus {
  CONFIRMED = "CONFIRMED",
  NOT_OFFERED = "NOT_OFFERED",
  NEEDS_VERIFICATION = "NEEDS_VERIFICATION",
  UNKNOWN = "UNKNOWN",
}

/** How much one documented benefit is worth to the user. */
export enum BenefitPriority {
  NOT_IMPORTANT = 0,
  NICE_TO_HAVE = 1,
  IMPORTANT = 2,
  ESSENTIAL = 3,
}

export function benefitPriorityFromWeight(weight: number): BenefitPriority {
  const entries: BenefitPriority[] = [
    BenefitPriority.NOT_IMPORTANT,
    BenefitPriority.NICE_TO_HAVE,
    BenefitPriority.IMPORTANT,
    BenefitPriority.ESSENTIAL,
  ];
  let best = BenefitPriority.IMPORTANT;
  let bestDistance = Infinity;
  for (const entry of entries) {
    const distance = Math.abs(entry - weight);
    if (distance < bestDistance) {
      bestDistance = distance;
      best = entry;
    }
  }
  return best;
}

/** How reliably one posting can be acted on. */
export enum VerificationState {
  CONFIRMED = "CONFIRMED",
  POSSIBLE = "POSSIBLE",
  UNKNOWN = "UNKNOWN",
  EXPIRED = "EXPIRED",
  INACCESSIBLE = "INACCESSIBLE",
  NOT_OFFERED = "NOT_OFFERED",
}

export function verificationIsUnknown(s: VerificationState): boolean {
  return s === VerificationState.UNKNOWN;
}

export function verificationIsNegative(s: VerificationState): boolean {
  return s === VerificationState.EXPIRED || s === VerificationState.INACCESSIBLE;
}

/** The kind of fact behind one verification fact. */
export enum VerificationAspect {
  SOURCE = "SOURCE",
  APPLICATION_LINK = "APPLICATION_LINK",
  VACANCY_ACTIVITY = "VACANCY_ACTIVITY",
  APPLICATION_DEADLINE = "APPLICATION_DEADLINE",
  VISA = "VISA",
  RELOCATION = "RELOCATION",
  FRESHNESS = "FRESHNESS",
}

/**
 * Where an "Apply" tap would actually take the candidate. Deliberately explicit: the app
 * never opens a link it has not first described.
 */
export enum ApplicationDestination {
  /** The employer's own posting page. */
  EMPLOYER_SITE = "EMPLOYER_SITE",
  /** The job board or aggregator that published the listing. */
  JOB_BOARD = "JOB_BOARD",
  /** A known government or official immigration portal. */
  GOVERNMENT_PORTAL = "GOVERNMENT_PORTAL",
  /** A valid link, but the app cannot vouch for the host. */
  UNKNOWN_HOST = "UNKNOWN_HOST",
  /** Not a usable web address. */
  INVALID = "INVALID",
}

/** The verdict for one posting's application link. */
export interface ApplicationLinkInspection {
  url: string;
  host: string | null;
  destination: ApplicationDestination;
  canOpen: boolean;
  message: string;
  warnings: string[];
}

/** How well one posting fits this candidate as an international applicant, as a band. */
export enum InternationalFitBand {
  INSUFFICIENT = "INSUFFICIENT",
  WEAK = "WEAK",
  POSSIBLE = "POSSIBLE",
  STRONG = "STRONG",
  EXCELLENT = "EXCELLENT",
}

export const INTERNATIONAL_FIT_EXCELLENT_MIN = 80;
export const INTERNATIONAL_FIT_STRONG_MIN = 65;
export const INTERNATIONAL_FIT_POSSIBLE_MIN = 45;

export function internationalFitBandFromScore(score: number): InternationalFitBand {
  if (score >= INTERNATIONAL_FIT_EXCELLENT_MIN) return InternationalFitBand.EXCELLENT;
  if (score >= INTERNATIONAL_FIT_STRONG_MIN) return InternationalFitBand.STRONG;
  if (score >= INTERNATIONAL_FIT_POSSIBLE_MIN) return InternationalFitBand.POSSIBLE;
  return InternationalFitBand.WEAK;
}

export function isInternationalFitActionable(band: InternationalFitBand): boolean {
  return band === InternationalFitBand.STRONG || band === InternationalFitBand.EXCELLENT;
}

// --------------------------------------------------------------------------- interfaces

export interface LanguageSkill {
  name: string;
  level: LanguageLevel | null;
  evidence: string;
}

/** The label shown for a language skill. Mirrors the Kotlin `LanguageSkill.displayLabel`. */
export function languageSkillDisplayLabel(skill: LanguageSkill): string {
  return skill.level == null ? skill.name : `${skill.name} — ${LANGUAGE_LEVEL_META[skill.level].label}`;
}

export interface CertificationEntry {
  name: string;
  issuer: string;
  year: number | null;
}

/** One qualification on the CV. Mirrors the Kotlin `EducationEntry`. */
export interface EducationEntry {
  degree: string;
  institution: string;
  country: string;
  year: number;
}

/** The structured, matching-ready view of the candidate. */
export interface CandidateProfile {
  fullName: string;
  email: string;
  phone: string;
  location: string;
  currentTitle: string;
  previousTitles: string[];
  yearsOfExperience: number | null;
  skills: string[];
  industries: string[];
  education: EducationEntry[];
  certifications: CertificationEntry[];
  languages: LanguageSkill[];
  preferredCountries: string[];
  preferredRoles: string[];
  salaryExpectationMin: number | null;
  salaryExpectationMax: number | null;
  salaryCurrency: string | null;
  relocationPreference: RelocationPreference;
  needsVisaSponsorship: boolean | null;
  workPermitNotes: string;
  summary: string;
}

export function emptyCandidateProfile(): CandidateProfile {
  return {
    fullName: "",
    email: "",
    phone: "",
    location: "",
    currentTitle: "",
    previousTitles: [],
    yearsOfExperience: null,
    skills: [],
    industries: [],
    education: [],
    certifications: [],
    languages: [],
    preferredCountries: [],
    preferredRoles: [],
    salaryExpectationMin: null,
    salaryExpectationMax: null,
    salaryCurrency: null,
    relocationPreference: RelocationPreference.UNKNOWN,
    needsVisaSponsorship: null,
    workPermitNotes: "",
    summary: "",
  };
}

export function profileInitials(profile: CandidateProfile): string {
  const initials = profile.fullName
    .trim()
    .split(" ")
    .filter((part) => part.trim().length > 0)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? "")
    .join("");
  return initials || "CV";
}

export function profileAllTitles(profile: CandidateProfile): string[] {
  const titles = [profile.currentTitle, ...profile.previousTitles].filter(
    (t) => t.trim().length > 0,
  );
  return Array.from(new Set(titles));
}

export interface MatchReason {
  label: string;
  detail: string;
  signal: MatchSignal;
  weight: number;
}

export interface EligibilityReason {
  label: string;
  detail: string;
  official: boolean;
}

export interface MatchWeights {
  skills: number;
  title: number;
  experience: number;
  location: number;
  language: number;
  recency: number;
}

export function defaultMatchWeights(): MatchWeights {
  return { skills: 34, title: 24, experience: 16, location: 12, language: 8, recency: 6 };
}

export interface BenefitType {
  key: string;
  label: string;
  shortLabel: string;
  emoji: string;
  defaultPriority: number;
}

export interface BenefitEvidence {
  type: BenefitType;
  status: BenefitStatus;
  evidence: string;
  source: string;
  verifiedAtEpochMillis: number | null;
}

export interface BenefitScoreComponent {
  type: BenefitType;
  priority: BenefitPriority;
  status: BenefitStatus;
  evidence: string;
  credit: number;
  potentialPoints: number;
  earnedPoints: number;
}

export interface ExpatPreferences {
  priorities: Record<string, number>;
  minimumSalaryEur: number | null;
  targetCountries: string[];
  minExpatScore: number;
  minMatchScore: number;
  requireVisaSponsorship: boolean;
}

export function defaultExpatPreferences(): ExpatPreferences {
  const priorities: Record<string, number> = {};
  for (const type of ALL_BENEFIT_TYPES) priorities[type.key] = type.defaultPriority;
  return {
    priorities,
    minimumSalaryEur: null,
    targetCountries: [],
    minExpatScore: 0,
    minMatchScore: 0,
    requireVisaSponsorship: false,
  };
}

export interface ExpatBenefitsAssessment {
  score: number;
  evidenceCoverage: number;
  components: BenefitScoreComponent[];
  explanation: string;
  unmetEssentials: BenefitType[];
  lowEvidence: boolean;
}

export function expatAssessmentHasEvidence(a: ExpatBenefitsAssessment): boolean {
  return a.components.some((c) => c.status !== BenefitStatus.UNKNOWN);
}

export interface JobRequirement {
  label: string;
  evidence: string;
}

export interface JobRequirements {
  minYears: number | null;
  certifications: JobRequirement[];
  licences: JobRequirement[];
}

export function emptyJobRequirements(): JobRequirements {
  return { minYears: null, certifications: [], licences: [] };
}

export interface QualificationGap {
  label: string;
  evidence: string;
  met: boolean | null;
}

export interface Evidence {
  kind: EvidenceKind;
  strength: EvidenceStrength;
  label: string;
  detail: string;
  sourceUrl: string;
}

export interface InternationalFitComponent {
  label: string;
  detail: string;
  strength: EvidenceStrength;
  points: number;
  maxPoints: number;
}

export function earnedShare(c: InternationalFitComponent): number {
  return c.maxPoints <= 0 ? 0 : c.points / c.maxPoints;
}

export interface InternationalFitAssessment {
  score: number;
  band: InternationalFitBand;
  components: InternationalFitComponent[];
  blockers: string[];
  routeSummary: string;
  expatBenefitsScore: number | null;
  expatEvidenceCoverage: number;
  expatLowEvidence: boolean;
  explanation: string;
}

export interface VerificationFact {
  aspect: VerificationAspect;
  strength: EvidenceStrength;
  label: string;
  detail: string;
  sourceUrl: string;
}

export interface OfferVerification {
  activity: VerificationState;
  deadline: VerificationState;
  link: VerificationState;
  visa: VerificationState;
  relocation: VerificationState;
  lastRetrievedAtEpochMillis: number | null;
  freshness: VerificationState;
  facts: VerificationFact[];
  caveats: string[];
  summary: string;
}

export function verificationIsActionable(v: OfferVerification): boolean {
  return !verificationIsNegative(v.activity) && !verificationIsNegative(v.link);
}

export interface Job {
  id: string;
  title: string;
  company: string;
  companyAccent: number;
  country: string;
  countryFlag: string;
  city: string;
  salaryMin: number;
  salaryMax: number;
  currencyCode: string;
  salaryPeriod: SalaryPeriod;
  matchScore: number;
  eligibility: MadagascarEligibility;
  visaSupport: VisaSupport;
  scamRisk: ScamRisk;
  postedAtEpochMillis: number | null;
  expiresAtEpochMillis: number | null;
  retrievedAtEpochMillis: number | null;
  source: string;
  sourceUrl: string;
  workMode: WorkMode;
  employmentType: EmploymentType;
  experienceYears: number;
  matchedSkills: string[];
  missingSkills: string[];
  languages: string[];
  summary: string;
  responsibilities: string[];
  benefits: string[];
  aiInsight: string;
  providerId: string;
  salaryIsEstimated: boolean;
  salaryPeriodInferred: boolean;
  salaryUnitUncertain: boolean;
  matchReasons: MatchReason[];
  eligibilityReasons: EligibilityReason[];
  benefitEvidence: BenefitEvidence[];
  roleFamily: RoleFamily;
  matchedTargetRole: string | null;
  requirements: JobRequirements;
  qualificationGaps: QualificationGap[];
  evidence: Evidence[];
  openToApplicants: boolean | null;
  verification: OfferVerification;
  isSaved: boolean;
  isNew: boolean;
}

export function jobTier(job: Job): MatchTier {
  return matchTierFromScore(job.matchScore);
}

export function jobIsMaintenanceRelevant(job: Job): boolean {
  return job.roleFamily === RoleFamily.TARGET || job.roleFamily === RoleFamily.MAINTENANCE_FAMILY;
}

export function jobApplyableFromMadagascar(job: Job): boolean {
  return (
    job.eligibility === MadagascarEligibility.ELIGIBLE ||
    job.eligibility === MadagascarEligibility.VISA_SPONSORED
  );
}

export interface DashboardStats {
  matchingJobs: number;
  newOpportunities: number;
  applications: number;
  interviews: number;
}

// --------------------------------------------------------------------------- benefit catalog

/**
 * The fixed benefit vocabulary. Keys are stable identifiers persisted in DataStore on
 * Android and in the web profile store, so they must not be renamed without a migration.
 */
export const JobBenefitTypes = {
  VISA_SPONSORSHIP: { key: "visa_sponsorship", label: "Visa sponsorship", shortLabel: "Visa", emoji: "🛂", defaultPriority: 3 },
  WORK_PERMIT: { key: "work_permit", label: "Work permit support", shortLabel: "Work permit", emoji: "📄", defaultPriority: 2 },
  RELOCATION_SUPPORT: { key: "relocation_support", label: "Relocation support", shortLabel: "Relocation", emoji: "📦", defaultPriority: 2 },
  ACCOMMODATION: { key: "accommodation", label: "Accommodation provided", shortLabel: "Housing", emoji: "🏠", defaultPriority: 2 },
  FLIGHTS_HOME: { key: "flights_home", label: "Annual flights home", shortLabel: "Flights", emoji: "✈️", defaultPriority: 1 },
  HEALTH_INSURANCE: { key: "health_insurance", label: "Health insurance", shortLabel: "Health cover", emoji: "🏥", defaultPriority: 1 },
  PAID_LEAVE: { key: "paid_leave", label: "Paid leave / annual leave", shortLabel: "Leave", emoji: "🌴", defaultPriority: 1 },
  MEALS: { key: "meals", label: "Meals provided", shortLabel: "Meals", emoji: "🍽️", defaultPriority: 0 },
  TRANSPORT: { key: "transport", label: "Transport / allowance", shortLabel: "Transport", emoji: "🚌", defaultPriority: 0 },
  LANGUAGE_TRAINING: { key: "language_training", label: "Language training", shortLabel: "Language", emoji: "🗣️", defaultPriority: 0 },
  FAMILY_SUPPORT: { key: "family_support", label: "Family / dependants support", shortLabel: "Family", emoji: "👨‍👩‍👧", defaultPriority: 1 },
  MINIMUM_SALARY: { key: "minimum_salary", label: "Salary meets your minimum", shortLabel: "Salary", emoji: "💰", defaultPriority: 2 },
} as const satisfies Record<string, BenefitType>;

/** Stable display order for the benefits read from the posting text. */
export const ALL_BENEFIT_TYPES: BenefitType[] = [
  JobBenefitTypes.VISA_SPONSORSHIP,
  JobBenefitTypes.WORK_PERMIT,
  JobBenefitTypes.RELOCATION_SUPPORT,
  JobBenefitTypes.ACCOMMODATION,
  JobBenefitTypes.FLIGHTS_HOME,
  JobBenefitTypes.HEALTH_INSURANCE,
  JobBenefitTypes.PAID_LEAVE,
  JobBenefitTypes.FAMILY_SUPPORT,
  JobBenefitTypes.MEALS,
  JobBenefitTypes.TRANSPORT,
  JobBenefitTypes.LANGUAGE_TRAINING,
];

export const SELECTABLE_BENEFIT_TYPES: BenefitType[] = [
  ...ALL_BENEFIT_TYPES,
  JobBenefitTypes.MINIMUM_SALARY,
];

export function benefitTypeByKey(key: string): BenefitType | null {
  return SELECTABLE_BENEFIT_TYPES.find((t) => t.key === key) ?? null;
}

export function benefitPriorityOf(preferences: ExpatPreferences, type: BenefitType): BenefitPriority {
  const weight = preferences.priorities[type.key] ?? type.defaultPriority;
  return benefitPriorityFromWeight(weight);
}

// --------------------------------------------------------------------------- empty values

export const EMPTY_EXPAT_ASSESSMENT: ExpatBenefitsAssessment = {
  score: 0,
  evidenceCoverage: 0,
  components: [],
  explanation: "Import and analyse your CV, then open a posting to score its benefits.",
  unmetEssentials: [],
  lowEvidence: true,
};

export const EMPTY_INTERNATIONAL_FIT: InternationalFitAssessment = {
  score: 0,
  band: InternationalFitBand.INSUFFICIENT,
  components: [],
  blockers: [],
  routeSummary: "Import and analyse your CV, then open a posting to see your international fit.",
  expatBenefitsScore: null,
  expatEvidenceCoverage: 0,
  expatLowEvidence: true,
  explanation: "There is not enough on your profile yet to judge this posting.",
};

export const UNKNOWN_VERIFICATION: OfferVerification = {
  activity: VerificationState.UNKNOWN,
  deadline: VerificationState.UNKNOWN,
  link: VerificationState.UNKNOWN,
  visa: VerificationState.UNKNOWN,
  relocation: VerificationState.UNKNOWN,
  lastRetrievedAtEpochMillis: null,
  freshness: VerificationState.UNKNOWN,
  facts: [],
  caveats: [],
  summary: "This posting has not been verified yet.",
};

// --------------------------------------------------------------------------- AI capability

/**
 * How a feature actually works. Ported from the Kotlin `AiCapabilityKind`: the app must never
 * describe a deterministic rule as a generative model, and must never imply that CV text is
 * sent to a cloud service when it is not. `label` is the honest name the UI shows.
 */
export enum AiCapabilityKind {
  /** A pure function of its inputs: phrase tables, weights, thresholds. */
  RULE_BASED = "RULE_BASED",
  /** Reads a document into structured fields (PDF/DOCX/TXT). */
  DOCUMENT_PARSER = "DOCUMENT_PARSER",
  /** Template filled from verified facts only. */
  TEMPLATE = "TEMPLATE",
  /** A trained machine-learning model running on the device. */
  ON_DEVICE_MODEL = "ON_DEVICE_MODEL",
  /** A remote generative or ML service, reached only when the user configures and enables it. */
  REMOTE_MODEL = "REMOTE_MODEL",
}

export const AI_CAPABILITY_KIND_META: Record<AiCapabilityKind, { label: string }> = {
  [AiCapabilityKind.RULE_BASED]: { label: "On-device rules" },
  [AiCapabilityKind.DOCUMENT_PARSER]: { label: "Document parser" },
  [AiCapabilityKind.TEMPLATE]: { label: "Grounded template" },
  [AiCapabilityKind.ON_DEVICE_MODEL]: { label: "On-device model" },
  [AiCapabilityKind.REMOTE_MODEL]: { label: "Remote AI service" },
};

/** Where the feature runs, so the privacy claim is explicit rather than implied. */
export enum AiProcessingLocation {
  ON_DEVICE = "ON_DEVICE",
  /** Data leaves the device and is processed by NVIDIA. */
  REMOTE = "REMOTE",
}

export const AI_PROCESSING_LOCATION_META: Record<AiProcessingLocation, { label: string }> = {
  [AiProcessingLocation.ON_DEVICE]: { label: "On this device" },
  [AiProcessingLocation.REMOTE]: { label: "Sent to NVIDIA" },
};

/**
 * One capability of the app, described exactly as it is implemented. `note` carries the
 * plain-language caveat that keeps the screen truthful (for example, that a rule-based score
 * is not a machine-learning prediction).
 */
export interface AiCapability {
  name: string;
  kind: AiCapabilityKind;
  location: AiProcessingLocation;
  description: string;
  note: string;
}

/**
 * The complete, current inventory of what the "AI" in this app does. Mirrors the Kotlin
 * `AiCapabilityCatalog`; every entry names the mechanism that actually runs, so the list can
 * be checked against the source tree.
 */
export const AI_CAPABILITY_CATALOG = {
  /** True when the app ships a trained on-device model. It does not, today. */
  HAS_ON_DEVICE_MODEL: false,
  /** True when the app can contact a remote AI service. It can, once the user configures NVIDIA. */
  HAS_REMOTE_MODEL: true,
  capabilities: [
    {
      name: "CV reading",
      kind: AiCapabilityKind.DOCUMENT_PARSER,
      location: AiProcessingLocation.ON_DEVICE,
      description: "Opens your PDF, Word or text CV and reads it into structured fields.",
      note: "This is a document parser, not a language model. It reads the file you picked and nothing else.",
    },
    {
      name: "CV skill extraction",
      kind: AiCapabilityKind.RULE_BASED,
      location: AiProcessingLocation.ON_DEVICE,
      description:
        "Finds skills, titles, employers, dates and languages using a fixed vocabulary and pattern rules.",
      note: "Deterministic and offline. A skill is only listed when its phrase appears in your CV.",
    },
    {
      name: "Job matching",
      kind: AiCapabilityKind.RULE_BASED,
      location: AiProcessingLocation.ON_DEVICE,
      description:
        "Scores each posting on six weighted signals: role, skills, experience, location, language and recency.",
      note: "A transparent weighted score, not a machine-learning prediction. Every point is explained.",
    },
    {
      name: "Madagascar eligibility check",
      kind: AiCapabilityKind.RULE_BASED,
      location: AiProcessingLocation.ON_DEVICE,
      description:
        "Reads the posting for statements about who may apply and whether a visa is sponsored.",
      note: "Only what the posting states is treated as fact; silence is reported as 'not stated'.",
    },
    {
      name: "Expat benefit evidence",
      kind: AiCapabilityKind.RULE_BASED,
      location: AiProcessingLocation.ON_DEVICE,
      description:
        "Finds accommodation, relocation, flights, leave and insurance claims with the sentence that proves each one.",
      note: "Benefits are never assumed from a job being international; each claim carries its evidence.",
    },
    {
      name: "Scam screening",
      kind: AiCapabilityKind.RULE_BASED,
      location: AiProcessingLocation.ON_DEVICE,
      description:
        "Flags upfront fees, unregistered employers, unrealistic pay and contact red flags.",
      note: "A rule-based warning list, not a guarantee. Always verify the employer yourself.",
    },
    {
      name: "Cover letter drafting",
      kind: AiCapabilityKind.TEMPLATE,
      location: AiProcessingLocation.ON_DEVICE,
      description: "Writes a letter from your verified CV facts and the selected posting.",
      note: "A grounded template, not a generative model. It reports which facts it could and could not use.",
    },
    {
      name: "Interview coaching",
      kind: AiCapabilityKind.TEMPLATE,
      location: AiProcessingLocation.ON_DEVICE,
      description:
        "Builds practice questions from the posting and your CV and scores your answers on structure.",
      note: "Question templates and a rubric, not a live AI interviewer.",
    },
    {
      name: "AI drafting assistant",
      kind: AiCapabilityKind.REMOTE_MODEL,
      location: AiProcessingLocation.REMOTE,
      description:
        "An optional NVIDIA-hosted language model that explains a match, drafts a cover letter, or rewrites your summary.",
      note: "Off until you turn it on. When on, only your CV facts and the posting text are sent to NVIDIA — never the CV file, your email, phone or address. Review every draft before you send it.",
    },
  ] as AiCapability[],
  summary:
    "Most capabilities run on your device as transparent rules and parsers. " +
    "One optional drafting assistant uses NVIDIA's hosted model, and only when you " +
    "turn it on — your CV file is never uploaded.",
} as const;

// --------------------------------------------------------------------------- cover letter

/** Languages the cover-letter writer can produce, and which market each fits. */
export enum LetterLanguage {
  ENGLISH = "ENGLISH",
  FRENCH = "FRENCH",
  GERMAN = "GERMAN",
}

export const LETTER_LANGUAGE_META: Record<LetterLanguage, { label: string; code: string; flag: string }> = {
  [LetterLanguage.ENGLISH]: { label: "English", code: "en", flag: "🇬🇧" },
  [LetterLanguage.FRENCH]: { label: "French", code: "fr", flag: "🇫🇷" },
  [LetterLanguage.GERMAN]: { label: "German", code: "de", flag: "🇩🇪" },
};

/**
 * Picks the language an employer most likely expects. France and most of francophone Africa
 * read French; Germany, Austria and Switzerland read German. Everything else defaults to
 * English, the safest international choice. Mirrors `LetterLanguage.forCountry`.
 */
export function letterLanguageForCountry(country: string): LetterLanguage {
  const key = country.trim().toLowerCase();
  const french = [
    "france", "belgium", "luxembourg", "monaco", "madagascar", "morocco", "tunisia",
    "senegal", "ivory coast", "côte d'ivoire", "cameroon", "canada",
  ];
  const german = ["germany", "austria", "switzerland", "liechtenstein"];
  if (french.includes(key)) return LetterLanguage.FRENCH;
  if (german.includes(key)) return LetterLanguage.GERMAN;
  return LetterLanguage.ENGLISH;
}

/** A cover letter written from the candidate's own CV and the posting. Nothing is invented. */
export interface CoverLetter {
  id: string;
  jobId: string;
  language: LetterLanguage;
  subject: string;
  salutation: string;
  paragraphs: string[];
  closing: string;
  signature: string;
  /** Exactly what the generator had to work with. */
  usedFacts: string[];
  /** What it did not find, so the user sees the gap rather than a fabricated sentence. */
  missingFacts: string[];
  generatedAtEpochMillis: number;
  /** True once the user has edited the body by hand. */
  edited: boolean;
}

/** The whole letter as plain text, ready to copy or export. Mirrors `CoverLetter.asPlainText`. */
export function coverLetterPlainText(letter: CoverLetter): string {
  const lines: string[] = [letter.subject, "", letter.salutation, ""];
  for (const paragraph of letter.paragraphs) {
    lines.push(paragraph, "");
  }
  lines.push(letter.closing, letter.signature);
  return lines.join("\n");
}

export function coverLetterWordCount(letter: CoverLetter): number {
  return letter.paragraphs.join(" ").split(/\s+/).filter((w) => w.length > 0).length;
}

// --------------------------------------------------------------------------- interview coach

/** The kind of interview question, used to group the rehearsal list. */
export enum InterviewCategory {
  OPENING = "OPENING",
  EXPERIENCE = "EXPERIENCE",
  TECHNICAL = "TECHNICAL",
  BEHAVIOURAL = "BEHAVIOURAL",
  RELOCATION = "RELOCATION",
  LANGUAGE = "LANGUAGE",
  SALARY = "SALARY",
  ASK_THEM = "ASK_THEM",
}

export const INTERVIEW_CATEGORY_META: Record<InterviewCategory, { label: string; emoji: string }> = {
  [InterviewCategory.OPENING]: { label: "Opening", emoji: "👋" },
  [InterviewCategory.EXPERIENCE]: { label: "Your experience", emoji: "🧰" },
  [InterviewCategory.TECHNICAL]: { label: "Technical", emoji: "🔧" },
  [InterviewCategory.BEHAVIOURAL]: { label: "Behavioural", emoji: "🧠" },
  [InterviewCategory.RELOCATION]: { label: "Relocation & visa", emoji: "🛂" },
  [InterviewCategory.LANGUAGE]: { label: "Language check", emoji: "🗣️" },
  [InterviewCategory.SALARY]: { label: "Salary & contract", emoji: "💰" },
  [InterviewCategory.ASK_THEM]: { label: "Questions to ask them", emoji: "❓" },
};

/** The order categories are displayed in. */
export const INTERVIEW_CATEGORY_ORDER: InterviewCategory[] = [
  InterviewCategory.OPENING,
  InterviewCategory.EXPERIENCE,
  InterviewCategory.TECHNICAL,
  InterviewCategory.BEHAVIOURAL,
  InterviewCategory.RELOCATION,
  InterviewCategory.LANGUAGE,
  InterviewCategory.SALARY,
  InterviewCategory.ASK_THEM,
];

/**
 * One rehearsal question, always tied to this posting and this CV. `structure` is the
 * STAR-style skeleton the user should fill in, `grounding` lists the facts the answer can
 * lean on, and `missingFacts` lists what the CV does not yet provide.
 */
export interface InterviewQuestion {
  id: string;
  category: InterviewCategory;
  prompt: string;
  whyItMatters: string;
  structure: string[];
  /** CV/posting facts the answer can lean on. Defaults to empty, as in Kotlin. */
  grounding?: string[];
  /** What the CV does not yet provide. Defaults to empty, as in Kotlin. */
  missingFacts?: string[];
}

/** The full rehearsal set for one application. */
export interface InterviewPrep {
  jobId: string;
  jobTitle: string;
  company: string;
  country: string;
  countryFlag: string;
  questions: InterviewQuestion[];
  focusAreas: string[];
  generatedAtEpochMillis: number;
}

/** How the candidate rated their own attempt. Feeds the readiness score. */
export enum AnswerConfidence {
  NOT_READY = "NOT_READY",
  GETTING_THERE = "GETTING_THERE",
  CONFIDENT = "CONFIDENT",
}

export const ANSWER_CONFIDENCE_META: Record<AnswerConfidence, { label: string }> = {
  [AnswerConfidence.NOT_READY]: { label: "Needs work" },
  [AnswerConfidence.GETTING_THERE]: { label: "Getting there" },
  [AnswerConfidence.CONFIDENT]: { label: "Confident" },
};

/** A saved rehearsal attempt, kept so readiness is derived and never guessed. */
export interface InterviewAttempt {
  questionId: string;
  confidence: AnswerConfidence;
  atEpochMillis: number;
}

/** Deterministic feedback on a written answer. */
export interface AnswerFeedback {
  score: number;
  headline: string;
  strengths: string[];
  improvements: string[];
}

/** Progress through a rehearsal set, derived from the saved attempts. */
export interface InterviewReadiness {
  attempted: number;
  total: number;
  confident: number;
  percent: number;
  isComplete: boolean;
  label: string;
}

// --------------------------------------------------------------------------- shortlist / readiness

/**
 * How ready one posting is for the candidate to actually apply, once the CV match, the
 * Madagascar eligibility verdict and the user's expat priorities have all been considered.
 * Ordered weakest → strongest. Deliberately never merged into `Job.matchScore`.
 */
export enum ApplicationReadiness {
  BLOCKED = "BLOCKED",
  NEEDS_REVIEW = "NEEDS_REVIEW",
  PREPARE_FIRST = "PREPARE_FIRST",
  READY = "READY",
  PRIORITY = "PRIORITY",
}

export const APPLICATION_READINESS_META: Record<
  ApplicationReadiness,
  { label: string; shortLabel: string; emoji: string }
> = {
  [ApplicationReadiness.BLOCKED]: { label: "Not open to you", shortLabel: "Blocked", emoji: "⛔" },
  [ApplicationReadiness.NEEDS_REVIEW]: { label: "Verify before applying", shortLabel: "Verify", emoji: "⚠️" },
  [ApplicationReadiness.PREPARE_FIRST]: { label: "Close — close the gaps", shortLabel: "Prepare", emoji: "🧩" },
  [ApplicationReadiness.READY]: { label: "Ready to apply", shortLabel: "Ready", emoji: "✅" },
  [ApplicationReadiness.PRIORITY]: { label: "Top priority", shortLabel: "Priority", emoji: "🏆" },
};

/** Lowest score in the top band. Shared so the dashboard and the bands cannot disagree. */
export const READINESS_PRIORITY_MIN = 85;
/** Lowest score in the ready band. */
export const READINESS_READY_MIN = 70;

export function applicationReadinessFromScore(score: number): ApplicationReadiness {
  if (score >= READINESS_PRIORITY_MIN) return ApplicationReadiness.PRIORITY;
  if (score >= READINESS_READY_MIN) return ApplicationReadiness.READY;
  if (score >= 50) return ApplicationReadiness.PREPARE_FIRST;
  if (score >= 30) return ApplicationReadiness.NEEDS_REVIEW;
  return ApplicationReadiness.BLOCKED;
}

export function applicationReadinessIsActionable(band: ApplicationReadiness): boolean {
  return band === ApplicationReadiness.READY || band === ApplicationReadiness.PRIORITY;
}

/** Whether a readiness finding came from the posting itself or from the app's inference. */
export enum BlockerKind {
  OFFICIAL = "OFFICIAL",
  HEURISTIC = "HEURISTIC",
  PROFILE = "PROFILE",
}

export enum NextStepAction {
  APPLY = "APPLY",
  REVIEW_ELIGIBILITY = "REVIEW_ELIGIBILITY",
  CONFIRM_BENEFITS = "CONFIRM_BENEFITS",
  COMPLETE_PROFILE = "COMPLETE_PROFILE",
  TAILOR_CV = "TAILOR_CV",
  RUN_SCAM_CHECK = "RUN_SCAM_CHECK",
  OPEN_JOB = "OPEN_JOB",
}

/** One concrete next action for a posting, always honest about whose side the gap is on. */
export interface NextStep {
  label: string;
  detail: string;
  action: NextStepAction;
}

/** One concrete reason a posting is not yet fully ready. */
export interface ReadinessBlocker {
  label: string;
  detail: string;
  kind: BlockerKind;
  points: number;
}

/** One ingredient of the readiness score, so the rank can be opened up and read. */
export interface RankComponent {
  label: string;
  detail: string;
  points: number;
  maxPoints: number;
}

/** A posting judged fit for the expat shortlist, with the full reasoning behind its rank. */
export interface ShortlistEntry {
  job: Job;
  matchScore: number;
  expatScore: number;
  readinessScore: number;
  readiness: ApplicationReadiness;
  blockers: ReadinessBlocker[];
  highlights: string[];
  generatedAtEpochMillis: number;
  rankComponents: RankComponent[];
  expatEvidenceCoverage: number;
  benefitsMatched: number;
  benefitsConsidered: number;
}

/** Labels in `EligibilityReason` that mark a stated, official route in. */
const OFFICIAL_ROUTE_LABELS = [
  "visa sponsorship",
  "relocation support",
  "open to international",
  "remote or worldwide",
];

export function shortlistHasOfficialRoute(entry: ShortlistEntry): boolean {
  return entry.job.eligibilityReasons.some(
    (reason) => reason.official && OFFICIAL_ROUTE_LABELS.some((label) => reason.label.toLowerCase().includes(label)),
  );
}

/** What is holding this posting back, hardest-hitting first. */
export function orderedBlockers(entry: ShortlistEntry): ReadinessBlocker[] {
  return [...entry.blockers].sort((a, b) => b.points - a.points);
}

/** True when the blocker is about the user's own profile rather than the posting. Mirrors `ReadinessBlocker.isProfileGap`. */
export function readinessBlockerIsProfileGap(blocker: ReadinessBlocker): boolean {
  return blocker.kind === BlockerKind.PROFILE;
}

/**
 * The one action that moves this posting forward, derived from the hardest-hitting blocker
 * so the advice and the score always agree. Mirrors `ShortlistEntry.nextStep`.
 */
export function shortlistNextStep(entry: ShortlistEntry): NextStep {
  const hardest = orderedBlockers(entry)[0]?.label ?? null;
  if (hardest === null) {
    return {
      label: "Ready to apply",
      detail: "The route and your profile both line up — open the posting and apply.",
      action: NextStepAction.APPLY,
    };
  }
  switch (hardest) {
    case "Eligibility not stated":
      return {
        label: "Check eligibility first",
        detail: "The posting does not say whether it accepts applicants from Madagascar. Ask before applying.",
        action: NextStepAction.REVIEW_ELIGIBILITY,
      };
    case "Restricted to local applicants":
      return {
        label: "Not open to you",
        detail: "The employer restricts this role to local applicants, so there is no route in.",
        action: NextStepAction.OPEN_JOB,
      };
    case "Confirm the visa terms":
      return {
        label: "Confirm the visa terms",
        detail: "Sponsorship is mentioned but the conditions are not spelled out. Confirm the fees and dependants.",
        action: NextStepAction.REVIEW_ELIGIBILITY,
      };
    case "Benefits not stated":
      return {
        label: "Ask about the package",
        detail: "No visa, relocation or housing support is documented. That is missing information, not a refusal.",
        action: NextStepAction.CONFIRM_BENEFITS,
      };
    case "Documented benefits fall short":
      return {
        label: "Review the benefits",
        detail: "The documented benefits score below your priorities. Read the evidence before deciding.",
        action: NextStepAction.CONFIRM_BENEFITS,
      };
    case "Scam shield flagged this posting":
      return {
        label: "Run the scam check",
        detail: "Several risk signals were found. Open the scam check before sharing any details.",
        action: NextStepAction.RUN_SCAM_CHECK,
      };
    case "Some details need checking":
      return {
        label: "Verify the employer",
        detail: "The scam shield found minor caution signals. Check the employer independently first.",
        action: NextStepAction.RUN_SCAM_CHECK,
      };
    case "No name on your profile":
      return {
        label: "Add your details",
        detail: "Import and analyse your CV so applications carry your real name and details.",
        action: NextStepAction.COMPLETE_PROFILE,
      };
    default:
      if (hardest.startsWith("CV match")) {
        return {
          label: "Tailor your CV",
          detail: "Close the skill gaps this posting lists, then adapt your CV to it before applying.",
          action: NextStepAction.TAILOR_CV,
        };
      }
      if (hardest.startsWith("Profile")) {
        return {
          label: "Complete your profile",
          detail: "A fuller profile scores more accurately, so this match can be trusted.",
          action: NextStepAction.COMPLETE_PROFILE,
        };
      }
      return {
        label: "Review before applying",
        detail: "Open the posting to see the full reasoning and what is in the way.",
        action: NextStepAction.OPEN_JOB,
      };
  }
}

export function blockerSummary(entry: ShortlistEntry): string {
  if (entry.blockers.length === 0) {
    return "Nothing is holding this back — the route and your profile both line up.";
  }
  if (entry.blockers.length === 1) {
    return `1 thing to sort: ${entry.blockers[0].label.toLowerCase()}.`;
  }
  return `${entry.blockers.length} things to sort, starting with ${orderedBlockers(entry)[0].label.toLowerCase()}.`;
}

/** How strong the shortlist itself looks, so the dashboard can be honest about a thin list. */
export enum ShortlistQualityBand {
  NONE = "NONE",
  THIN = "THIN",
  BUILDING = "BUILDING",
  STRONG = "STRONG",
}

export interface ShortlistQuality {
  band: ShortlistQualityBand;
  headline: string;
  detail: string;
  actionableCount: number;
  actionableShare: number;
  strongestReadiness: number;
  countriesRepresented: number;
  /** The country with the most actionable postings, as flag to name, when there is one. */
  topCountry: { flag: string; name: string } | null;
}

/** The whole shortlist plus the numbers that describe it. */
export interface ShortlistState {
  isLoading: boolean;
  entries: ShortlistEntry[];
  totalJobs: number;
  /**
   * Whether the feed behind this shortlist came from a live run (at least one source
   * answered). Mirrors the Android `ShortlistState.isLive`; used only for the source note.
   */
  isLive: boolean;
  /** Plain-language note about where the postings came from, or "" when there is nothing to say. */
  sourceNote: string;
  generatedAtEpochMillis: number;
}

/**
 * Plain-language note about where the shortlist's postings came from, mirroring the Android
 * `ShortlistViewModel.sourceNote`. Blank when there is nothing to say (an empty feed), so the
 * footnote disappears rather than asserting anything about a list that does not exist.
 */
export function shortlistSourceNote(
  successCount: number,
  errorCount: number,
  total: number,
): string {
  if (total === 0) return "";
  if (successCount > 0 && errorCount === 0) {
    return `Live from ${successCount} source${successCount === 1 ? "" : "s"}.`;
  }
  if (successCount > 0) {
    return `${successCount} source(s) answered, ${errorCount} did not. Showing what came back.`;
  }
  return "Showing cached postings — no source answered this run.";
}

/** Profile gaps that recur across postings, so the user sees what to fix once. */
export function recurringProfileGaps(entries: ShortlistEntry[]): ReadinessBlocker[] {
  const byLabel = new Map<string, ReadinessBlocker>();
  for (const entry of entries) {
    for (const blocker of entry.blockers) {
      if (blocker.kind !== BlockerKind.PROFILE) continue;
      const existing = byLabel.get(blocker.label);
      if (!existing || blocker.points > existing.points) byLabel.set(blocker.label, blocker);
    }
  }
  return [...byLabel.values()].sort((a, b) => b.points - a.points);
}

/** A read on how strong the shortlist itself is, so a thin list is never dressed up. */
export function shortlistQuality(state: ShortlistState): ShortlistQuality {
  const entries = state.entries;
  if (entries.length === 0) {
    return {
      band: ShortlistQualityBand.NONE,
      headline: "No shortlist yet",
      detail:
        "Nothing screened so far is open to applicants in Madagascar. Refresh to pull the latest postings.",
      actionableCount: 0,
      actionableShare: 0,
      strongestReadiness: 0,
      countriesRepresented: 0,
      topCountry: null,
    };
  }
  const strongest = Math.max(...entries.map((e) => e.readinessScore));
  const actionable = entries.filter((e) => applicationReadinessIsActionable(e.readiness));
  const share = Math.round((actionable.length * 100) / entries.length);
  const countries = [...new Set(entries.map((e) => e.job.country))];

  const byCountry = new Map<string, ShortlistEntry[]>();
  for (const entry of entries) {
    const list = byCountry.get(entry.job.country) ?? [];
    list.push(entry);
    byCountry.set(entry.job.country, list);
  }
  let topCountry: { flag: string; name: string } | null = null;
  let topActionable = 0;
  for (const [country, group] of byCountry) {
    const count = group.filter((e) => applicationReadinessIsActionable(e.readiness)).length;
    if (count > topActionable) {
      topActionable = count;
      topCountry = { flag: group[0].job.countryFlag, name: country };
    }
  }

  const band =
    actionable.length === 0
      ? ShortlistQualityBand.THIN
      : actionable.length >= 5 && strongest >= READINESS_PRIORITY_MIN
        ? ShortlistQualityBand.STRONG
        : ShortlistQualityBand.BUILDING;

  const headline =
    band === ShortlistQualityBand.STRONG
      ? `${actionable.length} postings you can act on now`
      : band === ShortlistQualityBand.BUILDING
        ? `${actionable.length} posting${actionable.length === 1 ? "" : "s"} to start with`
        : "Routes in are thin right now";

  const base = `Screened ${entries.length} posting${entries.length === 1 ? "" : "s"}; ${actionable.length} ${actionable.length === 1 ? "is" : "are"} open to you now`;
  const places = countries.length > 0 ? ` across ${countries.length} ${countries.length === 1 ? "country" : "countries"}` : "";
  const lead = topCountry ? `, with ${topCountry.name} leading` : "";

  return {
    band,
    headline,
    detail: `${base}${places}${lead}. That is what the listings state — verify before you invest time.`,
    actionableCount: actionable.length,
    actionableShare: share,
    strongestReadiness: strongest,
    countriesRepresented: countries.length,
    topCountry,
  };
}

/**
 * Derived read on a shortlist snapshot, mirroring the Android `ShortlistState` computed
 * properties. Kept here (not in the screen) so the dashboard tiles, the summary header and
 * the list can never disagree about how many postings are ready, verify-only or blocked.
 */
export interface ShortlistBreakdown {
  priorityCount: number;
  readyCount: number;
  verifyCount: number;
  blockedCount: number;
  officialRouteCount: number;
  actionableCount: number;
  isEmpty: boolean;
  /** Postings worth acting on now, strongest first (the engine already ranked them). */
  rankedActionable: ShortlistEntry[];
  /** Postings to act on once a gap is closed — never a local-only posting. */
  needsPreparation: ShortlistEntry[];
  recurringProfileGaps: ReadinessBlocker[];
}

export function shortlistBreakdown(state: ShortlistState): ShortlistBreakdown {
  const entries = state.entries;
  return {
    priorityCount: entries.filter((e) => e.readiness === ApplicationReadiness.PRIORITY).length,
    readyCount: entries.filter((e) => e.readiness === ApplicationReadiness.READY).length,
    verifyCount: entries.filter((e) => e.readiness === ApplicationReadiness.NEEDS_REVIEW).length,
    blockedCount: entries.filter((e) => e.readiness === ApplicationReadiness.BLOCKED).length,
    officialRouteCount: entries.filter((e) => shortlistHasOfficialRoute(e)).length,
    actionableCount: entries.filter((e) => applicationReadinessIsActionable(e.readiness)).length,
    isEmpty: entries.length === 0,
    rankedActionable: entries.filter((e) => applicationReadinessIsActionable(e.readiness)),
    needsPreparation: entries.filter(
      (e) => !applicationReadinessIsActionable(e.readiness) && e.job.eligibility !== MadagascarEligibility.RESTRICTED,
    ),
    recurringProfileGaps: recurringProfileGaps(entries),
  };
}

// --------------------------------------------------------------------------- expat intelligence

/**
 * How one destination country looks across the whole feed: how many postings are actually
 * open to an applicant in Madagascar, the strongest readiness found there, and whether any
 * employer states an official route in.
 */
export interface CountryOpportunity {
  country: string;
  countryFlag: string;
  actionableCount: number;
  totalCount: number;
  bestReadiness: number;
  hasOfficialRoute: boolean;
}

export function countryOpportunityOpenShare(c: CountryOpportunity): number {
  return c.totalCount === 0 ? 0 : Math.round((c.actionableCount * 100) / c.totalCount);
}

/** A whole-feed digest for the expat job seeker. */
export interface ExpatIntelligence {
  totalJobs: number;
  actionableCount: number;
  priorityCount: number;
  officialRouteCount: number;
  countries: CountryOpportunity[];
  recurringProfileGaps: ReadinessBlocker[];
  topOpportunities: ShortlistEntry[];
  summary: string;
  generatedAtEpochMillis: number;
}

// --------------------------------------------------------------------------- alerts
// Behaviour engines (alerts, auto-apply, submissions). Mirrors the Kotlin models the same
// way the rest of this file does: one shape per concept, no behaviour beyond the tiny
// derivations the screens read.

/** How often a saved search should look for new postings. */
export enum AlertFrequency {
  DAILY = "DAILY",
  WEEKLY = "WEEKLY",
  MONTHLY = "MONTHLY",
}

export const ALERT_FREQUENCY_LABEL: Record<AlertFrequency, string> = {
  [AlertFrequency.DAILY]: "Daily",
  [AlertFrequency.WEEKLY]: "Weekly",
  [AlertFrequency.MONTHLY]: "Monthly",
};

/** Which saved postings an alert is allowed to surface. */
export enum AlertScope {
  ANY_MATCH = "ANY_MATCH",
  STRONG_ONLY = "STRONG_ONLY",
  VISA_FRIENDLY = "VISA_FRIENDLY",
}

export const ALERT_SCOPE_LABEL: Record<AlertScope, string> = {
  [AlertScope.ANY_MATCH]: "Any new match",
  [AlertScope.STRONG_ONLY]: "Strong matches only",
  [AlertScope.VISA_FRIENDLY]: "Visa-sponsoring only",
};

/**
 * A saved search. Alerts are derived by re-scoring the live feed against this rule, so a
 * new posting is caught the moment it appears rather than on a stored snapshot.
 */
export interface JobAlertRule {
  id: string;
  name: string;
  /** Case-insensitive keywords matched against the title, employer and summary. */
  keywords: string[];
  countries: string[];
  frequency: AlertFrequency;
  scope: AlertScope;
  minMatchScore: number;
  createdAtEpochMillis: number;
  enabled: boolean;
}

export function defaultJobAlertRule(overrides: Partial<JobAlertRule> = {}): JobAlertRule {
  return {
    id: "rule",
    name: "Saved search",
    keywords: [],
    countries: [],
    frequency: AlertFrequency.DAILY,
    scope: AlertScope.ANY_MATCH,
    minMatchScore: 60,
    createdAtEpochMillis: 0,
    enabled: true,
    ...overrides,
  };
}

/** A posting an alert rule caught, kept until the user dismisses or acts on it. */
export interface JobAlert {
  ruleId: string;
  ruleName: string;
  jobId: string;
  jobTitle: string;
  company: string;
  country: string;
  countryFlag: string;
  matchScore: number;
  isVisaFriendly: boolean;
  detectedAtEpochMillis: number;
  /** True once the user has opened it, so the unread badge is honest. */
  read: boolean;
}

/** Shared notion of "this employer will sponsor a candidate from abroad". Mirrors `Job.isVisaFriendly`. */
export function jobIsVisaFriendly(job: Job): boolean {
  return (
    job.visaSupport === VisaSupport.FULL_SPONSORSHIP ||
    job.visaSupport === VisaSupport.SPONSORSHIP_AVAILABLE ||
    job.visaSupport === VisaSupport.RELOCATION_PACKAGE
  );
}

// --------------------------------------------------------------------------- auto-apply

/**
 * Where a queued job stands in the auto-apply flow. The flow is deliberately "prepare,
 * never submit": [DRAFT_READY] means the CV and cover letter are ready for one-tap
 * approval, not that anything was sent.
 */
export enum AutoApplyStatus {
  QUEUED = "QUEUED",
  DRAFT_READY = "DRAFT_READY",
  APPROVED = "APPROVED",
  SKIPPED = "SKIPPED",
  BLOCKED = "BLOCKED",
}

export const AUTO_APPLY_STATUS_LABEL: Record<AutoApplyStatus, string> = {
  [AutoApplyStatus.QUEUED]: "Queued",
  [AutoApplyStatus.DRAFT_READY]: "Draft ready",
  [AutoApplyStatus.APPROVED]: "Approved to send",
  [AutoApplyStatus.SKIPPED]: "Skipped",
  [AutoApplyStatus.BLOCKED]: "Blocked",
};

/** Why the engine put a job in the queue, or refused to. Always shown to the user. */
export interface AutoApplyItem {
  jobId: string;
  jobTitle: string;
  company: string;
  country: string;
  countryFlag: string;
  matchScore: number;
  sourceUrl: string;
  status: AutoApplyStatus;
  coverLetterId: string | null;
  reason: string;
  preparedAtEpochMillis: number;
}

// --------------------------------------------------------------------------- submissions

/**
 * Where a prepared application stands. The flow is deliberately **prepare → review → approve
 * → submit**, with submission as its own state so a crash between "the user approved" and
 * "the submission was recorded" can be recovered rather than lost.
 */
export enum DraftStatus {
  DRAFT = "DRAFT",
  READY_FOR_REVIEW = "READY_FOR_REVIEW",
  APPROVED = "APPROVED",
  NEEDS_RESUBMISSION = "NEEDS_RESUBMISSION",
  SUBMISSION_PENDING = "SUBMISSION_PENDING",
  SUBMISSION_SIMULATED = "SUBMISSION_SIMULATED",
  SUBMISSION_SENT = "SUBMISSION_SENT",
  SKIPPED = "SKIPPED",
  BLOCKED = "BLOCKED",
}

export const DRAFT_STATUS_LABEL: Record<DraftStatus, string> = {
  [DraftStatus.DRAFT]: "Draft",
  [DraftStatus.READY_FOR_REVIEW]: "Ready for review",
  [DraftStatus.APPROVED]: "Approved",
  [DraftStatus.NEEDS_RESUBMISSION]: "Changed — re-approve",
  [DraftStatus.SUBMISSION_PENDING]: "Submitting",
  [DraftStatus.SUBMISSION_SIMULATED]: "Simulated (nothing sent)",
  [DraftStatus.SUBMISSION_SENT]: "Sent",
  [DraftStatus.SKIPPED]: "Skipped",
  [DraftStatus.BLOCKED]: "Blocked",
};

/** True while the draft is waiting for the user, rather than the app. */
export function draftAwaitsUser(status: DraftStatus): boolean {
  return status === DraftStatus.READY_FOR_REVIEW || status === DraftStatus.NEEDS_RESUBMISSION;
}

/** One question the employer asked, and the answer the user reviewed. */
export interface DraftAnswer {
  question: string;
  answer: string;
}

/** The outcome of one submission attempt, recorded so the flow can resume after a restart. */
export interface SubmissionAttempt {
  atEpochMillis: number;
  /** The content hash at the moment of the attempt, so a stale attempt is detectable. */
  contentHash: string;
  outcome: string;
  detail: string;
  simulated: boolean;
}

/** A fully prepared application the user reviews before anything is sent. */
export interface ApplicationDraft {
  id: string;
  jobId: string;
  jobTitle: string;
  company: string;
  country: string;
  countryFlag: string;
  sourceUrl: string;
  channelKey: string;
  cvSummary: string;
  coverLetterId: string | null;
  coverLetterText: string;
  answers: DraftAnswer[];
  documents: string[];
  status: DraftStatus;
  /** Hash of the reviewed content right now. */
  contentHash: string;
  approvedHash: string | null;
  approvedAtEpochMillis: number | null;
  preparedAtEpochMillis: number;
  updatedAtEpochMillis: number;
  attempts: SubmissionAttempt[];
  consentGiven: boolean;
  remoteApplicationId: string | null;
  confirmedAtEpochMillis: number | null;
  reason: string;
}

/** True when the reviewed content matches the last approval and is ready to submit. */
export function draftIsApproved(draft: ApplicationDraft): boolean {
  return (
    draft.approvedHash != null &&
    draft.approvedHash === draft.contentHash &&
    (draft.status === DraftStatus.APPROVED || draft.status === DraftStatus.SUBMISSION_PENDING)
  );
}

/** True when the content changed after approval, so a fresh approval is required. */
export function draftNeedsResubmission(draft: ApplicationDraft): boolean {
  return draft.approvedHash != null && draft.approvedHash !== draft.contentHash;
}

// --------------------------------------------------------------------------- tiny helpers
