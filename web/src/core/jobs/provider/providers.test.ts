import { describe, expect, it } from "vitest";

import { SalaryPeriod } from "../../models";
import {
  ArbeitnowProvider,
  HimalayasProvider,
  JobicyProvider,
  RemotiveProvider,
  SmartRecruitersProvider,
  providerFailureLine,
} from "./providers";
import { JobProviderError, ProviderErrorCategory, classifyProviderError, toStringList, type JsonFeed } from "./feed";

/** A feed stub: returns canned bodies per URL, so provider translation runs with no network. */
function stubFeed(bodies: Record<string, unknown>): JsonFeed {
  return {
    async objectAt(url) {
      const match = Object.keys(bodies).find((key) => url.includes(key));
      if (!match) throw new Error(`no canned body for ${url}`);
      return bodies[match] as Record<string, unknown>;
    },
    async arrayAt(url) {
      return (bodies[url] as unknown[]) ?? [];
    },
  };
}

describe("RemotiveProvider", () => {
  const feed = stubFeed({
    "api/remote-jobs": {
      jobs: [
        {
          id: "2091158",
          url: "https://remotive.com/remote-jobs/x/2091158",
          title: "Software Engineer",
          company_name: "Askable",
          category: "Software Development",
          tags: ["C++", "python", ""],
          job_type: "contract",
          publication_date: "2026-10-17T16:04:29",
          candidate_required_location: "Worldwide",
          salary: "$1000 per task",
          description: "<p>Build things</p>",
        },
      ],
    },
  });

  it("translates a Remotive item field for field", async () => {
    const [job] = await new RemotiveProvider(feed).fetch([], 5);
    expect(job.providerId).toBe("remotive");
    expect(job.providerName).toBe("Remotive");
    expect(job.externalId).toBe("2091158");
    expect(job.title).toBe("Software Engineer");
    expect(job.company).toBe("Askable");
    expect(job.location).toBe("Worldwide");
    expect(job.url).toBe("https://remotive.com/remote-jobs/x/2091158");
    expect(job.tags).toEqual(["C++", "python"]);
    expect(job.employmentType).toBe("contract");
    expect(job.isRemote).toBe(true);
    expect(job.postedAtEpochMillis).toBe(Date.parse("2026-10-17T16:04:29"));
  });

  it("defaults the location to Worldwide when the source omits it", async () => {
    const blank = stubFeed({ "api/remote-jobs": { jobs: [{ id: "1", title: "T", url: "u" }] } });
    const [job] = await new RemotiveProvider(blank).fetch();
    expect(job.location).toBe("Worldwide");
  });

  it("sends the search term only when one is given", async () => {
    let seen = "";
    const spy: JsonFeed = {
      async objectAt(url) {
        seen = url;
        return { jobs: [] };
      },
      async arrayAt() {
        return [];
      },
    };
    await new RemotiveProvider(spy).fetch(["welder"], 10);
    expect(seen).toContain("search=welder");
    await new RemotiveProvider(spy).fetch([], 10);
    expect(seen).not.toContain("search=");
  });
});

describe("ArbeitnowProvider", () => {
  const feed = stubFeed({
    "job-board-api": {
      data: [
        {
          slug: "expansion-manager-berlin-227543",
          company_name: "Pflegia",
          title: "Expansion Customer Success Manager",
          description: "<p>Pflegia ist eine Plattform.</p>",
          remote: "False",
          url: "https://www.arbeitnow.com/jobs/companies/pflegia/227543",
          tags: ["Customer Service"],
          job_types: ["Unbefristeter arbeitsvertrag"],
          location: "Berlin",
          created_at: "1792396850",
        },
      ],
    },
  });

  it("merges tags and job_types and reads the epoch-seconds date", async () => {
    const [job] = await new ArbeitnowProvider(feed).fetch();
    expect(job.externalId).toBe("expansion-manager-berlin-227543");
    expect(job.location).toBe("Berlin");
    expect(job.tags).toEqual(["Customer Service", "Unbefristeter arbeitsvertrag"]);
    expect(job.employmentType).toBe("Unbefristeter arbeitsvertrag");
    expect(job.postedAtEpochMillis).toBe(1_792_396_850_000);
    expect(job.isRemote).toBe(false);
  });

  it("falls back to a Remote location for a remote posting with no location", async () => {
    const remote = stubFeed({
      "job-board-api": { data: [{ slug: "s", title: "T", url: "u", remote: "true" }] },
    });
    const [job] = await new ArbeitnowProvider(remote).fetch();
    expect(job.location).toBe("Remote");
    expect(job.isRemote).toBe(true);
  });

  it("pools several pages so trade roles sitting past the first page are found", async () => {
    const seen: string[] = [];
    const paged: JsonFeed = {
      async objectAt(url) {
        seen.push(url);
        const page = Number(new URL(url).searchParams.get("page"));
        if (page === 1) {
          return { data: [{ slug: "p1", title: "Software Engineer", url: "u1" }] };
        }
        if (page === 2) {
          return { data: [{ slug: "p2", title: "Maintenance Technician", url: "u2" }] };
        }
        return { data: [] };
      },
      async arrayAt() {
        return [];
      },
    };
    const jobs = await new ArbeitnowProvider(paged).fetch([], 60);
    expect(jobs.map((j) => j.title)).toEqual(["Software Engineer", "Maintenance Technician"]);
    expect(seen).toEqual([
      "https://www.arbeitnow.com/api/job-board-api?page=1",
      "https://www.arbeitnow.com/api/job-board-api?page=2",
      "https://www.arbeitnow.com/api/job-board-api?page=3",
    ]);
  });

  it("keeps the pages it already read when a later page fails", async () => {
    const flaky: JsonFeed = {
      async objectAt(url) {
        const page = Number(new URL(url).searchParams.get("page"));
        if (page === 1) return { data: [{ slug: "p1", title: "Fitter", url: "u1" }] };
        throw new Error("rate limited");
      },
      async arrayAt() {
        return [];
      },
    };
    const jobs = await new ArbeitnowProvider(flaky).fetch();
    expect(jobs.map((j) => j.title)).toEqual(["Fitter"]);
  });
});

