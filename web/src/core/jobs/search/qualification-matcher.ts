import type { CandidateProfile, JobRequirements, QualificationGap } from "../../models";

/**
 * Compares what a posting asks for against what the CV actually shows, and reports the
 * gaps. Mirrors the Kotlin `QualificationMatcher`. A gap is never invented: when the
 * profile does not record the thing being asked about, the result is `met === null`
 * ("cannot tell"), not `false`.
 */
export function qualificationGaps(requirements: JobRequirements, profile: CandidateProfile): QualificationGap[] {
  const gaps: QualificationGap[] = [];

  if (requirements.minYears != null) {
    const minYears = requirements.minYears;
    const met = profile.yearsOfExperience != null ? profile.yearsOfExperience >= minYears : null;
    gaps.push({
      label: `At least ${minYears} years of experience`,
      evidence: `The posting states a minimum of ${minYears} years.`,
      met,
    });
  }

  for (const requirement of requirements.certifications) {
    gaps.push({ label: requirement.label, evidence: requirement.evidence, met: matchesCandidate(requirement.label, profile) });
  }

  for (const requirement of requirements.licences) {
    gaps.push({ label: requirement.label, evidence: requirement.evidence, met: licenceMatch(requirement.label, profile) });
  }

  const seen = new Set<string>();
  return gaps.filter((g) => (seen.has(g.label) ? false : (seen.add(g.label), true)));
}

function matchesCandidate(label: string, profile: CandidateProfile): boolean | null {
  const needles = label
    .toLowerCase()
    .split(/[ &/]+/)
    .filter((n) => n.length >= 3);
  if (needles.length === 0) return null;
  const haystack = [...profile.certifications.map((c) => c.name), ...profile.skills, profile.summary].join(" ").toLowerCase();
  // Only a positive match is asserted. A miss is reported as "cannot tell" rather than
  // "you do not have this", because the CV may simply not mention it.
  return needles.some((n) => haystack.includes(n)) ? true : null;
}

function licenceMatch(label: string, profile: CandidateProfile): boolean | null {
  const haystack = [
    ...profile.certifications.map((c) => c.name),
    ...profile.skills,
    profile.summary,
    profile.workPermitNotes,
  ]
    .join(" ")
    .toLowerCase();
  if (label.toLowerCase().includes("driv")) return haystack.includes("driv") ? true : null;
  if (
    label.toLowerCase().includes("work permit") ||
    label.toLowerCase().includes("right to work") ||
    label.toLowerCase().includes("authoris") ||
    label.toLowerCase().includes("authoriz")
  ) {
    return null;
  }
  return haystack.includes(label.toLowerCase()) ? true : null;
}
