import { describe, expect, it } from "vitest";

import fixture from "../parity/kotlin-parity.json";
import {
  LanguageLevel,
  RelocationPreference,
  SalaryPeriod,
  ScamRisk,
  type CandidateProfile,
  type LanguageSkill,
} from "../models";
import { rawJob } from "./provider/raw-job";
import { JobMapper } from "./job-mapper";

/**
 * The `JobMapper` is the one piece of the pipeline that has no direct counterpart in the
 * Stage 58 golden fixture: the Kotlin exporter builds the `Job` by hand so each engine's
 * output can be compared in isolation. These tests close that gap by running the real
 * mapper over the same scenario inputs and asserting that every field the mapper derives
 * from an engine equals the engine's own golden output.
 */

const CLOCK = fixture.meta.clockMillis;

type Scenario = (typeof fixture.scenarios)[number];

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

const profile = buildProfile();
const mapper = new JobMapper({ clock: () => CLOCK });

function rawFromScenario(s: Scenario) {
  const i = s.input;
  return rawJob({
    providerId: i.providerId,
    providerName: i.source,
    externalId: s.id,
    title: i.title,
    company: i.company,
    // The fixture publishes the resolved country as `country`; feeding it as the location
    // exercises the real `CountryCatalog.resolve` against the same input the exporter used.
    location: i.country,
    descriptionHtml: i.description,
    url: i.url,
    tags: i.tags,
    employmentType: i.employmentType,
    postedAtEpochMillis: i.postedDaysAgo == null ? null : CLOCK - i.postedDaysAgo * 86_400_000,
    expiresAtEpochMillis: i.expiresAtEpochMillis,
    retrievedAtEpochMillis: CLOCK,
    isRemote: i.isRemote,
    salaryMin: i.salaryMin > 0 ? i.salaryMin : null,
    salaryMax: i.salaryMax > 0 ? i.salaryMax : null,
    salaryCurrency: i.currency,
    salaryPeriod: i.salaryPeriod as SalaryPeriod,
    salaryPeriodInferred: false,
    salaryUnitUncertain: i.salaryUnitUncertain,
    closedToNewApplicants: i.closedToNewApplicants,
    openToApplicants: null,
  });
}

describe.each(fixture.scenarios.map((s) => [s.id, s] as const))("JobMapper over scenario %s", (_id, scenario) => {
  const expected = scenario.output;
  const job = mapper.toJob(rawFromScenario(scenario), profile);

  it("uses the matching engine's score and reasons verbatim", () => {
    expect(job.matchScore).toBe(expected.matching.score);
    expect(job.matchReasons).toEqual(expected.matching.reasons);
    expect(job.matchedSkills).toEqual(expected.matching.matchedSkills);
    expect(job.missingSkills).toEqual(expected.matching.missingSkills);
    expect(job.languages).toEqual(expected.matching.requiredLanguages);
  });

  it("uses the eligibility engine's verdict verbatim", () => {
    expect(job.eligibility).toBe(expected.eligibility.eligibility);
    expect(job.visaSupport).toBe(expected.eligibility.visaSupport);
    expect(job.eligibilityReasons).toEqual(expected.eligibility.reasons);
  });

  it("uses the role classifier's verdict verbatim", () => {
    expect(job.roleFamily).toBe(expected.role.family);
    expect(job.matchedTargetRole).toBe(expected.role.matchedTarget);
  });

  it("uses the requirement analyser and qualification matcher verbatim", () => {
    expect(job.requirements).toEqual(expected.requirements);
    expect(job.qualificationGaps).toEqual(expected.gaps);
    expect(job.experienceYears).toBe(expected.requirements.minYears ?? 0);
  });

  it("uses the expat benefit extractor verbatim", () => {
    // The fixture serialises the benefit type as its stable `typeKey` (the Android model
    // keeps the full object), so compare on the key plus the finding, as parity.test.ts does.
    expect(
      job.benefitEvidence.map((b) => ({
        typeKey: b.type.key,
        status: b.status,
        evidence: b.evidence,
        source: b.source,
        verifiedAtEpochMillis: b.verifiedAtEpochMillis,
      })),
    ).toEqual(expected.job.benefitEvidence);
  });

  it("uses the evidence engine verbatim", () => {
    expect(job.evidence).toEqual(expected.evidence);
  });

  it("uses the offer verification engine verbatim", () => {
    expect(job.verification).toEqual(expected.verification);
  });

  it("resolves the country the fixture published", () => {
    expect(job.country).toBe(expected.job.country);
  });

  it("carries the salary honesty flags through", () => {
    expect(job.salaryUnitUncertain).toBe(expected.job.salaryUnitUncertain);
    expect(job.salaryPeriod).toBe(expected.job.salaryPeriod);
  });
});

