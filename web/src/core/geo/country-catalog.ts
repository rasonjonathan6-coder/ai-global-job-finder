/**
 * Maps the free-text location strings that job boards publish ("Berlin", "France",
 * "Worldwide", "Anywhere in the World") onto a country with a flag. Mirrors the Kotlin
 * `CountryCatalog`.
 *
 * Job APIs are inconsistent: some send a city, some a country, some a region. This catalog
 * resolves all of them to one canonical name so the eligibility engine and the country
 * filters can work on a single value.
 */

export const WORLDWIDE = "Worldwide";
export const WORLDWIDE_FLAG = "🌍";

/**
 * Flag for a location the catalog cannot map to a country — a bare city ("Munich") or a
 * region ("Europe"). It is deliberately **not** the worldwide globe: the source said a
 * place, not "anywhere", so badging it as worldwide would misrepresent a local vacancy as
 * a role open to applicants from anywhere.
 */
export const UNRESOLVED_FLAG = "📍";

interface Entry {
  country: string;
  flag: string;
  aliases: string[];
}

const ENTRIES: Entry[] = [
  { country: "United States", flag: "🇺🇸", aliases: ["united states of america", "united states", "usa", "u.s.a", "u.s.", "america"] },
  { country: "Canada", flag: "🇨🇦", aliases: ["canada"] },
  { country: "United Kingdom", flag: "🇬🇧", aliases: ["united kingdom", "uk", "great britain", "britain", "england", "scotland", "wales", "northern ireland"] },
  { country: "Germany", flag: "🇩🇪", aliases: ["germany", "deutschland"] },
  { country: "France", flag: "🇫🇷", aliases: ["france"] },
  { country: "Netherlands", flag: "🇳🇱", aliases: ["netherlands", "holland"] },
  { country: "Belgium", flag: "🇧🇪", aliases: ["belgium"] },
  { country: "Switzerland", flag: "🇨🇭", aliases: ["switzerland"] },
  { country: "Austria", flag: "🇦🇹", aliases: ["austria"] },
  { country: "Ireland", flag: "🇮🇪", aliases: ["ireland"] },
  { country: "Spain", flag: "🇪🇸", aliases: ["spain"] },
  { country: "Portugal", flag: "🇵🇹", aliases: ["portugal"] },
  { country: "Italy", flag: "🇮🇹", aliases: ["italy"] },
  { country: "Sweden", flag: "🇸🇪", aliases: ["sweden"] },
  { country: "Norway", flag: "🇳🇴", aliases: ["norway"] },
  { country: "Denmark", flag: "🇩🇰", aliases: ["denmark"] },
  { country: "Finland", flag: "🇫🇮", aliases: ["finland"] },
  { country: "Poland", flag: "🇵🇱", aliases: ["poland"] },
  { country: "Czechia", flag: "🇨🇿", aliases: ["czechia", "czech republic"] },
  { country: "Slovakia", flag: "🇸🇰", aliases: ["slovakia", "slovak republic"] },
  { country: "Slovenia", flag: "🇸🇮", aliases: ["slovenia"] },
  { country: "Bulgaria", flag: "🇧🇬", aliases: ["bulgaria"] },
  { country: "Croatia", flag: "🇭🇷", aliases: ["croatia"] },
  { country: "Cyprus", flag: "🇨🇾", aliases: ["cyprus"] },
  { country: "Malta", flag: "🇲🇹", aliases: ["malta"] },
  { country: "Estonia", flag: "🇪🇪", aliases: ["estonia"] },
  { country: "Latvia", flag: "🇱🇻", aliases: ["latvia"] },
  { country: "Lithuania", flag: "🇱🇹", aliases: ["lithuania"] },
  { country: "Liechtenstein", flag: "🇱🇮", aliases: ["liechtenstein"] },
  { country: "Luxembourg", flag: "🇱🇺", aliases: ["luxembourg"] },
  { country: "United Arab Emirates", flag: "🇦🇪", aliases: ["united arab emirates", "uae", "dubai", "abu dhabi"] },
  { country: "Qatar", flag: "🇶🇦", aliases: ["qatar", "doha"] },
  { country: "Saudi Arabia", flag: "🇸🇦", aliases: ["saudi arabia", "riyadh", "jeddah"] },
  { country: "Kuwait", flag: "🇰🇼", aliases: ["kuwait"] },
  { country: "Oman", flag: "🇴🇲", aliases: ["oman"] },
  { country: "Bahrain", flag: "🇧🇭", aliases: ["bahrain"] },
  { country: "Australia", flag: "🇦🇺", aliases: ["australia"] },
  { country: "New Zealand", flag: "🇳🇿", aliases: ["new zealand"] },
  { country: "Japan", flag: "🇯🇵", aliases: ["japan"] },
  { country: "South Korea", flag: "🇰🇷", aliases: ["south korea", "korea"] },
  { country: "Singapore", flag: "🇸🇬", aliases: ["singapore"] },
  { country: "Malaysia", flag: "🇲🇾", aliases: ["malaysia"] },
  { country: "India", flag: "🇮🇳", aliases: ["india"] },
  { country: "China", flag: "🇨🇳", aliases: ["china"] },
  { country: "South Africa", flag: "🇿🇦", aliases: ["south africa"] },
  { country: "Kenya", flag: "🇰🇪", aliases: ["kenya", "nairobi"] },
  { country: "Nigeria", flag: "🇳🇬", aliases: ["nigeria"] },
  { country: "Ghana", flag: "🇬🇭", aliases: ["ghana"] },
  { country: "Morocco", flag: "🇲🇦", aliases: ["morocco"] },
  { country: "Tunisia", flag: "🇹🇳", aliases: ["tunisia"] },
  { country: "Egypt", flag: "🇪🇬", aliases: ["egypt"] },
  { country: "Mauritius", flag: "🇲🇺", aliases: ["mauritius"] },
  { country: "Madagascar", flag: "🇲🇬", aliases: ["madagascar", "antananarivo"] },
  { country: "Guinea", flag: "🇬🇳", aliases: ["guinea"] },
  { country: "Mali", flag: "🇲🇱", aliases: ["mali"] },
  { country: "Burkina Faso", flag: "🇧🇫", aliases: ["burkina faso"] },
  { country: "Tanzania", flag: "🇹🇿", aliases: ["tanzania"] },
  { country: "Zambia", flag: "🇿🇲", aliases: ["zambia"] },
  { country: "Zimbabwe", flag: "🇿🇼", aliases: ["zimbabwe", "harare"] },
  { country: "Côte d'Ivoire", flag: "🇨🇮", aliases: ["côte d'ivoire", "cote d'ivoire", "ivory coast", "abidjan"] },
  { country: "Botswana", flag: "🇧🇼", aliases: ["botswana"] },
  { country: "Namibia", flag: "🇳🇦", aliases: ["namibia"] },
  { country: "Democratic Republic of the Congo", flag: "🇨🇩", aliases: ["democratic republic of the congo", "dr congo", "drc", "congo"] },
  { country: "Mongolia", flag: "🇲🇳", aliases: ["mongolia"] },
  { country: "Kazakhstan", flag: "🇰🇿", aliases: ["kazakhstan"] },
  { country: "Chile", flag: "🇨🇱", aliases: ["chile"] },
  { country: "Peru", flag: "🇵🇪", aliases: ["peru"] },
  { country: "Suriname", flag: "🇸🇷", aliases: ["suriname"] },
  { country: "Panama", flag: "🇵🇦", aliases: ["panama"] },
  { country: "Papua New Guinea", flag: "🇵🇬", aliases: ["papua new guinea"] },
  { country: "Brazil", flag: "🇧🇷", aliases: ["brazil"] },
  { country: "Mexico", flag: "🇲🇽", aliases: ["mexico"] },
  { country: "Argentina", flag: "🇦🇷", aliases: ["argentina"] },
  { country: "Turkey", flag: "🇹🇷", aliases: ["turkey", "türkiye", "turkiye"] },
  { country: "Israel", flag: "🇮🇱", aliases: ["israel"] },
  { country: "Philippines", flag: "🇵🇭", aliases: ["philippines"] },
  { country: "Indonesia", flag: "🇮🇩", aliases: ["indonesia"] },
  { country: "Thailand", flag: "🇹🇭", aliases: ["thailand"] },
  { country: "Vietnam", flag: "🇻🇳", aliases: ["vietnam"] },
  { country: "Romania", flag: "🇷🇴", aliases: ["romania"] },
  { country: "Hungary", flag: "🇭🇺", aliases: ["hungary"] },
  { country: "Greece", flag: "🇬🇷", aliases: ["greece"] },
  { country: "Iceland", flag: "🇮🇸", aliases: ["iceland"] },
];

