import { SalaryPeriod } from "../../models";

/**
 * Parses the ISO-8601 timestamps the boards publish into epoch millis. Mirrors the Kotlin
 * `IsoDates`. Accepts a full offset datetime, a bare local datetime (treated as UTC) and a
 * plain date; anything else yields null rather than a guessed time.
 */
export function isoEpochMillis(text: string | null | undefined): number | null {
  if (!text || text.trim().length === 0) return null;
  const raw = text.trim();

  const offset = Date.parse(raw);
  if (!Number.isNaN(offset)) return offset;

  // A bare "2024-06-01T09:30:00" is parsed as local time by Date.parse, which would shift the
  // instant by the host's offset. The Kotlin side pins it to UTC, so do the same here.
  const localDateTime = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?$/.exec(raw);
  if (localDateTime) {
    return Date.UTC(
      Number(localDateTime[1]),
      Number(localDateTime[2]) - 1,
      Number(localDateTime[3]),
      Number(localDateTime[4]),
      Number(localDateTime[5]),
      Number(localDateTime[6] ?? "0"),
    );
  }

  const dateOnly = /^(\d{4})-(\d{2})-(\d{2})$/.exec(raw);
  if (dateOnly) {
    return Date.UTC(Number(dateOnly[1]), Number(dateOnly[2]) - 1, Number(dateOnly[3]));
  }

  return null;
}

