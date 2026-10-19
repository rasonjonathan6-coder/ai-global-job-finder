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

// --------------------------------------------------------------------------- AI capability

/**
 * How a feature actually produces its output. Ported from the Kotlin `AiCapabilityKind`:
 * the app must never describe a deterministic rule as generative AI, so every feature that
 * surfaces itself as "AI" declares which kind it is, and the UI labels it honestly.
 */
export enum AiCapabilityKind {
  /** Pure, offline logic: rules and scoring. Runs on the device, no model involved. */
  RULE_BASED = "RULE_BASED",
  /** Text assembled from facts the app already holds. Grounded, never invented. */
  TEMPLATE = "TEMPLATE",
  /** A generative model writes the text. Requires a network call and a provider key. */
  GENERATIVE = "GENERATIVE",
}

export interface AiCapability {
  id: string;
  title: string;
  detail: string;
  kind: AiCapabilityKind;
}

/** Whether a capability can run right now, given whether a generative provider is configured. */
export function aiCapabilityIsAvailable(kind: AiCapabilityKind, hasGenerativeProvider: boolean): boolean {
  return kind !== AiCapabilityKind.GENERATIVE || hasGenerativeProvider;
}

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
  grounding: string[];
  missingFacts: string[];
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
  generatedAtEpochMillis: number;
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

// --------------------------------------------------------------------------- tiny helpers
