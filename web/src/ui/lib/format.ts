import { isRecognized, resolveCountry } from "../../core/geo/country-catalog";
import { SalaryPeriod } from "../../core/models";

/** "€1,200" / "CAD 3,400" style currency formatting that never throws on bad input. */
export function formatCurrency(amount: number, currencyCode: string): string {
  if (!Number.isFinite(amount) || amount <= 0) return "Salary not stated";
  const code = currencyCode.trim().toUpperCase() || "EUR";
  try {
    return new Intl.NumberFormat("en-GB", {
      style: "currency",
      currency: code,
      maximumFractionDigits: 0,
    }).format(amount);
  } catch {
    return `${code} ${Math.round(amount).toLocaleString("en-GB")}`;
  }
}

const PERIOD_SUFFIX: Record<SalaryPeriod, string> = {
  [SalaryPeriod.YEAR]: "/year",
  [SalaryPeriod.MONTH]: "/month",
  [SalaryPeriod.DAY]: "/day",
  [SalaryPeriod.HOUR]: "/hour",
};

export function salaryPeriodSuffix(period: SalaryPeriod): string {
  return PERIOD_SUFFIX[period] ?? "";
}

/** "€1,200 – €1,800/month", collapsing to a single figure when only a minimum is known. */
export function formatSalaryRange(
  min: number,
  max: number,
  currencyCode: string,
  period: SalaryPeriod,
  estimated: boolean,
): string {
  if (min <= 0) return "Salary not stated";
  const suffix = salaryPeriodSuffix(period);
  const prefix = estimated ? "≈ " : "";
  if (max > 0 && max !== min) {
    return `${prefix}${formatCurrency(min, currencyCode)} – ${formatCurrency(max, currencyCode)}${suffix}`;
  }
  return `${prefix}${formatCurrency(min, currencyCode)}${suffix}`;
}

/** Compact "3d ago" / "5h ago" relative time used on job cards. */
export function relativeTimeFrom(epochMillis: number | null, now: number = Date.now()): string {
  if (!epochMillis) return "Date unknown";
  const diff = Math.max(0, now - epochMillis);
  const minutes = Math.floor(diff / 60_000);
  if (minutes < 1) return "Just now";
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}d ago`;
  const months = Math.floor(days / 30);
  if (months < 12) return `${months}mo ago`;
  return `${Math.floor(months / 12)}y ago`;
}

/** "CAD" → "🇨🇦" is out of scope; we rely on the posting's own flag instead. */
export function countryLine(country: string, city: string): string {
  const c = country.trim();
  const town = city.trim();
  // Some feeds repeat the country as the city ("Germany, Germany") or name it in both the
  // city and country parts ("USA" + "United States"). Showing both reads as a data error,
  // so a city that resolves to the same country as the country part is dropped.
  const sameCountry =
    town.length > 0 &&
    (town.toLowerCase() === c.toLowerCase() ||
      (isRecognized(resolveCountry(town)) && resolveCountry(town).name === resolveCountry(c).name));
  const parts = (sameCountry ? [c] : [town, c]).filter(Boolean);
  return parts.length ? parts.join(", ") : "Location not stated";
}

export function clampPercent(value: number): number {
  if (!Number.isFinite(value)) return 0;
  return Math.max(0, Math.min(100, Math.round(value)));
}
