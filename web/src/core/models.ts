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

export enum RelocationPreference {
  UNKNOWN = "UNKNOWN",
  PREFERS_RELOCATION = "PREFERS_RELOCATION",
  OPEN_TO_RELOCATION = "OPEN_TO_RELOCATION",
  REMOTE_ONLY = "REMOTE_ONLY",
  NO_RELOCATION = "NO_RELOCATION",
}

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

export interface CertificationEntry {
  name: string;
  issuer: string;
  year: number | null;
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
  education: unknown[];
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

// --------------------------------------------------------------------------- tiny helpers
