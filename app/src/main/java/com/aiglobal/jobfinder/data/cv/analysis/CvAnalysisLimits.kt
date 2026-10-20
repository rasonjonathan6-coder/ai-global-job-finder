package com.aiglobal.jobfinder.data.cv.analysis

/**
 * Thresholds the analysis core shares with the extraction layer.
 *
 * Defined here rather than in the parse layer so the analysis core stays free of any
 * dependency on platform-specific file handling: the parsers read this constant, not the
 * other way around. [com.aiglobal.jobfinder.data.cv.CvParseResult] re-exposes it as its
 * own `MIN_USABLE_CHARACTERS` so existing call sites keep working.
 */
object CvAnalysisLimits {
    /** Below this many non-whitespace characters there is not enough text to analyse. */
    const val MIN_USABLE_CHARACTERS = 60
}
