import {
  MatchSignal,
  type CandidateProfile,
  type MatchReason,
  type MatchWeights,
  defaultMatchWeights,
} from "../../models";
import { canonicalizeSkill, detectSkills } from "./skill-lexicon";

/** The full result of scoring one posting against the candidate profile. */
export interface MatchAssessment {
  score: number;
  reasons: MatchReason[];
  matchedSkills: string[];
  missingSkills: string[];
  requiredYears: number | null;
  requiredLanguages: string[];
}

/** Everything the matching engine may read from a posting. */
export interface MatchInput {
  title: string;
  country: string;
  description: string;
  tags?: string[];
  /** Days since the posting was published, or null when the source omitted the date. */
  postedDaysAgo?: number | null;
}

const STOP_WORDS = new Set([
  "and", "or", "the", "a", "an", "of", "for", "with", "to", "in", "at", "on",
  "senior", "junior", "lead", "staff", "m", "f", "d", "h", "w",
]);

const YEAR_PATTERN = /(\d{1,2})\s*\+?\s*(?:to\s*\d{1,2}\s*)?(?:years|yrs|year)/g;

const LANGUAGE_NAMES = [
  "English", "French", "German", "Spanish", "Portuguese", "Dutch",
  "Italian", "Arabic", "Mandarin", "Russian", "Swedish", "Norwegian",
];

