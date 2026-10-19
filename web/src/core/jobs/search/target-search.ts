import { RoleFamily } from "../../models";

/**
 * One role the candidate is actually looking for, with the phrases a job board will
 * recognise and the words that mark it in a posting title. Mirrors the Kotlin `TargetRole`.
 */
export interface TargetRole {
  /** Canonical display title, e.g. "Mechanical Fitter". */
  canonical: string;
  /** Phrases a board accepts for this role, most specific first. */
  searchPhrases: string[];
  /** Whole words that mark this role in a posting title. */
  titleSignals: string[];
}

/**
 * The candidate's target-role taxonomy: an industrial-maintenance tradesperson with a
 * juice-production, nickel/cobalt pump and automotive background. This is static data, not
 * a model, and it is the single source of truth for what "relevant" means.
 */
export const roles: TargetRole[] = [
  {
    canonical: "Industrial Maintenance Technician",
    searchPhrases: ["industrial maintenance technician", "industrial maintenance", "maintenance technician"],
    titleSignals: ["industrial maintenance", "maintenance technician"],
  },
  {
    canonical: "Mechanical Maintenance Technician",
    searchPhrases: ["mechanical maintenance technician", "mechanical maintenance", "mechanical technician"],
    titleSignals: ["mechanical maintenance", "mechanical technician"],
  },
  {
    canonical: "Pump Technician",
    searchPhrases: ["pump technician", "pump maintenance", "pump fitter"],
    titleSignals: ["pump"],
  },
  {
    canonical: "Mechanical Fitter",
    searchPhrases: ["mechanical fitter", "maintenance fitter", "fitter"],
    titleSignals: ["fitter", "fitting"],
  },
  {
    canonical: "Rotating Equipment Technician",
    searchPhrases: ["rotating equipment technician", "rotating equipment", "rotating equipment mechanic"],
    titleSignals: ["rotating equipment", "rotating machinery"],
  },
  {
    canonical: "Mechanical Seal Technician",
    searchPhrases: ["mechanical seal technician", "mechanical seals technician", "mechanical seal fitter", "mechanical seals"],
    titleSignals: ["mechanical seal", "mechanical seals", "seal technician"],
  },
  {
    canonical: "Mining Maintenance Technician",
    searchPhrases: ["mining maintenance technician", "mine maintenance", "mining maintenance"],
    titleSignals: ["mine maintenance", "mining maintenance", "mining technician"],
  },
  {
    canonical: "Plant Maintenance Technician",
    searchPhrases: ["plant maintenance technician", "plant maintenance", "plant mechanic"],
    titleSignals: ["plant maintenance", "plant mechanic"],
  },
  {
    canonical: "Equipment Service Technician",
    searchPhrases: ["equipment service technician", "field service technician", "service technician"],
    titleSignals: ["service technician", "field service"],
  },
  {
    canonical: "On-site Services Technician",
    searchPhrases: ["site services technician", "onsite services technician", "field service engineer", "site maintenance services"],
    titleSignals: ["site services", "onsite services", "on-site services"],
  },
  {
    canonical: "Production Maintenance Technician",
    searchPhrases: ["production maintenance technician", "production maintenance", "production technician"],
    titleSignals: ["production maintenance"],
  },
];

/** Title words that on their own mark a role as maintenance/trade family. */
export const strongCoreTerms: string[] = ["maintenance", "mechanic", "mechanical", "fitter", "millwright", "mechatronics"];

/**
 * Title words that are only meaningful with a supporting equipment or industry word.
 * "Technician" alone could be a lab technician, so it is not treated as maintenance unless
 * the posting also names plant machinery or an industrial setting.
 */
export const weakCoreTerms: string[] = [
  "technician",
  "electrician",
  "welder",
  "machinist",
  "artisan",
  "instrumentation",
  "repairer",
  "assembler",
];

/** Equipment/industry nouns that add relevance from a title or the description head. */
export const equipmentTerms: string[] = [
  "pump", "pumps", "conveyor", "conveyors", "rotating equipment", "rotating machinery",
  "mechanical seal", "mechanical seals", "seal kit", "gasket", "impeller", "wear ring",
  "coupling", "laser alignment", "vibration analysis", "condition monitoring",
  "compressor", "gearbox", "bearing", "hydraulic", "hydraulics", "pneumatic",
  "pneumatics", "turbine", "diesel", "generator", "motor", "valve", "pipeline",
  "crusher", "mill", "kiln", "boiler", "chiller", "hvac",
  "drill rig", "drilling", "excavator", "haul truck", "loader", "shovel",
  "smelter", "refinery", "blast furnace", "coke oven", "flotation", "thickener",
  "screening plant", "processing plant", "pump station", "plant machinery",
];

/** Industry context words that support a maintenance reading when they appear in the title. */
export const industryTerms: string[] = [
  "industrial", "plant", "production", "manufacturing", "mining", "mine", "smelter",
  "refinery", "processing", "factory", "mill", "juice", "beverage", "food processing",
];

/**
 * The subset of `industryTerms` specific enough to support a weak trade word from the
 * *description* as well as the title. The broad ones are excluded: a long employer blurb
 * about its factory would otherwise turn every "Lab Technician" into a maintenance role.
 */
export const descriptionIndustryTerms: string[] = [
  "mining", "mine", "smelter", "refinery", "juice", "beverage", "food processing",
];

/** Titles for clearly different professions. */
export const otherProfessionMarkers: string[] = [
  "social worker", "account executive", "account manager", "sales representative",
  "sales executive", "sales manager", "marketing", "graphic design", "product design",
  "product manager", "ux designer", "ui designer", "nurse", "caregiver", "teacher",
  "tutor", "professor", "accountant", "bookkeeper", "financial analyst", "recruiter",
  "talent acquisition", "human resources", "customer support", "customer service",
  "data entry", "content writer", "copywriter", "translator", "lawyer", "paralegal",
  "software engineer", "software developer", "frontend developer", "backend developer",
  "full stack", "data scientist", "devops", "business analyst", "project manager",
  "chef", "cook", "waiter", "barista", "housekeeper", "cleaner", "security guard",
  "truck driver", "delivery driver", "courier", "warehouse picker",
];

/**
 * The keyword sets to send, most valuable first: the canonical target titles, then the
 * candidate's own titles. Capped so a keyword-search provider is not asked for an
 * unreasonable number of requests in one refresh.
 */
export function keywordPlan(profileTitles: string[], max = 6): string[] {
  const canonical = roles.map((r) => r.canonical);
  const merged = [...canonical, ...profileTitles]
    .map((t) => t.trim())
    .filter((t) => t.length > 0)
    .filter((t, i, arr) => arr.findIndex((o) => o.toLowerCase() === t.toLowerCase()) === i);
  return merged.slice(0, Math.max(1, max));
}

/** How a posting's title relates to what the candidate is looking for. */
export interface RoleRelevance {
  family: RoleFamily;
  /** The target role the title matched, when it is one of the canonical targets. */
  matchedTarget: TargetRole | null;
  /** The words in the title/description that drove the verdict, for the explanation. */
  evidence: string[];
}
