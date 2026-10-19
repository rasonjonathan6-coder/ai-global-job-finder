import { describe, expect, it } from "vitest";

import fixture from "./kotlin-parity.json";
import {
  ALL_BENEFIT_TYPES,
  EmploymentType,
  JobBenefitTypes,
  LanguageLevel,
  RelocationPreference,
  SalaryPeriod,
  ScamRisk,
  ScamSignalTone,
  ScamVerdict,
  UNKNOWN_VERIFICATION,
  WorkMode,
  blockerSummary,
  orderedBlockers,
  readinessBlockerIsProfileGap,
  shortlistHasOfficialRoute,
  shortlistNextStep,
  type CandidateProfile,
  type CountryOpportunity,
  type EligibilityReason,
  type ExpatPreferences,
  type Job,
  type LanguageSkill,
  type MatchReason,
  type ReadinessBlocker,
  type ScamReport,
  type ShortlistEntry,
  type ShortlistState,
} from "../models";
import {
  planDoneCount,
  planIsReadyToApply,
  planNextStep,
  planOpenSteps,
  planProgressPercent,
  planTotalCount,
  type ApplicationPlan,
} from "../assistant/application-plan";
import { ApplicationPlanEngine } from "../assistant/application-plan-engine";
import { profileCompleteness } from "../cv/completeness";
import { ShortlistEngine } from "../shortlist/engine/shortlist-engine";
import { ExpatIntelligenceEngine } from "../expat/expat-intelligence-engine";
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

  // ----------------------------------------------------------------- stage 62: pipeline parity

  /**
   * The fixture now carries a rebuilt `Job` per scenario, so the shortlist, intelligence and
   * application-plan engines can be driven by the exact input the Kotlin exporter scored.
   * `verification` is the one field the pipeline engines never read; it is left unknown.
   */
  function jobFromScenario(scenario: Scenario): Job {
    const j = scenario.output.job;
    return {
      id: j.id,
      title: j.title,
      company: j.company,
      companyAccent: j.companyAccent,
      country: j.country,
      countryFlag: j.countryFlag,
      city: j.city,
      salaryMin: j.salaryMin,
      salaryMax: j.salaryMax,
      currencyCode: j.currencyCode,
      salaryPeriod: j.salaryPeriod as SalaryPeriod,
      matchScore: j.matchScore,
      eligibility: j.eligibility as Job["eligibility"],
      visaSupport: j.visaSupport as Job["visaSupport"],
      scamRisk: j.scamRisk as ScamRisk,
      postedAtEpochMillis: j.postedAtEpochMillis,
      expiresAtEpochMillis: j.expiresAtEpochMillis,
      retrievedAtEpochMillis: j.retrievedAtEpochMillis,
      source: j.source,
      sourceUrl: j.sourceUrl,
      workMode: j.workMode as WorkMode,
      employmentType: j.employmentType as EmploymentType,
      experienceYears: j.experienceYears,
      matchedSkills: [...j.matchedSkills],
      missingSkills: [...j.missingSkills],
      languages: [...j.languages],
      summary: j.summary,
      responsibilities: [],
      benefits: [],
      aiInsight: j.aiInsight,
      providerId: j.providerId,
      salaryIsEstimated: j.salaryIsEstimated,
      salaryPeriodInferred: j.salaryPeriodInferred,
      salaryUnitUncertain: j.salaryUnitUncertain,
      matchReasons: j.matchReasons.map((r) => ({
        label: r.label,
        detail: r.detail,
        signal: r.signal as MatchReason["signal"],
        weight: r.weight,
      })),
      eligibilityReasons: j.eligibilityReasons.map((r) => ({
        label: r.label,
        detail: r.detail,
        official: r.official,
      })),
      benefitEvidence: j.benefitEvidence.map((b) => ({
        type: JobBenefitTypesByKey(b.typeKey),
        status: b.status as BenefitEvidenceStatus,
        evidence: b.evidence,
        source: b.source,
        verifiedAtEpochMillis: b.verifiedAtEpochMillis,
      })),
      roleFamily: j.roleFamily as Job["roleFamily"],
      matchedTargetRole: j.matchedTargetRole,
      requirements: {
        minYears: j.requirements.minYears,
        certifications: j.requirements.certifications.map((c) => ({ label: c.label, evidence: c.evidence })),
        licences: j.requirements.licences.map((c) => ({ label: c.label, evidence: c.evidence })),
      },
      qualificationGaps: j.qualificationGaps.map((g) => ({ label: g.label, evidence: g.evidence, met: g.met })),
      evidence: [],
      openToApplicants: j.openToApplicants,
      verification: UNKNOWN_VERIFICATION,
      isSaved: j.isSaved,
      isNew: j.isNew,
    };
  }

  function shortlistEntry(scenario: Scenario, index = 0): ShortlistEntry {
    const job = jobFromScenario(scenario);
    const completeness = profileCompleteness(profile);
    const entry = new ShortlistEngine({ clock: () => CLOCK }).assess(
      job,
      profile,
      preferences,
      completeness.percent,
      scenario.output.salaryEurPerYear,
    );
    return index === 0 ? entry : { ...entry, job };
  }

  function shortlistState(entries: ShortlistEntry[], totalJobs = entries.length): ShortlistState {
    return { isLoading: false, entries, totalJobs, generatedAtEpochMillis: CLOCK };
  }

  function planFrom(entry: ShortlistEntry, hasCoverLetter: boolean): ApplicationPlan {
    const completeness = profileCompleteness(profile);
    return new ApplicationPlanEngine({ clock: () => CLOCK }).build(
      entry.job,
      entry,
      completeness.percent,
      hasCoverLetter,
      hasCoverLetter ? 3 : 0,
      hasCoverLetter ? 3 : 0,
      hasCoverLetter ? ScamVerdict.NEEDS_CAUTION : null,
      hasCoverLetter,
    );
  }

  describe("stage 62 profile completeness", () => {
    it.each(fixture.shortlistPipeline.map((p) => [p.id, p] as const))("%s matches", (_id, pipeline) => {
      const completeness = profileCompleteness(profile);
      expect({ percent: completeness.percent, missing: completeness.missing }).toEqual(pipeline.profileCompleteness);
    });
  });

  describe("stage 62 shortlist engine", () => {
    it.each(fixture.shortlistPipeline.map((p) => [p.id, p] as const))("%s matches", (_id, pipeline) => {
      const entry = shortlistEntry(fixture.scenarios.find((s) => s.id === pipeline.id)!);
      expect(entryJson(entry)).toEqual(pipeline.entry);
    });

    it("ordered blockers and next step agree with the entry", () => {
      const pipeline = fixture.shortlistPipeline[0];
      const entry = shortlistEntry(fixture.scenarios.find((s) => s.id === pipeline.id)!);
      expect(orderedBlockers(entry).map((b) => b.label)).toEqual(pipeline.entry.orderedBlockers.map((b) => b.label));
      expect(shortlistNextStep(entry)).toEqual({
        label: pipeline.entry.nextStep.label,
        detail: pipeline.entry.nextStep.detail,
        action: pipeline.entry.nextStep.action,
      });
      expect(blockerSummary(entry)).toEqual(pipeline.entry.blockerSummary);
      expect(shortlistHasOfficialRoute(entry)).toEqual(pipeline.entry.hasOfficialRoute);
    });
  });

  describe("stage 62 application plan engine", () => {
    it.each(fixture.shortlistPipeline.map((p) => [p.id, p] as const))("%s bare plan matches", (_id, pipeline) => {
      const entry = shortlistEntry(fixture.scenarios.find((s) => s.id === pipeline.id)!);
      const plan = planFrom(entry, false);
      expect(planJson(plan)).toEqual(pipeline.plan);
    });

    it.each(fixture.shortlistPipeline.map((p) => [p.id, p] as const))("%s prepared plan matches", (_id, pipeline) => {
      const entry = shortlistEntry(fixture.scenarios.find((s) => s.id === pipeline.id)!);
      const plan = planFrom(entry, true);
      expect(planJson(plan)).toEqual(pipeline.preparedPlan);
    });
  });

  describe("stage 62 expat intelligence engine", () => {
    it.each(fixture.intelligence.map((p) => [p.id, p] as const))("%s matches", (_id, golden) => {
      const scenario = fixture.scenarios.find((s) => s.id === golden.id)!;
      const base = shortlistEntry(scenario);
      const single = new ExpatIntelligenceEngine({ clock: () => CLOCK }).build(shortlistState([base], 1));
      expect(intelligenceJson(single)).toEqual(golden.single);

      const germany: ShortlistEntry = {
        ...base,
        job: { ...base.job, country: "Germany", countryFlag: "🇩🇪" },
      };
      const duplicated = new ExpatIntelligenceEngine({ clock: () => CLOCK }).build(shortlistState([base, germany], 2));
      expect(intelligenceJson(duplicated)).toEqual(golden.duplicated);

      const empty = new ExpatIntelligenceEngine({ clock: () => CLOCK }).build(shortlistState([], 0));
      expect(intelligenceJson(empty)).toEqual(golden.empty);
    });
  });
});

