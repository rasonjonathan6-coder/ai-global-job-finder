import type {
  CandidateProfile,
  CertificationEntry,
  CvSectionPresence,
  EducationEntry,
  LanguageSkill,
} from "../models";
import {
  CandidateField,
  CvAiStatus,
  LanguageLevel,
  RelocationPreference,
  emptyCandidateProfile,
  languageLevelFromToken,
} from "../models";
import {
  CV_CERTIFICATION_MARKERS,
  CV_COUNTRIES,
  CV_DEGREE_MARKERS,
  CV_FOLDED_PLACES,
  CV_INSTITUTION_MARKERS,
  CV_LANGUAGE_ALIASES,
  CV_SECTION_KEYWORDS,
  CV_SKILLS,
  CV_TITLE_MARKERS,
  CV_VISA_PHRASES,
  CvSection,
} from "./lexicon";
import { foldCvText, isBulletLine } from "./normalizer";
import { CV_ANALYSIS_LIMITS } from "./limits";
import { CvSections, headingSectionFor, sectionLines, segmentCvSections } from "./structure";
import { cvAnalysisWarnings } from "./warnings";
import { mergeCvProfile } from "./merger";

/**
 * Outcome of analysing CV text: the structured profile plus how it was produced. Mirrors the
 * Kotlin `CvAnalysisResult`.
 */
export interface CvAnalysisResult {
  profile: CandidateProfile;
  warnings: string[];
  /** 0–100, how much of the profile the analyser could actually fill. */
  confidence: number;
  /** The snippet each field was derived from, so the user can verify it. */
  evidence: Map<CandidateField, string>;
  /** Whether and how the AI pass contributed to this result. */
  aiStatus: CvAiStatus;
  effectiveEngineId: string | null;
  effectiveEngineLabel: string | null;
  /** Which sections the document carried, so an unreadable one is not called absent. */
  presence: CvSectionPresence;
}

/** A single field's "worth checking" note is derived from the profile, not guessed. */
export interface CvAnalyzer {
  readonly id: string;
  readonly label: string;
  readonly sendsDataOffDevice: boolean;
  analyze(text: string, existing: CandidateProfile, protectedFields: Set<CandidateField>): CvAnalysisResult;
}

const CURRENT_YEAR = 2026;
const MIN_USABLE_CHARACTERS = CV_ANALYSIS_LIMITS.MIN_USABLE_CHARACTERS;

const EMAIL = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/;
const YEAR = /\b(19|20)\d{2}\b/;
const DATE_RANGE = /\b(19|20\d{2})\s*(?:-|–|—|to|à|a)\s*(present|current|now|actuel|(?:19|20)\d{2})\b/gi;
const PHONE = /(\+?\d[\d\s().-]{6,18}\d)/g;

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function wordBoundary(value: string): RegExp {
  return new RegExp(`\\b${escapeRegExp(value)}\\b`, "g");
}

/**
 * Deterministic, on-device analyser. Direct port of the Kotlin `HeuristicCvAnalyzer`. Every
 * value it reports is matched in the CV text by the lexicon; nothing is inferred, generated
 * or padded. No network, no telemetry.
 */
export class HeuristicCvAnalyzer implements CvAnalyzer {
  readonly id = "on-device-heuristic";
  readonly label = "On-device analysis";
  readonly sendsDataOffDevice = false;

