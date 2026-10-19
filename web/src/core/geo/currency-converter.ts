import { SalaryPeriod } from "../models";

/**
 * Converts advertised salaries into a single currency so they can be compared across
 * sources. Mirrors the Kotlin `CurrencyConverter`.
 *
 * Rates are a coarse, hand-maintained table: the app only needs the right order of
 * magnitude to sort and to flag "above/below your expectation", never to quote an exact
 * figure. When a rate is unknown the original amount is returned and the caller keeps
 * showing the original currency.
 */

const EUR = "EUR";

/** Units of EUR per one unit of the key currency. */
const TO_EUR: Record<string, number> = {
  EUR: 1.0,
  USD: 0.92,
  CAD: 0.68,
  GBP: 1.17,
  AUD: 0.61,
  NZD: 0.56,
  CHF: 1.05,
  SEK: 0.088,
  NOK: 0.086,
  DKK: 0.134,
  PLN: 0.23,
  CZK: 0.04,
  RON: 0.2,
  HUF: 0.0026,
  AED: 0.25,
  SAR: 0.245,
  QAR: 0.252,
  KWD: 3.0,
  OMR: 2.39,
  BHD: 2.44,
  SGD: 0.69,
  JPY: 0.0061,
  KRW: 0.00068,
  MYR: 0.2,
  ZAR: 0.05,
  KES: 0.0071,
  MAD: 0.092,
  MUR: 0.02,
  INR: 0.011,
  TRY: 0.027,
  ILS: 0.25,
  MXN: 0.048,
  BRL: 0.17,
};

/** True when a rate exists and conversion is meaningful. */
export function canConvert(currency: string): boolean {
  return Object.prototype.hasOwnProperty.call(TO_EUR, currency.toUpperCase());
}

/** Multiplier that turns an amount in `period` into an amount per year. */
function yearlyMultiplier(period: SalaryPeriod): number {
  switch (period) {
    case SalaryPeriod.YEAR:
      return 1.0;
    case SalaryPeriod.MONTH:
      return 12.0;
    case SalaryPeriod.DAY:
      return 220.0;
    case SalaryPeriod.HOUR:
      return 1760.0;
  }
}

/**
 * Converts an amount per `period` into EUR per year. Returns null when the currency is
 * unknown, so the caller can fall back to displaying the raw value.
 */
export function toEurPerYear(amount: number, currency: string, period: SalaryPeriod): number | null {
  const rate = TO_EUR[currency.toUpperCase()];
  if (rate == null) return null;
  return Math.trunc(amount * rate * yearlyMultiplier(period));
}

/** Currency used for every normalised comparison. */
export const baseCurrency = EUR;

/**
 * Where the rates come from and how fresh they are. They are a coarse, hand-maintained
 * table shipped with the app, so the figures are only good enough to sort and to flag
 * "above/below expectation" — never to quote.
 */
export const rateSource = "On-device reference table (approximate)";

/** Epoch millis the table was last reviewed. */
export const rateAsOfEpochMillis = 1717200000000; // 2024-06-01

/** Human date for the rate table, shown next to a converted figure. */
export const rateAsOfLabel = "2024-06";
