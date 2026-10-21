import { describe, expect, it } from "vitest";

import fixture from "./kotlin-parity.json";
import {
  AI_CAPABILITY_CATALOG,
  AI_CAPABILITY_KIND_META,
  AI_PROCESSING_LOCATION_META,
  ALL_BENEFIT_TYPES,
  AnswerConfidence,
  ApplicationDestination,
  EmploymentType,
  JobBenefitTypes,
  LanguageLevel,
  LetterLanguage,
  MadagascarEligibility,
  RelocationPreference,
  RoleFamily,
  SalaryPeriod,
  ScamRisk,
  ScamSignalTone,
  ScamVerdict,
  UNKNOWN_VERIFICATION,
  VerificationState,
  WorkMode,
  blockerSummary,
  coverLetterPlainText,
  coverLetterWordCount,
  letterLanguageForCountry,
  orderedBlockers,
  readinessBlockerIsProfileGap,
  shortlistHasOfficialRoute,
  shortlistNextStep,
  shortlistSourceNote,
  type AnswerFeedback,
  type CandidateProfile,
  CandidateField,
  type CountryOpportunity,
  type CoverLetter,
  type EligibilityReason,
  type ExpatPreferences,
  type InterviewPrep,
  type InterviewQuestion,
  type InterviewReadiness,
  type Job,
  type LanguageSkill,
  type MatchReason,
  type ReadinessBlocker,
  type ScamReport,
  type ShortlistEntry,
  type ShortlistState,
} from "../models";
import { CoverLetterGenerator } from "../coverletter/cover-letter-generator";
import { InterviewCoach, InterviewQuestionBank } from "../interview/interview-coach";
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
import { analyzeCvInto, CV_ANALYSIS_SCHEMA_VERSION } from "../cv/contract";
import { foldCvText, normalizeCvText } from "../cv/normalizer";
import {
  CV_CERTIFICATION_MARKERS,
  CV_CITIES,
  CV_COUNTRIES,
  CV_DEGREE_MARKERS,
  CV_FOLDED_PLACES,
  CV_INSTITUTION_MARKERS,
  CV_LANGUAGE_ALIASES,
  CV_LANGUAGES,
  CV_SECTION_KEYWORDS,
  CV_SKILLS,
  CV_TITLE_MARKERS,
  SkillCategory,
} from "../cv/lexicon";
import { ShortlistEngine } from "../shortlist/engine/shortlist-engine";
import { ExpatIntelligenceEngine } from "../expat/expat-intelligence-engine";
import { assessEligibility } from "../jobs/engine/eligibility-engine";
import { MatchingEngine } from "../jobs/engine/matching-engine";
import { InternationalFitEngine } from "../jobs/engine/international-fit-engine";
import { JobFreshnessEngine } from "../jobs/freshness/job-freshness-engine";
import { ExpatOpportunityEngine } from "../expat/expat-opportunity-engine";
import { BenefitFinding } from "../expat/opportunity";
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
import { relevanceSummary, relevantJobs, unrelatedJobs } from "../jobs/search/relevance-feed";
import { extractBenefits } from "../expat/expat-benefit-extractor";
import { assessExpatBenefits } from "../expat/expat-benefits-engine";
import { toEurPerYear } from "../geo/currency-converter";
import { scanForScams } from "../scam/scam-detector";
import { rawJob, ProviderKind, type RawJob } from "../jobs/provider/raw-job";
import { defaultProviders, type JobProvider } from "../jobs/provider/providers";
import { expand, isTradeQuery, knownQueryPhrases, queryMatches } from "../jobs/search/query-expansion";
import {
  SearchStrategy,
  broadSampleSourceCount,
  coverageHasRelevantResults,
  coverageHasTargetedSource,
  coverageRelevantSharePercent,
  coverageSummary,
  defaultProviderCapabilities,
  targetedSourceCount,
  type CoverageDiagnostic,
  type ProviderCapabilities,
} from "../jobs/search/search-strategy";
import { ExpatRankingEngine } from "../jobs/expat-ranking-engine";
import {
  expatRankingActionableCount,
  expatRankingDisqualifiedCount,
  expatRankingTopOpportunities,
  type ExpatRankedJob,
  type ExpatRankingState,
} from "../jobs/expat-ranking";
import { JobAlertEngine } from "../alerts/alert-engine";
import { AutoApplyEngine } from "../autoapply/auto-apply-engine";
import {
  AlertFrequency,
  AlertScope,
  AutoApplyStatus,
  DraftStatus,
  type ApplicationDraft,
  type AutoApplyItem,
  type JobAlert,
  type JobAlertRule,
} from "../models";
import { SubmissionKey } from "../submissions/submission-identity";
import { SubmissionChannel } from "../submissions/submission-channel";
import { SubmissionCapability } from "../submissions/submission-capability";
import {
  classifySubmission,
  type OfficialSubmissionOutcome,
} from "../submissions/submission-classifier";
import {
  SubmissionLifecycle,
  submissionLifecycleOf as lifecycleOfDraft,
} from "../submissions/submission-lifecycle";
import { evaluateSubmissionPreflight } from "../submissions/submission-preflight";

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
    education: p.education.map((e) => ({
      degree: e.degree,
      institution: e.institution,
      country: e.country,
      year: e.year,
    })),
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

    it("reads an applicant-tracking host as the employer's own site, not an unknown host", () => {
      const inspector = new ApplicationUrlInspector();
      const atsHosts = [
        "https://sulzer.wd502.myworkdayjobs.com/en-US/External/job/Mannheim/Maintenance-Mechanic_R1",
        "https://firstquantum.wd3.myworkdaysite.com/en-US/recruiting/fqml/External/job/Panama",
        "https://boards.greenhouse.io/lucidmotors/jobs/5231094007",
        "https://jobs.lever.co/sila/eaebee13-fd57-4187-a16e-a39c033afd24",
        "https://jobs.ashbyhq.com/formenergy/a5c18c3f-da88-4743-9d11-58a5205d2883",
        "https://jobs.smartrecruiters.com/Wabtec/3743990016234090-assembler-2-compressor-technician",
      ];
      for (const url of atsHosts) {
        expect(inspector.inspect(url, "Employer career sites").destination).toBe(
          ApplicationDestination.EMPLOYER_SITE,
        );
      }
      // The suffix rule must not turn an unrelated look-alike into an employer site.
      expect(
        inspector.inspect("https://careers.example.com/lever.co/job", "Workday").destination,
      ).toBe(ApplicationDestination.UNKNOWN_HOST);
    });
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
    return {
      isLoading: false,
      entries,
      totalJobs,
      isLive: true,
      sourceNote: "",
      generatedAtEpochMillis: CLOCK,
    };
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

    it("source note mirrors the Kotlin ShortlistViewModel wording", () => {
      // Empty feed says nothing, live-only counts its sources, partial names both sides, and
      // an all-error run admits it is showing cached postings.
      expect(shortlistSourceNote(0, 0, 0)).toBe("");
      expect(shortlistSourceNote(1, 0, 5)).toBe("Live from 1 source.");
      expect(shortlistSourceNote(3, 0, 9)).toBe("Live from 3 sources.");
      expect(shortlistSourceNote(2, 1, 9)).toBe("2 source(s) answered, 1 did not. Showing what came back.");
      expect(shortlistSourceNote(0, 3, 9)).toBe("Showing cached postings — no source answered this run.");
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

  describe("stage 62 cover letter generator", () => {
    const generator = new CoverLetterGenerator({ clock: () => CLOCK, idFactory: () => "letter-fixed" });

    it("picks the same language per country as LetterLanguage.forCountry", () => {
      const expected = fixture.coverLetters.languageByCountry;
      for (const [country, language] of Object.entries(expected)) {
        expect(letterLanguageForCountry(country)).toBe(language as LetterLanguage);
      }
    });

    it.each(fixture.coverLetters.letters.map((l) => [l.jobId, l] as const))(
      "%s matches in every language",
      (_id, entry) => {
        const job = jobFromScenario(fixture.scenarios.find((s) => s.id === entry.jobId)!);
        for (const { language, letter } of entry.languages) {
          const produced = generator.generate(job, profile, language as LetterLanguage);
          expect(coverLetterJson(produced)).toEqual(letter);
        }
      },
    );
  });

  describe("stage 62 interview coach", () => {
    const coach = new InterviewCoach({ clock: () => CLOCK });
    const bank = new InterviewQuestionBank();

    it.each(fixture.interview.map((i) => [i.id, i] as const))("%s matches", (_id, golden) => {
      const job = jobFromScenario(fixture.scenarios.find((s) => s.id === golden.id)!);

      expect(bank.focusAreas(job, profile)).toEqual(golden.focusAreas);

      const prep = coach.prep(job, profile);
      expect(prepJson(prep)).toEqual(golden.prep);

      // Rebuild the same partial/full attempt sets the exporter used, then compare readiness.
      const partial = prep.questions
        .filter((_q, index) => index % 2 === 0)
        .map((q, index) => ({
          questionId: q.id,
          confidence: index % 3 === 0 ? AnswerConfidence.CONFIDENT : AnswerConfidence.GETTING_THERE,
          atEpochMillis: CLOCK,
        }));
      const full = prep.questions.map((q) => ({
        questionId: q.id,
        confidence: AnswerConfidence.CONFIDENT,
        atEpochMillis: CLOCK,
      }));

      expect(readinessJson(coach.readiness(prep.questions, []))).toEqual(golden.readiness.none);
      expect(readinessJson(coach.readiness(prep.questions, partial))).toEqual(golden.readiness.partial);
      expect(readinessJson(coach.readiness(prep.questions, full))).toEqual(golden.readiness.full);

      const firstQuestion = prep.questions[0];
      for (const entry of golden.feedback) {
        expect(feedbackJson(coach.feedback(firstQuestion, entry.answer))).toEqual(entry.feedback);
      }
    });
  });

  describe("stage 62 AI capability catalog", () => {
    it("matches the Kotlin AiCapabilityCatalog exactly", () => {
      expect(aiCapabilitiesJson()).toEqual(fixture.aiCapabilities);
    });

    it("labels every kind and location", () => {
      for (const capability of AI_CAPABILITY_CATALOG.capabilities) {
        expect(AI_CAPABILITY_KIND_META[capability.kind].label).toBeTruthy();
        expect(AI_PROCESSING_LOCATION_META[capability.location].label).toBeTruthy();
      }
    });
  });

  // ----------------------------------------------------------------- stage 65: freshness & expatriate opportunity

  describe("stage 65 job freshness engine", () => {
    const engine = new JobFreshnessEngine();
    const custom30 = new JobFreshnessEngine(30);
    const base = jobFromScenario(fixture.scenarios[0]);

    it.each(fixture.freshness.map((f) => [f.id, f] as const))("%s matches", (_id, c) => {
      const job: Job = {
        ...base,
        postedAtEpochMillis: c.input.postedAtEpochMillis,
        retrievedAtEpochMillis: c.input.retrievedAtEpochMillis,
      };
      const assessment = engine.assess(job, CLOCK);
      expect({ freshness: assessment.freshness, label: assessment.label, detail: assessment.detail }).toEqual(c.output);
      expect(custom30.assess(job, CLOCK).freshness).toBe(c.custom30);
    });

    it("never invents a date: a missing or future date is UNKNOWN", () => {
      for (const id of ["null-date", "future-date", "epoch-zero"]) {
        const c = fixture.freshness.find((f) => f.id === id)!;
        expect(c.output.freshness).toBe("UNKNOWN");
      }
    });
  });

  describe("stage 65 expatriate opportunity engine", () => {
    const engine = new ExpatOpportunityEngine({ clock: () => CLOCK });

    function expatAssessment(scenario: Scenario) {
      return assessExpatBenefits(jobFromScenario(scenario).benefitEvidence, preferences, scenario.output.salaryEurPerYear);
    }

    function fitFor(scenario: Scenario) {
      return new InternationalFitEngine().assess(jobFromScenario(scenario), profile, expatAssessment(scenario));
    }

    function opportunityJson(o: ReturnType<ExpatOpportunityEngine["assess"]>) {
      return {
        score: o.score,
        band: o.band,
        confidence: o.confidence,
        confidenceNote: o.confidenceNote,
        hardStops: o.hardStops,
        disqualified: o.disqualified,
        labels: o.labels,
        routeSummary: o.routeSummary,
        explanation: o.explanation,
        dimensions: o.dimensions.map((d) => ({
          dimension: d.dimension,
          points: d.points,
          maxPoints: d.maxPoints,
          summary: d.summary,
          strength: d.strength,
        })),
        claims: o.claims.map((c) => ({
          label: c.label,
          finding: c.finding,
          evidence: c.evidence,
          sourceUrl: c.sourceUrl,
          sourceType: c.sourceType,
          explicit: c.explicit,
          limitations: c.limitations,
        })),
        salary: o.salary
          ? {
              originalAmount: o.salary.originalAmount,
              originalCurrency: o.salary.originalCurrency,
              originalPeriod: o.salary.originalPeriod,
              eurPerYear: o.salary.eurPerYear,
              rateSource: o.salary.rateSource,
              rateAsOfEpochMillis: o.salary.rateAsOfEpochMillis,
              converted: o.salary.converted,
              note: o.salary.note,
            }
          : null,
      };
    }

    it.each(fixture.expatOpportunity.map((o) => [o.id, o] as const))("%s matches", (_id, expected) => {
      const scenario = fixture.scenarios.find((s) => s.id === expected.id)!;
      const job = jobFromScenario(scenario);
      const fit = fitFor(scenario);

      expect(opportunityJson(engine.assess(job, preferences, fit))).toEqual(expected.opportunity);
      expect(opportunityJson(engine.assess(job, preferences, null))).toEqual(expected.noFit);
      expect(opportunityJson(engine.assess({ ...job, scamRisk: ScamRisk.HIGH }, preferences, fit))).toEqual(
        expected.highScamRisk,
      );
      expect(
        opportunityJson(engine.assess({ ...job, eligibility: MadagascarEligibility.RESTRICTED }, preferences, fit)),
      ).toEqual(expected.restrictedEligibility);
      expect(
        opportunityJson(
          engine.assess({ ...job, verification: { ...job.verification, link: VerificationState.INACCESSIBLE } }, preferences, fit),
        ),
      ).toEqual(expected.expiredLink);
    });

    it("caps and disqualifies a posting with a hard stop", () => {
      for (const o of fixture.expatOpportunity) {
        expect(o.highScamRisk.disqualified).toBe(true);
        expect(o.highScamRisk.score).toBeLessThanOrEqual(35);
        expect(o.restrictedEligibility.disqualified).toBe(true);
        expect(o.expiredLink.disqualified).toBe(true);
      }
    });

    it("treats silence as UNKNOWN, never as a confirmed benefit", () => {
      for (const o of fixture.expatOpportunity) {
        for (const claim of o.opportunity.claims) {
          if (claim.finding === BenefitFinding.CONFIRMED) {
            expect(claim.evidence.length).toBeGreaterThan(0);
          }
        }
      }
    });
  });

  // --------------------------------------------- stage 66: search engine, query expansion, ranking

  describe("stage 66 query expansion", () => {
    it("exposes exactly the Kotlin taxonomy's known phrases", () => {
      expect(Array.from(knownQueryPhrases()).sort()).toEqual(fixture.queryExpansion.knownPhrases);
    });

    it.each(fixture.queryExpansion.cases.map((c) => [JSON.stringify(c.query), c] as const))(
      "%s matches",
      (_id, c) => {
        expect(expand(c.query)).toEqual(c.expanded);
        expect(isTradeQuery(c.query)).toBe(c.isTrade);
        for (const [haystack, expected] of Object.entries(c.matches)) {
          expect(queryMatches(c.query, haystack)).toBe(expected);
        }
      },
    );

    it("never fires a complete term inside another word, but a partial term is a word prefix", () => {
      expect(queryMatches("pump", "Pumpkin Farmer")).toBe(false);
      expect(queryMatches("pumpkin", "Pumpkin Farmer")).toBe(true);
      expect(queryMatches("rotat", "Rotating Equipment Maintenance Technician")).toBe(true);
      // Three characters is the prefix floor; two is not.
      expect(queryMatches("pum", "Pump Technician")).toBe(true);
      expect(queryMatches("pu", "Pump Technician")).toBe(false);
      expect(queryMatches("  ", "anything")).toBe(true);
    });
  });

  describe("stage 66 search strategy", () => {
    /** The Kotlin capability facts, keyed by provider id, that drive every plan. */
    function capabilitiesFor(providerId: string): ProviderCapabilities {
      switch (providerId) {
        case "adzuna":
          return defaultProviderCapabilities({ serverSideKeyword: true, countryFilter: true, pagination: true });
        case "arbeitnow":
        case "lever":
          return defaultProviderCapabilities({ pagination: true });
        case "eures":
        case "smartrecruiters":
        case "workday":
          return defaultProviderCapabilities({ serverSideKeyword: true, pagination: true });
        case "jooble":
          return defaultProviderCapabilities({ serverSideKeyword: true, countryFilter: true, pagination: true });
        default:
          return defaultProviderCapabilities();
      }
    }

    const providers: JobProvider[] = fixture.searchStrategy.plans.map((plan) => ({
      id: plan.providerId,
      name: plan.providerName,
      homepage: "",
      kind: ProviderKind.AGGREGATOR,
      documentationUrl: "",
      capabilities: capabilitiesFor(plan.providerId),
      fetch: async () => [],
    }));

    it.each(fixture.searchStrategy.plans.map((p) => [p.providerId, p] as const))("%s plan matches", (_id, plan) => {
      const provider = providers.find((p) => p.id === plan.providerId)!;
      const produced = SearchStrategy.planFor(provider, fixture.searchStrategy.titles);
      expect({
        providerId: produced.providerId,
        providerName: produced.providerName,
        serverSideSearch: produced.serverSideSearch,
        broadSample: !produced.serverSideSearch,
        keywordSets: produced.keywordSets,
      }).toEqual(plan);
    });

    it("builds the same coverage report as the Kotlin engine", () => {
      const expected = fixture.searchStrategy.coverage;
      const relevant = new Map(expected.sources.map((s) => [s.providerId, s.relevantCount]));
      const totals = new Map(expected.sources.map((s) => [s.providerId, s.totalCount]));
      const diagnostics: CoverageDiagnostic[] = expected.sources.map((s) => ({
        providerId: s.providerId,
        name: s.providerName,
        serverSideSearch: s.targeted,
        validJobCount: s.totalCount,
      }));
      const coverage = SearchStrategy.coverageFrom(diagnostics, relevant, totals);

      expect(coverage.sources).toEqual(expected.sources);
      expect({
        totalRelevant: coverage.totalRelevant,
        totalRetrieved: coverage.totalRetrieved,
        targetedSourceCount: targetedSourceCount(coverage),
        broadSampleSourceCount: broadSampleSourceCount(coverage),
        hasTargetedSource: coverageHasTargetedSource(coverage),
        hasRelevantResults: coverageHasRelevantResults(coverage),
        relevantSharePercent: coverageRelevantSharePercent(coverage),
        summary: coverageSummary(coverage),
      }).toEqual({
        totalRelevant: expected.totalRelevant,
        totalRetrieved: expected.totalRetrieved,
        targetedSourceCount: expected.targetedSourceCount,
        broadSampleSourceCount: expected.broadSampleSourceCount,
        hasTargetedSource: expected.hasTargetedSource,
        hasRelevantResults: expected.hasRelevantResults,
        relevantSharePercent: expected.relevantSharePercent,
        summary: expected.summary,
      });
    });

    it("describes an empty search honestly", () => {
      const empty = SearchStrategy.coverageFrom([], new Map(), new Map());
      expect({ totalRelevant: empty.totalRelevant, totalRetrieved: empty.totalRetrieved, summary: coverageSummary(empty) }).toEqual(
        fixture.searchStrategy.emptyCoverage,
      );
    });

    it("declares the same capabilities on the real web providers as the Kotlin ones", () => {
      for (const provider of defaultProviders()) {
        const plan = fixture.searchStrategy.plans.find((p) => p.providerId === provider.id);
        if (!plan) continue;
        const produced = SearchStrategy.planFor(provider, fixture.searchStrategy.titles);
        expect(produced.serverSideSearch).toBe(plan.serverSideSearch);
        expect(produced.keywordSets).toEqual(plan.keywordSets);
      }
    });
  });

  describe("stage 70 live relevance filter", () => {
    it.each(fixture.relevance.verdicts.map((v) => [v.title, v] as const))(
      "classifies %s the same as the Kotlin engine",
      (_title, verdict) => {
        const produced = classifyRole(verdict.title, verdict.descriptionHead);
        expect({
          family: produced.family,
          matchedTarget: produced.matchedTarget?.canonical ?? null,
          isMaintenanceRelevant:
            produced.family === RoleFamily.TARGET || produced.family === RoleFamily.MAINTENANCE_FAMILY,
          evidence: produced.evidence,
        }).toEqual({
          family: verdict.family,
          matchedTarget: verdict.matchedTarget,
          isMaintenanceRelevant: verdict.isMaintenanceRelevant,
          evidence: verdict.evidence,
        });
      },
    );

    it("keeps and drops the same postings as the Kotlin RelevanceFeed", () => {
      const feed = fixture.relevance.feed;
      const jobs = fixture.scenarios.map((s) => jobFromScenario(s));
      const kept = relevantJobs(jobs);
      const dropped = unrelatedJobs(jobs);
      expect(kept.map((j) => j.id)).toEqual(feed.keptIds);
      expect(dropped.map((j) => j.id)).toEqual(feed.droppedIds);
      expect(jobs.length).toBe(feed.total);
    });

    it("describes the filter result the same way as the Kotlin engine", () => {
      const feed = fixture.relevance.feed;
      expect(relevanceSummary(feed.total, feed.keptIds.length)).toBe(feed.summary);
      expect(relevanceSummary(0, 0)).toBe(feed.emptySummary);
      expect(relevanceSummary(3, 0)).toBe(feed.noneMatchedSummary);
      expect(relevanceSummary(2, 2)).toBe(feed.allMatchedSummary);
    });
  });

  describe("stage 66 expat ranking engine", () => {
    const engine = new ExpatRankingEngine({ clock: () => CLOCK });

    function rankedJson(item: ExpatRankedJob) {
      return {
        jobId: item.job.id,
        rank: item.rank,
        finalScore: item.finalScore,
        readinessScore: item.readinessScore,
        readiness: item.readiness,
        disqualified: item.opportunity.disqualified,
        opportunityScore: item.opportunity.score,
        opportunityBand: item.opportunity.band,
        labels: item.labels,
        reasons: item.reasons,
      };
    }

    function rankingStateJson(state: ExpatRankingState) {
      return {
        isLoading: state.isLoading,
        totalJobs: state.totalJobs,
        actionableCount: expatRankingActionableCount(state),
        disqualifiedCount: expatRankingDisqualifiedCount(state),
        topOpportunityIds: expatRankingTopOpportunities(state).map((r) => r.job.id),
        ranked: state.ranked.map(rankedJson),
      };
    }

    const entries = fixture.scenarios.map((s) => shortlistEntry(s));

    it("ranks the whole shortlist exactly as Kotlin does", () => {
      const state = engine.rank(shortlistState(entries, entries.length), preferences);
      expect(rankingStateJson(state)).toEqual(fixture.expatRanking.full);
    });

    it("is deterministic and independent of input order", () => {
      const subset = entries.slice(0, 2).reverse();
      const state = engine.rank(shortlistState(subset, subset.length), preferences);
      expect(rankingStateJson(state)).toEqual(fixture.expatRanking.subset);
    });

    it("reports an empty, still-loading ranking without inventing a row", () => {
      const state = engine.rank(
        { isLoading: true, entries: [], totalJobs: 0, isLive: false, sourceNote: "", generatedAtEpochMillis: 0 },
        preferences,
      );
      expect({
        isLoading: state.isLoading,
        totalJobs: state.totalJobs,
        ranked: state.ranked,
        generatedAtEpochMillis: state.generatedAtEpochMillis,
      }).toEqual(fixture.expatRanking.empty);
    });

    it("caps a disqualified posting and never lets it sit above an actionable one", () => {
      for (const state of [fixture.expatRanking.full, fixture.expatRanking.subset]) {
        let sawDisqualified = false;
        for (const row of state.ranked) {
          if (row.disqualified) {
            sawDisqualified = true;
            expect(row.finalScore).toBeLessThanOrEqual(35);
            expect(row.labels).toContain("NOT_OPEN_TO_YOU");
          } else {
            expect(sawDisqualified).toBe(false);
          }
        }
      }
    });
  });

  describe("stage 67 job alert engine", () => {
    const engine = new JobAlertEngine();
    const jobs = fixture.scenarios.map((s) => makeJob(s));

    const rules: JobAlertRule[] = fixture.alertEngine.rules.map((r) => ({
      id: r.id,
      name: r.name,
      keywords: [...r.keywords],
      countries: [...r.countries],
      frequency: r.frequency as AlertFrequency,
      scope: r.scope as AlertScope,
      minMatchScore: r.minMatchScore,
      createdAtEpochMillis: CLOCK,
      enabled: r.enabled,
    }));

    function alertJson(alert: JobAlert) {
      return {
        ruleId: alert.ruleId,
        ruleName: alert.ruleName,
        jobId: alert.jobId,
        jobTitle: alert.jobTitle,
        company: alert.company,
        country: alert.country,
        countryFlag: alert.countryFlag,
        matchScore: alert.matchScore,
        isVisaFriendly: alert.isVisaFriendly,
        detectedAtEpochMillis: alert.detectedAtEpochMillis,
        read: alert.read,
      };
    }

    it("matches each rule against every posting exactly as Kotlin does", () => {
      const matches: Record<string, Record<string, boolean>> = {};
      for (const rule of rules) {
        matches[rule.id] = {};
        for (const job of jobs) matches[rule.id][job.id] = engine.matches(rule, job);
      }
      expect(matches).toEqual(fixture.alertEngine.matches);
    });

    it("produces the same alerts, and never fires a disabled rule", () => {
      const alerts = engine.alertsFor(rules, jobs, CLOCK);
      expect(alerts.map(alertJson)).toEqual(fixture.alertEngine.alerts);
      expect(alerts.some((a) => a.ruleId === "disabled")).toBe(false);
    });
  });

  describe("stage 67 auto-apply engine", () => {
    const jobs = fixture.scenarios.map((s) => makeJob(s));
    const engine = new AutoApplyEngine(70, () => CLOCK);

    function itemJson(item: AutoApplyItem) {
      return {
        jobId: item.jobId,
        jobTitle: item.jobTitle,
        company: item.company,
        country: item.country,
        countryFlag: item.countryFlag,
        matchScore: item.matchScore,
        sourceUrl: item.sourceUrl,
        status: item.status,
        coverLetterId: item.coverLetterId,
        reason: item.reason,
        preparedAtEpochMillis: item.preparedAtEpochMillis,
      };
    }

    it("decides each posting exactly as Kotlin does", () => {
      const decisions: Record<string, unknown> = {};
      for (const job of jobs) {
        const decision = engine.decide(job);
        decisions[job.id] = { status: decision.status, reason: decision.reason, eligible: decision.eligible };
      }
      expect(decisions).toEqual(fixture.autoApply.decisions);
    });

    it("builds the same queue, best match first, dropping skipped postings", () => {
      const queue = engine.buildQueue(jobs, []);
      expect(queue.map(itemJson)).toEqual(fixture.autoApply.baseQueue);
      expect(queue.every((i) => i.status !== AutoApplyStatus.SKIPPED)).toBe(true);
    });

    it("preserves an approved or skipped entry across a refresh", () => {
      const baseQueue = engine.buildQueue(jobs, []);
      const existing: AutoApplyItem[] = [];
      if (baseQueue[0]) existing.push({ ...baseQueue[0], status: AutoApplyStatus.APPROVED });
      if (baseQueue[1]) existing.push({ ...baseQueue[1], status: AutoApplyStatus.SKIPPED });
      const preserved = engine.buildQueue(jobs, existing);
      expect(preserved.map(itemJson)).toEqual(fixture.autoApply.preservedQueue);
    });
  });

  describe("stage 67 submission engines", () => {
    const jobs = fixture.scenarios.map((s) => makeJob(s));

    it("reports the same channel facts and capability", () => {
      const channels = SubmissionChannel.values.map((channel) => ({
        name: channel.key,
        label: channel.label,
        realSendSupported: channel.realSendSupported,
        compatibilityNote: channel.compatibilityNote,
        statement: SubmissionCapability.statement(channel),
      }));
      expect(channels).toEqual(fixture.submission.channels);
      expect({
        sendsNothing: SubmissionCapability.sendsNothing,
        supportedChannelCount: SubmissionCapability.supportedChannelCount,
      }).toEqual(fixture.submission.capability);
    });

    it("infers the application channel from the posting URL", () => {
      const inference = fixture.submission.inference.map((entry) => ({
        url: entry.url,
        channel: SubmissionChannel.infer({ ...jobs[0], sourceUrl: entry.url }).key,
      }));
      expect(inference).toEqual(fixture.submission.inference);
    });

    it("derives the same application identity, including the blank-employer fallback", () => {
      const identity = fixture.submission.identity.map((_entry, index) => {
        const key = SubmissionKey.of(jobs[index]);
        return {
          jobId: jobs[index].id,
          key: key.value,
          normalizedCompany: key.normalizedCompany,
          normalizedTitle: key.normalizedTitle,
          normalizedCountry: key.normalizedCountry,
        };
      });
      expect(identity).toEqual(fixture.submission.identity);
      expect(SubmissionKey.of({ ...jobs[0], company: "", title: "Field Service Engineer" }).value).toEqual(
        fixture.submission.blankEmployerKey,
      );
    });

    it("classifies every submission response, and never turns ambiguity into success", () => {
      const classified = fixture.submission.classifier.map((entry) => {
        const outcome = classifySubmission(entry.httpStatus, entry.remoteApplicationId);
        const detail =
          outcome.kind === "CONFIRMED" ? outcome.remoteApplicationId : outcome.reason;
        return {
          httpStatus: entry.httpStatus,
          remoteApplicationId: entry.remoteApplicationId,
          kind: outcome.kind,
          detail,
          lifecycle: lifecycleOfOutcome(outcome),
        };
      });
      expect(classified).toEqual(fixture.submission.classifier);
    });

    it("maps every draft status onto the same lifecycle state", () => {
      const lifecycle = Object.values(DraftStatus).map((status) => {
        const state = lifecycleOfDraft(status);
        return {
          draftStatus: status,
          lifecycle: state,
          isConfirmedSent: state === SubmissionLifecycle.SUBMITTED_CONFIRMED,
          isNotSent:
            state === SubmissionLifecycle.SIMULATED_NOT_SENT ||
            state === SubmissionLifecycle.DRAFT ||
            state === SubmissionLifecycle.PENDING_APPROVAL,
        };
      });
      expect(lifecycle).toEqual(fixture.submission.lifecycle);
    });

    it("gates a real submission on every rule, in the same order", () => {
      function draft(
        status: DraftStatus,
        contentHash: string,
        approvedHash: string | null,
        cvSummary = "Maintenance technician, 8 years.",
        coverLetterText = "Dear team, I am applying.",
      ): ApplicationDraft {
        return {
          id: "draft-1",
          jobId: jobs[0].id,
          jobTitle: jobs[0].title,
          company: jobs[0].company,
          country: jobs[0].country,
          countryFlag: "🌍",
          sourceUrl: jobs[0].sourceUrl,
          channelKey: "EMPLOYER_PORTAL",
          cvSummary,
          coverLetterId: "cl-1",
          coverLetterText,
          answers: [{ question: "Notice period?", answer: "One month" }],
          documents: ["cv.pdf"],
          status,
          contentHash,
          approvedHash,
          approvedAtEpochMillis: approvedHash != null ? CLOCK : null,
          preparedAtEpochMillis: CLOCK,
          updatedAtEpochMillis: CLOCK,
          attempts: [],
          consentGiven: false,
          remoteApplicationId: null,
          confirmedAtEpochMillis: null,
          reason: "",
        };
      }

      const notApproved = draft(DraftStatus.READY_FOR_REVIEW, "h1", null);
      const approved = draft(DraftStatus.APPROVED, "h1", "h1");
      const changed = draft(DraftStatus.APPROVED, "h2", "h1");
      const missingContent = draft(DraftStatus.APPROVED, "h1", "h1", "", "");

      const cases: Array<[string, ApplicationDraft, boolean, boolean, boolean]> = [
        ["not-approved", notApproved, false, false, false],
        ["approved-but-no-route", approved, false, true, false],
        ["changed-content", changed, true, true, false],
        ["missing-content", missingContent, true, true, false],
        ["no-consent", approved, true, false, false],
        ["already-applied", approved, true, true, true],
        ["allowed", approved, true, true, false],
      ];

      const preflights: Record<string, unknown> = {};
      for (const [label, d, realRoute, consent, alreadyApplied] of cases) {
        const result = evaluateSubmissionPreflight(d, realRoute, consent, alreadyApplied);
        preflights[label] = {
          blockers: result.blockers,
          allowed: result.allowed,
          primaryBlocker: result.primaryBlocker,
          explanation: result.explanation,
        };
      }
      expect(preflights).toEqual(fixture.submission.preflights);
    });
  });

  describe("CV analysis core", () => {
    const cv = fixture.cvAnalysis;
    const emptyProfile = (): CandidateProfile => buildProfileEmpty();

    function buildProfileEmpty(): CandidateProfile {
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

    function profileFromFixture(
      p: typeof fixture.profile | (typeof fixture.cvAnalysis.cases)[number]["existing"],
    ): CandidateProfile {
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
        education: p.education.map((e) => ({
          degree: e.degree,
          institution: e.institution,
          country: e.country,
          year: e.year,
        })),
        certifications: p.certifications.map((c) => ({ name: c.name, issuer: c.issuer, year: c.year })),
        languages: p.languages.map((l): LanguageSkill => ({
          name: l.name,
          level: (l.level as LanguageLevel | null) ?? null,
          evidence: l.evidence,
        })),
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

    function profileJson(profile: CandidateProfile): Record<string, unknown> {
      return {
        fullName: profile.fullName,
        email: profile.email,
        phone: profile.phone,
        location: profile.location,
        currentTitle: profile.currentTitle,
        previousTitles: profile.previousTitles,
        yearsOfExperience: profile.yearsOfExperience,
        skills: profile.skills,
        industries: profile.industries,
        education: profile.education.map((e) => ({
          degree: e.degree,
          institution: e.institution,
          country: e.country,
          year: e.year,
        })),
        certifications: profile.certifications.map((c) => ({ name: c.name, issuer: c.issuer, year: c.year })),
        languages: profile.languages.map((l) => ({ name: l.name, level: l.level, evidence: l.evidence })),
        preferredCountries: profile.preferredCountries,
        preferredRoles: profile.preferredRoles,
        relocationPreference: profile.relocationPreference,
        needsVisaSponsorship: profile.needsVisaSponsorship,
        workPermitNotes: profile.workPermitNotes,
        summary: profile.summary,
      };
    }

    it("records the schema version it was exported with", () => {
      expect(cv.schemaVersion).toBe(CV_ANALYSIS_SCHEMA_VERSION);
    });

    it("analyses each case exactly as Kotlin did", () => {
      expect(cv.cases.length).toBeGreaterThanOrEqual(6);
      for (const testCase of cv.cases) {
        const existing = testCase.id.startsWith("merge") ? profileFromFixture(testCase.existing) : emptyProfile();
        const result = analyzeCvInto(testCase.text, existing, new Set(testCase.protectedFields as CandidateField[]));
        const evidence: Record<string, string> = {};
        for (const [key, value] of result.evidence) evidence[key] = value;
        expect(profileJson(result.profile), testCase.id).toEqual(testCase.output.profile);
        expect(result.warnings, testCase.id).toEqual(testCase.output.warnings);
        expect(result.confidence, testCase.id).toBe(testCase.output.confidence);
        expect(evidence, testCase.id).toEqual(testCase.output.evidence);
        expect(result.effectiveEngineId, testCase.id).toBe(testCase.output.effectiveEngineId);
        expect(result.effectiveEngineLabel, testCase.id).toBe(testCase.output.effectiveEngineLabel);
        expect(result.presence, testCase.id).toEqual(testCase.output.presence);
      }
    });

    it("normalises text exactly as Kotlin did", () => {
      for (const testCase of cv.normalize) {
        expect(normalizeCvText(testCase.raw), testCase.id).toBe(testCase.output);
      }
    });

    it("folds text exactly as Kotlin did", () => {
      for (const testCase of cv.fold) {
        expect(foldCvText(testCase.raw), testCase.id).toBe(testCase.output);
      }
    });

    it("carries the same skill lexicon", () => {
      expect(cv.lexicon.skills).toEqual(
        CV_SKILLS.map((s) => ({ canonical: s.canonical, category: s.category, aliases: s.aliases })),
      );
    });

    it("carries the same language, degree, institution, certification and title vocabulary", () => {
      expect(cv.lexicon.languages).toEqual(CV_LANGUAGES);
      expect(cv.lexicon.languageAliases).toEqual(CV_LANGUAGE_ALIASES);
      expect(cv.lexicon.degreeMarkers).toEqual(CV_DEGREE_MARKERS);
      expect(cv.lexicon.institutionMarkers).toEqual(CV_INSTITUTION_MARKERS);
      expect(cv.lexicon.certificationMarkers).toEqual(CV_CERTIFICATION_MARKERS);
      expect(cv.lexicon.titleMarkers).toEqual(CV_TITLE_MARKERS);
      expect(cv.lexicon.countries).toEqual(CV_COUNTRIES);
      expect(cv.lexicon.cities).toEqual(CV_CITIES);
      expect(cv.lexicon.foldedPlaces).toEqual([...CV_FOLDED_PLACES].sort());
      expect(cv.lexicon.sectionKeywords).toEqual(CV_SECTION_KEYWORDS);
    });

    it("keeps the skill category vocabulary in sync", () => {
      const categories = new Set(Object.values(SkillCategory));
      for (const skill of CV_SKILLS) expect(categories.has(skill.category)).toBe(true);
    });
  });
});

function lifecycleOfOutcome(outcome: OfficialSubmissionOutcome): string {
  switch (outcome.kind) {
    case "CONFIRMED":
      return SubmissionLifecycle.SUBMITTED_CONFIRMED;
    case "FAILED":
      return SubmissionLifecycle.SUBMISSION_FAILED;
    case "UNKNOWN":
      return SubmissionLifecycle.SUBMISSION_UNKNOWN;
  }
}

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

function coverLetterJson(letter: CoverLetter) {
  return {
    id: letter.id,
    jobId: letter.jobId,
    language: letter.language,
    subject: letter.subject,
    salutation: letter.salutation,
    paragraphs: letter.paragraphs,
    closing: letter.closing,
    signature: letter.signature,
    usedFacts: letter.usedFacts,
    missingFacts: letter.missingFacts,
    generatedAtEpochMillis: letter.generatedAtEpochMillis,
    edited: letter.edited,
    plainText: coverLetterPlainText(letter),
    wordCount: coverLetterWordCount(letter),
  };
}

function questionJson(q: InterviewQuestion) {
  return {
    id: q.id,
    category: q.category,
    prompt: q.prompt,
    whyItMatters: q.whyItMatters,
    structure: q.structure,
    grounding: q.grounding ?? [],
    missingFacts: q.missingFacts ?? [],
  };
}

function prepJson(prep: InterviewPrep) {
  return {
    jobId: prep.jobId,
    jobTitle: prep.jobTitle,
    company: prep.company,
    country: prep.country,
    countryFlag: prep.countryFlag,
    generatedAtEpochMillis: prep.generatedAtEpochMillis,
    questionCount: prep.questions.length,
    questions: prep.questions.map(questionJson),
  };
}

function readinessJson(r: InterviewReadiness) {
  return {
    attempted: r.attempted,
    total: r.total,
    confident: r.confident,
    percent: r.percent,
    isComplete: r.isComplete,
    label: r.label,
  };
}

function feedbackJson(f: AnswerFeedback) {
  return {
    score: f.score,
    headline: f.headline,
    strengths: f.strengths,
    improvements: f.improvements,
  };
}

function aiCapabilitiesJson() {
  return {
    hasOnDeviceModel: AI_CAPABILITY_CATALOG.HAS_ON_DEVICE_MODEL,
    hasRemoteModel: AI_CAPABILITY_CATALOG.HAS_REMOTE_MODEL,
    summary: AI_CAPABILITY_CATALOG.summary,
    capabilities: AI_CAPABILITY_CATALOG.capabilities.map((c) => ({
      name: c.name,
      kind: c.kind,
      kindLabel: AI_CAPABILITY_KIND_META[c.kind].label,
      location: c.location,
      locationLabel: AI_PROCESSING_LOCATION_META[c.location].label,
      description: c.description,
      note: c.note,
    })),
  };
}