describe("SmartRecruitersProvider", () => {
  const board = { identifier: "SGS", label: "SGS" };
  const listRow = (id: string, name: string) => ({ id, name });
  const detail = (id: string, name: string, location = "Perth, Australia") => ({
    id,
    name,
    active: true,
    releasedDate: "2026-09-01T00:00:00.000Z",
    typeOfEmployment: { label: "Full-time" },
    company: { name: "SGS" },
    location: { fullLocation: location, remote: false },
    postingUrl: `https://jobs.smartrecruiters.com/SGS/${id}`,
    jobAd: {
      sections: {
        jobDescription: { text: "<p>Maintain pumps and rotating equipment.</p>" },
        qualifications: { text: "<p>5 years experience.</p>" },
      },
    },
  });

  it("reads the list, then the detail, and joins the description sections", async () => {
    const feed: JsonFeed = {
      async objectAt(url) {
        if (url.includes("/postings?")) return { content: [listRow("744", "Maintenance Technician")] };
        if (url.endsWith("/postings/744")) return detail("744", "Maintenance Technician");
        throw new Error(`unexpected ${url}`);
      },
      async arrayAt() {
        return [];
      },
    };
    const [job] = await new SmartRecruitersProvider(feed, [board]).fetch([], 10);
    expect(job.providerId).toBe("smartrecruiters");
    expect(job.externalId).toBe("SGS-744");
    expect(job.title).toBe("Maintenance Technician");
    expect(job.company).toBe("SGS");
    expect(job.location).toBe("Perth, Australia");
    expect(job.descriptionHtml).toContain("Maintain pumps");
    expect(job.descriptionHtml).toContain("5 years experience");
    expect(job.employmentType).toBe("Full-time");
    expect(job.url).toBe("https://jobs.smartrecruiters.com/SGS/744");
  });

  it("sends the keyword as the server-side q parameter", async () => {
    let listUrl = "";
    const feed: JsonFeed = {
      async objectAt(url) {
        if (url.includes("/postings?")) {
          listUrl = url;
          return { content: [] };
        }
        throw new Error(`unexpected ${url}`);
      },
      async arrayAt() {
        return [];
      },
    };
    await new SmartRecruitersProvider(feed, [board]).fetch(["pump fitter"], 10);
    expect(listUrl).toContain("q=pump%20fitter");
  });

  it("reads trade-titled rows before others within the detail budget", async () => {
    const asked: string[] = [];
    const rows = [
      listRow("1", "Marketing Intern"),
      listRow("2", "Maintenance Technician"),
      listRow("3", "Sales Executive"),
      listRow("4", "Millwright"),
      listRow("5", "Software Engineer"),
    ];
    const feed: JsonFeed = {
      async objectAt(url) {
        if (url.includes("/postings?")) return { content: rows };
        const id = url.split("/postings/")[1];
        asked.push(id);
        return detail(id, "x");
      },
      async arrayAt() {
        return [];
      },
    };
    await new SmartRecruitersProvider(feed, [board]).fetch([], 10);
    // Budget is four detail reads; the two trade titles must be inside it.
    expect(asked.length).toBe(4);
    expect(asked.slice(0, 2)).toEqual(["2", "4"]);
  });

  it("skips a detail row that fails without dropping the rest", async () => {
    const feed: JsonFeed = {
      async objectAt(url) {
        if (url.includes("/postings?")) {
          return { content: [listRow("1", "Fitter"), listRow("2", "Millwright")] };
        }
        if (url.endsWith("/postings/1")) throw new Error("gone");
        return detail("2", "Millwright");
      },
      async arrayAt() {
        return [];
      },
    };
    const jobs = await new SmartRecruitersProvider(feed, [board]).fetch([], 10);
    expect(jobs.map((j) => j.externalId)).toEqual(["SGS-2"]);
  });
});

