import type { CandidateProfile, CvSectionPresence } from "../models";
import { EMPTY_CV_SECTION_PRESENCE } from "../models";

/**
 * The single source of the "worth checking" notes. Direct port of the Kotlin
 * `CvAnalysisWarnings` in `data/cv/analysis`.
 *
 * Every note is derived from the *same* profile the UI renders, so a note can never
 * contradict a section the user can see: if education is present, no "no education" note is
 * produced. The optional `presence` only changes the *wording* ("a section was found but
 * nothing was readable" vs "no section was found"); it never decides whether a note appears.
 */
export function cvAnalysisWarnings(
  profile: CandidateProfile,
  presence: CvSectionPresence = EMPTY_CV_SECTION_PRESENCE,
): string[] {
  const warnings: string[] = [];

  if (profile.skills.length === 0) {
    warnings.push(
      presence.skills
        ? "A skills section was found but no known skills were recognised in it. Add yours by hand."
        : "No skills section was found. Add your skills to improve matching.",
    );
  }
  if (profile.education.length === 0) {
    warnings.push(
      presence.education
        ? "A training section was found but no degree or diploma could be read from it."
        : "No education or training section was found.",
    );
  }
  if (profile.languages.length === 0) {
    warnings.push(
      presence.languages
        ? "A languages section was found but no known language was recognised in it."
        : "No languages were listed. Many international roles require a language level.",
    );
  }
  if (profile.email.trim().length === 0) {
    warnings.push("No email address was found. Add one so applications can be sent.");
  }
  if (profile.phone.trim().length === 0) {
    warnings.push("No phone number was found.");
  }
  if (profile.fullName.trim().length === 0) {
    warnings.push("The candidate name could not be identified. Check the document header.");
  }

  return warnings;
}
