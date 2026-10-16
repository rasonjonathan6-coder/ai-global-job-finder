package com.aiglobal.jobfinder.data.submissions

import com.aiglobal.jobfinder.data.model.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The persistent registry of applications, and the single point that decides whether a
 * posting may be applied to.
 *
 * Three guarantees, in order of importance:
 *
 * 1. One opening, one application. The registry is keyed by [SubmissionKey] (employer +
 *    title + country), not by the app's job id, so the same posting arriving from several
 *    boards — or re-imported after a restart — is recognised as the same application. A
 *    second attempt is refused with [SubmissionOutcome.BLOCKED_DUPLICATE] and the first
 *    receipt is left untouched.
 * 2. No concurrent sends. [submit] runs its decide-and-record step under a mutex, so two
 *    coroutines racing on the same opening cannot both pass the duplicate check; the loser
 *    gets [SubmissionOutcome.BLOCKED_CONCURRENT].
 * 3. Simulation only, for now. Unless sending has been explicitly activated **and** the
 *    channel is one the app can actually drive (see [SubmissionChannel.realSendSupported]),
 *    nothing is transmitted: the attempt is recorded as [SubmissionOutcome.DRY_RUN]. With
 *    no verified sender wired in, [SubmissionOutcome.SENT] is unreachable. The app never
 *    asks for, stores, or uses a mailbox password.
 *
 * The registry owns the rules; [SubmissionRegistryStore] owns the storage.
 */