// ------------------------------------------------------------------ stage 62 helpers

function JobBenefitTypesByKey(key: string): Job["benefitEvidence"][number]["type"] {
  const found = ALL_BENEFIT_TYPES.find((t) => t.key === key) ?? (key === JobBenefitTypes.MINIMUM_SALARY.key ? JobBenefitTypes.MINIMUM_SALARY : undefined);
  if (!found) throw new Error(`unknown benefit type ${key}`);
  return found;
}

type BenefitEvidenceStatus = Job["benefitEvidence"][number]["status"];

function blockerJson(blocker: ReadinessBlocker) {
  return {
    label: blocker.label,
    detail: blocker.detail,
    kind: blocker.kind,
    points: blocker.points,
    isProfileGap: readinessBlockerIsProfileGap(blocker),
  };
}

function entryJson(entry: ShortlistEntry) {
  return {
    readinessScore: entry.readinessScore,
    readiness: entry.readiness,
    matchScore: entry.matchScore,
    expatScore: entry.expatScore,
    expatEvidenceCoverage: entry.expatEvidenceCoverage,
    benefitsMatched: entry.benefitsMatched,
    benefitsConsidered: entry.benefitsConsidered,
    hasOfficialRoute: shortlistHasOfficialRoute(entry),
    blockers: entry.blockers.map(blockerJson),
    orderedBlockers: orderedBlockers(entry).map(blockerJson),
    highlights: entry.highlights,
    rankComponents: entry.rankComponents.map((c) => ({
      label: c.label,
      detail: c.detail,
      points: c.points,
      maxPoints: c.maxPoints,
    })),
    nextStep: shortlistNextStep(entry),
    blockerSummary: blockerSummary(entry),
  };
}

