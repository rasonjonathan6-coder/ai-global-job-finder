package com.aiglobal.jobfinder.data.applications

import com.aiglobal.jobfinder.data.model.ApplicationDraft
import com.aiglobal.jobfinder.data.model.CoverLetter
import com.aiglobal.jobfinder.data.model.DraftAnswer
import com.aiglobal.jobfinder.data.model.DraftStatus
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.SubmissionAttempt
import com.aiglobal.jobfinder.data.repository.JobRepository
import com.aiglobal.jobfinder.data.submissions.SubmissionChannel
import com.aiglobal.jobfinder.data.submissions.SubmissionOutcome
import com.aiglobal.jobfinder.data.submissions.SubmissionRegistry
import com.aiglobal.jobfinder.data.submissions.SubmissionRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Owns the **prepare → review → approve → submit** flow for prepared applications.
 *
 * The central rule is that nothing is submitted without a current, explicit approval of the
 * exact content being submitted. This is enforced by the content hash on
 * [ApplicationDraft]: [approve] captures the hash of what the user reviewed, [submit] refuses
 * to proceed unless the live hash still matches, and any edit changes the hash so the draft
 * must be approved again. A stale approval can therefore never send a changed application.
 *
 * The second rule is crash-safety. [submit] writes [DraftStatus.SUBMISSION_PENDING] *before*
 * it talks to [SubmissionRegistry], so a process death between "approved" and "recorded"
 * leaves a resumable marker rather than a silently lost submission. [resumePending] replays
 * any such draft on startup.
 *
 * The app never fabricates the submitted content: [prepare] copies the CV summary the user
 * reviewed and the exact cover-letter text, and every submitted field lives on the draft.
 * Real sending stays off — the registry only ever simulates until a verified sender exists.
 */
