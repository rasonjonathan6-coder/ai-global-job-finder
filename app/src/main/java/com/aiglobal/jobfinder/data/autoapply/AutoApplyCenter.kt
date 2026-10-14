package com.aiglobal.jobfinder.data.autoapply

import com.aiglobal.jobfinder.data.applications.ApplicationCenter
import com.aiglobal.jobfinder.data.model.AutoApplyItem
import com.aiglobal.jobfinder.data.model.AutoApplyState
import com.aiglobal.jobfinder.data.model.AutoApplyStatus
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.repository.JobRepository
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
    private val engine: AutoApplyEngine = AutoApplyEngine(),
    private val candidateProfile: () -> CandidateProfile = { CandidateProfile() },
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
            val queue = engine.buildQueue(jobs, store.items.value, limit = AutoApplyEngine.DEFAULT_LIMIT)
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

    /** Marks a draft as approved. This is the only way an item leaves the "ready" state. */
    suspend fun approve(jobId: String) = store.updateItem(jobId) {
        it.copy(status = AutoApplyStatus.APPROVED, reason = "Approved. Open the posting to send it yourself.")
    }

    suspend fun skip(jobId: String) = store.updateItem(jobId) {
        it.copy(status = AutoApplyStatus.SKIPPED, reason = "You skipped this one.")
    }
}
