import { describe, expect, it } from "vitest";

import fixture from "./kotlin-parity.json";
import {
  EmploymentType,
  LanguageLevel,
  RelocationPreference,
  SalaryPeriod,
  ScamRisk,
  ScamSignalTone,
  UNKNOWN_VERIFICATION,
  WorkMode,
  type CandidateProfile,
  type EligibilityReason,
  type ExpatPreferences,
  type Job,
  type LanguageSkill,
  type MatchReason,
  type ScamReport,
} from "../models";
import { assessEligibility } from "../jobs/engine/eligibility-engine";
import { MatchingEngine } from "../jobs/engine/matching-engine";
import { InternationalFitEngine } from "../jobs/engine/international-fit-engine";
import { OfferVerificationEngine } from "../jobs/engine/offer-verification-engine";
import { ApplicationUrlInspector } from "../apply/application-url-inspector";
import { buildEvidence } from "../jobs/engine/job-evidence-engine";
import {
  RecordValidationEngine,
  dedupKey as tsDedupKey,
  recordId as tsRecordId,
  type DeduplicationResult,
  type RecordRejectionReason,
  type RecordValidation,
} from "../jobs/engine/record-validation-engine";
import { detectSkills } from "../jobs/engine/skill-lexicon";
import { analyzeRequirements } from "../jobs/search/requirement-analyzer";
import { qualificationGaps } from "../jobs/search/qualification-matcher";
import { classifyRole } from "../jobs/search/role-relevance";
import { extractBenefits } from "../expat/expat-benefit-extractor";
import { assessExpatBenefits } from "../expat/expat-benefits-engine";
import { toEurPerYear } from "../geo/currency-converter";
import { scanForScams } from "../scam/scam-detector";
import { rawJob, type RawJob } from "../jobs/provider/raw-job";

const CLOCK = fixture.meta.clockMillis;
const DAY_MS = 86_400_000;

type Scenario = (typeof fixture.scenarios)[number];
type RawJobFixture = (typeof fixture.recordValidation)[number]["raw"];

function buildProfile(): CandidateProfile {
  const p = fixture.profile;
  return {
    fullName: p.fullName,
    email: p.email,
    phone: p.phone,
    location: p.location,
    currentTitle: p.currentTitle,
    previousTitles: [...p.previousTitles],
    yearsOfExperience: p.yearsOfExperience,
    skills: [...p.skills],
    industries: [...p.industries],
    education: [],
    certifications: p.certifications.map((c) => ({ name: c.name, issuer: c.issuer, year: c.year })),
    languages: p.languages.map(
      (l): LanguageSkill => ({
        name: l.name,
        level: (l.level as LanguageLevel | null) ?? null,
        evidence: l.evidence,
      }),
    ),
    preferredCountries: [...p.preferredCountries],
    preferredRoles: [...p.preferredRoles],
    salaryExpectationMin: null,
    salaryExpectationMax: null,
    salaryCurrency: null,
    relocationPreference: p.relocationPreference as RelocationPreference,
    needsVisaSponsorship: p.needsVisaSponsorship,
    workPermitNotes: p.workPermitNotes,
    summary: p.summary,
  };
}

function buildPreferences(): ExpatPreferences {
  return {
    priorities: { ...fixture.expatPreferences.priorities },
    minimumSalaryEur: fixture.expatPreferences.minimumSalaryEur,
    targetCountries: [...fixture.expatPreferences.targetCountries],
    minExpatScore: fixture.expatPreferences.minExpatScore,
    minMatchScore: fixture.expatPreferences.minMatchScore,
    requireVisaSponsorship: fixture.expatPreferences.requireVisaSponsorship,
  };
}

const profile = buildProfile();
const preferences = buildPreferences();

function matchInput(input: Scenario["input"]) {
  return {
    title: input.title,
    country: input.country,
    description: input.description,
    tags: input.tags,
    postedDaysAgo: input.postedDaysAgo,
  };
}

function eligibilityInput(input: Scenario["input"]) {
  return {
    title: input.title,
    location: input.country,
    description: input.description,
    tags: input.tags,
    employmentType: input.employmentType,
    isRemote: input.isRemote,
    isRecognizedCountry: input.isRecognizedCountry,
  };
}