  analyze(text: string, existing: CandidateProfile, protectedFields: Set<CandidateField>): CvAnalysisResult {
    const evidence = new Map<CandidateField, string>();

    if (countNonWhitespace(text) < MIN_USABLE_CHARACTERS) {
      return {
        profile: existing,
        warnings: ["There is not enough text in this document to analyse."],
        confidence: 0,
        evidence,
        aiStatus: CvAiStatus.NONE,
        effectiveEngineId: null,
        effectiveEngineLabel: null,
        presence: { skills: false, education: false, languages: false },
      };
    }

    const lines = text.split("\n").map((line) => line.trim()).filter((line) => line.length > 0);
    const sections = segmentCvSections(lines);
    // Matching runs on an accent-folded copy; values shown to the user keep their accents.
    const foldedText = foldCvText(text);

    const email = EMAIL.exec(text)?.[0];
    const phone = findPhone(text);
    const name = findName(lines, sections);
    const location = findLocation(lines, sections);
    const titles = findTitles(sections, lines);
    const years = findYearsOfExperience(text, sections);
    const skills = matchSkills(foldedText);
    const languages = findLanguages(sections, lines);
    const education = findEducation(sections);
    const certifications = findCertifications(sections, lines);
    const countries = findCountries(foldedText);
    const salary = findSalaryExpectation(text);
    const relocation = findRelocation(foldedText);
    const visaNotes = findVisaNotes(foldedText);
    const industries = findIndustries(foldedText);
    const summary = findSummary(sections, lines);

    // Which sections the document carried, so an unreadable one is worded differently from a
    // genuinely absent one. Recorded before merging: the merged profile may also carry values
    // the user typed by hand, which the document never contained.
    const presence: CvSectionPresence = {
      skills: hasSectionContent(sections, CvSection.SKILLS),
      education: hasSectionContent(sections, CvSection.EDUCATION),
      languages: hasSectionContent(sections, CvSection.LANGUAGES),
    };

    const extracted: CandidateProfile = {
      ...emptyCandidateProfile(),
      fullName: name ?? "",
      email: email ?? "",
      phone: phone ?? "",
      location: location ?? "",
      currentTitle: titles[0] ?? "",
      previousTitles: titles.slice(1),
      yearsOfExperience: years,
      skills,
      industries,
      education,
      certifications,
      languages,
      preferredCountries: countries.filter((c) => c.toLowerCase() !== "madagascar").slice(0, 8),
      preferredRoles: titles.slice(0, 3),
      salaryExpectationMin: salary ? salary[0] : null,
      salaryExpectationMax: salary ? salary[1] : null,
      salaryCurrency: salary ? salary[2] : null,
      relocationPreference: relocation ?? RelocationPreference.UNKNOWN,
      needsVisaSponsorship: visaNotes.length > 0 ? true : null,
      workPermitNotes: visaNotes.join("; "),
      summary: summary ?? "",
    };

    if (name) evidence.set(CandidateField.FULL_NAME, name);
    if (email) evidence.set(CandidateField.EMAIL, email);
    if (phone) evidence.set(CandidateField.PHONE, phone);
    if (location) evidence.set(CandidateField.LOCATION, location);
    if (titles[0]) evidence.set(CandidateField.CURRENT_TITLE, titles[0]);
    if (years !== null) evidence.set(CandidateField.YEARS_OF_EXPERIENCE, `${years} years`);
    if (skills.length > 0) {
      evidence.set(
        CandidateField.SKILLS,
        skills.slice(0, 6).join(", ") + (skills.length > 6 ? ` +${skills.length - 6} more` : ""),
      );
    }
    if (education.length > 0) evidence.set(CandidateField.EDUCATION, education[0].degree);
    if (languages.length > 0) {
      evidence.set(CandidateField.LANGUAGES, languages.slice(0, 4).map(languageSkillDisplayLabel).join(", "));
    }

    const merged = mergeCvProfile(extracted, existing, protectedFields);

    return {
      profile: merged,
      // Notes come from the profile that is actually shown, so a warning can never contradict
      // a section the user can see.
      warnings: cvAnalysisWarnings(merged, presence),
      confidence: computeConfidence(extracted),
      evidence,
      aiStatus: CvAiStatus.NONE,
      effectiveEngineId: null,
      effectiveEngineLabel: null,
      presence,
    };
  }
}

