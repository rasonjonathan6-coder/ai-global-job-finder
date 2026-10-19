import {
  MadagascarEligibility,
  VisaSupport,
  type EligibilityReason,
} from "../../models";

/** The eligibility verdict for one posting. */
export interface EligibilityAssessment {
  eligibility: MadagascarEligibility;
  visaSupport: VisaSupport;
  reasons: EligibilityReason[];
  /** True when a quoted official fact about international hiring was found. */
  hasOfficialSignal: boolean;
}

/** Everything the engine may look at. Kept explicit so it is obvious what drives a verdict. */
export interface EligibilityInput {
  title: string;
  location: string;
  description: string;
  tags?: string[];
  employmentType?: string | null;
  isRemote?: boolean;
  /**
   * True when the posting's location resolves to a country the catalog actually knows. A
   * remote role bound to one country is not the same as "remote worldwide": the employer
   * may be hiring only where it can lawfully employ, so eligibility is left UNCLEAR rather
   * than assumed. A region ("EMEA", "Europe") is deliberately **not** a recognised country.
   */
  isRecognizedCountry?: boolean;
}

const LOCAL_ONLY_MARKERS = [
  "local candidates only",
  "locals only",
  "local applicants only",
  "must be authorized to work in",
  "must already have the right to work",
  "must have the right to work in",
  "no visa sponsorship",
  "we do not sponsor",
  "we are unable to sponsor",
  "cannot sponsor",
  "without sponsorship",
  "citizens only",
  "citizens and permanent residents",
  "must be a citizen",
  "residing in the country",
  "must reside in",
  // An explicit refusal of sponsorship is a hard restriction, not a missing fact. Without
  // these, "this position is not eligible for visa sponsorship" contains "visa sponsorship"
  // and was read as a *confirmation* of sponsorship.
  "not eligible for visa sponsorship",
  "not eligible for sponsorship",
  "visa sponsorship is not available",
  "no visa sponsorship available",
  "unable to provide sponsorship",
  "unable to offer sponsorship",
  "do not offer sponsorship",
  "does not offer sponsorship",
  "do not provide sponsorship",
  "not able to sponsor",
];

const SPONSORSHIP_MARKERS = [
  "visa sponsorship",
  "sponsorship available",
  "we sponsor visas",
  "sponsor a work visa",
  "sponsorship is available",
  "work permit sponsorship",
  "tier 2 sponsorship",
  "skilled worker visa",
  "we will sponsor",
];

const RELOCATION_MARKERS = [
  "relocation package",
  "relocation support",
  "relocation assistance",
  "relocation bonus",
  "we help you relocate",
  "visa and relocation",
];

const INTERNATIONAL_MARKERS = [
  "international applicants",
  "international candidates",
  "open to international",
  "applicants from anywhere",
  "candidates from anywhere",
  "any nationality",
  "all nationalities",
  "worldwide applicants",
  "global applicants",
  "remote worldwide",
  "work from anywhere",
  "anywhere in the world",
];

const REMOTE_WORLDWIDE_LOCATIONS = ["worldwide", "anywhere", "global", "remote", "any location"];

/**
 * Regions that normally include Madagascar. A remote role scoped to one of these is treated
 * as a signal (not proof) of eligibility. Only regions that genuinely contain Madagascar
 * are listed.
 */
const REMOTE_REGIONS: Array<[string, string[]]> = [
  ["emea", ["mg", "fr", "de"]],
  ["europe, middle east and africa", ["mg"]],
  ["middle east and africa", ["mg"]],
  ["sub-saharan africa", ["mg"]],
  ["africa", ["mg"]],
  ["indian ocean", ["mg"]],
  ["utc+1", ["mg"]],
  ["utc+2", ["mg"]],
  ["utc+3", ["mg"]],
];

/**
 * Decides whether a candidate currently in Madagascar can realistically apply. Mirrors the
 * Kotlin `EligibilityEngine`.
 *
 * Rules, in priority order:
 *  1. The posting states it is open only to local/national candidates → RESTRICTED.
 *  2. The posting states international candidates are welcome, or it is explicitly
 *     remote/worldwide → ELIGIBLE.
 *  3. The posting offers visa sponsorship or relocation → VISA_SPONSORED.
 *  4. Nothing is stated → UNCLEAR, with a reminder to verify. Absence of a statement is
 *     never treated as sponsorship.
 */
