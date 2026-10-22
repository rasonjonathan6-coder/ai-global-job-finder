package com.aiglobal.jobfinder.data.jobs.availability

import com.aiglobal.jobfinder.data.model.AvailabilityEvidence
import com.aiglobal.jobfinder.data.model.AvailabilityResult
import com.aiglobal.jobfinder.data.model.AvailabilitySignalType
import com.aiglobal.jobfinder.data.model.AvailabilityStatus
import com.aiglobal.jobfinder.data.model.Job

/**
 * Reads the two indicators that are *always* available without touching the network: the
 * source's own "still accepting applicants" flag and any employer deadline the source
 * published. Everything else — reaching the application link — is optional and lives in
 * [AvailabilityVerifier].
 *
 * The rules that keep this honest, in order of importance:
 *  - A source flag of `false` is [AvailabilityStatus.EXPIRED]; the source itself said so.
 *  - A source flag of `true` is [AvailabilityStatus.OPEN]; the source itself said so.
 *  - A published deadline already behind us is [AvailabilityStatus.EXPIRED], even when the
 *    source's flag is silent, because the employer named the date.
 *  - Silence is [AvailabilityStatus.UNCONFIRMED]. A posting being young is not proof it is
 *    open, and being old is not proof it is closed.
 *
 * Pure and clock-injected, so it runs on the JVM and is testable without a device or network.
 * It never writes a date and never invents a signal.
 */
class OfferAvailabilityEngine {

    fun assess(job: Job, now: Long = System.currentTimeMillis()): AvailabilityResult {
        val evidence = buildList {
            job.openToApplicants?.let { add(sourceFlagEvidence(job, it)) }
            deadlineEvidence(job, now)?.let { add(it) }
        }

        // The flag is decisive when it is present: the source's own word outranks everything.
        val status = when {
            job.openToApplicants == true -> AvailabilityStatus.OPEN
            job.openToApplicants == false -> AvailabilityStatus.EXPIRED
            job.expiresAtEpochMillis != null && job.expiresAtEpochMillis <= now -> AvailabilityStatus.EXPIRED
            else -> AvailabilityStatus.UNCONFIRMED
        }

        return AvailabilityResult(
            status = status,
            evidence = evidence,
            lastCheckedAtEpochMillis = now,
        )
    }

    /** The source's own flag, carried as [AvailabilitySignalType.SOURCE_FLAG]. */
    private fun sourceFlagEvidence(job: Job, open: Boolean): AvailabilityEvidence = AvailabilityEvidence(
        type = AvailabilitySignalType.SOURCE_FLAG,
        detail = if (open) {
            "The source itself reports this posting is still accepting applicants."
        } else {
            "The source itself reports this posting is no longer accepting applicants."
        },
        sourceUrl = job.sourceUrl,
    )

    /**
     * The employer's own deadline, carried as [AvailabilitySignalType.EMPLOYER_DEADLINE].
     * A future deadline is recorded as a fact but does not, on its own, confirm the posting
     * is open — a stated closing date says nothing about whether it opened for applicants —
     * so only a past deadline changes the verdict.
     */
    private fun deadlineEvidence(job: Job, now: Long): AvailabilityEvidence? {
        val expires = job.expiresAtEpochMillis ?: return null
        return if (expires <= now) {
            AvailabilityEvidence(
                type = AvailabilitySignalType.EMPLOYER_DEADLINE,
                detail = "The employer stated applications close ${dateOf(expires)}, which has passed.",
                sourceUrl = job.sourceUrl,
            )
        } else {
            AvailabilityEvidence(
                type = AvailabilitySignalType.EMPLOYER_DEADLINE,
                detail = "The employer states applications close ${dateOf(expires)}.",
                sourceUrl = job.sourceUrl,
            )
        }
    }

    /** A stated calendar date, rendered in UTC so the same instant reads the same everywhere. */
    private fun dateOf(epochMillis: Long): String =
        java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))
}
