import { describe, expect, it, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { AppStoreProvider } from "../../state/app-store";
import { ThemeProvider } from "../theme/ThemeProvider";
import { AiScreen } from "./AiScreen";

/**
 * The AI screen must describe the app's capabilities honestly. This proves the transparency
 * card is wired to the ported `AI_CAPABILITY_CATALOG` (the same catalogue the Kotlin screen
 * reads), rather than to a hand-written list that could drift from the engines.
 */
describe("AiScreen AI transparency", () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.localStorage.setItem("aigjf.store", JSON.stringify({ version: 2, demoMode: true }));
  });

  it("summarises the catalogue and expands to the full, labelled breakdown", async () => {
    render(
      <AppStoreProvider>
        <ThemeProvider>
          <AiScreen onNavigate={() => {}} />
        </ThemeProvider>
      </AppStoreProvider>,
    );

    expect(screen.getByText("How this app's AI works")).toBeInTheDocument();
    expect(screen.getByText(/Rules and parsers on-device/)).toBeInTheDocument();

    const toggle = screen.getByRole("button", { name: /See the full breakdown/ });
    expect(toggle).toHaveAttribute("aria-expanded", "false");

    await userEvent.click(toggle);

    // Every catalogue entry renders with its kind label and processing location.
    expect(screen.getByText("CV reading")).toBeInTheDocument();
    expect(screen.getByText("AI drafting assistant")).toBeInTheDocument();
    expect(screen.getAllByText("Sent to NVIDIA").length).toBeGreaterThan(0);
    expect(screen.getAllByText("On this device").length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: /Hide the full breakdown/ })).toBeInTheDocument();
  });

  it("states that applications are prepared but never sent automatically", () => {
    render(
      <AppStoreProvider>
        <ThemeProvider>
          <AiScreen onNavigate={() => {}} />
        </ThemeProvider>
      </AppStoreProvider>,
    );

    // The claim is driven by `SubmissionCapability`, not a hand-written sentence.
    expect(screen.getByText("Prepared, never sent for you")).toBeInTheDocument();
    expect(screen.getByText(/nothing is ever submitted automatically/i)).toBeInTheDocument();
    expect(screen.getByText(/every application stops at your approval/i)).toBeInTheDocument();
  });
});
