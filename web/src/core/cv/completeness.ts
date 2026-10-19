import type { CandidateProfile } from "../models";

/**
 * How complete the CV-derived profile is, weighted by how much each field carries the
 * matching and tailoring. Mirrors the Kotlin `CvCompleteness` in `data/cv/CvProfileMerger.kt`.
 *
 * The score is deliberately not "how much the user typed": it weights the fields the engines
 * actually rely on, so a profile missing its skills scores lower than one missing a phone
 * number. `missing` names the gaps so the UI can prompt for them rather than guess.
 */

export interface CompletenessHint {
  label: string;
  field: string;
}

export interface ProfileCompleteness {
  percent: number;
  missing: CompletenessHint[];
}

interface Rule {
  field: string;
  label: string;
  weight: number;
  isPresent: (profile: CandidateProfile) => boolean;
}

const RULES: Rule[] = [
  { field: "FULL_NAME", label: "Name", weight: 10, isPresent: (p) => p.fullName.trim().length > 0 },
  { field: "EMAIL", label: "Email", weight: 12, isPresent: (p) => p.email.trim().length > 0 },
  { field: "PHONE", label: "Phone", weight: 6, isPresent: (p) => p.phone.trim().length > 0 },
  { field: "LOCATION", label: "Location", weight: 6, isPresent: (p) => p.location.trim().length > 0 },
  { field: "CURRENT_TITLE", label: "Professional title", weight: 12, isPresent: (p) => p.currentTitle.trim().length > 0 },
  { field: "YEARS_OF_EXPERIENCE", label: "Years of experience", weight: 10, isPresent: (p) => p.yearsOfExperience != null },
  { field: "SKILLS", label: "Skills", weight: 16, isPresent: (p) => p.skills.length >= 3 },
  { field: "EDUCATION", label: "Education", weight: 10, isPresent: (p) => p.education.length > 0 },
  { field: "LANGUAGES", label: "Languages", weight: 8, isPresent: (p) => p.languages.length > 0 },
  { field: "PREFERRED_COUNTRIES", label: "Preferred countries", weight: 6, isPresent: (p) => p.preferredCountries.length > 0 },
  { field: "SUMMARY", label: "Professional summary", weight: 4, isPresent: (p) => p.summary.trim().length > 0 },
];

export function profileCompleteness(profile: CandidateProfile): ProfileCompleteness {
  const total = RULES.reduce((sum, rule) => sum + rule.weight, 0);
  const earned = RULES.filter((rule) => rule.isPresent(profile)).reduce((sum, rule) => sum + rule.weight, 0);
  const missing = RULES.filter((rule) => !rule.isPresent(profile)).map((rule) => ({
    label: rule.label,
    field: rule.field,
  }));
  return { percent: Math.trunc((earned * 100) / total), missing };
}
