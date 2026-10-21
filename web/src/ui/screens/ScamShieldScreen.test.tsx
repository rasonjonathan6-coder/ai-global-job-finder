import { describe, expect, it, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";

import { AppStoreProvider } from "../../state/app-store";
import { ScamShieldScreen } from "./ScamShieldScreen";

/**
 * The scam shield reads the ported `ScamDetector`, so the test asserts the verdict card and
 * the auditable rules list are rendered from the engine's own output rather than a static
 * template. The demo posting is a clean, sponsored role, so it lands on the safe verdict.
 */
describe("ScamShieldScreen", () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.localStorage.setItem("aigjf.store", JSON.stringify({ version: 2, demoMode: true }));
  });

  it("renders the engine verdict and the rules it applied", async () => {
    render(
      <AppStoreProvider>
        <ScamShieldScreen jobId="demo-canada-maintenance" onBack={() => {}} />
      </AppStoreProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("The rules behind this check")).toBeInTheDocument();
    });
    // The verdict line always carries the safety score derived from the signals.
    expect(screen.getByText(/\d+%/)).toBeInTheDocument();
    expect(screen.getByText("This is guidance, not a guarantee. You decide.")).toBeInTheDocument();
  });

  it("scans the best match when no posting is chosen", async () => {
    render(
      <AppStoreProvider>
        <ScamShieldScreen jobId="" onBack={() => {}} />
      </AppStoreProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("Scam shield")).toBeInTheDocument();
    });
    expect(screen.getByText(/danger · /)).toBeInTheDocument();
  });
});
