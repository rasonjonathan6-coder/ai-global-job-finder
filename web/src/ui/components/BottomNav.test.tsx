import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { DESTINATIONS } from "../navigation/destinations";
import { BottomNav } from "./BottomNav";

describe("BottomNav", () => {
  it("renders every destination with its visible label and stable a11y name", () => {
    render(<BottomNav current="home" onNavigate={() => {}} />);
    for (const destination of DESTINATIONS) {
      expect(screen.getByText(destination.label)).toBeInTheDocument();
      expect(screen.getByRole("button", { name: destination.contentDescription })).toBeInTheDocument();
    }
  });

  it("marks only the current route as the active page", () => {
    render(<BottomNav current="jobs" onNavigate={() => {}} />);
    const active = screen.getByRole("button", { name: "Jobs" });
    expect(active).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("button", { name: "Home" })).not.toHaveAttribute("aria-current");
  });
});
