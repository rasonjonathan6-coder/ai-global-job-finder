import type { JobRequirement, JobRequirements } from "../../models";
import { containsPhrase } from "./phrases";

/**
 * Reads the requirements a posting states about years of experience, certifications and
 * licences. Mirrors the Kotlin `RequirementAnalyzer`. It is a pure function of the text and
 * never invents a requirement: a phrase only counts when it actually appears.
 */

/** Certification keywords that matter for industrial maintenance roles. */
const CERTIFICATION_TERMS = [
  "nvq", "city & guilds", "city and guilds", "bs en", "iso 9712", "asnt", "aws certified",
  "certified welder", "welding certificate", "electrical license", "electrical licence",
  "licensed electrician", "licenced electrician", "cscs card", "compEx", "comp ex",
  "hydraulic certificate", "pneumatic certificate", "first aid certificate",
  "confined space", "working at height", "forklift licence", "forklift license",
  "rigging certificate", "rigger", "trade certificate", "trade test", "red seal",
  "vocational qualification", "technical diploma", "engineering diploma",
];

/** Licence/permit terms that gate a trades role. */
const LICENCE_TERMS = [
  "driving licence", "driving license", "valid driver", "clean driving",
  "work permit", "work permit required", "right to work", "work authorisation",
  "work authorization", "cscs", "site card", "medical certificate", "medical fitness",
];

const YEAR_PATTERN = /(\d{1,2})\s*(?:-\s*\d{1,2}\s*)?\+?\s*(?:years|yrs|year)\b/gi;

function displayLabel(term: string): string {
  return term
    .split(" ")
    .map((word) =>
      word.length <= 3 && !["iso", "aws", "nvq", "cscs"].includes(word)
        ? word
        : word.charAt(0).toUpperCase() + word.slice(1),
    )
    .join(" ");
}

function sentenceWith(sentences: string[], term: string): string | null {
  return sentences.find((s) => containsPhrase(s.toLowerCase(), term)) ?? null;
}

/** Reads the requirements a posting states. */
export function analyzeRequirements(description: string): JobRequirements {
  const lower = description.toLowerCase();
  // Sentences keep their original casing so the evidence reads as the employer wrote it.
  const sentences = description
    .split(/[.\n;!?•]+/)
    .map((s) => s.trim())
    .filter((s) => s.length >= 4);

  const years = Array.from(lower.matchAll(YEAR_PATTERN))
    .map((m) => Number.parseInt(m[1], 10))
    .filter((n) => n >= 1 && n <= 40);

  const certs: JobRequirement[] = [];
  for (const term of CERTIFICATION_TERMS) {
    const sentence = sentenceWith(sentences, term);
    if (sentence) certs.push({ label: displayLabel(term), evidence: sentence });
  }
  const licences: JobRequirement[] = [];
  for (const term of LICENCE_TERMS) {
    const sentence = sentenceWith(sentences, term);
    if (sentence) licences.push({ label: displayLabel(term), evidence: sentence });
  }

  return {
    minYears: years.length === 0 ? null : Math.min(...years),
    certifications: dedupeByLabel(certs),
    licences: dedupeByLabel(licences),
  };
}

function dedupeByLabel(items: JobRequirement[]): JobRequirement[] {
  const seen = new Set<string>();
  return items.filter((item) => (seen.has(item.label) ? false : (seen.add(item.label), true)));
}