const WORLDWIDE_WORDS = ["worldwide", "anywhere", "global", "world", "international", "any location", "flexible"];

const KNOWN_COUNTRY_NAMES = new Set(ENTRIES.map((e) => e.country));

/** A resolved country with its flag emoji. Mirrors the Kotlin `Country`. */
export interface Country {
  name: string;
  flag: string;
}

export function isWorldwide(country: Country): boolean {
  return country.name === WORLDWIDE;
}

/**
 * True when the label matched a country the catalog actually knows (Canada, Germany, …).
 * False for `isWorldwide` and for a free-text label that resolved to no known country —
 * which is also the case for a region such as "EMEA", "Europe" or "Africa".
 *
 * This is deliberately stricter than "not worldwide": a region is not a country, and
 * treating one as a country let the eligibility engine's region rules (which do recognise
 * regions) be short-circuited before they could run.
 */
export function isRecognized(country: Country): boolean {
  return KNOWN_COUNTRY_NAMES.has(country.name);
}

/** Matches `needle` only when it is bounded by non-letters, so "us" ≠ "business". */
function containsWord(haystack: string, needle: string): boolean {
  let index = haystack.indexOf(needle);
  while (index >= 0) {
    const before = index === 0 || !isLetter(haystack[index - 1]);
    const afterIndex = index + needle.length;
    const after = afterIndex >= haystack.length || !isLetter(haystack[afterIndex]);
    if (before && after) return true;
    index = haystack.indexOf(needle, index + 1);
  }
  return false;
}

