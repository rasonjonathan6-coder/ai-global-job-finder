/**
 * A narrow seam over `fetch`, so a provider can be exercised with a canned JSON body and no
 * network. Mirrors the Kotlin `JsonFeed`/`HttpJsonClient` boundary.
 *
 * The web edition is a static SPA: every request goes straight from the browser to the job
 * source. Only key-free feeds are wired in Stage 59, because shipping an API key to the
 * browser would expose it to every visitor. See docs/web-parity.md.
 */

export interface JsonFeed {
  objectAt(url: string, init?: RequestInit): Promise<Record<string, unknown>>;
  arrayAt(url: string, init?: RequestInit): Promise<unknown[]>;
}

/** Raised by a provider when the feed is unreachable or malformed. */
export class JobProviderError extends Error {
  constructor(
    readonly providerId: string,
    message: string,
    readonly category: ProviderErrorCategory,
    readonly cause?: unknown,
  ) {
    super(message);
    this.name = "JobProviderError";
  }
}

/**
 * Why a provider attempt did not return usable postings. Kept coarse and stable so the
 * diagnostics line can describe the failure in plain words instead of a stack trace.
 * Mirrors the Kotlin `ProviderErrorCategory`.
 */
export enum ProviderErrorCategory {
  NOT_CONFIGURED = "NOT_CONFIGURED",
  AUTHENTICATION = "AUTHENTICATION",
  RATE_LIMITED = "RATE_LIMITED",
  TIMEOUT = "TIMEOUT",
  NETWORK = "NETWORK",
  INVALID_RESPONSE = "INVALID_RESPONSE",
  HTTP_ERROR = "HTTP_ERROR",
  UNKNOWN = "UNKNOWN",
}

const CATEGORY_LABEL: Record<ProviderErrorCategory, { label: string; explanation: string }> = {
  [ProviderErrorCategory.NOT_CONFIGURED]: {
    label: "Not configured",
    explanation: "This source needs credentials before it can be searched.",
  },
  [ProviderErrorCategory.AUTHENTICATION]: {
    label: "Authentication failed",
    explanation: "The source rejected the credentials. Check that the key is correct and still active.",
  },
  [ProviderErrorCategory.RATE_LIMITED]: {
    label: "Rate limited",
    explanation: "The source is temporarily refusing requests. Wait a little before trying again.",
  },
  [ProviderErrorCategory.TIMEOUT]: {
    label: "Timed out",
    explanation: "The source did not answer in time. It may be slow or unreachable right now.",
  },
  [ProviderErrorCategory.NETWORK]: {
    label: "Network error",
    explanation:
      "The source could not be reached from the browser. Some boards block direct browser requests; this is a source limitation, not your connection.",
  },
  [ProviderErrorCategory.INVALID_RESPONSE]: {
    label: "Invalid response",
    explanation: "The source replied, but its response could not be understood. Nothing was changed.",
  },
  [ProviderErrorCategory.HTTP_ERROR]: {
    label: "Request rejected",
    explanation: "The source answered with an error status instead of job data.",
  },
  [ProviderErrorCategory.UNKNOWN]: {
    label: "Unknown failure",
    explanation: "The source failed for a reason the app could not classify.",
  },
};

export function describeCategory(category: ProviderErrorCategory) {
  return CATEGORY_LABEL[category];
}

const HTTP_STATUS = /\b(\d{3})\b/;
const AUTH = /unauthor|forbidden|invalid (?:api )?key|invalid token|access denied|credential|not authorized|401|403|auth_fail|auth fail/;
const RATE_LIMIT = /rate limit|too many requests|throttl|quota|429/;
const TIMEOUT = /timeout|timed out|deadline exceeded|abort/;
const INVALID_RESPONSE = /invalid response|malformed|unexpected|not valid json|json(?:parse)?exception|not a json|unexpected token/;
const NETWORK = /unable to resolve host|connection refused|connectexception|no address|network is unreachable|failed to connect|unknownhost|load failed|failed to fetch/;

