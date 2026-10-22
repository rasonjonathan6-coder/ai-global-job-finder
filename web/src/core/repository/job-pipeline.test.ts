import { describe, expect, it } from "vitest";
import { JobPipeline } from "./job-pipeline";
import { emptyCandidateProfile } from "../models";
import { ProviderKind, rawJob, type RawJob } from "../jobs/provider/raw-job";
import { defaultProviderCapabilities } from "../jobs/search/search-strategy";
import type { JobProvider } from "../jobs/provider/providers";

/**
 * The fetch-orchestration bounds added after the provider audit, mirrored from the Android
 * `LiveJobRepository`. A provider may spend at most a fixed total across all of its keyword
 * fetches, and a provider that re-walks its whole site list on every call declares a small
 * keyword sweep budget so the plan cannot multiply its request volume.
 */
function slowProvider(options: {
  id: string;
  sweepBudget?: number;
  /** Returns rows immediately; the first `fastCalls` calls resolve, later ones hang. */
  fastCalls?: number;
  /** Number of rows returned per resolving call. */
  rows?: number;
}): JobProvider & { calls: number } {
  const provider: JobProvider & { calls: number } = {
    id: options.id,
    name: options.id,
    homepage: "",
    kind: ProviderKind.EMPLOYER_BOARD,
    documentationUrl: "",
    capabilities: defaultProviderCapabilities({
      serverSideKeyword: true,
      ...(options.sweepBudget === undefined ? {} : { keywordSweepBudget: options.sweepBudget }),
    }),
    calls: 0,
    async fetch(): Promise<RawJob[]> {
      provider.calls += 1;
      if (options.fastCalls !== undefined && provider.calls > options.fastCalls) {
        return new Promise<RawJob[]>(() => {});
      }
      const rows = options.rows ?? 1;
      return Array.from({ length: rows }, (_, i) =>
        rawJob({
          providerId: options.id,
          providerName: options.id,
          externalId: `${options.id}-${provider.calls}-${i}`,
          title: "Maintenance Technician",
          company: `Northline ${provider.calls}`,
          location: `City ${provider.calls}`,
          descriptionHtml: "Maintain and repair plant equipment.",
          url: `https://example.test/${options.id}/${provider.calls}/${i}`,
        }),
      );
    },
  };
  return provider;
}

describe("job pipeline provider budgets", () => {
  it("cuts a source off at its total budget and keeps the rows it already returned", async () => {
    // The first keyword answers; the second hangs. The budget must stop the walk and the first
    // keyword's row must survive, with the source not reported as a total failure.
    const provider = slowProvider({ id: "partial", fastCalls: 1, rows: 1 });
    const pipeline = new JobPipeline({
      providers: [provider],
      perProviderTotalTimeoutMillis: 40,
    });

    const result = await pipeline.refresh(emptyCandidateProfile());

    expect(provider.calls).toBe(2);
    const report = result.reports.find((r) => r.providerId === "partial")!;
    expect(report.ok).toBe(true);
    expect(report.fetched).toBe(1);
    expect(report.accepted).toBe(1);
  });

  it("reports a source that never answers as failed after a single attempt", async () => {
    const provider = slowProvider({ id: "hang", fastCalls: 0 });
    const pipeline = new JobPipeline({
      providers: [provider],
      perProviderTotalTimeoutMillis: 40,
    });

    const result = await pipeline.refresh(emptyCandidateProfile());

    expect(provider.calls).toBe(1);
    const report = result.reports.find((r) => r.providerId === "hang")!;
    expect(report.ok).toBe(false);
  });

  it("one hanging source does not stop a fast one from receiving every keyword set", async () => {
    const hanging = slowProvider({ id: "hang", fastCalls: 0 });
    const fast = slowProvider({ id: "fast", fastCalls: Number.MAX_SAFE_INTEGER });
    const pipeline = new JobPipeline({
      providers: [hanging, fast],
      perProviderTotalTimeoutMillis: 40,
    });

    const result = await pipeline.refresh(emptyCandidateProfile());

    expect(fast.calls).toBe(6);
    const fastReport = result.reports.find((r) => r.providerId === "fast")!;
    expect(fastReport.ok).toBe(true);
    expect(fastReport.accepted).toBe(6);
  });

  it("honours a declared keyword sweep budget", async () => {
    const provider = slowProvider({ id: "sr-like", sweepBudget: 2, fastCalls: Number.MAX_SAFE_INTEGER });
    const pipeline = new JobPipeline({ providers: [provider] });

    await pipeline.refresh(emptyCandidateProfile());

    expect(provider.calls).toBe(2);
  });

  it("gives an uncapped provider the whole keyword plan", async () => {
    const provider = slowProvider({ id: "eures-like", fastCalls: Number.MAX_SAFE_INTEGER });
    const pipeline = new JobPipeline({ providers: [provider] });

    await pipeline.refresh(emptyCandidateProfile());

    expect(provider.calls).toBe(6);
  });
});
