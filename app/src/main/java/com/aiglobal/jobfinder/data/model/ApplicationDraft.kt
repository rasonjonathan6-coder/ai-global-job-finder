package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * Where a prepared application stands. The flow is deliberately **prepare → review → approve →
 * submit**, with submission as its own state so a crash between "the user approved" and "the
 * submission was recorded" can be recovered rather than lost.
 *
 * [APPROVED] is only ever set together with the exact content hash the user reviewed, so a
 * later edit to the CV, letter or answers flips the draft to [NEEDS_RESUBMISSION] instead of
 * letting a changed application be sent under an old approval.
 */
@Serializable
enum class DraftStatus(val label: String) {
    DRAFT("Draft"),
    READY_FOR_REVIEW("Ready for review"),
    APPROVED("Approved"),
    NEEDS_RESUBMISSION("Changed — re-approve"),
    SUBMISSION_PENDING("Submitting"),
    SUBMISSION_SIMULATED("Simulated (nothing sent)"),
    SUBMISSION_SENT("Sent"),
    SKIPPED("Skipped"),
    BLOCKED("Blocked"),
    ;

    /** True while the draft is waiting for the user, rather than the app. */
    val awaitsUser: Boolean get() = this == READY_FOR_REVIEW || this == NEEDS_RESUBMISSION
}

/** One question the employer asked, and the answer the user reviewed. */
@Immutable
@Serializable
data class DraftAnswer(
    val question: String,
    val answer: String,
)

/** The outcome of one submission attempt, recorded so the flow can resume after a restart. */
@Immutable
@Serializable
data class SubmissionAttempt(
    val atEpochMillis: Long,
    /** The content hash at the moment of the attempt, so a stale attempt is detectable. */
    val contentHash: String,
    val outcome: String,
    val detail: String,
    val simulated: Boolean,
)

/**
 * A fully prepared application the user reviews before anything is sent.
 *
 * Every field the user is asked to approve lives here, and [contentHash] is derived from
 * exactly those fields. That single invariant is what makes approval meaningful: if any
 * reviewed content changes, the hash changes, and the draft must be re-approved before it can
 * be submitted.
 */
@Immutable
@Serializable
data class ApplicationDraft(
    val id: String,
    val jobId: String,
    val jobTitle: String,
    val company: String,
    val country: String,
    val countryFlag: String,
    val sourceUrl: String,
    /** The submission route, keyed by [com.aiglobal.jobfinder.data.submissions.SubmissionChannel.name]. */
    val channelKey: String,
    /** The CV headline the user reviewed (tailored summary, never a fabricated rewrite). */
    val cvSummary: String,
    val coverLetterId: String? = null,
    /** The full cover-letter text the user reviewed. */
    val coverLetterText: String = "",
    val answers: List<DraftAnswer> = emptyList(),
    val documents: List<String> = emptyList(),
    val status: DraftStatus,
    /** Hash of the reviewed content right now. */
    val contentHash: String,
    /** Hash captured when the user last approved. Null until the first approval. */
    val approvedHash: String? = null,
    val approvedAtEpochMillis: Long? = null,
    val preparedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val attempts: List<SubmissionAttempt> = emptyList(),
    /** Plain-language explanation of the current status. */
    val reason: String = "",
) {
    /** True when the reviewed content matches the last approval and is ready to submit. */
    val isApproved: Boolean
        get() = approvedHash != null && approvedHash == contentHash &&
            (status == DraftStatus.APPROVED || status == DraftStatus.SUBMISSION_PENDING)

    /** True when the content changed after approval, so a fresh approval is required. */
    val needsResubmission: Boolean
        get() = approvedHash != null && approvedHash != contentHash

    /** True once a real send has been recorded (unreachable while real sending is off). */
    val wasSent: Boolean get() = status == DraftStatus.SUBMISSION_SENT

    /** True once a simulated submission has been recorded. */
    val wasSimulated: Boolean get() = status == DraftStatus.SUBMISSION_SIMULATED

    /** The most recent submission attempt, if any. */
    val lastAttempt: SubmissionAttempt? get() = attempts.lastOrNull()

    /** True while the app is mid-submission, so the UI can offer to resume. */
    val isMidSubmission: Boolean get() = status == DraftStatus.SUBMISSION_PENDING

    companion object {
        /**
         * Derives the canonical content hash from the reviewed fields. It is a pure function
         * of the content, so the same draft always yields the same hash and any change to a
         * reviewed field yields a different one.
         */
        fun hashOf(
            cvSummary: String,
            coverLetterText: String,
            answers: List<DraftAnswer>,
            documents: List<String>,
        ): String = DraftContentHash.of(cvSummary, coverLetterText, answers, documents)
    }
}

/**
 * A stable content hash over the reviewed parts of a draft. SHA-256 over a length-prefixed
 * encoding, so two different field arrangements can never collide by concatenation. Kept
 * pure and dependency-free so it is unit-testable off-device.
 */
object DraftContentHash {

    fun of(
        cvSummary: String,
        coverLetterText: String,
        answers: List<DraftAnswer>,
        documents: List<String>,
    ): String {
        val canonical = buildString {
            appendField(cvSummary)
            appendField(coverLetterText)
            answers.forEach { answer ->
                appendField(answer.question)
                appendField(answer.answer)
            }
            documents.forEach { appendField(it) }
        }
        return sha256(canonical)
    }

    private fun StringBuilder.appendField(value: String) {
        append(value.length).append(':').append(value).append('\n')
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
