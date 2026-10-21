import { describe, expect, it, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { AppStoreProvider } from "../../state/app-store";
import { JobAlertsScreen } from "./JobAlertsScreen";

/**
 * The alerts screen reads the live store, so the test seeds the same persisted document the
 * app writes: demo fixtures for the feed plus one saved search. It proves the saved search is
 * evaluated against the real ported `JobAlertEngine` and that a caught posting renders, rather
 * than the list being hand-written.
 */
describe("JobAlertsScreen", () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.localStorage.setItem(
      "aigjf.store",
      JSON.stringify({
        version: 2,
        demoMode: true,
        alertRules: [
          {
            id: "rule-strong",
            name: "Strong matches",
            keywords: [],
            countries: [],
            frequency: "DAILY",
            scope: "ANY_MATCH",
            minMatchScore: 70,
            createdAtEpochMillis: 0,
            enabled: true,
          },
        ],
      }),
    );
  });

  it("evaluates a saved search against the feed and lists the matches", async () => {
    render(
      <AppStoreProvider>
        <JobAlertsScreen onNavigate={() => {}} />
      </AppStoreProvider>,
    );

    expect(screen.getByText("Job alerts")).toBeInTheDocument();
    expect(screen.getByText("Strong matches")).toBeInTheDocument();

    // The demo feed has six postings at or above 70%, so the rule catches all six.
    await waitFor(() => {
      expect(screen.getByText("Maintenance Technician")).toBeInTheDocument();
    });
    expect(screen.getByText("6 caught")).toBeInTheDocument();
    expect(screen.getByText("95% Match")).toBeInTheDocument();
  });

  it("adds a new saved search from the editor", async () => {
    render(
      <AppStoreProvider>
        <JobAlertsScreen onNavigate={() => {}} />
      </AppStoreProvider>,
    );

    await userEvent.click(screen.getByRole("button", { name: "New" }));
    await userEvent.type(screen.getByPlaceholderText(/Welding jobs in Canada/), "Welders in Canada");
    await userEvent.click(screen.getByRole("button", { name: "Save search" }));

    expect(screen.getByText("Welders in Canada")).toBeInTheDocument();
  });

  it("shows the empty state when there are no saved searches", async () => {
    window.localStorage.setItem("aigjf.store", JSON.stringify({ version: 2, demoMode: true }));

    render(
      <AppStoreProvider>
        <JobAlertsScreen onNavigate={() => {}} />
      </AppStoreProvider>,
    );

    expect(
      screen.getByText("No saved searches yet. Add one to be told when a matching posting appears."),
    ).toBeInTheDocument();
    expect(screen.getByText("No alerts yet")).toBeInTheDocument();
  });
});
