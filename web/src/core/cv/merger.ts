import type { CandidateProfile } from "../models";
import { CandidateField, RelocationPreference } from "../models";

/**
 * Combines a fresh extraction with what is already on file. Direct port of the Kotlin
 * `CvProfileMerger` in `data/cv/analysis`.
 *
 * The rule is simple and deliberately conservative: a field the user edited by hand is never
 * overwritten, and an extraction that found nothing never erases a value the user already
 * had. That keeps re-importing a CV from destroying manual corrections.
 */
export function mergeCvProfile(
  extracted: CandidateProfile,
  existing: CandidateProfile,
  protectedFields: Set<CandidateField>,
): CandidateProfile {
  function choose<T>(field: CandidateField, fresh: T, previous: T, isPresent: (value: T) => boolean): T {
    if (protectedFields.has(field) && isPresent(previous)) return previous;
    if (isPresent(fresh)) return fresh;
    return previous;
  }

  const notBlank = (value: string): boolean => value.trim().length > 0;
  const notEmpty = (value: unknown[]): boolean => value.length > 0;
  const present = (value: unknown): boolean => value !== null && value !== undefined;

  const relocationPreference =
    protectedFields.has(CandidateField.RELOCATION) && existing.relocationPreference !== RelocationPreference.UNKNOWN
      ? existing.relocationPreference
      : extracted.relocationPreference !== RelocationPreference.UNKNOWN
        ? extracted.relocationPreference
        : existing.relocationPreference;

  return {
    fullName: choose(CandidateField.FULL_NAME, extracted.fullName, existing.fullName, notBlank),
    email: choose(CandidateField.EMAIL, extracted.email, existing.email, notBlank),
    phone: choose(CandidateField.PHONE, extracted.phone, existing.phone, notBlank),
    location: choose(CandidateField.LOCATION, extracted.location, existing.location, notBlank),
    currentTitle: choose(CandidateField.CURRENT_TITLE, extracted.currentTitle, existing.currentTitle, notBlank),
    previousTitles: choose(CandidateField.PREVIOUS_TITLES, extracted.previousTitles, existing.previousTitles, notEmpty),
    yearsOfExperience: choose(CandidateField.YEARS_OF_EXPERIENCE, extracted.yearsOfExperience, existing.yearsOfExperience, present),
    skills: choose(CandidateField.SKILLS, extracted.skills, existing.skills, notEmpty),
    industries: choose(CandidateField.INDUSTRIES, extracted.industries, existing.industries, notEmpty),
    education: choose(CandidateField.EDUCATION, extracted.education, existing.education, notEmpty),
    certifications: choose(CandidateField.CERTIFICATIONS, extracted.certifications, existing.certifications, notEmpty),
    languages: choose(CandidateField.LANGUAGES, extracted.languages, existing.languages, notEmpty),
    preferredCountries: choose(CandidateField.PREFERRED_COUNTRIES, extracted.preferredCountries, existing.preferredCountries, notEmpty),
    preferredRoles: choose(CandidateField.PREFERRED_ROLES, extracted.preferredRoles, existing.preferredRoles, notEmpty),
    salaryExpectationMin: choose<number | null>(CandidateField.SALARY_EXPECTATION, extracted.salaryExpectationMin, existing.salaryExpectationMin, present),
    salaryExpectationMax: choose<number | null>(CandidateField.SALARY_EXPECTATION, extracted.salaryExpectationMax, existing.salaryExpectationMax, present),
    salaryCurrency: choose<string | null>(CandidateField.SALARY_EXPECTATION, extracted.salaryCurrency, existing.salaryCurrency, present),
    relocationPreference,
    needsVisaSponsorship: choose(CandidateField.VISA, extracted.needsVisaSponsorship, existing.needsVisaSponsorship, present),
    workPermitNotes: choose(CandidateField.VISA, extracted.workPermitNotes, existing.workPermitNotes, notBlank),
    summary: choose(CandidateField.SUMMARY, extracted.summary, existing.summary, notBlank),
  };
}
