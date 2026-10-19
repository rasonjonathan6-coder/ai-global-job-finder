import {
  ALL_BENEFIT_TYPES,
  BenefitStatus,
  EmploymentType,
  MadagascarEligibility,
  MatchSignal,
  RoleFamily,
  SalaryPeriod,
  ScamRisk,
  UNKNOWN_VERIFICATION,
  VisaSupport,
  WorkMode,
  emptyJobRequirements,
  type BenefitEvidence,
  type Job,
  type MatchReason,
} from "../../core/models";

const DAY = 86_400_000;
const NOW = Date.UTC(2026, 9, 13); // 2026-10-13, matches the app's demo "today"

function reason(label: string, detail: string, weight: number, signal = MatchSignal.POSITIVE): MatchReason {
  return { label, detail, signal, weight };
}

function benefit(key: keyof typeof ALL_BENEFIT_BY_KEY, status: BenefitStatus, evidence: string): BenefitEvidence {
  const type = ALL_BENEFIT_BY_KEY[key];
  return { type, status, evidence, source: "posting", verifiedAtEpochMillis: NOW };
}

const ALL_BENEFIT_BY_KEY = Object.fromEntries(ALL_BENEFIT_TYPES.map((t) => [t.key, t])) as Record<
  string,
  (typeof ALL_BENEFIT_TYPES)[number]
>;

type JobSeed = Omit<
  Job,
  | "companyAccent"
  | "postedAtEpochMillis"
  | "expiresAtEpochMillis"
  | "retrievedAtEpochMillis"
  | "matchReasons"
  | "eligibilityReasons"
  | "benefitEvidence"
  | "requirements"
  | "qualificationGaps"
  | "evidence"
  | "verification"
  | "isSaved"
  | "isNew"
  | "providerId"
  | "openToApplicants"
> & {
  daysAgo?: number;
  matchReasons?: MatchReason[];
  benefitEvidence?: BenefitEvidence[];
  isNew?: boolean;
};

function makeJob(seed: JobSeed): Job {
  const daysAgo = seed.daysAgo ?? 2;
  const { daysAgo: _daysAgo, ...rest } = seed;
  void _daysAgo;
  return {
    ...rest,
    companyAccent: 0,
    providerId: "demo",
    postedAtEpochMillis: NOW - daysAgo * DAY,
    expiresAtEpochMillis: NOW + (30 - daysAgo) * DAY,
    retrievedAtEpochMillis: NOW - daysAgo * DAY,
    matchReasons: seed.matchReasons ?? [
      reason("Skills", "Your CV lists the core trade skills this posting asks for.", 34),
      reason("Experience", "Your years on the tools meet the advertised requirement.", 16),
    ],
    eligibilityReasons: [],
    benefitEvidence: seed.benefitEvidence ?? [],
    requirements: emptyJobRequirements(),
    qualificationGaps: [],
    evidence: [],
    verification: UNKNOWN_VERIFICATION,
    isSaved: false,
    isNew: seed.isNew ?? daysAgo <= 2,
    openToApplicants: true,
  };
}

/**
 * Stage-1 demo catalogue. Real provider data arrives in a later stage; these postings exist
 * so the premium UI can be exercised with realistic, Madagascar-relevant content — trades
 * that appear on international shortage lists, with a mix of eligibility verdicts.
 */
