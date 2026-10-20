import { describe, expect, it, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { AppStoreProvider } from "../../state/app-store";
import { AutoApplyScreen } from "./AutoApplyScreen";

/**
 * Safe auto-apply must prepare drafts and never claim to send. The test seeds the persisted
 * document the app writes (demo feed, auto-apply on) so the real `AutoApplyEngine` builds the
 * queue, then drives the approve flow and asserts the honest wording.
 */
describe("AutoApplyScreen", () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.localStorage.setItem(
      "aigjf.store",
      JSON.stringify({ version: 1, demoMode: true, autoApplyEnabled: true }),
    );
  });

  it("prepares a draft for each strong match above the threshold", async () => {
    render(
      <AppStoreProvider>
        <AutoApplyScreen onNavigate={() => {}} />
      </AppStoreProvider>,
    );

    expect(screen.getByText("Safe auto-apply")).toBeInTheDocument();
    expect(screen.getByText("Drafts only. The app never submits on your behalf.")).toBeInTheDocument();

    // Every demo posting is at or above the default 70% threshold.
    await waitFor(() => {
      expect(screen.getAllByText("Draft ready").length).toBeGreaterThan(0);
    });
    expect(screen.getByText("Maintenance Technician")).toBeInTheDocument();
  });

  it("marks a draft approved without claiming it was sent", async () => {
    render(
      <AppStoreProvider>
        <AutoApplyScreen onNavigate={() => {}} />
      </AppStoreProvider>,
    );

    await waitFor(() => {
      expect(screen.getAllByRole("button", { name: /Review & approve/ }).length).toBeGreaterThan(0);
    });

    await userEvent.click(screen.getAllByRole("button", { name: /Review & approve/ })[0]);

    await waitFor(() => {
      expect(screen.getAllByText("Approved").length).toBeGreaterThan(0);
    });
    expect(screen.getByText("Open posting to send")).toBeInTheDocument();
  });

  it("explains that nothing happens while the mode is off", () => {
    window.localStorage.setItem("aigjf.store", JSON.stringify({ version: 1, demoMode: true }));

    render(
      <AppStoreProvider>
        <AutoApplyScreen onNavigate={() => {}} />
      </AppStoreProvider>,
    );

    expect(screen.getByText("Auto-apply is off")).toBeInTheDocument();
  });
});
