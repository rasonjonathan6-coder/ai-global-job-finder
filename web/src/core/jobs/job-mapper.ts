import {
  EmploymentType,
  MadagascarEligibility,
  MatchSignal,
  SalaryPeriod,
  ScamRisk,
  VerificationState,
  WorkMode,
  type CandidateProfile,
  type Job,
  type MatchReason,
} from "../models";
import { resolveCountry, isRecognized, WORLDWIDE } from "../geo/country-catalog";
import { toEurPerYear } from "../geo/currency-converter";
import { extractBenefits } from "../expat/expat-benefit-extractor";
import { HtmlText } from "./provider/text-parsing";
import type { RawJob } from "./provider/raw-job";
import { MatchingEngine } from "./engine/matching-engine";
import { assessEligibility } from "./engine/eligibility-engine";
import { buildEvidence } from "./engine/job-evidence-engine";
import { OfferVerificationEngine } from "./engine/offer-verification-engine";
import { classifyRole } from "./search/role-relevance";
import { analyzeRequirements } from "./search/requirement-analyzer";
import { qualificationGaps } from "./search/qualification-matcher";

const DAY_MS = 86_400_000;
const EXCERPT_CHARS = 260;
const DESCRIPTION_HEAD_CHARS = 400;

const ACCENT_PALETTE = [
  0xff1e4fd8, 0xff0e7490, 0xff7c3aed, 0xffb45309, 0xff047857, 0xffbe123c, 0xff2563eb, 0xff0f766e,
];

/** A stable, non-negative 32-bit hash so the same employer keeps the same accent colour. */
function hashString(value: string): number {
  let hash = 0;
  for (let i = 0; i < value.length; i++) {
    hash = (Math.imul(31, hash) + value.charCodeAt(i)) | 0;
  }
  return Math.abs(hash);
}

/**
 * Turns a `RawJob` into the `Job` the UI renders, applying the matching and eligibility
 * engines. Mirrors the Kotlin `JobMapper` line for line, so a posting produces the same
 * card — and the same stored `Job` — in both editions.
 *
 * Design rules encoded here:
 *  - The country is resolved once and used for the flag, the filters and the engine.
 *  - Salary is only shown when the source published it; the card marks it as stated.
 *  - The match score and the eligibility verdict are always accompanied by the reasons that
 *    produced them, so nothing is asserted without explanation.
 */
export class JobMapper {
  private readonly matchingEngine: MatchingEngine;
  private readonly verificationEngine: OfferVerificationEngine;
  private readonly clock: () => number;

  constructor(options?: {
    matchingEngine?: MatchingEngine;
    verificationEngine?: OfferVerificationEngine;
    clock?: () => number;
  }) {
    this.matchingEngine = options?.matchingEngine ?? new MatchingEngine();
    this.verificationEngine = options?.verificationEngine ?? new OfferVerificationEngine();
    this.clock = options?.clock ?? Date.now;
  }

  toJob(raw: RawJob, profile: CandidateProfile): Job {
    const country = resolveCountry(raw.location);
    const plainDescription = HtmlText.toPlainText(raw.descriptionHtml);
    const now = this.clock();
    const postedDaysAgo =
      raw.postedAtEpochMillis == null
        ? null
        : Math.max(0, Math.trunc((now - raw.postedAtEpochMillis) / DAY_MS));

    const match = this.matchingEngine.assess(
      {
        title: raw.title,
        country: country.name,
        description: plainDescription,
        tags: raw.tags,
        postedDaysAgo,
      },
      profile,
    );

    const eligibility = assessEligibility({
      title: raw.title,
      location: raw.location,
      description: plainDescription,
      tags: raw.tags,
      employmentType: raw.employmentType,
      isRemote: raw.isRemote,
      // A remote listing whose location names a real country (not "Worldwide", not a region
      // such as "EMEA") is location-restricted: the employer bounded where it may hire.
      isRecognizedCountry: raw.isRemote && isRecognized(country),
    });

    const summary = HtmlText.excerpt(raw.descriptionHtml, EXCERPT_CHARS);
    const benefitEvidence = extractBenefits(
      raw.title,
      plainDescription,
      raw.tags,
      raw.providerName,
      raw.retrievedAtEpochMillis ?? now,
    );

    const relevance = classifyRole(raw.title, plainDescription.slice(0, DESCRIPTION_HEAD_CHARS));
    const requirements = analyzeRequirements(plainDescription);
    const gaps = qualificationGaps(requirements, profile);
    const evidence = buildEvidence(
      eligibility.eligibility,
      eligibility.visaSupport,
      eligibility.reasons,
      benefitEvidence,
      gaps,
      raw.url,
    );

    const job: Job = {
      id: `${raw.providerId}:${raw.externalId}`,
      title: raw.title.trim().length > 0 ? raw.title : "Untitled role",
      company: raw.company.trim().length > 0 ? raw.company : "Unknown employer",
      companyAccent: accentFor(raw.company),
      country: country.name,
      countryFlag: country.flag,
      city: cityOf(raw.location),
      salaryMin: raw.salaryMin ?? 0,
      salaryMax: raw.salaryMax ?? raw.salaryMin ?? 0,
      currencyCode: raw.salaryCurrency ?? "",
      salaryPeriod: raw.salaryPeriod ?? SalaryPeriod.YEAR,
      matchScore: match.score,
      eligibility: eligibility.eligibility,
      visaSupport: eligibility.visaSupport,
      scamRisk: scamRiskFor(raw),
      // Never invent a publication date: a month-old posting shown as "just now" misleads.
      postedAtEpochMillis: raw.postedAtEpochMillis,
      expiresAtEpochMillis: raw.expiresAtEpochMillis,
      retrievedAtEpochMillis: raw.retrievedAtEpochMillis ?? now,
      source: raw.providerName,
      sourceUrl: raw.url,
      workMode: raw.isRemote ? WorkMode.REMOTE : WorkMode.ONSITE,
      employmentType: employmentTypeOf(raw.employmentType, plainDescription),
      experienceYears: match.requiredYears ?? 0,
      matchedSkills: match.matchedSkills,
      missingSkills: match.missingSkills,
      languages: match.requiredLanguages,
      summary,
      responsibilities: [],
      benefits: [],
      aiInsight: insightFor(match.score, match.reasons, eligibility.eligibility),
      providerId: raw.providerId,
      salaryIsEstimated: raw.salaryMin == null,
      salaryPeriodInferred: raw.salaryPeriodInferred,
      salaryUnitUncertain: raw.salaryUnitUncertain,
      matchReasons: match.reasons,
      eligibilityReasons: eligibility.reasons,
      benefitEvidence,
      roleFamily: relevance.family,
      matchedTargetRole: relevance.matchedTarget?.canonical ?? null,
      requirements,
      qualificationGaps: gaps,
      evidence,
      openToApplicants: raw.openToApplicants,
      isSaved: false,
      isNew: postedDaysAgo != null && postedDaysAgo <= 3,
      verification: {
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
      },
    };

    // The verification read depends on fields the Job now carries (link, eligibility,
    // documented benefits, retrieval time), so it is built from the assembled Job.
    return { ...job, verification: this.verificationEngine.assess(job, now) };
  }