/**
 * Turns whatever a provider threw into one category. Mirrors the Kotlin
 * `ProviderErrorClassifier`: the most specific, most actionable cause wins.
 */
export function classifyProviderError(message: string | null | undefined, cause?: unknown): ProviderErrorCategory {
  const causeMessage = cause instanceof Error ? cause.message : typeof cause === "string" ? cause : "";
  const haystack = `${message ?? ""} ${causeMessage}`.toLowerCase();

  const statusMatch = HTTP_STATUS.exec(haystack);
  const httpStatus = statusMatch ? Number(statusMatch[1]) : null;
  if (httpStatus != null) {
    if (httpStatus === 401 || httpStatus === 403) return ProviderErrorCategory.AUTHENTICATION;
    if (httpStatus === 429) return ProviderErrorCategory.RATE_LIMITED;
  }

  const name = cause instanceof Error ? cause.name : "";
  if (name === "AbortError") return ProviderErrorCategory.TIMEOUT;
  if (name === "TypeError" && NETWORK.test(haystack)) return ProviderErrorCategory.NETWORK;

  if (AUTH.test(haystack)) return ProviderErrorCategory.AUTHENTICATION;
  if (RATE_LIMIT.test(haystack)) return ProviderErrorCategory.RATE_LIMITED;
  if (TIMEOUT.test(haystack)) return ProviderErrorCategory.TIMEOUT;
  if (INVALID_RESPONSE.test(haystack)) return ProviderErrorCategory.INVALID_RESPONSE;
  if (NETWORK.test(haystack)) return ProviderErrorCategory.NETWORK;
  if (httpStatus != null) return ProviderErrorCategory.HTTP_ERROR;
  return ProviderErrorCategory.UNKNOWN;
}

const DEFAULT_TIMEOUT_MS = 12_000;

/**
 * The real feed. A per-request timeout keeps one slow board from holding up the whole
 * refresh; a non-2xx status becomes a classified error rather than being parsed as JSON.
 */
export const HttpJsonFeed: JsonFeed = {
  async objectAt(url, init) {
    const parsed = await fetchJson(url, init);
    if (parsed == null || typeof parsed !== "object" || Array.isArray(parsed)) {
      throw new Error("unexpected response shape: expected a JSON object");
    }
    return parsed as Record<string, unknown>;
  },
  async arrayAt(url, init) {
    const parsed = await fetchJson(url, init);
    if (!Array.isArray(parsed)) throw new Error("unexpected response shape: expected a JSON array");
    return parsed as unknown[];
  },
};

async function fetchJson(url: string, init?: RequestInit): Promise<unknown> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), DEFAULT_TIMEOUT_MS);
  try {
    const response = await fetch(url, {
      ...init,
      headers: { Accept: "application/json", ...(init?.headers ?? {}) },
      signal: controller.signal,
    });
    if (!response.ok) throw new Error(`HTTP ${response.status} from ${url}`);
    return await response.json();
  } finally {
    clearTimeout(timer);
  }
}

/** Safely reads a JSON array of strings, tolerating nulls and non-string entries. */
export function toStringList(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((entry) => (entry == null ? null : String(entry)))
    .filter((entry): entry is string => entry != null && entry.trim().length > 0);
}

export function optString(value: unknown): string {
  if (value == null) return "";
  return typeof value === "string" ? value : String(value);
}

export function optBool(value: unknown, fallback = false): boolean {
  if (typeof value === "boolean") return value;
  if (typeof value === "string") return value.toLowerCase() === "true";
  return fallback;
}

/** Reads a numeric field that may arrive as a number or a numeric string. */
export function optInt(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) return Math.trunc(value);
  if (typeof value === "string" && value.trim().length > 0) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? Math.trunc(parsed) : null;
  }
  return null;
}