describe("JobicyProvider", () => {
  const feed = stubFeed({
    "v2/remote-jobs": {
      jobs: [
        {
          id: "153612",
          jobSlug: "153612-conceptual-copywriter",
          url: "https://jobicy.com/jobs/153612-conceptual-copywriter",
          jobTitle: "Conceptual Copywriter",
          companyName: "Superside",
          jobIndustry: ["Content & Editorial"],
          jobType: ["Contract"],
          jobGeo: "Anywhere",
          jobExcerpt: "Superside is looking for a copywriter.",
          jobDescription: "<p>Full description</p>",
          pubDate: "2026-10-19T02:50:22+00:00",
          salaryMin: 40000,
          salaryMax: 55000,
          salaryCurrency: "USD",
          salaryPeriod: "yearly",
        },
      ],
    },
  });

  it("translates structured salary fields and the geo location", async () => {
    const [job] = await new JobicyProvider(feed).fetch();
    expect(job.title).toBe("Conceptual Copywriter");
    expect(job.location).toBe("Anywhere");
    expect(job.tags).toEqual(["Content & Editorial", "Contract"]);
    expect(job.salaryMin).toBe(40000);
    expect(job.salaryMax).toBe(55000);
    expect(job.salaryCurrency).toBe("USD");
    expect(job.salaryPeriod).toBe(SalaryPeriod.YEAR);
    expect(job.isRemote).toBe(true);
  });

  it("prefers the description over the excerpt", async () => {
    const [job] = await new JobicyProvider(feed).fetch();
    expect(job.descriptionHtml).toBe("<p>Full description</p>");
  });
});

describe("HimalayasProvider", () => {
  const feed = stubFeed({
    "jobs/api": {
      jobs: [
        {
          title: "Data Consultant",
          excerpt: "By joining Sedgwick…",
          companyName: "Sedgwick",
          employmentType: "Full Time",
          minSalary: "90000",
          maxSalary: "100000",
          salaryPeriod: "annual",
          currency: "USD",
          locationRestrictions: ["Canada"],
          categories: ["Data-Consultant"],
          parentCategories: ["Data Science"],
          seniority: ["Mid-level"],
          description: "<p>Description</p>",
          pubDate: "1792400768",
          expiryDate: "1797584766",
          applicationLink: "https://himalayas.app/companies/sedgwick/jobs/data-consultant",
          guid: "https://himalayas.app/companies/sedgwick/jobs/data-consultant",
        },
      ],
    },
  });

  it("reads locationRestrictions into the location and joins categories", async () => {
    const [job] = await new HimalayasProvider(feed).fetch();
    expect(job.location).toBe("Canada");
    expect(job.tags).toEqual(["Data-Consultant", "Data Science", "Mid-level"]);
    expect(job.salaryMin).toBe(90000);
    expect(job.salaryCurrency).toBe("USD");
    expect(job.salaryPeriod).toBe(SalaryPeriod.YEAR);
    expect(job.postedAtEpochMillis).toBe(1_792_400_768_000);
  });

  it("treats an empty restriction list as hiring from anywhere", async () => {
    const open = stubFeed({
      "jobs/api": { jobs: [{ title: "T", companyName: "C", locationRestrictions: [], applicationLink: "u" }] },
    });
    const [job] = await new HimalayasProvider(open).fetch();
    expect(job.location).toBe("Worldwide");
  });
});

describe("provider failure classification", () => {
  it("reads an HTTP status into the right category", () => {
    expect(classifyProviderError("HTTP 401 from x")).toBe(ProviderErrorCategory.AUTHENTICATION);
    expect(classifyProviderError("HTTP 403 from x")).toBe(ProviderErrorCategory.AUTHENTICATION);
    expect(classifyProviderError("HTTP 429 from x")).toBe(ProviderErrorCategory.RATE_LIMITED);
    expect(classifyProviderError("HTTP 503 from x")).toBe(ProviderErrorCategory.HTTP_ERROR);
  });

  it("recognises a browser CORS/network failure as a network error", () => {
    expect(classifyProviderError("Failed to fetch")).toBe(ProviderErrorCategory.NETWORK);
  });

  it("wraps a bad feed shape as an invalid response", async () => {
    const bad = stubFeed({ "api/remote-jobs": { notJobs: [] } });
    await expect(new RemotiveProvider(bad).fetch()).rejects.toMatchObject({
      category: ProviderErrorCategory.INVALID_RESPONSE,
    });
  });

  it("describes a failure in plain words", () => {
    const error = new JobProviderError("remotive", "x", ProviderErrorCategory.NETWORK);
    expect(providerFailureLine("Remotive", error)).toContain("Remotive");
    expect(providerFailureLine("Remotive", error)).toContain("network error");
  });

  it("tolerates nulls and non-strings in a string list", () => {
    expect(toStringList(["a", null, "", 3, "b"])).toEqual(["a", "3", "b"]);
    expect(toStringList(undefined)).toEqual([]);
  });
});
