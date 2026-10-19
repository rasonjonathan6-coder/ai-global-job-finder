import { describe, expect, it, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";

import { AppStoreProvider } from "../../state/app-store";
import { ShortlistScreen } from "./ShortlistScreen";

/**
 * The shortlist screen reads the live store, so the test seeds the persisted demo flag the
 * same way the app does and lets the fixture feed build a real shortlist. It proves the
 * source footnote (mirroring the Android `SourceFootnote`) renders with the live wording
 * rather than being silently dropped.
 */
describe("ShortlistScreen source footnote", () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.localStorage.setItem(
      "aigjf.store",
      JSON.stringify({ version: 1, demoMode: true }),
    );
  });

  it("shows where the postings came from once the shortlist is built", async () => {
    render(
      <AppStoreProvider>
        <ShortlistScreen onNavigate={() => {}} />
      </AppStoreProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("Expat shortlist")).toBeInTheDocument();
    });
    await waitFor(() => {
      expect(screen.getByText("Live from 1 source.")).toBeInTheDocument();
    });
  });
});