function planJson(plan: ApplicationPlan) {
  return {
    jobId: plan.jobId,
    jobTitle: plan.jobTitle,
    company: plan.company,
    country: plan.country,
    matchScore: plan.matchScore,
    readinessScore: plan.readinessScore,
    readiness: plan.readiness,
    steps: plan.steps.map((s) => ({
      kind: s.kind,
      status: s.status,
      title: s.title,
      detail: s.detail,
      action: s.action,
      weight: s.weight,
      official: s.official,
    })),
    tailoring: {
      jobTitle: plan.tailoring.jobTitle,
      leadSkills: plan.tailoring.leadSkills.map((h) => ({ skill: h.skill, detail: h.detail })),
      addressSkills: plan.tailoring.addressSkills,
      keywords: plan.tailoring.keywords,
      note: plan.tailoring.note,
    },
    documents: plan.documents.map((d) => ({ label: d.label, detail: d.detail, required: d.required })),
    routeSummary: plan.routeSummary,
    nextAction: plan.nextAction,
    hasCoverLetter: plan.hasCoverLetter,
    scamVerdict: plan.scamVerdict,
    doneCount: planDoneCount(plan),
    totalCount: planTotalCount(plan),
    progressPercent: planProgressPercent(plan),
    isReadyToApply: planIsReadyToApply(plan),
    openSteps: planOpenSteps(plan).map((s) => s.kind),
    nextStep: planNextStep(plan)?.kind ?? null,
  };
}

function intelligenceJson(intel: {
  totalJobs: number;
  actionableCount: number;
  priorityCount: number;
  officialRouteCount: number;
  countries: CountryOpportunity[];
  recurringProfileGaps: ReadinessBlocker[];
  topOpportunities: ShortlistEntry[];
  summary: string;
}) {
  return {
    totalJobs: intel.totalJobs,
    actionableCount: intel.actionableCount,
    priorityCount: intel.priorityCount,
    officialRouteCount: intel.officialRouteCount,
    countries: intel.countries.map((c) => ({
      country: c.country,
      countryFlag: c.countryFlag,
      actionableCount: c.actionableCount,
      totalCount: c.totalCount,
      bestReadiness: c.bestReadiness,
      hasOfficialRoute: c.hasOfficialRoute,
    })),
    recurringProfileGaps: intel.recurringProfileGaps.map((g) => ({ label: g.label, kind: g.kind, points: g.points })),
    topOpportunities: intel.topOpportunities.map((e) => e.job.id),
    summary: intel.summary,
  };
}