class SubmissionRegistry(
    private val store: SubmissionRegistryStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * The seam for a real, verified sender. Null in this stage: without it a real send can
     * never be performed, only simulated. A future integration supplies one per channel and
     * is expected to have been verified against that platform first.
     */
    private val realSender: (suspend (SubmissionRequest) -> Boolean)? = null,
) {

    /** Every recorded attempt, newest last. Persisted across restarts. */
    val entries = store.entries

    /** Whether real sending has been explicitly activated. False by default. */
    val sendingEnabled = store.sendingEnabled

    /**
     * Serialises the decide-and-record step. Two coroutines cannot both observe "not yet
     * applied" for the same opening and both record a send.
     */
    private val gate = Mutex()

    suspend fun load() = store.load()

    // ------------------------------------------------------------------ queries

    /** The recorded attempt for this posting, if any — regardless of which source it came from. */
    fun entryFor(job: Job): SubmissionEntry? = entryForKey(SubmissionKey.of(job).value)

    fun entryForKey(key: String): SubmissionEntry? = entries.value.firstOrNull { it.key == key }

    /** True when a real application has already gone out for this opening. */
    fun isAlreadyApplied(job: Job): Boolean = entryFor(job)?.isCommitted == true

    /** True when this opening has any recorded attempt, simulated or real. */
    fun hasRecordedAttempt(job: Job): Boolean = entryFor(job) != null

    // ------------------------------------------------------------------ decisions

    /**
     * Decides whether [request] may proceed, without recording anything. Safe to call from
     * the UI to preview the outcome; [submit] re-runs the same rules under the lock.
     */
    fun evaluate(request: SubmissionRequest): SubmissionDecision {
        val key = SubmissionKey.of(request.job)
        val existing = entryForKey(key.value)
        val channel = request.channel

        if (existing != null) {
            return SubmissionDecision(
                key = key,
                outcome = SubmissionOutcome.BLOCKED_DUPLICATE,
                recorded = false,
                channel = existing.channel,
                reason = duplicateReason(existing, request.job.source),
            )
        }

        if (request.allowRealSend) {
            if (!store.sendingEnabled.value) {
                return SubmissionDecision(
                    key = key,
                    outcome = SubmissionOutcome.BLOCKED_NOT_ENABLED,
                    recorded = false,
                    channel = channel,
                    reason = "Real sending is not activated. Turn it on in settings before requesting a real send.",
                )
            }
            if (!channel.realSendSupported || realSender == null) {
                return SubmissionDecision(
                    key = key,
                    outcome = SubmissionOutcome.BLOCKED_INCOMPATIBLE,
                    recorded = false,
                    channel = channel,
                    reason = channel.compatibilityNote,
                )
            }
        }

        return SubmissionDecision(
            key = key,
            outcome = SubmissionOutcome.DRY_RUN,
            recorded = true,
            channel = channel,
            reason = "Simulated: the full application was walked through, but nothing was sent.",
        )
    }

    /**
     * Decides and, when allowed, records the attempt atomically. This is the only way an
     * application enters the registry, so the duplicate and concurrency rules cannot be
     * bypassed by a caller.
     */
    suspend fun submit(request: SubmissionRequest): SubmissionDecision = gate.withLock {
        val key = SubmissionKey.of(request.job)
        val existing = entryForKey(key.value)
        val now = clock()

        if (existing != null) {
            // A second attempt on the same opening: refuse, leave the first receipt intact.
            return@withLock SubmissionDecision(
                key = key,
                outcome = SubmissionOutcome.BLOCKED_DUPLICATE,
                recorded = false,
                channel = existing.channel,
                reason = duplicateReason(existing, request.job.source),
            )
        }

        val channel = request.channel
        if (request.allowRealSend && !store.sendingEnabled.value) {
            return@withLock SubmissionDecision(
                key = key,
                outcome = SubmissionOutcome.BLOCKED_NOT_ENABLED,
                recorded = false,
                channel = channel,
                reason = "Real sending is not activated. Turn it on in settings before requesting a real send.",
            )
        }
        if (request.allowRealSend && (!channel.realSendSupported || realSender == null)) {
            return@withLock SubmissionDecision(
                key = key,
                outcome = SubmissionOutcome.BLOCKED_INCOMPATIBLE,
                recorded = false,
                channel = channel,
                reason = channel.compatibilityNote,
            )
        }

        val reallySent = request.allowRealSend && realSender?.invoke(request) == true
        val outcome = if (reallySent) SubmissionOutcome.SENT else SubmissionOutcome.DRY_RUN
        val entry = SubmissionEntry(
            key = key.value,
            jobId = request.job.id,
            jobTitle = request.job.title,
            company = request.job.company,
            country = request.job.country,
            source = request.job.source,
            channel = channel,
            outcome = outcome,
            sent = reallySent,
            seenSources = listOf(request.job.source).filter { it.isNotBlank() }.distinct(),
            firstRequestedAtEpochMillis = now,
            updatedAtEpochMillis = now,
            reason = if (reallySent) {
                "Sent via ${channel.label}."
            } else {
                "Simulated: the full application was walked through, but nothing was sent."
            },
        )
        store.putEntry(entry)
        SubmissionDecision(
            key = key,
            outcome = outcome,
            recorded = true,
            channel = channel,
            reason = entry.reason,
        )
    }

    /**
     * Records that the same opening has also been seen from [source], on top of an existing
     * entry, without changing what was (or was not) sent. Used to explain a duplicate
     * clearly rather than to create a second record.
     */
    suspend fun noteSource(job: Job) {
        val key = SubmissionKey.of(job).value
        val existing = entryForKey(key) ?: return
        if (job.source.isBlank() || existing.seenSources.contains(job.source)) return
        store.putEntry(
            existing.copy(
                seenSources = existing.seenSources + job.source,
                updatedAtEpochMillis = clock(),
            ),
        )
    }

    // ------------------------------------------------------------------ policy

    /**
     * Explicitly activates or deactivates real sending. Activation alone never sends
     * anything — [SubmissionChannel.realSendSupported] must also hold for the channel — but
     * it is the deliberate switch the user must flip before any real send is considered.
     */
    suspend fun setSendingEnabled(enabled: Boolean) = store.setSendingEnabled(enabled)

    suspend fun forget(key: String) = store.removeEntry(key)

    private fun duplicateReason(existing: SubmissionEntry, source: String): String {
        val where = existing.seenSources.firstOrNull { it.isNotBlank() }
        val via = if (where != null) " (via $where)" else ""
        val sameSource = source.isBlank() || existing.seenSources.isEmpty() || existing.seenSources.contains(source)
        return when {
            existing.sent && !sameSource ->
                "You already applied to this opening via another source$via. A second application would be a duplicate."
            existing.sent ->
                "You already applied to this opening$via. A second application would be a duplicate."
            !sameSource ->
                "This opening is already in your application registry, seen from another source$via. It will not be submitted again."
            else ->
                "This opening is already in your application registry$via. It will not be submitted again."
        }
    }
}