class ApplicationDraftCenter(
    private val repository: JobRepository,
    private val store: ApplicationDraftStore,
    private val submissionRegistry: SubmissionRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Serialises prepare/approve/submit so two actions cannot interleave on one draft. */
    private val gate = Mutex()

    val drafts: StateFlow<List<ApplicationDraft>> = store.drafts

    /** Drafts waiting on the user, newest first, for the approval queue. */
    val awaitingApproval: StateFlow<List<ApplicationDraft>> = drafts
        .map { list -> list.filter { it.status.awaitsUser }.sortedByDescending { it.preparedAtEpochMillis } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            store.load()
            resumePending()
        }
    }

    // ------------------------------------------------------------------ queries

    fun draft(id: String): ApplicationDraft? = drafts.value.firstOrNull { it.id == id }

    fun draftForJob(jobId: String): ApplicationDraft? = drafts.value.firstOrNull { it.jobId == jobId }

    /** True when this posting already has a prepared draft the user has not yet dealt with. */
    fun hasOpenDraft(jobId: String): Boolean =
        drafts.value.any { it.jobId == jobId && it.status.awaitsUser }

    // ------------------------------------------------------------------ prepare

    /**
     * Prepares (or refreshes) a draft for [job]. The content is copied verbatim from the
     * reviewed sources; the app never invents a field. Re-preparing an existing draft whose
     * reviewed content is unchanged is a no-op, so a refresh cannot needlessly revoke an
     * approval. Re-preparing with changed content updates the hash, which flips an approved
     * draft to [DraftStatus.NEEDS_RESUBMISSION].
     */
    suspend fun prepare(
        job: Job,
        cvSummary: String,
        coverLetter: CoverLetter?,
        answers: List<DraftAnswer> = emptyList(),
        documents: List<String> = emptyList(),
    ): ApplicationDraft = gate.withLock {
        val now = clock()
        val channel = SubmissionChannel.infer(job)
        val coverText = coverLetter?.asPlainText().orEmpty()
        val existing = draftForJob(job.id)

        val base = existing?.copy(
            jobTitle = job.title,
            company = job.company,
            country = job.country,
            countryFlag = job.countryFlag,
            sourceUrl = job.sourceUrl,
            channelKey = channel.name,
            cvSummary = cvSummary,
            coverLetterId = coverLetter?.id,
            coverLetterText = coverText,
            answers = answers,
            documents = documents,
            updatedAtEpochMillis = now,
        ) ?: ApplicationDraft(
            id = idFactory(),
            jobId = job.id,
            jobTitle = job.title,
            company = job.company,
            country = job.country,
            countryFlag = job.countryFlag,
            sourceUrl = job.sourceUrl,
            channelKey = channel.name,
            cvSummary = cvSummary,
            coverLetterId = coverLetter?.id,
            coverLetterText = coverText,
            answers = answers,
            documents = documents,
            status = DraftStatus.DRAFT,
            contentHash = "",
            preparedAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )

        val newHash = ApplicationDraft.hashOf(
            cvSummary = base.cvSummary,
            coverLetterText = base.coverLetterText,
            answers = base.answers,
            documents = base.documents,
        )
        val contentChanged = newHash != base.contentHash

        val status = when {
            base.status == DraftStatus.SUBMISSION_SENT || base.status == DraftStatus.SUBMISSION_SIMULATED ->
                base.status
            base.approvedHash != null && contentChanged -> DraftStatus.NEEDS_RESUBMISSION
            base.status == DraftStatus.APPROVED && !contentChanged -> DraftStatus.APPROVED
            else -> DraftStatus.READY_FOR_REVIEW
        }

        val reason = when (status) {
            DraftStatus.NEEDS_RESUBMISSION ->
                "Your CV, letter or answers changed since you approved this — review and approve again."
            DraftStatus.READY_FOR_REVIEW -> "Review the CV summary, cover letter and answers, then approve."
            else -> base.reason
        }

        val updated = base.copy(contentHash = newHash, status = status, reason = reason)
        store.putDraft(updated)
        updated
    }

    // ------------------------------------------------------------------ edit + approve

    /**
     * Applies a content edit to a draft, then re-derives the hash and status. An edit to an
     * approved draft always requires a fresh approval, because the reviewed content changed.
     */
    suspend fun edit(
        id: String,
        transform: (ApplicationDraft) -> ApplicationDraft,
    ): ApplicationDraft? = gate.withLock {
        val current = draft(id) ?: return@withLock null
        val now = clock()
        val edited = transform(current).copy(updatedAtEpochMillis = now)
        val newHash = ApplicationDraft.hashOf(
            cvSummary = edited.cvSummary,
            coverLetterText = edited.coverLetterText,
            answers = edited.answers,
            documents = edited.documents,
        )
        val status = when {
            edited.status == DraftStatus.SUBMISSION_SENT || edited.status == DraftStatus.SUBMISSION_SIMULATED ->
                edited.status
            edited.approvedHash != null && edited.approvedHash != newHash -> DraftStatus.NEEDS_RESUBMISSION
            else -> edited.status
        }
        val updated = edited.copy(
            contentHash = newHash,
            status = status,
            reason = if (status == DraftStatus.NEEDS_RESUBMISSION) {
                "Your changes need a fresh approval before this can be submitted."
            } else {
                edited.reason
            },
        )
        store.putDraft(updated)
        updated
    }

    /**
     * The user approves the exact content currently shown. The hash is captured at this
     * moment, which is what makes a later submission provably the approved content.
     */
    suspend fun approve(id: String): ApplicationDraft? = gate.withLock {
        val current = draft(id) ?: return@withLock null
        val now = clock()
        val liveHash = ApplicationDraft.hashOf(
            cvSummary = current.cvSummary,
            coverLetterText = current.coverLetterText,
            answers = current.answers,
            documents = current.documents,
        )
        val updated = current.copy(
            approvedHash = liveHash,
            approvedAtEpochMillis = now,
            status = DraftStatus.APPROVED,
            updatedAtEpochMillis = now,
            reason = "Approved. You can submit when you are ready.",
        )
        store.putDraft(updated)
        updated
    }

    suspend fun skip(id: String) {
        store.updateDraft(id) { draft ->
            draft.copy(status = DraftStatus.SKIPPED, updatedAtEpochMillis = clock(), reason = "Skipped.")
        }
    }

    suspend fun remove(id: String) = store.removeDraft(id)

    // ------------------------------------------------------------------ submit

    /**
     * Submits an approved draft. Two phases, with the pending marker persisted in between,
     * so an interrupted submission is resumable rather than lost. Returns the recorded draft,
     * or null when the id is unknown.
     */
    suspend fun submit(id: String): ApplicationDraft? {
        val current = draft(id) ?: return null
        if (!current.isApproved) {
            val blocked = current.copy(
                status = if (current.needsResubmission) DraftStatus.NEEDS_RESUBMISSION else current.status,
                reason = if (current.needsResubmission) {
                    "The content changed since you approved it — approve the current version first."
                } else {
                    "Approve this application before submitting it."
                },
                updatedAtEpochMillis = clock(),
            )
            store.putDraft(blocked)
            return blocked
        }

        // Phase 1: persist the intent. A crash after this leaves a resumable marker.
        val pending = current.copy(
            status = DraftStatus.SUBMISSION_PENDING,
            updatedAtEpochMillis = clock(),
            reason = "Submitting…",
        )
        store.putDraft(pending)

        // Phase 2: actually attempt it, under the gate so two submits cannot race.
        return gate.withLock { performSubmit(pending) }
    }

    private suspend fun performSubmit(draft: ApplicationDraft): ApplicationDraft {
        val now = clock()
        val job = repository.jobs.first().firstOrNull { it.id == draft.jobId }
        val hash = ApplicationDraft.hashOf(
            cvSummary = draft.cvSummary,
            coverLetterText = draft.coverLetterText,
            answers = draft.answers,
            documents = draft.documents,
        )
        // The content must still be the approved content at the moment of submission.
        if (draft.approvedHash == null || draft.approvedHash != hash) {
            val needsApproval = draft.copy(
                status = DraftStatus.NEEDS_RESUBMISSION,
                contentHash = hash,
                updatedAtEpochMillis = now,
                reason = "The content changed before submission — approve the current version again.",
            )
            store.putDraft(needsApproval)
            return needsApproval
        }

        if (job == null) {
            val attempt = SubmissionAttempt(
                atEpochMillis = now,
                contentHash = hash,
                outcome = "Job no longer available",
                detail = "This posting is no longer in your feed, so nothing could be submitted.",
                simulated = true,
            )
            val updated = draft.copy(
                status = DraftStatus.BLOCKED,
                attempts = draft.attempts + attempt,
                updatedAtEpochMillis = now,
                reason = attempt.detail,
            )
            store.putDraft(updated)
            return updated
        }

        val channel = runCatching { SubmissionChannel.valueOf(draft.channelKey) }
            .getOrDefault(SubmissionChannel.UNKNOWN)
        val decision = submissionRegistry.submit(
            SubmissionRequest(job = job, channel = channel, allowRealSend = false),
        )
        val simulated = decision.outcome != SubmissionOutcome.SENT
        val status = when (decision.outcome) {
            SubmissionOutcome.SENT -> DraftStatus.SUBMISSION_SENT
            SubmissionOutcome.BLOCKED_DUPLICATE,
            SubmissionOutcome.BLOCKED_CONCURRENT,
            SubmissionOutcome.BLOCKED_NOT_ENABLED,
            SubmissionOutcome.BLOCKED_INCOMPATIBLE,
            SubmissionOutcome.NEEDS_VERIFICATION,
            -> DraftStatus.BLOCKED
            else -> DraftStatus.SUBMISSION_SIMULATED
        }
        val attempt = SubmissionAttempt(
            atEpochMillis = now,
            contentHash = hash,
            outcome = decision.outcome.label,
            detail = decision.reason,
            simulated = simulated,
        )
        val updated = draft.copy(
            status = status,
            attempts = draft.attempts + attempt,
            updatedAtEpochMillis = now,
            reason = decision.reason,
        )
        store.putDraft(updated)
        return updated
    }

    /**
     * Replays any draft left mid-submission by a crash. Called on startup; safe to call
     * again because a draft that has moved past [DraftStatus.SUBMISSION_PENDING] is skipped.
     */
    suspend fun resumePending() {
        drafts.value
            .filter { it.status == DraftStatus.SUBMISSION_PENDING }
            .forEach { pending -> performSubmit(pending) }
    }
}