function salaryEur(input: Scenario["input"]): number | null {
  return input.salaryMin > 0 ? toEurPerYear(input.salaryMin, input.currency, input.salaryPeriod as SalaryPeriod) : null;
}

/**
 * Rebuilds the exact `Job` the Kotlin exporter scored, driven entirely by the web engines.
 * Any divergence in the engines therefore surfaces in the fit, evidence and scam outputs
 * compared against the fixture, not just in the component engines.
 */
function makeJob(scenario: Scenario): Job {
  const input = scenario.input;
  const matching = new MatchingEngine().assess(matchInput(input), profile);
  const eligibility = assessEligibility(eligibilityInput(input));
  const role = classifyRole(input.title, input.description);
  const requirements = analyzeRequirements(input.description);
  const gaps = qualificationGaps(requirements, profile);
  const benefits = extractBenefits(input.title, input.description, input.tags, input.source, CLOCK);
  const reasons: EligibilityReason[] = eligibility.reasons.map((r) => ({
    label: r.label,
    detail: r.detail,
    official: r.official,
  }));

  return {
    id: scenario.id,
    title: input.title,
    company: input.company,
    companyAccent: 0xff1e4fd8,
    country: input.country,
    countryFlag: "🌍",
    city: input.city,
    salaryMin: input.salaryMin,
    salaryMax: input.salaryMax,
    currencyCode: input.currency,
    salaryPeriod: input.salaryPeriod as SalaryPeriod,
    matchScore: matching.score,
    eligibility: eligibility.eligibility,
    visaSupport: eligibility.visaSupport,
    scamRisk: ScamRisk.LOW,
    postedAtEpochMillis: input.postedDaysAgo == null ? null : CLOCK - input.postedDaysAgo * DAY_MS,
    expiresAtEpochMillis: input.expiresAtEpochMillis,
    retrievedAtEpochMillis: CLOCK,
    source: input.source,
    sourceUrl: input.url,
    workMode: input.isRemote ? WorkMode.REMOTE : WorkMode.ONSITE,
    employmentType: EmploymentType.FULL_TIME,
    experienceYears: requirements.minYears ?? 0,
    matchedSkills: matching.matchedSkills,
    missingSkills: matching.missingSkills,
    languages: [...input.languages],
    summary: input.description,
    responsibilities: [],
    benefits: [],
    aiInsight: "",
    providerId: input.providerId,
    salaryIsEstimated: false,
    salaryPeriodInferred: false,
    salaryUnitUncertain: input.salaryUnitUncertain,
    matchReasons: matching.reasons,
    eligibilityReasons: reasons,
    benefitEvidence: benefits,
    roleFamily: role.family,
    matchedTargetRole: role.matchedTarget?.canonical ?? null,
    requirements,
    qualificationGaps: gaps,
    evidence: [],
    openToApplicants: null,
    verification: UNKNOWN_VERIFICATION,
    isSaved: false,
    isNew: false,
  };
}

function reasonJson(r: MatchReason) {
  return { label: r.label, detail: r.detail, signal: r.signal, weight: r.weight };
}

function eligibilityReasonJson(r: EligibilityReason) {
  return { label: r.label, detail: r.detail, official: r.official };
}

function safetyScore(report: ScamReport): number {
  const dangers = report.signals.filter((s) => s.tone === ScamSignalTone.DANGER).length;
  const cautions = report.signals.filter((s) => s.tone === ScamSignalTone.CAUTION).length;
  return Math.max(0, Math.min(100, 100 - dangers * 28 - cautions * 11));
}

function rawJobFromFixture(raw: RawJobFixture): RawJob {
  return rawJob({
    providerId: raw.providerId,
    providerName: raw.providerName,
    externalId: raw.externalId,
    title: raw.title,
    company: raw.company,
    location: raw.location,
    descriptionHtml: raw.descriptionHtml,
    url: raw.url,
    tags: raw.tags,
    employmentType: raw.employmentType,
    postedAtEpochMillis: raw.postedAtEpochMillis,
    expiresAtEpochMillis: raw.expiresAtEpochMillis,
    retrievedAtEpochMillis: raw.retrievedAtEpochMillis,
    isRemote: raw.isRemote,
    salaryMin: raw.salaryMin,
    salaryMax: raw.salaryMax,
    salaryCurrency: raw.salaryCurrency,
    salaryPeriod: raw.salaryPeriod as SalaryPeriod | null,
    salaryPeriodInferred: raw.salaryPeriodInferred,
    salaryUnitUncertain: raw.salaryUnitUncertain,
    closedToNewApplicants: raw.closedToNewApplicants,
    openToApplicants: raw.openToApplicants,
  });
}

