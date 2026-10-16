package com.aiglobal.jobfinder.data.submissions

import com.aiglobal.jobfinder.data.model.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The persistent registry of applications, and the single point that decides whether a
 * posting may be applied to.
 *
 * Guarantees, in order of importance:
 *
 * 1. One opening, one application. Identity is layered (see [SubmissionIdentity]): a shared
 *    source record id, a shared canonical URL, or an identical normalised fingerprint all
 *    mean the same posting, so a re-post from another board or after a restart is refused
 *    with [SubmissionOutcome.BLOCKED_DUPLICATE] and the first receipt is left untouched.
 * 2. No concurrent sends. [submit] decides, checks and records under a single mutex, so two
 *    coroutines racing on the same opening cannot both pass the check; the loser is refused.
 * 3. Ambiguity is never guessed. When a new posting is only *close* to an existing one — the
 *    same country with a very similar employer and title, but no shared id or URL — nothing
 *    is sent and nothing is merged: it is parked as [SubmissionOutcome.NEEDS_VERIFICATION]
 *    until the user says whether it is the same opening.
 * 4. Simulation only, for now. Unless sending has been explicitly activated **and** the
 *    channel is one the app can actually drive (see [SubmissionChannel.realSendSupported]),
 *    nothing is transmitted: the attempt is recorded as [SubmissionOutcome.DRY_RUN]. With no
 *    verified sender wired in, [SubmissionOutcome.SENT] is unreachable. The app never asks
 *    for, stores, or uses a mailbox password.
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
     * Serialises decide-and-record. Two coroutines cannot both observe "not yet applied" for
     * the same opening and both record a send.
     */
    private val gate = Mutex()

    /**
     * Indexes every recorded entry by the identity signals it carries (record id, canonical
     * URL, fingerprint), each mapped to the entry's registry key. Rebuilt from the persisted
     * entries so it survives a restart.
     */
    private val identityIndex = HashMap<String, String>()

    suspend fun load() {
        store.load()
        rebuildIndex()
    }

    // ------------------------------------------------------------------ queries

    /** The recorded attempt for this posting, if any — regardless of which source it came from. */
    fun entryFor(job: Job): SubmissionEntry? {
        val identity = SubmissionIdentity.of(job)
        return matchedKey(identity)?.let { key -> entries.value.firstOrNull { it.key == key } }
    }

    fun entryForKey(key: String): SubmissionEntry? = entries.value.firstOrNull { it.key == key }

    /** True when a real application has already gone out for this opening. */
    fun isAlreadyApplied(job: Job): Boolean = entryFor(job)?.isCommitted == true

    /** True when this opening has any recorded attempt, simulated or real. */
    fun hasRecordedAttempt(job: Job): Boolean = entryFor(job) != null

    /** Attempts parked for the user to confirm whether they are the same posting. */
    fun pendingVerifications(): List<SubmissionEntry> = entries.value.filter { it.awaitsVerification }

    // ------------------------------------------------------------------ decisions

    /**
     * Decides whether [request] may proceed, without recording anything. Safe to call from
     * the UI to preview the outcome; [submit] re-runs the same rules under the lock.
     */
    fun evaluate(request: SubmissionRequest): SubmissionDecision {
        val identity = SubmissionIdentity.of(request.job)
        val key = SubmissionKey.of(request.job)
        val channel = request.channel

        matchedEntry(identity)?.let { existing ->
            // An entry already parked for verification is reported as still pending, not as a
            // duplicate, so the user is not told they "already applied" to something unresolved.
            return if (existing.awaitsVerification) {
                SubmissionDecision(
                    key = key,
                    outcome = SubmissionOutcome.NEEDS_VERIFICATION,
                    recorded = false,
                    channel = existing.channel,
                    reason = existing.reason,
                )
            } else {
                SubmissionDecision(
                    key = key,
                    outcome = SubmissionOutcome.BLOCKED_DUPLICATE,
                    recorded = false,
                    channel = existing.channel,
                    reason = duplicateReason(existing, request.job.source),
                )
            }
        }
        ambiguousEntry(identity)?.let { close ->
            return SubmissionDecision(
                key = key,
                outcome = SubmissionOutcome.NEEDS_VERIFICATION,
                recorded = false,
                channel = channel,
                reason = verificationReason(close, request.job.source),
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
     * application enters the registry, so the duplicate, ambiguity and concurrency rules
     * cannot be bypassed by a caller.
     */
    suspend fun submit(request: SubmissionRequest): SubmissionDecision = gate.withLock {
        val identity = SubmissionIdentity.of(request.job)
        val key = SubmissionKey.of(request.job)
        val now = clock()

        matchedEntry(identity)?.let { existing ->
            // A second attempt on the same opening: refuse, leave the first receipt intact.
            // An entry already parked for verification stays pending rather than becoming a
            // duplicate, so the same unresolved posting is never re-parked.
            return@withLock if (existing.awaitsVerification) {
                SubmissionDecision(
                    key = key,
                    outcome = SubmissionOutcome.NEEDS_VERIFICATION,
                    recorded = false,
                    channel = existing.channel,
                    reason = existing.reason,
                )
            } else {
                SubmissionDecision(
                    key = key,
                    outcome = SubmissionOutcome.BLOCKED_DUPLICATE,
                    recorded = false,
                    channel = existing.channel,
                    reason = duplicateReason(existing, request.job.source),
                )
            }
        }
        ambiguousEntry(identity)?.let { close ->
            // Close but not provably the same: record a parked attempt for the user to settle.
            val entry = buildEntry(request, identity, now, outcome = SubmissionOutcome.NEEDS_VERIFICATION)
                .copy(pendingReview = close.key, reason = verificationReason(close, request.job.source))
            store.putEntry(entry)
            indexEntry(entry, identity)
            return@withLock SubmissionDecision(
                key = key,
                outcome = SubmissionOutcome.NEEDS_VERIFICATION,
                recorded = true,
                channel = request.channel,
                reason = entry.reason,
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
        val entry = buildEntry(request, identity, now, outcome = outcome).copy(
            sent = reallySent,
            reason = if (reallySent) {
                "Sent via ${channel.label}."
            } else {
                "Simulated: the full application was walked through, but nothing was sent."
            },
        )
        store.putEntry(entry)
        indexEntry(entry, identity)
        SubmissionDecision(
            key = key,
            outcome = outcome,
            recorded = true,
            channel = channel,
            reason = entry.reason,
        )
    }

    // ------------------------------------------------------------------ verification

    /**
     * The user confirms that the parked attempt *is* the same posting as [comparedToKey].
     * The parked entry is folded into the earlier one and the new attempt is refused as a
     * duplicate, so no second application is ever recorded.
     */
    suspend fun confirmSame(key: String, comparedToKey: String): SubmissionDecision = gate.withLock {
        val parked = entries.value.firstOrNull { it.key == key } ?: return@withLock missing(key)
        val existing = entries.value.firstOrNull { it.key == comparedToKey } ?: return@withLock missing(key)

        store.putEntry(
            existing.copy(
                seenSources = (existing.seenSources + parked.seenSources + parked.source)
                    .filter { it.isNotBlank() }
                    .distinct(),
                // Keep the parked posting's signals so the same opening is recognised later.
                aliases = (existing.aliases + parked.providerRecordId + parked.canonicalUrl)
                    .filter { it.isNotBlank() }
                    .distinct(),
                updatedAtEpochMillis = clock(),
            ),
        )
        store.removeEntry(key)
        rebuildIndex()
        SubmissionDecision(
            key = SubmissionKey.of(parked.company, parked.jobTitle, parked.country),
            outcome = SubmissionOutcome.BLOCKED_DUPLICATE,
            recorded = false,
            channel = parked.channel,
            reason = "Confirmed the same posting as ${existing.jobTitle} at ${existing.company}. Not submitted again.",
        )
    }

    /**
     * The user confirms that the parked attempt is a *different* posting. It is promoted to
     * a normal simulated attempt, and its identity signals are indexed so it can never be
     * recorded twice.
     */
    suspend fun confirmDistinct(key: String): SubmissionDecision = gate.withLock {
        val parked = entries.value.firstOrNull { it.key == key } ?: return@withLock missing(key)
        val promoted = parked.copy(
            outcome = SubmissionOutcome.DRY_RUN,
            reason = "Confirmed a different posting. Simulated: nothing was sent.",
            pendingReview = "",
            updatedAtEpochMillis = clock(),
        )
        store.putEntry(promoted)
        indexEntry(promoted, identityOf(promoted))
        SubmissionDecision(
            key = SubmissionKey.of(parked.company, parked.jobTitle, parked.country),
            outcome = SubmissionOutcome.DRY_RUN,
            recorded = true,
            channel = parked.channel,
            reason = promoted.reason,
        )
    }

    /**
     * Records that the same opening has also been seen from [source], on top of an existing
     * entry, without changing what was (or was not) sent. Used to explain a duplicate
     * clearly rather than to create a second record.
     */
    suspend fun noteSource(job: Job) {
        val entry = entryFor(job) ?: return
        if (job.source.isBlank() || entry.seenSources.contains(job.source)) return
        store.putEntry(
            entry.copy(
                seenSources = entry.seenSources + job.source,
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

    suspend fun forget(key: String) {
        store.removeEntry(key)
        rebuildIndex()
    }

    // ------------------------------------------------------------------ internals

    private fun buildEntry(
        request: SubmissionRequest,
        identity: SubmissionIdentity,
        now: Long,
        outcome: SubmissionOutcome,
    ): SubmissionEntry = SubmissionEntry(
        key = SubmissionKey.of(request.job).value,
        jobId = request.job.id,
        jobTitle = request.job.title,
        company = request.job.company,
        country = request.job.country,
        providerRecordId = identity.providerRecordId,
        canonicalUrl = identity.canonicalUrl,
        source = request.job.source,
        channel = request.channel,
        outcome = outcome,
        sent = false,
        seenSources = listOf(request.job.source).filter { it.isNotBlank() }.distinct(),
        firstRequestedAtEpochMillis = now,
        updatedAtEpochMillis = now,
        reason = "",
    )

    private fun identityOf(entry: SubmissionEntry): SubmissionIdentity = SubmissionIdentity(
        providerRecordId = entry.providerRecordId,
        canonicalUrl = entry.canonicalUrl,
        company = SubmissionKey.normalise(entry.company.ifBlank { entry.jobTitle }),
        title = SubmissionKey.normalise(entry.jobTitle),
        country = SubmissionKey.normalise(entry.country),
    )

    private fun rebuildIndex() {
        identityIndex.clear()
        entries.value.forEach { entry -> indexEntry(entry, identityOf(entry)) }
    }

    /** Registers every non-blank identity signal of [entry] against its registry key. */
    private fun indexEntry(entry: SubmissionEntry, identity: SubmissionIdentity) {
        if (identity.providerRecordId.isNotBlank()) identityIndex[identity.providerRecordId] = entry.key
        if (identity.canonicalUrl.isNotBlank()) identityIndex[identity.canonicalUrl] = entry.key
        if (identity.fingerprint.isNotBlank()) identityIndex[identity.fingerprint] = entry.key
        entry.aliases.filter { it.isNotBlank() }.forEach { alias -> identityIndex[alias] = entry.key }
    }

    /** The key of the entry this identity is provably the same as, if any. */
    private fun matchedKey(identity: SubmissionIdentity): String? {
        val signals = listOfNotNull(
            identity.providerRecordId.takeIf { it.isNotBlank() },
            identity.canonicalUrl.takeIf { it.isNotBlank() },
            identity.fingerprint.takeIf { it.isNotBlank() },
        )
        return signals.firstNotNullOfOrNull { signal ->
            identityIndex[signal]?.takeIf { key -> entries.value.any { it.key == key } }
        }
    }

    private fun matchedEntry(identity: SubmissionIdentity): SubmissionEntry? =
        matchedKey(identity)?.let { key -> entries.value.firstOrNull { it.key == key } }

    /**
     * An existing entry this identity is only *close* to. Only entries that are not already
     * parked for verification are considered, so ambiguity does not cascade.
     */
    private fun ambiguousEntry(identity: SubmissionIdentity): SubmissionEntry? =
        entries.value.firstOrNull { entry ->
            if (entry.awaitsVerification) return@firstOrNull false
            identity.relationTo(identityOf(entry)) == IdentityRelation.AMBIGUOUS
        }

    private fun missing(key: String) = SubmissionDecision(
        key = SubmissionKey("", "", ""),
        outcome = SubmissionOutcome.NEEDS_VERIFICATION,
        recorded = false,
        channel = SubmissionChannel.UNKNOWN,
        reason = "No pending verification found for $key.",
    )

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

    private fun verificationReason(close: SubmissionEntry, source: String): String {
        val where = close.seenSources.firstOrNull { it.isNotBlank() }
        val via = if (where != null) " (from $where)" else ""
        val incoming = if (source.isNotBlank()) " from $source" else ""
        return "This looks close to an existing entry — ${close.jobTitle} at ${close.company}$via — but the " +
            "employer or title differs slightly. Nothing was sent$incoming. Confirm whether it is the same posting."
    }
}
