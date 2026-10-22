import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import {
  AvailabilitySignalType,
  AvailabilityStatus,
  UNKNOWN_AVAILABILITY,
  type AvailabilityResult,
} from "../../core/models";
import { AvailabilityBadge, AvailabilityDetail, availabilityBadgeLabel } from "./AvailabilityBadge";

function availability(overrides: Partial<AvailabilityResult>): AvailabilityResult {
  return { ...UNKNOWN_AVAILABILITY, lastCheckedAtEpochMillis: 1_720_000_000_000, ...overrides };
}

describe("AvailabilityBadge", () => {
  it("words a settled verdict exactly as the Kotlin badge does", () => {
    expect(
      availabilityBadgeLabel(
        availability({
          status: AvailabilityStatus.OPEN,
          evidence: [
            {
              type: AvailabilitySignalType.SOURCE_FLAG,
              detail: "still accepting",
              sourceUrl: "https://example.test/job",
            },
          ],
        }),
      ),
    ).toBe("Open · verified by source");
    expect(availabilityBadgeLabel(availability({ status: AvailabilityStatus.EXPIRED }))).toBe("Expired");
    expect(availabilityBadgeLabel(availability({ status: AvailabilityStatus.UNCONFIRMED }))).toBe(
      "Availability unconfirmed",
    );
  });

  it("renders an unconfirmed read neutrally, never as a rejection", () => {
    render(<AvailabilityBadge availability={UNKNOWN_AVAILABILITY} />);
    const chip = screen.getByText("Availability unconfirmed");
    expect(chip).toBeInTheDocument();
    // The neutral tone is the surface-variant background, not the danger/warning container.
    expect(chip.className).toContain("bg-surface-variant");
    expect(chip.className).not.toContain("danger");
  });

  it("states why a verdict was reached, with the checked-at stamp", () => {
    render(
      <AvailabilityDetail
        availability={availability({
          status: AvailabilityStatus.EXPIRED,
          evidence: [
            {
              type: AvailabilitySignalType.EMPLOYER_DEADLINE,
              detail: "The employer stated applications close 1 Jul 2024, which has passed.",
              sourceUrl: "https://example.test/job",
            },
          ],
        })}
      />,
    );
    expect(screen.getByText("Expired — confirmed closed")).toBeInTheDocument();
    expect(
      screen.getByText("The employer stated applications close 1 Jul 2024, which has passed."),
    ).toBeInTheDocument();
  });

  it("says the app has not confirmed an unproven read rather than implying closure", () => {
    render(<AvailabilityDetail availability={UNKNOWN_AVAILABILITY} />);
    expect(screen.getByText("Availability not confirmed")).toBeInTheDocument();
    expect(
      screen.getByText(
        "This app has not confirmed whether the vacancy is still open. Open the posting to check.",
      ),
    ).toBeInTheDocument();
  });
});
