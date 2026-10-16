package com.aiglobal.jobfinder.data.autoapply

import com.aiglobal.jobfinder.data.applications.ApplicationCenter
import com.aiglobal.jobfinder.data.model.AutoApplyItem
import com.aiglobal.jobfinder.data.model.AutoApplyState
import com.aiglobal.jobfinder.data.model.AutoApplyStatus
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.repository.JobRepository
import com.aiglobal.jobfinder.data.submissions.SubmissionOutcome
import com.aiglobal.jobfinder.data.submissions.SubmissionRegistry
import com.aiglobal.jobfinder.data.submissions.SubmissionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Safe auto-apply: prepares a tailored CV and cover letter for every posting worth
 * applying to, then stops. Nothing is submitted without the user's explicit approval.
 *
 * The queue is rebuilt from the live feed on every refresh, so it never offers a job
 * that has disappeared or that the scam detector has since flagged.
 */
class AutoApplyCenter(
    private val repository: JobRepository,
    private val applicationCenter: ApplicationCenter,
    private val store: AutoApplyStore,
    private val candidateProfile: () -> CandidateProfile = { CandidateProfile() },
    /**
     * The application registry. When present, approving a draft records a simulated
     * attempt, so the same opening can never be approved — or, later, sent — twice.
     * Optional so the pure decision logic stays testable without a registry.
     */
    private val submissionRegistry: SubmissionRegistry? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isPreparing = MutableStateFlow(false)

    val state: StateFlow<AutoApplyState> =
        combine(store.enabled, store.threshold, store.items, _isPreparing) { enabled, threshold, items, preparing ->
            AutoApplyState(
                enabled = enabled,
                isPreparing = preparing,
                items = items.sortedByDescending { it.matchScore },
                threshold = threshold,
            )
        }.stateIn(scope, SharingStarted.Eagerly, AutoApplyState())

    init {
        scope.launch {
            store.load()
            // Turning the mode on prepares the first queue straight away.
            store.enabled.collect { enabled -> if (enabled) prepare() }
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        store.setEnabled(enabled)
        if (enabled) prepare() else store.replaceItems(emptyList())
    }

    suspend fun setThreshold(value: Int) {
        store.setThreshold(value)
        if (store.enabled.value) prepare()
    }

    /**
     * Rebuilds the queue and generates a cover letter for every draft. Letters are only
     * produced when the CV is usable; otherwise the item stays queued with a clear reason.
     */
    suspend fun prepare() {
        _isPreparing.value = true
        try {
            val jobs = repository.jobs.first()
            val profile = candidateProfile()
            // Honour the user's saved threshold, not the engine's default, so the queue
            // always matches the number the settings screen shows.
            val activeEngine = AutoApplyEngine(threshold = store.threshold.value)
            val queue = activeEngine.buildQueue(jobs, store.items.value, limit = AutoApplyEngine.DEFAULT_LIMIT)
            val prepared = queue.map { item ->
                if (item.status != AutoApplyStatus.DRAFT_READY) return@map item
                val job = jobs.firstOrNull { it.id == item.jobId } ?: return@map item
                if (profile.fullName.isBlank() && profile.skills.isEmpty()) {
                    item.copy(reason = "Import and analyse your CV first, so the letter can use your real details.")
                } else {
                    val letter = applicationCenter.generateCoverLetter(job, profile)
                    item.copy(coverLetterId = letter.id)
                }
            }
            store.replaceItems(prepared)
        } finally {
            _isPreparing.value = false
        }
    }

    /**
     * Marks a draft as approved. This is the only way an item leaves the "ready" state.
     *
     * Approval is a deliberate user tap and never sends anything. When a [SubmissionRegistry]
     * is wired in, it records the attempt as a simulation, which also means the same opening
     * cannot be approved twice: the second approval is refused by the registry and the item
     * says so plainly instead of pretending to have gone through again.
     */
    suspend fun approve(jobId: String) {
        val item = store.items.value.firstOrNull { it.jobId == jobId }
        val job = item?.let { candidate -> repository.jobs.first().firstOrNull { it.id == candidate.jobId } }
        if (item != null && job != null && submissionRegistry != null) {
            val decision = submissionRegistry.submit(SubmissionRequest(job = job, allowRealSend = false))
            val reason = when (decision.outcome) {
                SubmissionOutcome.DRY_RUN ->
                    "Approved — simulated. Nothing was sent; open the posting to send it yourself."
                SubmissionOutcome.BLOCKED_DUPLICATE, SubmissionOutcome.BLOCKED_CONCURRENT ->
                    "Already handled — not submitted again. ${decision.reason}"
                SubmissionOutcome.NEEDS_VERIFICATION ->
                    "Close to an entry you already have — nothing was sent. Confirm it in the submission history."
                else -> "Approved. Open the posting to send it yourself."
            }
            store.updateItem(jobId) { it.copy(status = AutoApplyStatus.APPROVED, reason = reason) }
            return
        }
        store.updateItem(jobId) {
            it.copy(status = AutoApplyStatus.APPROVED, reason = "Approved. Open the posting to send it yourself.")
        }
    }

    suspend fun skip(jobId: String) = store.updateItem(jobId) {
        it.copy(status = AutoApplyStatus.SKIPPED, reason = "You skipped this one.")
    }
}