describe("Kotlin parity — golden fixtures", () => {
  it("exports a non-trivial fixture set", () => {
    expect(fixture.scenarios.length).toBeGreaterThanOrEqual(9);
    expect(fixture.recordValidation.length).toBeGreaterThanOrEqual(15);
  });

  describe.each(fixture.scenarios.map((s) => [s.id, s] as const))("scenario %s", (_id, scenario) => {
    const input = scenario.input;
    const expected = scenario.output;

    it("role classifier matches", () => {
      const role = classifyRole(input.title, input.description);
      expect({ family: role.family, matchedTarget: role.matchedTarget?.canonical ?? null, evidence: role.evidence }).toEqual(
        expected.role,
      );
    });

    it("eligibility engine matches", () => {
      const result = assessEligibility(eligibilityInput(input));
      expect({
        eligibility: result.eligibility,
        visaSupport: result.visaSupport,
        hasOfficialSignal: result.hasOfficialSignal,
        reasons: result.reasons.map(eligibilityReasonJson),
      }).toEqual(expected.eligibility);
    });

    it("matching engine matches", () => {
      const result = new MatchingEngine().assess(matchInput(input), profile);
      expect({
        score: result.score,
        matchedSkills: result.matchedSkills,
        missingSkills: result.missingSkills,
        requiredYears: result.requiredYears,
        requiredLanguages: result.requiredLanguages,
        reasons: result.reasons.map(reasonJson),
      }).toEqual(expected.matching);
    });

    it("requirement analyzer matches", () => {
      expect(analyzeRequirements(input.description)).toEqual(expected.requirements);
    });

    it("qualification matcher matches", () => {
      expect(qualificationGaps(analyzeRequirements(input.description), profile)).toEqual(expected.gaps);
    });

    it("skill lexicon matches", () => {
      const skills = Array.from(detectSkills(`${input.title} ${input.description}`)).sort();
      expect(skills).toEqual(expected.skills);
    });

    it("currency conversion matches", () => {
      expect(salaryEur(input)).toEqual(expected.salaryEurPerYear);
    });

    it("expat benefit extractor matches", () => {
      const benefits = extractBenefits(input.title, input.description, input.tags, input.source, CLOCK);
      expect(
        benefits.map((b) => ({
          typeKey: b.type.key,
          status: b.status,
          evidence: b.evidence,
          source: b.source,
          verifiedAtEpochMillis: b.verifiedAtEpochMillis,
        })),
      ).toEqual(
        expected.job.benefitEvidence.map((b) => ({
          typeKey: b.typeKey,
          status: b.status,
          evidence: b.evidence,
          source: b.source,
          verifiedAtEpochMillis: b.verifiedAtEpochMillis,
        })),
      );
    });

    it("expat benefits engine matches", () => {
      const benefits = extractBenefits(input.title, input.description, input.tags, input.source, CLOCK);
      const assessment = assessExpatBenefits(benefits, preferences, salaryEur(input));
      expect({
        score: assessment.score,
        evidenceCoverage: assessment.evidenceCoverage,
        explanation: assessment.explanation,
        unmetEssentials: assessment.unmetEssentials.map((t) => t.key),
        lowEvidence: assessment.lowEvidence,
        components: assessment.components.map((c) => ({
          typeKey: c.type.key,
          priority: c.priority,
          status: c.status,
          evidence: c.evidence,
          credit: c.credit,
          potentialPoints: c.potentialPoints,
          earnedPoints: c.earnedPoints,
        })),
      }).toEqual(expected.expatBenefits);
    });

    it("international fit engine matches", () => {
      const job = makeJob(scenario);
      const expat = assessExpatBenefits(job.benefitEvidence, preferences, salaryEur(input));
      const fit = new InternationalFitEngine().assess(job, profile, expat);
      expect({
        score: fit.score,
        band: fit.band,
        blockers: fit.blockers,
        routeSummary: fit.routeSummary,
        expatBenefitsScore: fit.expatBenefitsScore,
        expatEvidenceCoverage: fit.expatEvidenceCoverage,
        expatLowEvidence: fit.expatLowEvidence,
        explanation: fit.explanation,
        components: fit.components,
      }).toEqual(expected.internationalFit);
    });

    it("job evidence engine matches", () => {
      const job = makeJob(scenario);
      const eligibility = assessEligibility(eligibilityInput(input));
      const evidence = buildEvidence(
        eligibility.eligibility,
        eligibility.visaSupport,
        eligibility.reasons,
        job.benefitEvidence,
        job.qualificationGaps,
        input.url,
      );
      expect(
        evidence.map((e) => ({
          kind: e.kind,
          strength: e.strength,
          label: e.label,
          detail: e.detail,
          sourceUrl: e.sourceUrl,
        })),
      ).toEqual(expected.evidence);
    });

    it("scam detector matches", () => {
      const report = scanForScams(makeJob(scenario), () => CLOCK);
      expect({
        risk: report.risk,
        verdict: report.verdict,
        safetyScore: safetyScore(report),
        signals: report.signals.map((s) => ({
          label: s.label,
          detail: s.detail,
          evidence: s.evidence,
          tone: s.tone,
        })),
      }).toEqual(expected.scam);
    });

    it("offer verification engine matches", () => {
      const verification = new OfferVerificationEngine().assess(makeJob(scenario), CLOCK);
      expect({
        activity: verification.activity,
        deadline: verification.deadline,
        link: verification.link,
        visa: verification.visa,
        relocation: verification.relocation,
        lastRetrievedAtEpochMillis: verification.lastRetrievedAtEpochMillis,
        freshness: verification.freshness,
        facts: verification.facts.map((f) => ({
          aspect: f.aspect,
          strength: f.strength,
          label: f.label,
          detail: f.detail,
          sourceUrl: f.sourceUrl,
        })),
        caveats: verification.caveats,
        summary: verification.summary,
      }).toEqual(expected.verification);
    });
  });

  describe("record validation", () => {
    it.each(fixture.recordValidation.map((c) => [c.id, c] as const))("case %s matches", (_id, testCase) => {
      const engine = new RecordValidationEngine();
      const raw = rawJobFromFixture(testCase.raw);
      const result: RecordValidation = engine.validate(raw, CLOCK);
      expect({
        accepted: result.accepted,
        reasons: result.reasons as RecordRejectionReason[],
        recordId: result.recordId,
        dedupKey: result.dedupKey,
      }).toEqual(testCase.output);
      expect(tsRecordId(raw)).toEqual(testCase.output.recordId);
      expect(tsDedupKey(raw)).toEqual(testCase.output.dedupKey);
    });
  });

  describe("application url inspector", () => {
    it.each(fixture.urlInspections.map((c) => [c.url || "<empty>", c] as const))(
      "url %s matches",
      (_label, testCase) => {
        const inspection = new ApplicationUrlInspector().inspect(testCase.url, testCase.source);
        expect({
          url: inspection.url,
          host: inspection.host,
          destination: inspection.destination,
          canOpen: inspection.canOpen,
          message: inspection.message,
          warnings: inspection.warnings,
        }).toEqual(testCase.output);
      },
    );
  });

  describe("deduplication", () => {
    it("collapses cross-source duplicates and matches the summary", () => {
      const engine = new RecordValidationEngine();
      const records = fixture.deduplication.records.map(rawJobFromFixture);
      const result: DeduplicationResult = engine.deduplicate(records, CLOCK);
      const expected = fixture.deduplication.output;
      expect(result.kept.map((r) => tsRecordId(r))).toEqual(expected.keptIds);
      expect(result.totalReceived).toEqual(expected.totalReceived);
      expect(result.totalKept).toEqual(expected.totalKept);
      expect(
        result.summaries.map((s) => ({
          providerId: s.providerId,
          receivedCount: s.receivedCount,
          acceptedCount: s.acceptedCount,
          duplicateCount: s.duplicateCount,
          rejectedCount: Array.from(s.rejectedByReason.values()).reduce((a, b) => a + b, 0),
          rejectedByReason: Object.fromEntries(
            Array.from(s.rejectedByReason.entries()).sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0)),
          ),
        })),
      ).toEqual(expected.summaries);
    });
  });
});
