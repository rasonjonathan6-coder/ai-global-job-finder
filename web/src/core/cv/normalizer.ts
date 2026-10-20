/**
 * Normalises raw extracted CV text before anything is matched against it.
 *
 * Direct port of the Kotlin `CvTextNormalizer` in `data/cv/analysis`. Text pulled out of a
 * real PDF is messy: a line may be split across many fragments, a word broken by a soft
 * hyphen, stray spaces inserted inside words, and accented letters rendered as bare Latin-1
 * bytes (a CV saying "Mécanicien" can arrive as "MÃ©canicien"). None of that should decide
 * whether a field is found, so the cleanup happens once, up front.
 *
 * `foldCvText` strips accents for language-tolerant matching. It is only ever used for
 * comparison — the value shown to the user keeps its original characters.
 */

const SOFT_HYPHEN_AT_LINE_END = /([A-Za-zÀ-ÿ])-\s*\n\s*([A-Za-zÀ-ÿ])/g;
const MULTI_SPACE = /[ \t\u00A0]{2,}/g;

/** Repairs encoding and layout damage without changing the words themselves. */
export function normalizeCvText(raw: string): string {
  if (raw.length === 0) return raw;

  let text = raw.replace(/\r/g, "\n").replace(/\u0000/g, " ");

  // A PDF page break must not glue the last word of one page to the first of the next.
  text = text.replace(/[\u000C\u2028\u2029]/g, "\n");

  // Word-reference separators: drop them rather than make the reader clean them up.
  text = text.replace(/[\u00AD\u200B]/g, "");

  // "Mainte-\n nance" -> "Maintenance" (hyphenated word split across two lines).
  text = text.replace(SOFT_HYPHEN_AT_LINE_END, "$1$2");

  // Their bullets and box-drawing marks become plain dashes.
  text = text.replace(/[\u2022\u25CF\u25AA\u25E6\u2023\u2043\u2219]/g, "-");

  // Unify the dash family so downstream regexes only deal with the ASCII hyphen.
  text = text.replace(/[\u2013\u2014\u2212\u2010\u2011]/g, "-");

  // Repair mojibake only when it is unambiguous: UTF-8 bytes read back as Latin-1.
  text = repairMojibake(text);

  // Collapse runs of spaces but keep single newlines so section splitting still works.
  text = text
    .split("\n")
    .map((line) => line.replace(MULTI_SPACE, " ").replace(/\s+$/, ""))
    .join("\n");

  return text.trim();
}

/**
 * True when `text` looks like UTF-8 that was decoded as Latin-1/Windows-1252. This is the
 * classic way "Mécanicien" reaches us as "MÃ©canicien".
 */
export function looksLikeMojibake(text: string): boolean {
  return text.includes("Ã") || text.includes("Â") || text.includes("â€");
}

function repairMojibake(text: string): string {
  if (!looksLikeMojibake(text)) return text;
  // Re-read the mojibake as Latin-1 bytes, then decode those bytes as UTF-8. When the
  // round-trip produces fewer replacement characters than we started with, it worked.
  const reDecoded = new TextDecoder("utf-8", { fatal: false }).decode(latin1Bytes(text));
  const before = countReplacementChars(text) + countSuspiciousSequences(text);
  const after = countReplacementChars(reDecoded) + countSuspiciousSequences(reDecoded);
  return after < before ? reDecoded : text;
}

function latin1Bytes(value: string): Uint8Array {
  const out = new Uint8Array(value.length);
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i);
    out[i] = code <= 0xff ? code : 0x3f; // Java's ISO-8859-1 encoder substitutes '?'
  }
  return out;
}

function countReplacementChars(text: string): number {
  let count = 0;
  for (const ch of text) if (ch === "\uFFFD") count++;
  return count;
}

function countSuspiciousSequences(text: string): number {
  return (text.match(/Ã.|Â.|â€/g) ?? []).length;
}

/** Accent-folded, lowercased form used only for keyword matching. */
export function foldCvText(text: string): string {
  return text
    .normalize("NFD")
    .replace(/\p{Mn}+/gu, "")
    .replace(/œ/g, "o")
    .replace(/æ/g, "a")
    .toLowerCase();
}

/** True when a line is a bullet item rather than a heading or a plain sentence. */
export function isBulletLine(line: string): boolean {
  const trimmed = line.trimStart();
  return (
    trimmed.startsWith("-") ||
    trimmed.startsWith("*") ||
    trimmed.startsWith("•") ||
    trimmed.startsWith("▪") ||
    trimmed.startsWith("○") ||
    trimmed.startsWith("‣")
  );
}
