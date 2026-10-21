import { describe, expect, it, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { AppStoreProvider } from "../../state/app-store";
import { InterviewScreen } from "./InterviewScreen";

/**
 * The interview coach reads the ported `InterviewQuestionBank`, so the test seeds the demo
 * profile and feed and then drives a real rehearsal: open a question, write an answer, record
 * a confidence and read the graded feedback the engine produced.
 */
describe("InterviewScreen", () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.localStorage.setItem("aigjf.store", JSON.stringify({ version: 2, demoMode: true }));
  });

  it("builds a grounded rehearsal set from the best match when no posting is chosen", async () => {
    render(
      <AppStoreProvider>
        <InterviewScreen jobId="" onBack={() => {}} />
      </AppStoreProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("Rehearsal questions")).toBeInTheDocument();
    });
    // The demo fixture's best match is the Canadian Maintenance Technician posting.
    expect(screen.getByText("Northline Industrial")).toBeInTheDocument();
    expect(screen.getByText(/grounded in your CV/)).toBeInTheDocument();
  });

  it("grades a written answer and advances the readiness score", async () => {
    render(
      <AppStoreProvider>
        <InterviewScreen jobId="demo-canada-maintenance" onBack={() => {}} />
      </AppStoreProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("Rehearsal questions")).toBeInTheDocument();
    });

    // The first question is collapsed until it is opened.
    const firstQuestion = screen.getAllByRole("button", { expanded: false })[0];
    await userEvent.click(firstQuestion);

    const box = await screen.findByPlaceholderText(/Write your answer here/);
    await userEvent.type(
      box,
      "In my last role I ran preventive maintenance on pumps and conveyors, cutting downtime by 20% over six months.",
    );
    await userEvent.click(screen.getByRole("button", { name: "Get feedback" }));

    // The feedback block is produced by the real engine, so a numeric score appears.
    await waitFor(() => {
      expect(screen.getByText(/\d+\/100/)).toBeInTheDocument();
    });
    // Recording the attempt advances the readiness card.
    await waitFor(() => {
      expect(screen.getByText(/1 of \d+ rehearsed/)).toBeInTheDocument();
    });
  });
});
