import {
  EMPTY_EXPAT_ASSESSMENT,
  EvidenceStrength,
  InternationalFitBand,
  LanguageLevel,
  MadagascarEligibility,
  RoleFamily,
  ScamRisk,
  earnedShare,
  expatAssessmentHasEvidence,
  internationalFitBandFromScore,
  type CandidateProfile,
  type ExpatBenefitsAssessment,
  type InternationalFitAssessment,
  type InternationalFitComponent,
  type Job,
} from "../../models";
import { canonicalizeSkill } from "./skill-lexicon";

/**
 * Scores how strong one posting is **for this candidate as an international applicant**, and
 * explains every point of it. Mirrors the Kotlin `InternationalFitEngine`.
 *
 * The score is a transparent weighted sum of six components, each capped at its own ceiling:
 *
 *   route in from Madagascar   0..35  — the employer's stated route, or its absence
 *   role alignment             0..20  — the candidate's trade vs. the posting's title
 *   skills overlap             0..20  — canonical skills in common, gated by role alignment
 *   experience                 0..10  — years of experience vs. the posting's stated minimum
 *   language                   0..10  — languages the posting asks for vs. the CV's
 *   destination                0..5   — a country the candidate wants, or a worldwide remote
 *
 * Three rules keep it honest:
 *  1. Silence is neutral, not negative — an unstated requirement earns nor loses its points,
 *     and the component is `UNKNOWN` so the UI can show "not stated".
 *  2. Nothing is invented — role and skills come from the matching output; route, language and
 *     destination come from recorded facts on the Job.
 *  3. A thin profile produces `INSUFFICIENT`, not a confident low number.
 */
export class InternationalFitEngine {
  assess(
    job: Job,
    profile: CandidateProfile,
    expat: ExpatBenefitsAssessment = EMPTY_EXPAT_ASSESSMENT,
  ): InternationalFitAssessment {
    if (isProfileThin(profile)) {
      return {
        score: 0,
        band: InternationalFitBand.INSUFFICIENT,
        components: [],
        blockers: [],
        routeSummary: routeSummary(job),
        expatBenefitsScore: expatAssessmentHasEvidence(expat) ? expat.score : null,
        expatEvidenceCoverage: expat.evidenceCoverage,
        expatLowEvidence: true,
        explanation:
          "There is not enough on your profile to judge this posting yet. " +
          "Import and analyse your CV so the fit reflects your real skills and experience.",
      };
    }

    const components = [
      this.routeComponent(job),
      this.roleComponent(job, profile),
      this.skillsComponent(job, profile),
      this.experienceComponent(job, profile),
      this.languageComponent(job, profile),
      this.destinationComponent(job, profile),
    ];
    const score = Math.max(0, Math.min(100, components.reduce((sum, c) => sum + c.points, 0)));
    const band = internationalFitBandFromScore(score);

    return {
      score,
      band,
      components,
      blockers: blockers(job),
      routeSummary: routeSummary(job),
      expatBenefitsScore: expatAssessmentHasEvidence(expat) ? expat.score : null,
      expatEvidenceCoverage: expat.evidenceCoverage,
      expatLowEvidence: expat.lowEvidence,
      explanation: explain(job, band, score, components),
    };
  }

  // --------------------------------------------------------------- components

  /** The employer's stated route in, or the honest absence of one. */
  private routeComponent(job: Job): InternationalFitComponent {
    const label = "Route from Madagascar";
    switch (job.eligibility) {
      case MadagascarEligibility.ELIGIBLE:
        return {
          label,
          detail: "The posting states it accepts applicants like you, or is a worldwide remote role.",
          strength: EvidenceStrength.DOCUMENTED,
          points: 35,
          maxPoints: 35,
        };
      case MadagascarEligibility.VISA_SPONSORED:
        return {
          label,
          detail: "The employer states visa sponsorship or relocation support. Confirm the conditions before applying.",
          strength: EvidenceStrength.DOCUMENTED,
          points: 30,
          maxPoints: 35,
        };
      case MadagascarEligibility.UNCLEAR:
        return {
          label,
          detail:
            "The posting does not say whether it accepts applicants from Madagascar. That is missing information, not a refusal.",
          strength: EvidenceStrength.UNKNOWN,
          points: 10,
          maxPoints: 35,
        };
      case MadagascarEligibility.RESTRICTED:
        return {
          label,
          detail: "The employer restricts this role to local applicants, so there is no route in from Madagascar.",
          strength: EvidenceStrength.REFUSED,
          points: 0,
          maxPoints: 35,
        };
    }
  }