export function assessEligibility(input: EligibilityInput): EligibilityAssessment {
  const tags = input.tags ?? [];
  const isRemote = input.isRemote ?? false;
  const isRecognizedCountry = input.isRecognizedCountry ?? false;

  const text = `${input.title} ${input.location} ${input.employmentType ?? ""} ${tags.join(" ")} ${input.description}`.toLowerCase();

  const reasons: EligibilityReason[] = [];
  let hasOfficial = false;

  // ---- 1. Hard restrictions stated by the employer.
  const restriction = LOCAL_ONLY_MARKERS.find((m) => text.includes(m));
  if (restriction != null) {
    reasons.push({
      label: "Restricted to local applicants",
      detail:
        `The posting states "${restriction}". Employers that phrase it this way ` +
        "normally require an existing work authorisation.",
      official: true,
    });
    hasOfficial = true;
    return {
      eligibility: MadagascarEligibility.RESTRICTED,
      visaSupport: VisaSupport.LOCAL_ONLY,
      reasons,
      hasOfficialSignal: hasOfficial,
    };
  }

  // ---- 2. Sponsorship / relocation statements.
  const sponsorship = SPONSORSHIP_MARKERS.find((m) => text.includes(m));
  const relocation = RELOCATION_MARKERS.find((m) => text.includes(m));
  if (sponsorship != null) {
    reasons.push({
      label: "Employer states visa sponsorship",
      detail: `The posting says "${sponsorship}". Confirm the conditions with the employer before applying.`,
      official: true,
    });
    hasOfficial = true;
  }
  if (relocation != null) {
    reasons.push({
      label: "Relocation support mentioned",
      detail: `The posting mentions "${relocation}". This usually implies the employer expects to hire from abroad.`,
      official: true,
    });
    hasOfficial = true;
  }

  // ---- 3. Explicit international openness.
  const international = INTERNATIONAL_MARKERS.find((m) => text.includes(m));
  if (international != null) {
    reasons.push({
      label: "Open to international applicants",
      detail: `The posting says "${international}".`,
      official: true,
    });
    hasOfficial = true;
  }

  // ---- 4. Remote roles, in priority order: a region that includes Madagascar, then a
  // worldwide/unspecified remote, then a remote bound to a country Madagascar is not in.
  const locationLower = input.location.toLowerCase();

  const remoteRegion =
    isRemote &&
    !isRecognizedCountry &&
    REMOTE_REGIONS.some(([region, codes]) => locationLower.includes(region) && codes.includes("mg"));

  const remoteAnywhere =
    isRemote &&
    !isRecognizedCountry &&
    !remoteRegion &&
    (input.location.trim().length === 0 || REMOTE_WORLDWIDE_LOCATIONS.some((l) => locationLower.includes(l)));

  if (remoteRegion) {
    reasons.push({
      label: "Remote role scoped to a region that includes Madagascar",
      detail:
        `The posting scopes this remote role to "${input.location}", a region ` +
        "that normally includes Madagascar. Confirm the employer can hire there before you rely on it.",
      official: false,
    });
  } else if (remoteAnywhere) {
    reasons.push({
      label: "Location listed as remote or worldwide",
      detail: `The source lists this role for "${input.location || "remote"}", so there is no local work-permit requirement stated.`,
      official: true,
    });
    hasOfficial = true;
  } else if (isRemote && isRecognizedCountry) {
    reasons.push({
      label: "Remote role limited to one country",
      detail:
        `The source lists this remote role for "${input.location}", so the employer ` +
        "may only hire where it has a legal entity. Eligibility from Madagascar is not stated.",
      official: true,
    });
    hasOfficial = true;
  } else if (isRemote && input.location.trim().length > 0) {
    reasons.push({
      label: "Remote role bound to another region",
      detail:
        `The source lists this remote role for "${input.location}", which is not ` +
        "stated to include Madagascar. Confirm the employer can hire there before you rely on it.",
      official: false,
    });
  }

  const eligibility: MadagascarEligibility =
    sponsorship != null
      ? MadagascarEligibility.VISA_SPONSORED
      : international != null || remoteAnywhere
        ? MadagascarEligibility.ELIGIBLE
        : relocation != null
          ? MadagascarEligibility.ELIGIBLE
          : remoteRegion
            ? MadagascarEligibility.ELIGIBLE
            : MadagascarEligibility.UNCLEAR;

  if (eligibility === MadagascarEligibility.UNCLEAR) {
    reasons.push({
      label: "Eligibility not stated",
      detail:
        "The posting does not say whether it accepts applicants from Madagascar. " +
        "Check the official listing or ask the employer before you rely on it.",
      official: false,
    });
  }

  const visaSupport: VisaSupport =
    sponsorship != null
      ? VisaSupport.SPONSORSHIP_AVAILABLE
      : relocation != null
        ? VisaSupport.RELOCATION_PACKAGE
        : VisaSupport.NOT_MENTIONED;

  return { eligibility, visaSupport, reasons, hasOfficialSignal: hasOfficial };
}