/** The label shown for a language skill. Mirrors the Kotlin `LanguageSkill.displayLabel`. */
function languageSkillDisplayLabel(skill: LanguageSkill): string {
  return skill.level === null ? skill.name : `${skill.name} — ${LEVEL_LABEL[skill.level]}`;
}

const LEVEL_LABEL: Record<LanguageLevel, string> = {
  [LanguageLevel.BEGINNER]: "Beginner",
  [LanguageLevel.INTERMEDIATE]: "Intermediate",
  [LanguageLevel.ADVANCED]: "Advanced",
  [LanguageLevel.FLUENT]: "Fluent",
  [LanguageLevel.NATIVE]: "Native",
};

function countNonWhitespace(text: string): number {
  let count = 0;
  for (const ch of text) if (!/\s/.test(ch)) count++;
  return count;
}

// ------------------------------------------------------------------ extraction

/**
 * Finds the candidate name. The header area is searched first, then a Contact section,
 * because many CVs put the name under "Contact" rather than at the very top. Nothing is
 * invented — if no line qualifies, the field stays empty.
 */
function findName(lines: string[], sections: CvSections): string | null {
  const header = lines.slice(0, 8);
  const contact = sectionLines(sections, CvSection.CONTACT).slice(0, 4);
  const candidate = [...header, ...contact].find((line) => isNameLike(line));
  return candidate !== undefined ? candidate.trim() : null;
}

function isNameLike(line: string): boolean {
  const trimmed = line.trim();
  if (headingSectionFor(trimmed) !== null) return false;
  if (/\p{Nd}/u.test(trimmed)) return false;
  if (trimmed.includes("@") || trimmed.toLowerCase().includes("http")) return false;
  if (trimmed.includes("|") || trimmed.includes(":") || trimmed.includes(",")) return false;
  if (isBulletLine(trimmed)) return false;

  const words = trimmed.split(/\s+/).filter((word) => word.length > 0);
  if (words.length < 2 || words.length > 5) return false;
  if (trimmed.length < 5 || trimmed.length > 44) return false;
  // Every word must start with a letter; this removes "Ref: 2024", "Tél:", etc.
  if (!words.every((word) => /^\p{L}/u.test(word))) return false;
  // A job-title line is a headline, not a name.
  const folded = foldCvText(trimmed);
  if (CV_TITLE_MARKERS.some((marker) => folded.includes(marker))) return false;
  if (Object.prototype.hasOwnProperty.call(CV_SECTION_KEYWORDS, folded)) return false;
  // A name has at least two capitalised words.
  const capitalised = words.filter((word) => /^\p{Lu}/u.test(word)).length;
  return capitalised >= 2;
}

/**
 * True when a heading introduces content that follows it. A lone heading with nothing under
 * it counts as absent, so the note the user reads stays truthful.
 */
function hasSectionContent(sections: CvSections, section: CvSection): boolean {
  const body = sectionLines(sections, section).filter((line) => line.trim().length > 0);
  const headings = body.filter((line) => headingSectionFor(line) !== null).length;
  return body.length > headings;
}

