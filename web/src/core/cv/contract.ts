import type { CandidateProfile } from "../models";
import { CandidateField, emptyCandidateProfile } from "../models";
import { HeuristicCvAnalyzer, type CvAnalysisResult } from "./analyzer";

export type { CvAnalysisResult } from "./analyzer";

/**
 * The shareable half of CV handling: the deterministic analysis core, behind one pure,
 * platform-independent entry point. Direct port of the Kotlin `CvAnalysisContract`.
 *
 * CV handling is split in two along the platform seam:
 *
 * - **Platform-bound**: reading the uploaded file, decoding a PDF/DOCX/DOC, and the optional
 *   cloud pass. These touch the DOM, the filesystem and the network, so they cannot be shared.
 * - **Shareable** (this folder): normalising extracted text, the skill/language lexicon,
 *   section segmentation, the deterministic analyser, the profile merger and the "worth
 *   checking" notes. This half is a pure function of its inputs — no clock, no disk, no
 *   network — so it mirrors the Android core exactly and the golden fixture proves it.
 *
 * Everything here treats the CV as private. The core never logs, prints or transmits the
 * document text: it takes a string and returns a result. Keeping the CV in the browser is a
 * product promise, so this boundary is deliberate, not incidental.
 */

/**
 * Schema version of the analysis contract. Bump it when the shape of `CvAnalysisResult`
 * changes; the parity fixture records it so a stale port is caught rather than silently
 * mismatched.
 */
export const CV_ANALYSIS_SCHEMA_VERSION = 1;

/** Analyses `text` against an empty profile — the plain "import a CV" path. */
export function analyzeCv(text: string): CvAnalysisResult {
  return analyzeCvInto(text, emptyCandidateProfile(), new Set());
}

/** Analyses `text`, merging into `existing` while preserving `protectedFields`. */
export function analyzeCvInto(
  text: string,
  existing: CandidateProfile,
  protectedFields: Set<CandidateField>,
): CvAnalysisResult {
  return new HeuristicCvAnalyzer().analyze(text, existing, protectedFields);
}