function isLetter(ch: string): boolean {
  return /\p{L}/u.test(ch);
}

/** Every country the catalog knows, sorted for the filter list. */
export const countries: Country[] = ENTRIES.map((e) => ({ name: e.country, flag: e.flag })).sort((a, b) =>
  a.name.localeCompare(b.name),
);

/** True when `name` is a country the catalog knows. */
export function isKnownCountry(name: string): boolean {
  return KNOWN_COUNTRY_NAMES.has(name);
}

/**
 * Resolves a published location to a country. Location strings arrive as "Berlin",
 * "France (Remote)" or "Anywhere in the World", so the longest alias found as a whole word
 * in the text wins.
 */
export function resolveCountry(rawLocation: string | null | undefined): Country {
  const raw = (rawLocation ?? "").trim();
  if (raw.length === 0) return { name: WORLDWIDE, flag: WORLDWIDE_FLAG };
  const normalized = raw.toLowerCase();

  let longest: Entry | null = null;
  let longestAlias = -1;
  for (const entry of ENTRIES) {
    for (const alias of entry.aliases) {
      if (alias.length > longestAlias && containsWord(normalized, alias)) {
        longestAlias = alias.length;
        longest = entry;
      }
    }
  }
  if (longest) return { name: longest.country, flag: longest.flag };

  const worldwide = WORLDWIDE_WORDS.some((word) => normalized.includes(word));
  return worldwide ? { name: WORLDWIDE, flag: WORLDWIDE_FLAG } : { name: raw, flag: UNRESOLVED_FLAG };
}