  /** Adds the normalised salary signal without changing the raw display values. */
  eurPerYear(job: Job): number | null {
    if (job.salaryMin <= 0 || job.salaryUnitUncertain || job.salaryPeriodInferred) return null;
    return toEurPerYear(job.salaryMin, job.currencyCode, job.salaryPeriod);
  }
}

function insightFor(
  _score: number,
  reasons: MatchReason[],
  eligibility: MadagascarEligibility,
): string {
  const strongest = reasons
    .filter((r) => r.signal === MatchSignal.POSITIVE)
    .reduce<MatchReason | null>((best, r) => (best == null || r.weight > best.weight ? r : best), null);
  const weakest = reasons
    .filter((r) => r.signal === MatchSignal.NEGATIVE)
    .reduce<MatchReason | null>((worst, r) => (worst == null || r.weight < worst.weight ? r : worst), null);

  const eligibilityNote = (() => {
    switch (eligibility) {
      case MadagascarEligibility.ELIGIBLE:
        return "Applicants from Madagascar are accepted.";
      case MadagascarEligibility.VISA_SPONSORED:
        return "The employer states visa sponsorship.";
      case MadagascarEligibility.UNCLEAR:
        return "Eligibility for Madagascar applicants is not stated — verify first.";
      case MadagascarEligibility.RESTRICTED:
        return "This posting is limited to local applicants.";
    }
  })();

  let text = eligibilityNote;
  if (strongest) text += ` Strongest signal: ${strongest.label.toLowerCase()}.`;
  if (weakest) text += ` Weakest: ${weakest.label.toLowerCase()} — ${weakest.detail}`;
  return text;
}

/** Remote/worldwide sources are legitimate, but not verified employers, so never "verified". */
function scamRiskFor(raw: RawJob): ScamRisk {
  if (raw.company.trim().length === 0) return ScamRisk.MEDIUM;
  if (raw.url.trim().length === 0) return ScamRisk.MEDIUM;
  return ScamRisk.LOW;
}

function employmentTypeOf(raw: string | null, description: string): EmploymentType {
  const haystack = `${raw ?? ""} ${description}`.toLowerCase();
  if (haystack.includes("part-time") || haystack.includes("part time")) return EmploymentType.PART_TIME;
  if (haystack.includes("contract") || haystack.includes("freelance")) return EmploymentType.CONTRACT;
  if (haystack.includes("intern")) return EmploymentType.INTERNSHIP;
  if (haystack.includes("temporary") || haystack.includes("temp ")) return EmploymentType.TEMPORARY;
  return EmploymentType.FULL_TIME;
}

/** The city part of "City, Country", or empty when the location is just a country. */
function cityOf(location: string): string {
  const first = location.split(",")[0]?.trim() ?? "";
  return first.toLowerCase() === WORLDWIDE.toLowerCase() ? "" : first;
}

/** Stable colour per employer, so the same company keeps the same accent. */
function accentFor(company: string): number {
  if (company.trim().length === 0) return ACCENT_PALETTE[0];
  return ACCENT_PALETTE[hashString(company) % ACCENT_PALETTE.length];
}