const BLOCK_TAG = /<\s*(br|\/p|\/div|\/li|\/h[1-6]|\/tr)\s*\/?>/gi;
const TAG = /<[^>]+>/g;
const ENTITY = /&(#x?[0-9a-fA-F]+|[a-zA-Z]+);/g;

function decodeNumeric(name: string): string {
  const code = name.toLowerCase().startsWith("#x")
    ? Number.parseInt(name.slice(2), 16)
    : Number.parseInt(name.slice(1), 10);
  if (!Number.isFinite(code)) return "";
  try {
    return String.fromCodePoint(code);
  } catch {
    return "";
  }
}

function decodeEntities(input: string): string {
  return input.replace(ENTITY, (match, name: string) => {
    switch (name) {
      case "amp":
        return "&";
      case "lt":
        return "<";
      case "gt":
        return ">";
      case "quot":
        return '"';
      case "apos":
      case "#39":
        return "'";
      case "nbsp":
        return " ";
      case "ndash":
        return "–";
      case "mdash":
        return "—";
      case "hellip":
        return "…";
      case "rsquo":
      case "lsquo":
        return "'";
      case "ldquo":
      case "rdquo":
        return '"';
      default:
        return name.startsWith("#") ? decodeNumeric(name) : match;
    }
  });
}

/** Turns the HTML descriptions the boards publish into readable plain text. */
export const HtmlText = {
  /** Block-level tags become newlines so lists and paragraphs survive as structure. */
  toPlainText(html: string | null | undefined): string {
    if (!html || html.trim().length === 0) return "";
    const withBreaks = html.replace(BLOCK_TAG, "\n");
    const stripped = withBreaks.replace(TAG, " ");
    const decoded = decodeEntities(stripped);
    return decoded
      .split("\n")
      .map((line) => line.replace(/[ \t\u00a0]+/g, " ").trim())
      .filter((line) => line.length > 0)
      .join("\n");
  },

  /** First `limit` characters of plain text, cut on a word boundary. */
  excerpt(html: string | null | undefined, limit = 240): string {
    const text = this.toPlainText(html).replace(/\n/g, " ").trim();
    if (text.length <= limit) return text;
    const cut = text.slice(0, limit);
    const lastSpace = cut.lastIndexOf(" ");
    return `${(lastSpace > limit / 2 ? cut.slice(0, lastSpace) : cut).trimEnd()}…`;
  },
};

export interface ParsedSalary {
  min: number | null;
  max: number | null;
  currency: string | null;
  period: SalaryPeriod | null;
  /** The source stated no unit; `period` was inferred from the amount. */
  periodInferred: boolean;
  /** The stated unit is impossible for the amount, so the figure is not trustworthy. */
  unitUncertain: boolean;
}

const CURRENCY_SYMBOLS: Array<[string, string]> = [
  ["$", "USD"],
  ["€", "EUR"],
  ["£", "GBP"],
  ["¥", "JPY"],
  ["₦", "NGN"],
  ["₹", "INR"],
  ["R", "ZAR"],
];

const CODE_PATTERN =
  /\b(USD|EUR|GBP|CAD|AUD|NZD|CHF|SEK|NOK|DKK|PLN|CZK|AED|SAR|QAR|SGD|JPY|KRW|ZAR|KES|MAD|MUR|INR|TRY|ILS|MXN|BRL|RON|HUF|NGN)\b/i;
const AMOUNT_PATTERN = /(\d[\d\s.,]*\d|\d)\s*([kK])?/g;

function toAmount(token: string): number | null {
  const cleaned = token.trim();
  const hasK = /k$/i.test(cleaned);
  const digits = cleaned.replace(/[^0-9.,]/g, "");
  if (digits.length === 0) return null;
  const normalized = normalizeSeparators(digits);
  const value = Number.parseFloat(normalized);
  if (!Number.isFinite(value)) return null;
  return Math.trunc(hasK ? value * 1000 : value);
}

/**
 * Handles both "58,000" (comma thousands) and "35,3" (comma decimal). A separator followed
 * by exactly three digits is treated as a thousands separator.
 */
function normalizeSeparators(digits: string): string {
  const hasComma = digits.includes(",");
  const hasDot = digits.includes(".");
  if (hasComma && hasDot) return digits.replace(/,/g, "");
  if (hasComma) {
    const parts = digits.split(",");
    return parts.length === 2 && parts[1].length === 3 ? digits.replace(/,/g, "") : digits.replace(",", ".");
  }
  return digits;
}

/** Falls back to a sensible period so a bare number is not read as hourly. */
function inferPeriod(amount: number | null): SalaryPeriod | null {
  if (amount == null) return null;
  if (amount >= 12_000) return SalaryPeriod.YEAR;
  if (amount >= 1_500) return SalaryPeriod.MONTH;
  if (amount >= 60) return SalaryPeriod.DAY;
  return SalaryPeriod.HOUR;
}

/** Whether an amount is believable for the unit the source gave (wide bounds by design). */
function isPlausible(period: SalaryPeriod, max: number): boolean {
  switch (period) {
    case SalaryPeriod.HOUR:
      return max >= 1 && max <= 5_000;
    case SalaryPeriod.DAY:
      return max >= 1 && max <= 50_000;
    case SalaryPeriod.MONTH:
      return max >= 1 && max <= 5_000_000;
    case SalaryPeriod.YEAR:
      return max >= 1_000;
  }
}

/**
 * Best-effort salary parsing. Boards publish salaries as free text ("$35,3k- $52k",
 * "€55,000 - €70,000 a year", "From $60,000 per year"). Every field is optional: when the
 * shape is not recognised, null is returned and the card simply omits salary.
 */
export const SalaryParser = {
  parse(raw: string | null | undefined): ParsedSalary {
    const empty: ParsedSalary = {
      min: null,
      max: null,
      currency: null,
      period: null,
      periodInferred: false,
      unitUncertain: false,
    };
    if (!raw || raw.trim().length === 0) return empty;
    const text = raw.trim();

    const codeMatch = CODE_PATTERN.exec(text);
    const symbol = CURRENCY_SYMBOLS.find(([s]) => text.includes(s));
    const currency = (codeMatch?.[1]?.toUpperCase() ?? symbol?.[1]) ?? null;

    const statedPeriod = /per\s*year|\/\s*year|annually|annual|a\s*year|yearly|\/yr/i.test(text)
      ? SalaryPeriod.YEAR
      : /per\s*month|\/\s*month|monthly|a\s*month|\/mo/i.test(text)
        ? SalaryPeriod.MONTH
        : /per\s*day|\/\s*day|daily|\/day/i.test(text)
          ? SalaryPeriod.DAY
          : /per\s*hour|\/\s*hour|hourly|\/hr/i.test(text)
            ? SalaryPeriod.HOUR
            : null;

    // Amounts below the floor are non-amounts for the periods where such a figure is
    // meaningless. Hourly and daily pay legitimately starts low, so they keep a 1-unit floor.
    const floor = statedPeriod === SalaryPeriod.HOUR || statedPeriod === SalaryPeriod.DAY ? 1 : 100;
    const amounts = Array.from(text.matchAll(AMOUNT_PATTERN))
      .map((m) => toAmount(m[0]))
      .filter((n): n is number => n != null && n >= floor && n <= 10_000_000);

    if (amounts.length === 0) return { ...empty, currency, period: statedPeriod };

    const min = Math.min(...amounts);
    const max = Math.max(...amounts);
    const period = statedPeriod ?? inferPeriod(min);

    const uncertain = statedPeriod != null && !isPlausible(statedPeriod, max);

    return {
      min,
      max: amounts.length > 1 && max !== min ? max : min,
      currency,
      period: uncertain ? null : period,
      periodInferred: statedPeriod == null && period != null,
      unitUncertain: uncertain,
    };
  },
};