describe("JobMapper mapping rules", () => {
  it("never fabricates a title or employer", () => {
    const job = mapper.toJob(
      rawJob({ providerId: "x", providerName: "X", externalId: "1", title: "", company: "" }),
      profile,
    );
    expect(job.title).toBe("Untitled role");
    expect(job.company).toBe("Unknown employer");
  });

  it("keeps the same accent colour for the same employer and differs across employers", () => {
    const a = mapper.toJob(rawJob({ providerId: "x", providerName: "X", externalId: "1", title: "Fitter", company: "Acme" }), profile);
    const b = mapper.toJob(rawJob({ providerId: "x", providerName: "X", externalId: "2", title: "Fitter", company: "Acme" }), profile);
    const c = mapper.toJob(rawJob({ providerId: "x", providerName: "X", externalId: "3", title: "Fitter", company: "Globex" }), profile);
    expect(a.companyAccent).toBe(b.companyAccent);
    expect(a.companyAccent).toBeGreaterThanOrEqual(0);
    expect(c.companyAccent).not.toBe(a.companyAccent);
  });

  it("strips the city from a worldwide location but keeps it for a country", () => {
    const remote = mapper.toJob(
      rawJob({ providerId: "x", providerName: "X", externalId: "1", title: "Fitter", location: "Worldwide" }),
      profile,
    );
    const local = mapper.toJob(
      rawJob({ providerId: "x", providerName: "X", externalId: "2", title: "Fitter", location: "Berlin, Germany" }),
      profile,
    );
    expect(remote.city).toBe("");
    expect(local.city).toBe("Berlin");
    expect(local.country).toBe("Germany");
  });

  it("raises scam risk only when the employer or link is missing", () => {
    const ok = mapper.toJob(rawJob({ providerId: "x", providerName: "X", externalId: "1", title: "Fitter", company: "Acme", url: "https://a.example/1" }), profile);
    const noUrl = mapper.toJob(rawJob({ providerId: "x", providerName: "X", externalId: "2", title: "Fitter", company: "Acme", url: "" }), profile);
    expect(ok.scamRisk).toBe(ScamRisk.LOW);
    expect(noUrl.scamRisk).toBe(ScamRisk.MEDIUM);
  });

  it("marks a posting published within three days as new", () => {
    const fresh = mapper.toJob(
      rawJob({ providerId: "x", providerName: "X", externalId: "1", title: "Fitter", company: "Acme", postedAtEpochMillis: CLOCK - 2 * 86_400_000 }),
      profile,
    );
    const old = mapper.toJob(
      rawJob({ providerId: "x", providerName: "X", externalId: "2", title: "Fitter", company: "Acme", postedAtEpochMillis: CLOCK - 40 * 86_400_000 }),
      profile,
    );
    expect(fresh.isNew).toBe(true);
    expect(old.isNew).toBe(false);
  });

  it("only converts salary to EUR when the unit is trustworthy", () => {
    const trustworthy = mapper.toJob(
      rawJob({ providerId: "x", providerName: "X", externalId: "1", title: "Fitter", company: "Acme", salaryMin: 60000, salaryCurrency: "CAD", salaryPeriod: SalaryPeriod.YEAR }),
      profile,
    );
    const uncertain = mapper.toJob(
      rawJob({ providerId: "x", providerName: "X", externalId: "2", title: "Fitter", company: "Acme", salaryMin: 60000, salaryCurrency: "CAD", salaryPeriod: SalaryPeriod.YEAR, salaryUnitUncertain: true }),
      profile,
    );
    expect(mapper.eurPerYear(trustworthy)).not.toBeNull();
    expect(mapper.eurPerYear(uncertain)).toBeNull();
  });
});
