/**
 * Whole-word/phrase containment, so "fit" does not fire inside "benefit" and "pump" does
 * not fire inside "pumpkin". Phrases may contain spaces. Mirrors the Kotlin `Phrases`
 * helper (and the private `containsWord`/`containsPhrase` copies the engines used).
 */
export function containsPhrase(haystack: string, phrase: string): boolean {
  const needle = phrase.toLowerCase().trim();
  if (needle.length === 0) return false;
  let index = haystack.indexOf(needle);
  while (index >= 0) {
    const beforeOk = index === 0 || !isLetterOrDigit(haystack[index - 1]);
    const afterIndex = index + needle.length;
    const afterOk = afterIndex >= haystack.length || !isLetterOrDigit(haystack[afterIndex]);
    if (beforeOk && afterOk) return true;
    index = haystack.indexOf(needle, index + 1);
  }
  return false;
}

/**
 * The variant used by `SkillLexicon` and `CountryCatalog`: bounded by non-*letters* rather
 * than non-alphanumerics, so "s7" still matches inside "siemens s7-1200" (the digit after
 * the phrase is not a letter).
 */
export function containsWord(haystack: string, needle: string): boolean {
  if (needle.length === 0) return false;
  let index = haystack.indexOf(needle);
  while (index >= 0) {
    const beforeOk = index === 0 || !isLetter(haystack[index - 1]);
    const afterIndex = index + needle.length;
    const afterOk = afterIndex >= haystack.length || !isLetter(haystack[afterIndex]);
    if (beforeOk && afterOk) return true;
    index = haystack.indexOf(needle, index + 1);
  }
  return false;
}

export function isLetter(ch: string): boolean {
  return /\p{L}/u.test(ch);
}

export function isLetterOrDigit(ch: string): boolean {
  return /[\p{L}\p{Nd}]/u.test(ch);
}