export const SAMPLE_JOBS: Job[] = [
  makeJob({
    id: "demo-canada-maintenance",
    title: "Maintenance Technician",
    company: "Northline Industrial",
    country: "Canada",
    countryFlag: "🇨🇦",
    city: "Calgary",
    salaryMin: 62000,
    salaryMax: 74000,
    currencyCode: "CAD",
    salaryPeriod: SalaryPeriod.YEAR,
    matchScore: 95,
    eligibility: MadagascarEligibility.VISA_SPONSORED,
    visaSupport: VisaSupport.FULL_SPONSORSHIP,
    scamRisk: ScamRisk.LOW,
    source: "Job Bank",
    sourceUrl: "https://www.jobbank.gc.ca/",
    workMode: WorkMode.ONSITE,
    employmentType: EmploymentType.FULL_TIME,
    experienceYears: 4,
    matchedSkills: ["Preventive maintenance", "Hydraulics", "Electrical fault-finding", "CMMS"],
    missingSkills: ["Red Seal certification"],
    languages: ["English"],
    summary:
      "Planned and reactive maintenance on production lines in a food-processing plant, working a two-shift rota.",
    responsibilities: [
      "Run scheduled preventive maintenance on conveyors, pumps and packaging lines.",
      "Diagnose electrical and hydraulic faults and complete repairs to schedule.",
      "Record work orders in the CMMS and hand over open issues each shift.",
    ],
    benefits: ["Visa sponsorship", "Relocation package", "Health insurance", "Annual flights home"],
    aiInsight:
      "Strong match. The employer advertises full sponsorship and a relocation package, so the route in from Madagascar is documented.",
    roleFamily: RoleFamily.TARGET,
    matchedTargetRole: "Maintenance Technician",
    salaryIsEstimated: false,
    salaryPeriodInferred: false,
    salaryUnitUncertain: false,
    daysAgo: 1,
    benefitEvidence: [
      benefit("visa_sponsorship", BenefitStatus.CONFIRMED, "We sponsor eligible international candidates."),
      benefit("relocation_support", BenefitStatus.CONFIRMED, "Relocation assistance provided."),
      benefit("health_insurance", BenefitStatus.CONFIRMED, "Extended health and dental plan."),
      benefit("flights_home", BenefitStatus.CONFIRMED, "One return flight home per year."),
    ],
  }),
  makeJob({
    id: "demo-uae-electrician",
    title: "Industrial Electrician",
    company: "Gulf Horizon Contracting",
    country: "United Arab Emirates",
    countryFlag: "🇦🇪",
    city: "Dubai",
    salaryMin: 9000,
    salaryMax: 11000,
    currencyCode: "AED",
    salaryPeriod: SalaryPeriod.MONTH,
    matchScore: 91,
    eligibility: MadagascarEligibility.VISA_SPONSORED,
    visaSupport: VisaSupport.RELOCATION_PACKAGE,
    scamRisk: ScamRisk.LOW,
    source: "Bayt",
    sourceUrl: "https://www.bayt.com/",
    workMode: WorkMode.ONSITE,
    employmentType: EmploymentType.CONTRACT,
    experienceYears: 5,
    matchedSkills: ["Panel wiring", "Motor controls", "Conduit installation", "Testing"],
    missingSkills: ["HV switching certificate"],
    languages: ["English"],
    summary:
      "Electrical installation and commissioning on a commercial tower project, on a two-year renewable contract.",
    responsibilities: [
      "Install and terminate low-voltage panels, trays and conduit runs.",
      "Test and commission circuits, recording results for handover.",
      "Work to method statements and permit-to-work systems on site.",
    ],
    benefits: ["Work permit", "Accommodation", "Transport allowance", "Health insurance"],
    aiInsight:
      "Strong match. Accommodation and transport are stated; confirm the visa terms in writing before resigning anything.",
    roleFamily: RoleFamily.MAINTENANCE_FAMILY,
    matchedTargetRole: "Industrial Electrician",
    salaryIsEstimated: false,
    salaryPeriodInferred: false,
    salaryUnitUncertain: false,
    daysAgo: 3,
    benefitEvidence: [
      benefit("work_permit", BenefitStatus.CONFIRMED, "Company arranges the employment visa."),
      benefit("accommodation", BenefitStatus.CONFIRMED, "Shared company accommodation provided."),
      benefit("transport", BenefitStatus.CONFIRMED, "Daily transport allowance."),
      benefit("health_insurance", BenefitStatus.CONFIRMED, "Medical insurance provided."),
    ],
  }),
  makeJob({
    id: "demo-germany-hvac",
    title: "HVAC Technician",
    company: "Rheinland Klimatechnik",
    country: "Germany",
    countryFlag: "🇩🇪",
    city: "Düsseldorf",
    salaryMin: 38000,
    salaryMax: 45000,
    currencyCode: "EUR",
    salaryPeriod: SalaryPeriod.YEAR,
    matchScore: 84,
    eligibility: MadagascarEligibility.VISA_SPONSORED,
    visaSupport: VisaSupport.SPONSORSHIP_AVAILABLE,
    scamRisk: ScamRisk.LOW,
    source: "Make it in Germany",
    sourceUrl: "https://www.make-it-in-germany.com/",
    workMode: WorkMode.ONSITE,
    employmentType: EmploymentType.FULL_TIME,
    experienceYears: 3,
    matchedSkills: ["Refrigeration", "Split systems", "Fault diagnosis", "Customer service"],
    missingSkills: ["German B1", "EU recognised qualification"],
    languages: ["English"],
    summary:
      "Service and installation of split and VRF systems for commercial clients, with support to recognise your qualification.",
    responsibilities: [
      "Service and repair split, multi-split and VRF air-conditioning systems.",
      "Install new systems on commercial sites and commission them.",
      "Advise clients on maintenance and complete digital service reports.",
    ],
    benefits: ["Visa sponsorship", "Language training", "Paid leave"],
    aiInsight:
      "Good match. The visa route is mentioned but not spelled out — ask who pays the fees and whether your qualification will be recognised.",
    roleFamily: RoleFamily.MAINTENANCE_FAMILY,
    matchedTargetRole: "HVAC Technician",
    salaryIsEstimated: false,
    salaryPeriodInferred: false,
    salaryUnitUncertain: false,
    daysAgo: 5,
    benefitEvidence: [
      benefit("visa_sponsorship", BenefitStatus.NEEDS_VERIFICATION, "We support your visa application."),
      benefit("language_training", BenefitStatus.CONFIRMED, "German courses funded."),
      benefit("paid_leave", BenefitStatus.CONFIRMED, "30 days annual leave."),
    ],
  }),
  makeJob({
    id: "demo-australia-diesel",
    title: "Heavy Diesel Mechanic",
    company: "Outback Fleet Services",
    country: "Australia",
    countryFlag: "🇦🇺",
    city: "Perth",
    salaryMin: 95000,
    salaryMax: 115000,
    currencyCode: "AUD",
    salaryPeriod: SalaryPeriod.YEAR,
    matchScore: 88,
    eligibility: MadagascarEligibility.VISA_SPONSORED,
    visaSupport: VisaSupport.FULL_SPONSORSHIP,
    scamRisk: ScamRisk.LOW,
    source: "SEEK",
    sourceUrl: "https://www.seek.com.au/",
    workMode: WorkMode.ONSITE,
    employmentType: EmploymentType.FULL_TIME,
    experienceYears: 5,
    matchedSkills: ["Diesel engines", "Diagnostics", "Fleet maintenance", "Hydraulics"],
    missingSkills: ["Australian skills assessment"],
    languages: ["English"],
    summary:
      "Maintain a fleet of mining haul trucks and support vehicles on a fly-in fly-out roster.",
    responsibilities: [
      "Diagnose and repair diesel engines, transmissions and hydraulic systems.",
      "Complete scheduled servicing on haul trucks and light vehicles.",
      "Follow site safety systems and report defects accurately.",
    ],
    benefits: ["Visa sponsorship", "Relocation package", "Flights home", "Health insurance"],
    aiInsight:
      "Strong match. A skills assessment is normally required — start it early, as it can take months.",
    roleFamily: RoleFamily.TARGET,
    matchedTargetRole: "Heavy Diesel Mechanic",
    salaryIsEstimated: false,
    salaryPeriodInferred: false,
    salaryUnitUncertain: false,
    daysAgo: 2,
    benefitEvidence: [
      benefit("visa_sponsorship", BenefitStatus.CONFIRMED, "482 sponsorship available for the right candidate."),
      benefit("relocation_support", BenefitStatus.CONFIRMED, "Relocation allowance negotiable."),
      benefit("flights_home", BenefitStatus.CONFIRMED, "FIFO flights provided."),
      benefit("health_insurance", BenefitStatus.CONFIRMED, "Private health cover included."),
    ],
  }),
  makeJob({
    id: "demo-qatar-welder",
    title: "Pipeline Welder (6G)",
    company: "Doha Energy Fabrication",
    country: "Qatar",
    countryFlag: "🇶🇦",
    city: "Doha",
    salaryMin: 7500,
    salaryMax: 9500,
    currencyCode: "QAR",
    salaryPeriod: SalaryPeriod.MONTH,
    matchScore: 76,
    eligibility: MadagascarEligibility.ELIGIBLE,
    visaSupport: VisaSupport.NOT_MENTIONED,
    scamRisk: ScamRisk.MEDIUM,
    source: "GulfTalent",
    sourceUrl: "https://www.gulftalent.com/",
    workMode: WorkMode.ONSITE,
    employmentType: EmploymentType.CONTRACT,
    experienceYears: 6,
    matchedSkills: ["6G welding", "Pipe fitting", "TIG", "Arc welding"],
    missingSkills: ["ASME IX certification"],
    languages: ["English"],
    summary:
      "Fabrication and welding of pipe spools for an energy project, on a one-year contract with possible extension.",
    responsibilities: [
      "Weld pipe spools to the project procedure and pass inspection.",
      "Prepare joints, align pipework and control distortion.",
      "Maintain welding consumables and equipment records.",
    ],
    benefits: ["Accommodation", "Transport allowance"],
    aiInsight:
      "Reasonable match. The posting does not name a visa route and the contact details look generic — verify the employer before sharing documents.",
    roleFamily: RoleFamily.MAINTENANCE_FAMILY,
    matchedTargetRole: "Pipeline Welder",
    salaryIsEstimated: false,
    salaryPeriodInferred: false,
    salaryUnitUncertain: false,
    daysAgo: 6,
    benefitEvidence: [
      benefit("accommodation", BenefitStatus.CONFIRMED, "Company accommodation provided."),
      benefit("transport", BenefitStatus.CONFIRMED, "Site transport provided."),
    ],
  }),
  makeJob({
    id: "demo-remote-solar",
    title: "Remote Solar O&M Technician",
    company: "Helios Grid",
    country: "Worldwide",
    countryFlag: "🌍",
    city: "Remote",
    salaryMin: 2400,
    salaryMax: 3200,
    currencyCode: "USD",
    salaryPeriod: SalaryPeriod.MONTH,
    matchScore: 72,
    eligibility: MadagascarEligibility.ELIGIBLE,
    visaSupport: VisaSupport.NOT_MENTIONED,
    scamRisk: ScamRisk.LOW,
    source: "Remote OK",
    sourceUrl: "https://remoteok.com/",
    workMode: WorkMode.REMOTE,
    employmentType: EmploymentType.CONTRACT,
    experienceYears: 4,
    matchedSkills: ["Solar PV", "Inverter maintenance", "Monitoring", "Reporting"],
    missingSkills: ["SCADA experience"],
    languages: ["English"],
    summary:
      "Remote monitoring and reporting for distributed solar sites, with occasional site travel arranged by the client.",
    responsibilities: [
      "Monitor PV plant performance and investigate underperformance alerts.",
      "Coordinate field teams for repairs and record outcomes.",
      "Produce weekly performance reports for the client.",
    ],
    benefits: ["Remote work", "No visa needed"],
    aiInsight:
      "Reasonable match. Remote work needs no visa, but you invoice as a contractor — set aside money for your own tax and health cover.",
    roleFamily: RoleFamily.MAINTENANCE_FAMILY,
    matchedTargetRole: "Solar O&M Technician",
    salaryIsEstimated: true,
    salaryPeriodInferred: false,
    salaryUnitUncertain: false,
    daysAgo: 8,
    benefitEvidence: [],
  }),
];
