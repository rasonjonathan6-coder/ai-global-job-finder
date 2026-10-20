package com.aiglobal.jobfinder.data.cv.analysis

import com.aiglobal.jobfinder.data.model.CandidateField
import com.aiglobal.jobfinder.data.model.CandidateProfile

/**
 * The shareable half of CV handling: the deterministic analysis core, behind one pure,
 * platform-independent entry point.
 *
 * CV handling is split in two along the platform seam:
 *
 * - **Platform-bound** (`data/cv`, *outside* this package): reading the file, decoding a
 *   PDF/DOCX/DOC, and the optional NVIDIA pass. These touch `Context`, `Uri`, the filesystem
 *   and the network, so they cannot be shared.
 * - **Shareable** (this package): normalising extracted text, the skill/language lexicon,
 *   section segmentation, the deterministic [HeuristicCvAnalyzer], the profile merger, the
 *   completeness scorer and the "worth checking" notes. This half is a pure function of its
 *   inputs — no clock, no disk, no network — so the web edition can port it verbatim and the
 *   golden parity fixture can prove the two agree.
 *
 * Everything here treats the CV as private. The core never logs, prints or transmits the
 * document text: it takes a `String` and returns a [CvAnalysisResult]. Keeping the CV on the
 * device is a product promise, so this boundary is deliberate, not incidental.
 */
object CvAnalysisContract {

    /**
     * Schema version of the analysis contract. Bump it when the shape of [CvAnalysisResult]
     * changes; the parity fixture records it so a stale web port is caught rather than
     * silently mismatched.
     */
    const val SCHEMA_VERSION: Int = 1

    /** Analyses [text] against an empty profile — the plain "import a CV" path. */
    suspend fun analyze(text: String): CvAnalysisResult =
        analyze(text, CandidateProfile(), emptySet())

    /**
     * Analyses [text], merging into [existing] while preserving any field named in
     * [protectedFields] (the ones the user edited by hand).
     */
    suspend fun analyze(
        text: String,
        existing: CandidateProfile,
        protectedFields: Set<CandidateField>,
    ): CvAnalysisResult = HeuristicCvAnalyzer().analyze(text, existing, protectedFields)
}
