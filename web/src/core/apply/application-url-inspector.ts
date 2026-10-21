import {
  ApplicationDestination,
  type ApplicationLinkInspection,
  type Job,
} from "../models";

/**
 * Inspects the URL behind an application. Mirrors the Kotlin `ApplicationUrlInspector`.
 *
 * It answers three questions the candidate cares about before leaving the app: is this a
 * real link, whose site is it, and is there anything about it worth flagging? The app never
 * opens a link it has not first described, because an application button that silently
 * leaves for an unknown host is exactly how a candidate ends up on a phishing page.
 */

const GOVERNMENT_SUFFIXES = [
  "gov", "gouv.fr", "gov.uk", "gov.au", "govt.nz", "gc.ca", "europa.eu", "gov.ae", "gov.qa",
];

const GOVERNMENT_HOSTS = [
  "jobbank.gc.ca", "canada.ca", "make-it-in-germany.com", "eures.europa.eu",
  "gov.uk", "immi.homeaffairs.gov.au", "immigration.govt.nz", "ind.nl",
  "mohre.gov.ae", "mol.gov.qa", "un.org", "ilo.org",
];

const JOB_BOARD_HOSTS = [
  "adzuna.com", "jooble.org", "himalayas.app", "remotive.com", "jobicy.com",
  "arbeitnow.com", "linkedin.com", "indeed.com", "bayt.com", "naukrigulf.com",
];

/**
 * Applicant-tracking systems the app's own sources link to. A posting on one of these is the
 * employer's own application page: the employer runs their careers site there, so the
 * candidate applies through the employer rather than to a third party. Matched as a suffix so
 * a Workday tenant host (`sulzer.wd502.myworkdayjobs.com`) is covered.
 */
const EMPLOYER_HOST_SUFFIXES = [
  "myworkdayjobs.com", "myworkdaysite.com", "workday.com",
  "greenhouse.io", "lever.co", "ashbyhq.com", "smartrecruiters.com",
];

const FREE_DOMAIN_SUFFIXES = [".tk", ".ml", ".ga", ".cf", ".gq"];

export class ApplicationUrlInspector {
  inspect(job: Job): ApplicationLinkInspection;
  inspect(url: string, sourceName?: string): ApplicationLinkInspection;
  inspect(urlOrJob: Job | string, sourceName = ""): ApplicationLinkInspection {
    const url = typeof urlOrJob === "string" ? urlOrJob : urlOrJob.sourceUrl;
    const source = typeof urlOrJob === "string" ? sourceName : urlOrJob.source;
    const trimmed = url.trim();

    if (trimmed.length === 0) {
      return {
        url: "",
        host: null,
        destination: ApplicationDestination.INVALID,
        canOpen: false,
        message: "This posting did not include an application link. Search the employer's own careers page.",
        warnings: [],
      };
    }

    const parsed = parseUrl(trimmed);
    const scheme = parsed?.protocol.replace(":", "").toLowerCase() ?? null;
    if (parsed == null || (scheme !== "http" && scheme !== "https")) {
      return {
        url: trimmed,
        host: null,
        destination: ApplicationDestination.INVALID,
        canOpen: false,
        message: "This link is not a web address the app can open safely.",
        warnings: ["Only http and https links are opened."],
      };
    }

    const host = (parsed.hostname ?? "").toLowerCase().replace(/^www\./, "");
    if (host.length === 0 || !host.includes(".")) {
      return {
        url: trimmed,
        host: host.length > 0 ? host : null,
        destination: ApplicationDestination.INVALID,
        canOpen: false,
        message: "This link has no usable web address.",
        warnings: [],
      };
    }

    const warnings: string[] = [];
    if (scheme === "http") {
      warnings.push("This page is not served over a secure connection (http).");
    }
    if (FREE_DOMAIN_SUFFIXES.some((s) => host.endsWith(s))) {
      warnings.push("Free-domain hosts are commonly used by recruitment scams. Verify the employer independently.");
    }
    if (host.startsWith("xn--")) {
      warnings.push("This address uses non-Latin characters that can imitate a real company's site.");
    }
    if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host)) {
      warnings.push("The link points at a raw IP address rather than a company domain.");
    }
    if (trimmed.length > 500) {
      warnings.push("This is an unusually long link, which can hide a redirect.");
    }

    const destination = classify(host, source);
    const message = messageFor(destination, host);
    return { url: trimmed, host, destination, canOpen: true, message, warnings };
  }
}

function messageFor(destination: ApplicationDestination, host: string): string {
  switch (destination) {
    case ApplicationDestination.GOVERNMENT_PORTAL:
      return `This link goes to ${host}, an official source. Applications here follow the government's own process.`;
    case ApplicationDestination.EMPLOYER_SITE:
      return `This link goes to ${host}. Read the posting there and apply through the employer's own form.`;
    case ApplicationDestination.JOB_BOARD:
      return `This link goes to ${host}, where the listing is published. Confirm the employer's details before applying.`;
    case ApplicationDestination.UNKNOWN_HOST:
      return `This link goes to ${host}. The app cannot verify this host, so treat it with care.`;
    case ApplicationDestination.INVALID:
      return "This link is not usable.";
  }
}

function classify(host: string, sourceName: string): ApplicationDestination {
  if (GOVERNMENT_SUFFIXES.some((it) => host === it || host.endsWith(`.${it}`))) {
    return ApplicationDestination.GOVERNMENT_PORTAL;
  }
  if (GOVERNMENT_HOSTS.some((it) => host === it || host.endsWith(`.${it}`))) {
    return ApplicationDestination.GOVERNMENT_PORTAL;
  }
  if (JOB_BOARD_HOSTS.some((it) => host === it || host.endsWith(`.${it}`))) {
    return ApplicationDestination.JOB_BOARD;
  }
  // An applicant-tracking host serves the employer's own posting, so the link is the
  // employer's own application page — not an unverifiable host. Most real vacancies are
  // reached through these (Workday, Greenhouse, Lever, Ashby, SmartRecruiters), so calling
  // them unverified would mislabel the majority of genuine employer links.
  if (EMPLOYER_HOST_SUFFIXES.some((it) => host === it || host.endsWith(`.${it}`))) {
    return ApplicationDestination.EMPLOYER_SITE;
  }
  // A posting whose host matches the source name is the board that published it.
  const sourceSlug = sourceName.toLowerCase().replace(/[^a-z0-9]/g, "");
  if (sourceSlug.length >= 4 && host.replace(/[^a-z0-9]/g, "").includes(sourceSlug)) {
    return ApplicationDestination.JOB_BOARD;
  }
  return ApplicationDestination.UNKNOWN_HOST;
}

function parseUrl(url: string): URL | null {
  try {
    return new URL(url);
  } catch {
    return null;
  }
}