  /** The candidate's trade vs. the posting's title, from the existing role classifier. */
  private roleComponent(job: Job, _profile: CandidateProfile): InternationalFitComponent {
    const label = "Role alignment";
    switch (job.roleFamily) {
      case RoleFamily.TARGET:
        return {
          label,
          detail: job.matchedTargetRole
            ? `"${job.title}" is one of your target roles (${job.matchedTargetRole}).`
            : `"${job.title}" is one of your target roles.`,
          strength: EvidenceStrength.DOCUMENTED,
          points: 20,
          maxPoints: 20,
        };
      case RoleFamily.MAINTENANCE_FAMILY:
        return {
          label,
          detail: `"${job.title}" is in your wider trade, though not one of your named target roles.`,
          strength: EvidenceStrength.INFERRED,
          points: 12,
          maxPoints: 20,
        };
      case RoleFamily.UNRELATED:
        return {
          label,
          detail: `"${job.title}" is a different profession from your trade, so this is not your role.`,
          strength: EvidenceStrength.INFERRED,
          points: 2,
          maxPoints: 20,
        };
      case RoleFamily.UNKNOWN:
        return {
          label,
          detail: "There was not enough of a title to judge whether this is your trade.",
          strength: EvidenceStrength.UNKNOWN,
          points: 8,
          maxPoints: 20,
        };
    }
  }

  /** Canonical skills in common, gated by role alignment exactly as the match score is. */
  private skillsComponent(job: Job, profile: CandidateProfile): InternationalFitComponent {
    const label = "Skills";
    const candidate = new Set(profile.skills.map((s) => canonicalizeSkill(s)));
    const named = new Set([...job.matchedSkills, ...job.missingSkills].map((s) => canonicalizeSkill(s)));
    const matched = [...named].filter((s) => candidate.has(s)).length;
    const total = named.size;

    if (candidate.size === 0) {
      return {
        label,
        detail: "Your profile lists no skills yet, so there is nothing to compare.",
        strength: EvidenceStrength.UNKNOWN,
        points: 0,
        maxPoints: 20,
      };
    }
    if (total === 0) {
      return {
        label,
        detail: "The posting does not name specific skills, so this part is scored neutrally.",
        strength: EvidenceStrength.UNKNOWN,
        points: 10,
        maxPoints: 20,
      };
    }

    const ratio = matched / total;
    const gate =
      job.roleFamily === RoleFamily.TARGET
        ? 1
        : job.roleFamily === RoleFamily.MAINTENANCE_FAMILY
          ? 0.85
          : job.roleFamily === RoleFamily.UNKNOWN
            ? 0.5
            : 0.25;
    const points = Math.max(0, Math.min(20, Math.round(20 * ratio * gate)));
    return {
      label,
      detail:
        job.roleFamily === RoleFamily.UNRELATED && matched > 0
          ? `You share ${matched} of the ${total} skills named, but this is not a role you do; the overlap counts for little.`
          : `You match ${matched} of the ${total} skills named in the posting.`,
      strength: matched === 0 ? EvidenceStrength.UNKNOWN : EvidenceStrength.DOCUMENTED,
      points,
      maxPoints: 20,
    };
  }

  /** Years of experience vs. the posting's stated minimum. Silence is neutral. */
  private experienceComponent(job: Job, profile: CandidateProfile): InternationalFitComponent {
    const label = "Experience";
    const required = job.requirements.minYears;
    const years = profile.yearsOfExperience;
    if (years == null) {
      return {
        label,
        detail: "Your profile does not state your years of experience yet.",
        strength: EvidenceStrength.UNKNOWN,
        points: 0,
        maxPoints: 10,
      };
    }
    if (required == null) {
      return {
        label,
        detail: "The posting does not state a required number of years.",
        strength: EvidenceStrength.UNKNOWN,
        points: 7,
        maxPoints: 10,
      };
    }
    if (years >= required) {
      return {
        label,
        detail: `Your ${years} years cover the ${required}+ years requested.`,
        strength: EvidenceStrength.DOCUMENTED,
        points: 10,
        maxPoints: 10,
      };
    }
    if (years + 1 >= required) {
      return {
        label,
        detail: `The posting asks for ${required}+ years; you are close with ${years}.`,
        strength: EvidenceStrength.INFERRED,
        points: 7,
        maxPoints: 10,
      };
    }
    return {
      label,
      detail: `The posting asks for ${required}+ years; your profile shows ${years}.`,
      strength: EvidenceStrength.INFERRED,
      points: Math.max(1, Math.min(6, Math.round((10 * years) / required))),
      maxPoints: 10,
    };
  }

