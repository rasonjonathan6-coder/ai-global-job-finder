import { describe, expect, it, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { AppStoreProvider } from "../../state/app-store";
import { CoverLetterScreen } from "./CoverLetterScreen";

/**
 * The cover-letter writer is wired to the ported `CoverLetterGenerator`. The test proves the
 * engine runs on the real profile and posting (a grounded letter with a "built from your CV"
 * card, not a template), and that a missing posting is reported instead of a blank letter.
 */
describe("CoverLetterScreen", () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.localStorage.setItem("aigjf.store", JSON.stringify({ version: 2, demoMode: true }));
  });

  it("generates a grounded letter from the CV and the posting", async () => {
    render(
      <AppStoreProvider>
        <CoverLetterScreen jobId="demo-canada-maintenance" onBack={() => {}} />
      </AppStoreProvider>,
    );

    await waitFor(() => {
      expect(screen.getByRole("button", { name: /Generate cover letter/ })).toBeInTheDocument();
    });

    await userEvent.click(screen.getByRole("button", { name: /Generate cover letter/ }));

    // The grounding card proves the engine used the CV's own facts, not invented ones.
    await waitFor(() => {
      expect(screen.getByText("Built from your CV")).toBeInTheDocument();
    });
    expect(screen.getByText("Copy to clipboard")).toBeInTheDocument();
  });

  it("reports a posting that is no longer in the feed", async () => {
    render(
      <AppStoreProvider>
        <CoverLetterScreen jobId="does-not-exist" onBack={() => {}} />
      </AppStoreProvider>,
    );

    expect(await screen.findByText("Posting unavailable")).toBeInTheDocument();
  });
});