function findLocation(lines: string[], sections: CvSections): string | null {
  // Prefer a contact section line, then the header area.
  const pool = [...sectionLines(sections, CvSection.CONTACT), ...lines.slice(0, 10)];

  // 1. A line naming a known country or major city, matched on folded text.
  const knownPlace = pool.find((line) => {
    const folded = foldCvText(line);
    for (const place of CV_FOLDED_PLACES) {
      if (new RegExp(`\\b${escapeRegExp(place)}\\b`).test(folded)) return true;
    }
    return false;
  });
  if (knownPlace !== undefined) return knownPlace.trim().slice(0, 60);

  // 2. A "City, Country"-shaped line: two comma-separated parts, letters only.
  const commaCity = pool.find((line) => {
    return (
      line.includes(",") &&
      line.length >= 4 &&
      line.length <= 40 &&
      !line.includes("@") &&
      line.split(",").length === 2 &&
      [...line].every((ch) => /[\p{L}\s,.'-]/u.test(ch))
    );
  });
  return commaCity !== undefined ? commaCity.trim() : null;
}

function findTitles(sections: CvSections, lines: string[]): string[] {
  const found: string[] = [];
  const seen = new Set<string>();
  const candidates = [...sectionLines(sections, CvSection.EXPERIENCE), ...lines.slice(0, 12)];
  for (const line of candidates) {
    const folded = foldCvText(line);
    const marker = CV_TITLE_MARKERS.find((m) => folded.includes(m));
    if (marker === undefined) continue;

    const part = line
      .split(/[|–—·,/]/)
      .find((segment) => foldCvText(segment).includes(marker));
    if (part === undefined) continue;
    const cleaned = trimChars(part.trim(), "-", "•", "*", " ", ":");
    if (cleaned.length >= 3 && cleaned.length <= 48 && cleaned.split(/\s+/).length <= 6) {
      const value = cleaned.charAt(0).toUpperCase() + cleaned.slice(1);
      if (!seen.has(value)) {
        seen.add(value);
        found.push(value);
      }
    }
    if (found.length >= 5) break;
  }
  return found;
}

function findYearsOfExperience(text: string, sections: CvSections): number | null {
  // An explicit statement wins.
  const explicit = [
    /(\d{1,2})\s*\+?\s*(?:years?|yrs?)\s*(?:of\s*)?(?:professional\s*)?experience/i,
    /experience\s*(?:of|:)?\s*(\d{1,2})\s*(?:years?|yrs?)/i,
    /(\d{1,2})\s*(?:ans?)\s*d[’']?\s*expérience/i,
    /plus de\s*(\d{1,2})\s*ans/i,
  ];
  for (const regex of explicit) {
    const match = regex.exec(text);
    const value = match ? Number.parseInt(match[1], 10) : NaN;
    if (!Number.isNaN(value) && value >= 1 && value <= 60) return value;
  }

  // Otherwise derive from the date ranges in the experience section.
  const experienceText = sectionLines(sections, CvSection.EXPERIENCE).join("\n");
  if (experienceText.trim().length === 0) return null;

  let earliest: number | null = null;
  let latest: number | null = null;
  for (const range of experienceText.matchAll(DATE_RANGE)) {
    const start = Number.parseInt(range[1], 10);
    if (Number.isNaN(start)) continue;
    const endToken = range[2];
    const end =
      /present|current|now|actuel/i.test(endToken) ? CURRENT_YEAR : (Number.parseInt(endToken, 10) || CURRENT_YEAR);
    if (start >= 1950 && start <= CURRENT_YEAR) {
      if (earliest === null || start < earliest) earliest = start;
      if (latest === null || end > latest) latest = end;
    }
  }
  if (earliest === null || latest === null) return null;
  const span = latest - earliest;
  return span >= 1 && span <= 60 ? span : null;
}

function matchSkills(foldedText: string): string[] {
  const matches: Array<[string, number]> = [];
  for (const definition of CV_SKILLS) {
    let best = 0;
    for (const alias of definition.aliases) {
      const occurrences = countMatches(wordBoundary(foldCvText(alias)), foldedText);
      if (occurrences > best) best = occurrences;
    }
    if (best > 0) matches.push([definition.canonical, best]);
  }
  // Most-mentioned skills first, then alphabetical for stable output.
  matches.sort((a, b) => (b[1] - a[1]) || (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
  const out: string[] = [];
  for (const [name] of matches) if (!out.includes(name)) out.push(name);
  return out;
}

function countMatches(pattern: RegExp, text: string): number {
  const matches = text.match(new RegExp(pattern.source, pattern.flags));
  return matches ? matches.length : 0;
}

function findLanguages(sections: CvSections, lines: string[]): LanguageSkill[] {
  const section = sectionLines(sections, CvSection.LANGUAGES);
  const pool = section.length > 0 ? section : lines;
  const out: LanguageSkill[] = [];
  const seen = new Set<string>();

  for (const line of pool) {
    // Fold the line so "Anglais", "francais" and "Espanol" all match their alias.
    const foldedLine = foldCvText(line);
    for (const [canonical, aliases] of Object.entries(CV_LANGUAGE_ALIASES)) {
      if (seen.has(canonical)) continue;
      const hit = aliases.find((alias) =>
        new RegExp(`\\b${escapeRegExp(foldCvText(alias))}\\b`).test(foldedLine),
      );
      if (hit === undefined) continue;

      // The level word sits right after the language name ("English - fluent").
      const levelRegex = new RegExp(
        `${escapeRegExp(foldCvText(hit))}(?:[\\s:-]+([a-z]{1,12}\\d?|native|fluent|beginner|intermediate|advanced|basic|courant|moyen|debutant|bilingue|natif|avance|maternelle))?`,
      );
      const token = levelRegex.exec(foldedLine)?.[1];
      const level = languageLevelFromToken(token && token.length > 0 ? token : null);
      out.push({ name: canonical, level, evidence: line.trim().slice(0, 80) });
      seen.add(canonical);
    }
  }
  return out;
}

function findEducation(sections: CvSections): EducationEntry[] {
  const pool = sectionLines(sections, CvSection.EDUCATION);
  if (pool.length === 0) return [];
  const out: EducationEntry[] = [];
  const lines = pool.map((line) => line.trim()).filter((line) => line.length > 0);

  let i = 0;
  while (i < lines.length && out.length < 6) {
    const line = lines[i];
    // Degree markers are matched on folded text, so "Ingenieur" and "Ingénieur" agree.
    const folded = foldCvText(line);
    const hasDegree = CV_DEGREE_MARKERS.some((marker) => folded.includes(marker));
    if (!hasDegree) {
      i++;
      continue;
    }

    const next = i + 1 < lines.length ? lines[i + 1] : null;
    const foldedNext = next !== null ? foldCvText(next) : null;

    // The institution is usually the next line, or the same line after a separator.
    const sameLineInstitution = CV_INSTITUTION_MARKERS.some((marker) => folded.includes(marker));
    const nextIsInstitution =
      foldedNext !== null &&
      CV_INSTITUTION_MARKERS.some((marker) => foldedNext.includes(marker)) &&
      !CV_DEGREE_MARKERS.some((marker) => foldedNext.includes(marker));

    // Split on the separators a CV uses to put degree and school on one line.
    const parts = line.split(/[|;,—·]/).map((part) => part.trim()).filter((part) => part.length > 0);
    const degree =
      parts.find((part) => {
        const f = foldCvText(part);
        return CV_DEGREE_MARKERS.some((marker) => f.includes(marker)) &&
          !CV_INSTITUTION_MARKERS.some((marker) => f.includes(marker));
      }) ??
      parts[0] ??
      line;

    let institution = "";
    if (nextIsInstitution) {
      institution = next!.trim();
    } else if (parts.length >= 2) {
      institution =
        parts.slice(1).find((part) =>
          CV_INSTITUTION_MARKERS.some((marker) => foldCvText(part).includes(marker)),
        ) ?? "";
    } else if (sameLineInstitution) {
      // Degree and school share the line but nothing after the degree marker names the school.
      institution = "";
    }

    const yearMatch = YEAR.exec(line)?.[0];
    const year = yearMatch
      ? Number.parseInt(yearMatch, 10)
      : next !== null
        ? Number.parseInt(YEAR.exec(next)?.[0] ?? "", 10) || null
        : null;

    out.push({
      degree: trimChars(degree.replace(new RegExp(YEAR.source, "g"), "").trim(), " ", "-", "•", ",").slice(0, 90),
      institution: trimChars(institution.replace(new RegExp(YEAR.source, "g"), "").trim(), " ", "-", "•", ",").slice(0, 90),
      country: "",
      year: year ?? 0,
    });
    // Skip the institution line we just consumed.
    i += nextIsInstitution ? 2 : 1;
  }
  return out;
}

function findCertifications(sections: CvSections, lines: string[]): CertificationEntry[] {
  const section = sectionLines(sections, CvSection.CERTIFICATIONS);
  const pool =
    section.length > 0
      ? section
      : lines.filter((line) => CV_CERTIFICATION_MARKERS.some((marker) => line.toLowerCase().includes(marker)));

  const out: CertificationEntry[] = [];
  const seen = new Set<string>();
  for (const line of pool) {
    const lower = line.toLowerCase();
    const marker = CV_CERTIFICATION_MARKERS.find((m) => lower.includes(m));
    if (marker === undefined) continue;
    if (line.length > 120) continue;
    const yearMatch = YEAR.exec(line)?.[0];
    const name = trimChars(line.trim(), "-", "•", "*", " ").slice(0, 90);
    const key = name.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({ name, issuer: "", year: yearMatch ? Number.parseInt(yearMatch, 10) : null });
    if (out.length >= 10) break;
  }
  return out;
}

function findCountries(foldedText: string): string[] {
  // [foldedText] is the accent-folded, lowercased document, so the country names are folded too.
  const found: Array<[string, number]> = [];
  for (const country of CV_COUNTRIES) {
    const folded = foldCvText(country);
    const count = countMatches(wordBoundary(folded), foldedText);
    if (count > 0) found.push([country, count]);
  }
  // Stable: Kotlin's sortedByDescending keeps insertion order for equal counts.
  return [...found].sort((a, b) => b[1] - a[1]).map(([name]) => name);
}

function findSalaryExpectation(text: string): [number, number, string] | null {
  const currencyPattern = "(EUR|USD|CAD|GBP|CHF|MGA|€|\\$|£)";
  const range = new RegExp(
    `${currencyPattern}\\s*([\\d.,]{2,9})\\s*(?:-|–|to)\\s*${currencyPattern}?\\s*([\\d.,]{2,9})`,
    "i",
  ).exec(text);
  if (range !== null) {
    const currency = normalizeCurrency(range[1]);
    const min = parseAmount(range[2]);
    if (min === null) return null;
    const max = parseAmount(range[4] && range[4].length > 0 ? range[4] : range[5]) ?? min;
    if (min >= 100 && min <= 10_000_000 && max >= 100 && max <= 10_000_000) return [min, max, currency];
  }
  const single = new RegExp(`${currencyPattern}\\s*([\\d.,]{3,9})`, "i").exec(text);
  if (single !== null) {
    const amount = parseAmount(single[2]);
    if (amount === null) return null;
    if (amount >= 100 && amount <= 10_000_000) return [amount, amount, normalizeCurrency(single[1])];
  }
  return null;
}

function parseAmount(raw: string): number | null {
  const cleaned = raw.replace(/[^\d]/g, "");
  if (cleaned.length === 0) return null;
  const value = Number.parseInt(cleaned, 10);
  return Number.isNaN(value) ? null : value;
}

function normalizeCurrency(token: string): string {
  switch (token.toUpperCase()) {
    case "€":
      return "EUR";
    case "$":
      return "USD";
    case "£":
      return "GBP";
    default:
      return token.toUpperCase();
  }
}

function findRelocation(foldedText: string): RelocationPreference | null {
  if (foldedText.includes("no relocation") || foldedText.includes("not willing to relocate")) {
    return RelocationPreference.NO_RELOCATION;
  }
  if (foldedText.includes("remote only")) return RelocationPreference.REMOTE_ONLY;
  if (
    foldedText.includes("willing to relocate") ||
    foldedText.includes("ready to relocate") ||
    foldedText.includes("open to relocation") ||
    foldedText.includes("mobilité") ||
    foldedText.includes("disponible pour expatriation")
  ) {
    return RelocationPreference.PREFERS_RELOCATION;
  }
  if (foldedText.includes("relocation") || foldedText.includes("relocate")) {
    return RelocationPreference.OPEN_TO_RELOCATION;
  }
  return null;
}

function findVisaNotes(foldedText: string): string[] {
  const out: string[] = [];
  for (const [phrase, note] of CV_VISA_PHRASES) {
    if (foldedText.includes(phrase) && !out.includes(note)) out.push(note);
  }
  return out;
}

const INDUSTRY_KEYWORDS: Array<[string, string[]]> = [
  ["Manufacturing", ["production line", "manufacturing", "factory", "usine", "assembly"]],
  ["Industrial maintenance", ["maintenance", "machinery", "conveyor", "machine"]],
  ["Construction", ["construction", "bâtiment", "chantier", "civil engineering"]],
  ["Logistics & transport", ["logistics", "warehouse", "supply chain", "transport", "shipping"]],
  ["Healthcare", ["hospital", "clinic", "patient", "nursing", "healthcare"]],
  ["Hospitality & tourism", ["hotel", "restaurant", "tourism", "hospitality"]],
  ["Agriculture", ["agriculture", "farming", "plantation", "sugar", "livestock"]],
  ["Energy & utilities", ["energy", "power plant", "electricity", "solar", "water treatment"]],
  ["Mining", ["mining", "mine", "quarry", "extraction"]],
  ["Information technology", ["software", "it support", "developer", "network", "database"]],
  ["Education", ["teaching", "school", "university", "training"]],
  ["Retail & sales", ["retail", "sales", "shop", "store", "commerce"]],
  ["Automotive", ["automotive", "vehicle", "car", "engine repair"]],
];

function findIndustries(foldedText: string): string[] {
  return INDUSTRY_KEYWORDS.filter(([, keywords]) => keywords.some((keyword) => foldedText.includes(keyword)))
    .map(([name]) => name)
    .slice(0, 4);
}

function findSummary(sections: CvSections, lines: string[]): string | null {
  const pool = sectionLines(sections, CvSection.SUMMARY);
  const source =
    pool.length > 0
      ? pool.join(" ")
      : (() => {
          // Fall back to the text directly under the name, which is often the headline.
          const firstComma = lines.findIndex((line) => line.includes(","));
          const start = firstComma < 0 ? 0 : firstComma + 1;
          return lines.slice(start, start + 4).join(" ");
        })();
  const cleaned = source.replace(/\s+/g, " ").trim();
  const clipped = cleaned.slice(0, 600);
  return clipped.length >= 30 ? clipped : null;
}

function computeConfidence(profile: CandidateProfile): number {
  let score = 0;
  if (profile.fullName.trim().length > 0) score += 15;
  if (profile.email.trim().length > 0) score += 15;
  if (profile.phone.trim().length > 0) score += 8;
  if (profile.location.trim().length > 0) score += 7;
  if (profile.currentTitle.trim().length > 0) score += 15;
  if (profile.yearsOfExperience !== null) score += 12;
  score += Math.min(profile.skills.length, 8) * 2; // up to 16
  if (profile.education.length > 0) score += 8;
  if (profile.languages.length > 0) score += 4;
  return Math.max(0, Math.min(100, score));
}

/**
 * Finds a phone number, rejecting anything whose digit count is outside the E.164-plausible
 * range so dates and reference numbers are not mistaken for it.
 */
function findPhone(text: string): string | null {
  for (const match of text.matchAll(PHONE)) {
    const value = match[0].trim();
    const digits = (value.match(/\d/g) ?? []).length;
    if (digits >= 7 && digits <= 15) return value.replace(/\s+/g, " ");
  }
  return null;
}

function trimChars(value: string, ...chars: string[]): string {
  const set = new Set(chars);
  let start = 0;
  let end = value.length;
  while (start < end && set.has(value[start])) start++;
  while (end > start && set.has(value[end - 1])) end--;
  return value.slice(start, end);
}