function tokens(text: string): string[] {
  return text
    .toLowerCase()
    .split(/[^a-z0-9+#]+/)
    .map((t) => t.trim())
    .filter((t) => t.length >= 2 && !STOP_WORDS.has(t));
}

/** Smallest "N years" requirement stated in the description. */
function requiredYears(description: string): number | null {
  const values = Array.from(description.toLowerCase().matchAll(YEAR_PATTERN))
    .map((m) => Number.parseInt(m[1], 10))
    .filter((n) => n >= 1 && n <= 40);
  return values.length === 0 ? null : Math.min(...values);
}

function languagesIn(text: string): string[] {
  const lower = text.toLowerCase();
  return LANGUAGE_NAMES.filter((name) => lower.includes(name.toLowerCase()));
}

/** Fraction of the posting's title words that appear in the candidate's titles. */
function titleSimilarity(profile: CandidateProfile, jobTitle: string): number {
  const sources = [profile.currentTitle, ...profile.previousTitles, ...profile.preferredRoles].filter(
    (t): t is string => typeof t === "string" && t.length > 0,
  );
  const candidateTokens = new Set(sources.flatMap((t) => tokens(t)));
  const jobTokens = tokens(jobTitle);
  if (jobTokens.length === 0 || candidateTokens.size === 0) return 0;
  const overlap = jobTokens.filter((t) => candidateTokens.has(t)).length;
  return Math.max(0, Math.min(1, overlap / jobTokens.length));
}

/**
 * Scores a posting against the candidate profile. Mirrors the Kotlin `MatchingEngine`.
 *
 * The score is the sum of six weighted signals, each capped at its own weight. Every
 * signal also produces a `MatchReason` explaining how many points it contributed, so the
 * number shown on the card can always be opened up and read. When the profile is empty the
 * score is low by construction rather than fabricated.
 */
export class MatchingEngine {
  constructor(private readonly weights: MatchWeights = defaultMatchWeights()) {}

  assess(job: MatchInput, profile: CandidateProfile): MatchAssessment {
    const tags = job.tags ?? [];
    const reasons: MatchReason[] = [];

    const candidateSkills = new Set(profile.skills.map((s) => canonicalizeSkill(s)));
    const requiredSkills = detectSkills(`${job.title} ${tags.join(" ")} ${job.description}`);

    const matched = [...requiredSkills].filter((s) => candidateSkills.has(s)).sort();
    const missing = [...requiredSkills].filter((s) => !candidateSkills.has(s)).sort();

    let score = 0;

    // ---- Role / title alignment
    // Computed before skills because it gates how much a skill overlap is worth: a single
    // familiar tool in an otherwise unrelated posting is not a skill match.
    const titleRatio = titleSimilarity(profile, job.title);
    const titlePoints = Math.round(this.weights.title * titleRatio);
    score += titlePoints;
    reasons.push({
      label: "Role",
      detail:
        titleRatio >= 0.75
          ? `Your current and previous titles closely match "${job.title}".`
          : titleRatio >= 0.35
            ? `Your background overlaps partly with "${job.title}".`
            : `Your recorded titles do not closely match "${job.title}".`,
      signal: titleRatio >= 0.5 ? MatchSignal.POSITIVE : titleRatio >= 0.25 ? MatchSignal.NEUTRAL : MatchSignal.NEGATIVE,
      weight: titlePoints,
    });

    // ---- Skills
    const rawSkillRatio = requiredSkills.size === 0 ? 0.45 : matched.length / requiredSkills.size;
    // A skill overlap only counts when the posting is for a role the candidate does.
    const skillGate = requiredSkills.size === 0 || titleRatio >= 0.35 ? 1 : titleRatio >= 0.2 ? 0.5 : 0.2;
    const skillPoints = Math.round(this.weights.skills * rawSkillRatio * skillGate);
    score += skillPoints;
    reasons.push({
      label: "Skills",
      detail:
        requiredSkills.size === 0 && candidateSkills.size === 0
          ? "No comparable skills found in the posting or your profile yet."
          : requiredSkills.size === 0
            ? "The posting does not name specific skills, so this part is scored neutrally."
            : matched.length === 0
              ? `None of the ${requiredSkills.size} skills named in the posting appear in your profile.`
              : skillGate < 1
                ? "The posting names a skill you have, but it is not a role you do; the overlap counts for little."
                : missing.length === 0
                  ? `You match all ${requiredSkills.size} skills named in the posting.`
                  : `You match ${matched.length} of ${requiredSkills.size} skills named in the posting.`,
      signal:
        requiredSkills.size === 0
          ? MatchSignal.NEUTRAL
          : matched.length === 0 || skillGate < 1
            ? MatchSignal.NEGATIVE
            : MatchSignal.POSITIVE,
      weight: skillPoints,
    });

    // ---- Experience
    const reqYears = requiredYears(job.description);
    const candidateYears = profile.yearsOfExperience ?? 0;
    const experienceRatio =
      reqYears == null
        ? 0.7
        : candidateYears >= reqYears
          ? 1
          : candidateYears + 1 >= reqYears
            ? 0.7
            : reqYears === 0
              ? 1
              : Math.max(0.15, Math.min(0.6, candidateYears / reqYears));
    const experiencePoints = Math.round(this.weights.experience * experienceRatio);
    score += experiencePoints;
    reasons.push({
      label: "Experience",
      detail:
        profile.yearsOfExperience == null
          ? "Your profile does not state your years of experience yet."
          : reqYears == null
            ? "The posting does not state a required number of years."
            : candidateYears >= reqYears
              ? `Your ${candidateYears} years cover the ${reqYears}+ years requested.`
              : `The posting asks for ${reqYears}+ years; your profile shows ${candidateYears}.`,
      signal: reqYears == null ? MatchSignal.NEUTRAL : candidateYears >= reqYears ? MatchSignal.POSITIVE : MatchSignal.NEGATIVE,
      weight: experiencePoints,
    });

    // ---- Location preference
    const preferred = profile.preferredCountries.map((c) => c.toLowerCase().trim());
    const locationRatio =
      preferred.length === 0
        ? 0.6
        : preferred.some((p) => p === job.country.toLowerCase())
          ? 1
          : job.country.toLowerCase() === "worldwide"
            ? 0.8
            : 0.3;
    const locationPoints = Math.round(this.weights.location * locationRatio);
    score += locationPoints;
    reasons.push({
      label: "Location",
      detail:
        preferred.length === 0
          ? "You have not chosen preferred countries, so location is scored neutrally."
          : preferred.some((p) => p === job.country.toLowerCase())
            ? `${job.country} is one of your preferred countries.`
            : job.country.toLowerCase() === "worldwide"
              ? "This is a worldwide remote role, which fits any location."
              : `${job.country} is not in your preferred countries.`,
      signal: preferred.length === 0 ? MatchSignal.NEUTRAL : locationRatio >= 0.8 ? MatchSignal.POSITIVE : MatchSignal.NEGATIVE,
      weight: locationPoints,
    });

    // ---- Language
    const requiredLanguages = languagesIn(`${job.description} ${tags.join(" ")}`);
    const candidateLanguages = new Set(profile.languages.map((l) => l.name.toLowerCase()));
    const languageRatio =
      requiredLanguages.length === 0
        ? 0.6
        : requiredLanguages.filter((req) => [...candidateLanguages].some((c) => c.includes(req.toLowerCase()))).length /
          requiredLanguages.length;
    const languagePoints = Math.round(this.weights.language * languageRatio);
    score += languagePoints;
    reasons.push({
      label: "Language",
      detail:
        requiredLanguages.length === 0
          ? "The posting does not name a required language."
          : candidateLanguages.size === 0
            ? `The posting asks for ${requiredLanguages.join(", ")}; your profile lists no languages.`
            : languageRatio >= 1
              ? `You list ${requiredLanguages.join(", ")}, which the posting asks for.`
              : `The posting asks for ${requiredLanguages.join(", ")}; check the level required.`,
      signal: requiredLanguages.length === 0 ? MatchSignal.NEUTRAL : languageRatio >= 1 ? MatchSignal.POSITIVE : MatchSignal.NEGATIVE,
      weight: languagePoints,
    });

    // ---- Recency
    const days = job.postedDaysAgo ?? null;
    const recencyRatio = days == null ? 0.6 : days >= 0 && days <= 7 ? 1 : days >= 8 && days <= 30 ? 0.6 : days > 30 ? 0.3 : 0.6;
    const recencyPoints = Math.round(this.weights.recency * recencyRatio);
    score += recencyPoints;
    reasons.push({
      label: "Freshness",
      detail:
        days == null
          ? "The source did not publish a posting date."
          : days >= 0 && days <= 7
            ? "Posted in the last week."
            : days >= 8 && days <= 30
              ? "Posted within the last month."
              : "Posted over a month ago; the role may be filled.",
      signal: days == null ? MatchSignal.NEUTRAL : days <= 7 ? MatchSignal.POSITIVE : MatchSignal.NEUTRAL,
      weight: recencyPoints,
    });

    const finalScore = Math.max(5, Math.min(99, score));
    return {
      score: finalScore,
      reasons,
      matchedSkills: matched,
      missingSkills: missing,
      requiredYears: reqYears,
      requiredLanguages,
    };
  }
}
