import { CV_SECTION_KEYWORDS, CvSection } from "./lexicon";
import { foldCvText } from "./normalizer";

/**
 * Splits a document into its named sections. Direct port of the Kotlin `CvTextStructure`.
 * Real CVs are inconsistent, so this is deliberately forgiving: a line counts as a heading
 * if it is short, has no sentence punctuation and matches a known heading word. Text before
 * the first heading stays available under [CvSection.UNKNOWN].
 */

const MAX_HEADING_LENGTH = 42;
const MAX_HEADING_WORDS = 6;

export type CvSections = Map<CvSection, string[]>;

export function segmentCvSections(lines: string[]): CvSections {
  const result: CvSections = new Map();
  let current = CvSection.UNKNOWN;

  for (const raw of lines) {
    const line = raw.trim();
    if (line.length === 0) continue;

    const heading = headingSectionFor(line);
    if (heading !== null) {
      current = heading;
      if (!result.has(current)) result.set(current, []);
      continue;
    }
    if (!result.has(current)) result.set(current, []);
    result.get(current)!.push(line);
  }

  return result;
}

/** The lines recorded under `section`, or an empty list when the section is absent. */
export function sectionLines(sections: CvSections, section: CvSection): string[] {
  return sections.get(section) ?? [];
}

/** Returns the section a line announces, or null when the line is body text. */
export function headingSectionFor(line: string): CvSection | null {
  const trimmed = trimChars(line.trim(), ":", "•", "-", "*", "=", "_", "|", " ").trim();
  if (trimmed.length > MAX_HEADING_LENGTH) return null;
  const words = trimmed.split(/\s+/);
  if (words.length > MAX_HEADING_WORDS) return null;
  if (trimmed.endsWith(".") && words.length > 2) return null;
  if (/\p{Nd}/u.test(trimmed)) return null;

  // Fold accents and drop decoration so "Compétences", "COMPETENCES" and
  // "Compétences techniques" all reach the same lookup key.
  const normalised = foldCvText(trimmed)
    .replace(/[^a-z'\s]/g, "")
    .replace(/\s+/g, " ")
    .trim();

  const direct = CV_SECTION_KEYWORDS[normalised];
  if (direct !== undefined) return direct;

  // Accept a single heading word, e.g. "SKILLS" or "Skills", in either language.
  const single = normalised.length >= 4 ? normalised : "";
  return CV_SECTION_KEYWORDS[single] ?? null;
}

function trimChars(value: string, ...chars: string[]): string {
  const set = new Set(chars);
  let start = 0;
  let end = value.length;
  while (start < end && set.has(value[start])) start++;
  while (end > start && set.has(value[end - 1])) end--;
  return value.slice(start, end);
}
