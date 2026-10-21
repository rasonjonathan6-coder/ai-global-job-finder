import {
  emptyCandidateProfile,
  LanguageLevel,
  RelocationPreference,
  type CandidateProfile,
} from "../core/models";

/**
 * The profile the app starts with before a CV is imported: empty.
 *
 * This mirrors the Android `LiveJobRepository.profile` contract — "The display profile is
 * derived from the imported CV, never invented. A fresh install therefore has an empty
 * profile and the Profile screen shows its empty state." An earlier revision seeded the
 * curated `SampleData.profile` (Jonathan Rakoto) as the *starting* profile, so a fresh
 * visitor read a stranger's name, e-mail, phone number and skills — and a "CV analysed"
 * badge — as if they were their own. That was the fake information the site displayed.
 * The fixtures now live behind demo mode only, exactly like Android.
 */
export function demoCandidateProfile(): CandidateProfile {
  return emptyCandidateProfile();
}

/**
 * The curated Android `SampleData.profile`, served only when the user turns demo fixtures
 * on. Kept verbatim so the demo showcase and the Kotlin golden fixtures stay identical.
 */
export function sampleCandidateProfile(): CandidateProfile {
  return {
    ...emptyCandidateProfile(),
    fullName: "Jonathan Rakoto",
    email: "jonathan.rakoto@example.mg",
    phone: "+261 34 00 000 00",
    location: "Antananarivo, Madagascar",
    currentTitle: "Maintenance Technician",
    previousTitles: ["Mechanical Fitter", "Pump Technician"],
    yearsOfExperience: 6,
    skills: [
      "Preventive maintenance",
      "Electrical diagnostics",
      "Hydraulics",
      "Pneumatics",
      "Mechanical repair",
      "Pump maintenance",
      "Rotating equipment",
      "CMMS",
      "PLC basics",
      "Condition monitoring",
      "Welding",
      "Heavy equipment",
      "Safety compliance",
      "Team supervision",
    ],
    industries: ["Industrial maintenance", "Mining", "Manufacturing"],
    certifications: [
      { name: "City & Guilds Mechanical Engineering", issuer: "City & Guilds", year: 2016 },
      { name: "First Aid", issuer: "Red Cross", year: 2020 },
    ],
    languages: [
      { name: "Malagasy", level: LanguageLevel.NATIVE, evidence: "CV" },
      { name: "French", level: LanguageLevel.FLUENT, evidence: "CV" },
      { name: "English", level: LanguageLevel.INTERMEDIATE, evidence: "CV" },
    ],
    preferredCountries: ["Canada", "Germany", "United Arab Emirates", "Qatar", "New Zealand"],
    preferredRoles: ["Maintenance Technician", "Industrial Electrician", "Facilities Supervisor"],
    relocationPreference: RelocationPreference.PREFERS_RELOCATION,
    needsVisaSponsorship: true,
    workPermitNotes: "",
    summary:
      "Hands-on maintenance technician with 6 years keeping production lines running. Strong in " +
      "preventive maintenance, electrical fault finding and hydraulic systems. Actively seeking an " +
      "employer abroad who will sponsor a work visa.",
  };
}

/** A profile the user has imported a CV into is considered "analysed". */
export function profileHasCv(profile: CandidateProfile): boolean {
  return profile.fullName.trim().length > 0 && (profile.skills.length > 0 || profile.currentTitle.trim().length > 0);
}

/** 0–100 completion, weighted by the sections the matching engines actually read. */
export function profileCompletion(profile: CandidateProfile): number {
  const checks = [
    profile.fullName.trim().length > 0,
    profile.currentTitle.trim().length > 0,
    profile.yearsOfExperience != null,
    profile.skills.length >= 5,
    profile.languages.length > 0,
    profile.preferredCountries.length > 0,
    profile.summary.trim().length > 0,
    profile.certifications.length > 0,
  ];
  const done = checks.filter(Boolean).length;
  return Math.round((done / checks.length) * 100);
}
