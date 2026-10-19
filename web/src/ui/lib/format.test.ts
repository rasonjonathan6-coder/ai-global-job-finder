import { describe, expect, it } from "vitest";
import { SalaryPeriod } from "../../core/models";
import { clampPercent, countryLine, formatSalaryRange, relativeTimeFrom } from "./format";

describe("format helpers", () => {
  it("formats a currency range with its period suffix", () => {
    const text = formatSalaryRange(62000, 74000, "CAD", SalaryPeriod.YEAR, false);
    expect(text).toContain("/year");
    expect(text).toContain("–");
    expect(text).not.toContain("≈");
  });

  it("marks estimated figures with an approximation prefix", () => {
    expect(formatSalaryRange(2400, 0, "USD", SalaryPeriod.MONTH, true)).toMatch(/^≈ /);
  });

  it("falls back to a friendly message when no salary is stated", () => {
    expect(formatSalaryRange(0, 0, "EUR", SalaryPeriod.YEAR, false)).toBe("Salary not stated");
  });

  it("renders relative time in the largest sensible unit", () => {
    const now = Date.UTC(2026, 9, 13);
    expect(relativeTimeFrom(now - 30_000, now)).toBe("Just now");
    expect(relativeTimeFrom(now - 3 * 3_600_000, now)).toBe("3h ago");
    expect(relativeTimeFrom(now - 5 * 86_400_000, now)).toBe("5d ago");
    expect(relativeTimeFrom(null, now)).toBe("Date unknown");
  });

  it("clamps percentages into range", () => {
    expect(clampPercent(104.6)).toBe(100);
    expect(clampPercent(-3)).toBe(0);
    expect(clampPercent(Number.NaN)).toBe(0);
  });

  it("joins city and country, collapsing a repeated country", () => {
    expect(countryLine("Canada", "Calgary")).toBe("Calgary, Canada");
    expect(countryLine("Germany", "Germany")).toBe("Germany");
    expect(countryLine("United States", "USA")).toBe("United States");
    expect(countryLine("Worldwide", "")).toBe("Worldwide");
    expect(countryLine("", "")).toBe("Location not stated");
  });
});
