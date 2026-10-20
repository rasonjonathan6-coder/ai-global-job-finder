/**
 * Thresholds the analysis core shares with the extraction layer. Direct port of the Kotlin
 * `CvAnalysisLimits` in `data/cv/analysis`.
 *
 * Defined here rather than in the parse layer so the analysis core stays free of any
 * dependency on platform-specific file handling: the parsers read this constant, not the
 * other way around.
 */
export const CV_ANALYSIS_LIMITS = {
  /** Below this many non-whitespace characters there is not enough text to analyse. */
  MIN_USABLE_CHARACTERS: 60,
} as const;