  /** Languages the posting asks for vs. the CV's, with the honest "cannot tell" case. */
  private languageComponent(job: Job, profile: CandidateProfile): InternationalFitComponent {
    const label = "Language";
    const required = job.languages;
    const known = profile.languages.map((l) => l.name.toLowerCase());
    if (required.length === 0) {
      return {
        label,
        detail: "The posting does not name a required language.",
        strength: EvidenceStrength.UNKNOWN,
        points: 6,
        maxPoints: 10,
      };
    }
    if (known.length === 0) {
      return {
        label,
        detail: `The posting asks for ${required.join(", ")}; your profile lists no languages.`,
        strength: EvidenceStrength.UNKNOWN,
        points: 0,
        maxPoints: 10,
      };
    }
    const have = required.filter((req) => known.some((k) => k.includes(req.toLowerCase()))).length;
    const points = Math.max(0, Math.min(10, Math.round((10 * have) / required.length)));
    const fluent = required.filter((req) =>
      profile.languages.some((l) => l.name.toLowerCase() === req.toLowerCase() && isFluent(l.level)),
    );
    return {
      label,
      detail:
        have === required.length && fluent.length > 0
          ? `You list ${fluent.join(", ")}, which the posting asks for.`
          : have === required.length
            ? `You list ${required.join(", ")}, which the posting asks for; check the level required.`
            : `The posting asks for ${required.join(", ")}; check the level required.`,
      strength: have === required.length && fluent.length > 0 ? EvidenceStrength.DOCUMENTED : EvidenceStrength.INFERRED,
      points,
      maxPoints: 10,
    };
  }

  /** A country the candidate wants, a worldwide remote, or a destination they did not choose. */
  private destinationComponent(job: Job, profile: CandidateProfile): InternationalFitComponent {
    const label = "Destination";
    const preferred = profile.preferredCountries.map((c) => c.toLowerCase().trim());
    if (preferred.length === 0) {
      return {
        label,
        detail: "You have not chosen preferred countries, so the destination is scored neutrally.",
        strength: EvidenceStrength.UNKNOWN,
        points: 3,
        maxPoints: 5,
      };
    }
    if (preferred.some((p) => p === job.country.toLowerCase())) {
      return {
        label,
        detail: `${job.country} is one of your preferred countries.`,
        strength: EvidenceStrength.DOCUMENTED,
        points: 5,
        maxPoints: 5,
      };
    }
    if (job.country.toLowerCase() === "worldwide") {
      return {
        label,
        detail: "This is a worldwide remote role, which fits any location.",
        strength: EvidenceStrength.DOCUMENTED,
        points: 4,
        maxPoints: 5,
      };
    }
    return {
      label,
      detail: `${job.country} is not one of your preferred countries.`,
      strength: EvidenceStrength.INFERRED,
      points: 1,
      maxPoints: 5,
    };
  }
}

/**
 * A profile with neither a title nor any skills cannot support a fit judgement. The engine
 * returns `INSUFFICIENT` rather than a number built on nothing.
 */
function isProfileThin(profile: CandidateProfile): boolean {
  return (
    profile.currentTitle.trim().length === 0 &&
    profile.previousTitles.length === 0 &&
    profile.skills.length === 0 &&
    profile.yearsOfExperience == null
  );
}

function isFluent(level: LanguageLevel | null): boolean {
  return level === LanguageLevel.FLUENT || level === LanguageLevel.NATIVE || level === LanguageLevel.ADVANCED;
}

/** The facts from the posting that stand in the way, official ones first. */
function blockers(job: Job): string[] {
  const out: string[] = [];
  if (job.eligibility === MadagascarEligibility.RESTRICTED) {
    out.push("The employer restricts this role to local applicants.");
  } else if (job.eligibility === MadagascarEligibility.UNCLEAR) {
    out.push("The posting does not document a visa route or sponsorship; confirm it before applying.");
  }
  if (job.scamRisk !== ScamRisk.LOW) {
    out.push("The scam shield flagged signals to check before you share any details.");
  }
  return out;
}

/** One honest sentence about the route in, or its absence. */
function routeSummary(job: Job): string {
  switch (job.eligibility) {
    case MadagascarEligibility.ELIGIBLE:
      return "There is a route in: the posting is open to applicants like you.";
    case MadagascarEligibility.VISA_SPONSORED:
      return "The employer states sponsorship or relocation. Confirm the conditions with them.";
    case MadagascarEligibility.UNCLEAR:
      return "No route in is stated. Ask the employer before you invest time.";
    case MadagascarEligibility.RESTRICTED:
      return "There is no route in from Madagascar for this posting.";
  }
}

function explain(job: Job, band: InternationalFitBand, score: number, components: InternationalFitComponent[]): string {
  const weakest = components.reduce<InternationalFitComponent | null>(
    (min, c) => (min == null || earnedShare(c) < earnedShare(min) ? c : min),
    null,
  );
  const weak = weakest != null && earnedShare(weakest) < 0.5 ? weakest : null;
  const base =
    band === InternationalFitBand.EXCELLENT
      ? `This is one of your strongest international fits at ${score}%.`
      : band === InternationalFitBand.STRONG
        ? `This is a strong international fit for you at ${score}%.`
        : band === InternationalFitBand.POSSIBLE
          ? `This is worth a look at ${score}%.`
          : band === InternationalFitBand.WEAK
            ? `This is a stretch for you at ${score}%.`
            : "There is not enough on your profile to judge this posting yet.";
  const gap = weak != null ? ` The weakest part is ${weak.label.toLowerCase()}: ${weak.detail}` : "";
  const expat = job.benefitEvidence.length > 0 ? " Benefits are scored separately, from what the posting documents." : "";
  return base + gap + expat;
}
