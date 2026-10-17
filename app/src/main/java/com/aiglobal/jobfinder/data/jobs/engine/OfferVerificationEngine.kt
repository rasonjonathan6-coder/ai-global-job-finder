package com.aiglobal.jobfinder.data.jobs.engine

import com.aiglobal.jobfinder.data.apply.ApplicationDestination
import com.aiglobal.jobfinder.data.apply.ApplicationUrlInspector
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.EvidenceStrength
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobBenefitTypes
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.OfferVerification
import com.aiglobal.jobfinder.data.model.VerificationAspect
import com.aiglobal.jobfinder.data.model.VerificationFact
import com.aiglobal.jobfinder.data.model.VerificationState
import com.aiglobal.jobfinder.data.model.VisaSupport

/**
 * Builds the transparent verification read for one posting.
 *
 * It answers four separate questions, each with its own state, because conflating them is
 * how a candidate ends up trusting a dead listing:
 *  - **Is the vacancy still taking applicants?** Only the source's own flag can confirm this;
 *    a posting being young is not proof it is open, and being old is not proof it is closed.
 *  - **Is the application link usable?** Delegated to [ApplicationUrlInspector], which already
 *    knows a real employer URL from a broken one. A working URL proves nothing about eligibility.
 *  - **Is there a route in for a Madagascar applicant?** Read from the employer's documented
 *    visa/relocation evidence. Silence stays [VerificationState.UNKNOWN]; only an explicit
 *    refusal becomes [VerificationState.NOT_OFFERED].
 *  - **How recent is this check?** The retrieval timestamp is classified so a stale read is
 *    never presented as a fresh one.
 *
 * Pure and clock-injected, so it runs on the JVM and is testable without a device or network.
 * It reads recorded facts, never invents them: a field the source did not send stays unknown.
 */
class OfferVerificationEngine(
    private val linkInspector: ApplicationUrlInspector = ApplicationUrlInspector(),
    /** Retrievals younger than this are treated as current. */
    private val recentDays: Int = DEFAULT_RECENT_DAYS,
    /** Retrievals older than this are no longer relied on. */
    private val staleDays: Int = DEFAULT_STALE_DAYS,
) {

    fun assess(job: Job, now: Long = System.currentTimeMillis()): OfferVerification {
        val activity = activityState(job)
        val linkState = linkState(job)
        val visa = visaState(job)
        val relocation = relocationState(job)
        val freshness = freshnessState(job, now)

        val facts = buildList {
            add(sourceFact(job))
            add(activityFact(job, activity))
            add(linkFact(job, linkState))
            add(visaFact(job, visa))
            add(relocationFact(job, relocation))
            add(freshnessFact(job, freshness, now))
        }

        val caveats = caveatsFor(job, activity, linkState, visa, freshness)

        return OfferVerification(
            activity = activity,
            link = linkState,
            visa = visa,
            relocation = relocation,
            lastRetrievedAtEpochMillis = job.retrievedAtEpochMillis,
            freshness = freshness,
            facts = facts,
            caveats = caveats,
            summary = summaryFor(activity, linkState, visa, freshness),
        )
    }

    // ---- Activity: only the source's own flag can confirm or deny it -----------

    private fun activityState(job: Job): VerificationState = when (job.openToApplicants) {
        true -> VerificationState.CONFIRMED
        false -> VerificationState.EXPIRED
        // No flag: age is not proof of expiry, so the honest answer is "unknown".
        null -> VerificationState.UNKNOWN
    }

    // ---- Link: present and plausible, or positively unusable -------------------

    private fun linkState(job: Job): VerificationState {
        val inspection = linkInspector.inspect(job)
        return when {
            !inspection.canOpen || inspection.destination == ApplicationDestination.INVALID ->
                VerificationState.INACCESSIBLE
            inspection.warnings.isNotEmpty() -> VerificationState.POSSIBLE
            else -> VerificationState.CONFIRMED
        }
    }

    // ---- Visa: documented route in, never assumed from silence -----------------

    private fun visaState(job: Job): VerificationState {
        val sponsorship = job.benefitEvidence.firstOrNull {
            it.type.key == JobBenefitTypes.VISA_SPONSORSHIP.key
        }
        return when (sponsorship?.status) {
            BenefitStatus.CONFIRMED -> VerificationState.CONFIRMED
            BenefitStatus.NEEDS_VERIFICATION -> VerificationState.POSSIBLE
            BenefitStatus.NOT_OFFERED -> VerificationState.NOT_OFFERED
            else -> when {
                job.eligibility == MadagascarEligibility.RESTRICTED -> VerificationState.NOT_OFFERED
                job.eligibility == MadagascarEligibility.ELIGIBLE -> VerificationState.POSSIBLE
                job.eligibility == MadagascarEligibility.VISA_SPONSORED -> VerificationState.CONFIRMED
                else -> VerificationState.UNKNOWN
            }
        }
    }

    // ---- Relocation: a separate axis from the visa -----------------------------

    private fun relocationState(job: Job): VerificationState {
        val relocation = job.benefitEvidence.firstOrNull {
            it.type.key == JobBenefitTypes.RELOCATION_SUPPORT.key
        }
        return when (relocation?.status) {
            BenefitStatus.CONFIRMED -> VerificationState.CONFIRMED
            BenefitStatus.NEEDS_VERIFICATION -> VerificationState.POSSIBLE
            BenefitStatus.NOT_OFFERED -> VerificationState.NOT_OFFERED
            else -> if (job.visaSupport == VisaSupport.RELOCATION_PACKAGE) {
                VerificationState.POSSIBLE
            } else {
                VerificationState.UNKNOWN
            }
        }
    }

    // ---- Freshness: how much the last retrieval can be trusted -----------------

    private fun freshnessState(job: Job, now: Long): VerificationState {
        val retrieved = job.retrievedAtEpochMillis ?: return VerificationState.UNKNOWN
        if (retrieved > now) return VerificationState.UNKNOWN
        val ageDays = (now - retrieved) / DAY_MS
        return when {
            ageDays <= recentDays -> VerificationState.CONFIRMED
            ageDays <= staleDays -> VerificationState.POSSIBLE
            // An aged-out check is not proof the offer is dead, only that we cannot rely on it.
            else -> VerificationState.UNKNOWN
        }
    }

    // ---- Facts, one per axis, official ones first ------------------------------

    private fun sourceFact(job: Job): VerificationFact = VerificationFact(
        aspect = VerificationAspect.SOURCE,
        strength = EvidenceStrength.DOCUMENTED,
        label = "Source",
        detail = if (job.source.isBlank()) {
            "The posting did not name the source it came from."
        } else {
            "Supplied by ${job.source}. The listing is the employer's or the board's own record."
        },
        sourceUrl = job.sourceUrl,
    )

    private fun activityFact(job: Job, state: VerificationState): VerificationFact {
        val label = "Vacancy activity"
        val detail = when (state) {
            VerificationState.CONFIRMED ->
                "The source itself reports this posting is still accepting applicants."
            VerificationState.EXPIRED ->
                "The source itself reports this posting is no longer accepting applicants."
            else -> "The source does not state whether this posting is still accepting applicants. " +
                "Open it to confirm before you invest time."
        }
        return VerificationFact(VerificationAspect.VACANCY_ACTIVITY, strengthFor(state), label, detail, job.sourceUrl)
    }

    private fun linkFact(job: Job, state: VerificationState): VerificationFact {
        val inspection = linkInspector.inspect(job)
        val detail = when (state) {
            VerificationState.CONFIRMED -> "The application link is present and well-formed: ${inspection.host ?: job.sourceUrl}."
            VerificationState.POSSIBLE -> inspection.warnings.joinToString(" ").ifBlank {
                "The application link is present but has something worth checking."
            }
            else -> inspection.message
        }
        return VerificationFact(VerificationAspect.APPLICATION_LINK, strengthFor(state), "Application link", detail, job.sourceUrl)
    }

    private fun visaFact(job: Job, state: VerificationState): VerificationFact {
        val evidence = job.benefitEvidence.firstOrNull {
            it.type.key == JobBenefitTypes.VISA_SPONSORSHIP.key
        }
        val detail = when (state) {
            VerificationState.CONFIRMED -> evidence?.evidence?.takeIf { it.isNotBlank() }
                ?: "The posting states the employer sponsors a work visa."
            VerificationState.POSSIBLE -> evidence?.evidence?.takeIf { it.isNotBlank() }
                ?: "The posting suggests a route in for international applicants, but does not promise sponsorship."
            VerificationState.NOT_OFFERED -> evidence?.evidence?.takeIf { it.isNotBlank() }
                ?: "The posting states it is not open to applicants who need sponsorship."
            else -> "The posting does not say whether it sponsors a work visa. Do not assume sponsorship."
        }
        return VerificationFact(VerificationAspect.VISA, strengthFor(state), "Visa / sponsorship", detail, job.sourceUrl)
    }

    private fun relocationFact(job: Job, state: VerificationState): VerificationFact {
        val evidence = job.benefitEvidence.firstOrNull {
            it.type.key == JobBenefitTypes.RELOCATION_SUPPORT.key
        }
        val detail = when (state) {
            VerificationState.CONFIRMED -> evidence?.evidence?.takeIf { it.isNotBlank() }
                ?: "The posting states the employer supports relocation."
            VerificationState.POSSIBLE -> evidence?.evidence?.takeIf { it.isNotBlank() }
                ?: "The posting hints at relocation support without committing to it."
            VerificationState.NOT_OFFERED -> evidence?.evidence?.takeIf { it.isNotBlank() }
                ?: "The posting states relocation is not provided."
            else -> "The posting does not mention relocation support."
        }
        return VerificationFact(VerificationAspect.RELOCATION, strengthFor(state), "Relocation", detail, job.sourceUrl)
    }

    private fun freshnessFact(job: Job, state: VerificationState, now: Long): VerificationFact {
        val retrieved = job.retrievedAtEpochMillis
        val detail = when {
            retrieved == null -> "This posting has not been retrieved from its source yet."
            state == VerificationState.CONFIRMED -> "Retrieved from the source ${relativeDays(retrieved, now)}."
            state == VerificationState.POSSIBLE -> "Retrieved from the source ${relativeDays(retrieved, now)}. Confirm it is still current."
            else -> "Last retrieved from the source ${relativeDays(retrieved, now)}. The check is old, so re-open the listing before relying on it."
        }
        return VerificationFact(VerificationAspect.FRESHNESS, strengthFor(state), "Verification age", detail, job.sourceUrl)
    }

    private fun relativeDays(retrieved: Long, now: Long): String {
        val ageDays = ((now - retrieved).coerceAtLeast(0L)) / DAY_MS
        return when (ageDays) {
            0L -> "today"
            1L -> "yesterday"
            else -> "$ageDays days ago"
        }
    }

    // ---- Caveats and summary ---------------------------------------------------

    private fun caveatsFor(
        job: Job,
        activity: VerificationState,
        link: VerificationState,
        visa: VerificationState,
        freshness: VerificationState,
    ): List<String> = buildList {
        if (link == VerificationState.INACCESSIBLE) {
            add("This posting has no usable application link. Search the employer's own careers page instead.")
        }
        if (activity == VerificationState.EXPIRED) {
            add("The source reports this posting is no longer accepting applicants.")
        }
        if (job.eligibility == MadagascarEligibility.RESTRICTED) {
            add("The posting is limited to local applicants, so a Madagascar application is unlikely to be considered.")
        }
        when (visa) {
            VerificationState.UNKNOWN -> add("The posting does not say whether it sponsors a visa. Confirm with the employer before relying on it.")
            VerificationState.NOT_OFFERED -> add("The employer states it does not sponsor a visa for this role.")
            else -> Unit
        }
        if (freshness == VerificationState.UNKNOWN && job.retrievedAtEpochMillis != null) {
            add("This listing was last retrieved a while ago. Re-open it to confirm it is still current.")
        }
    }

    private fun summaryFor(
        activity: VerificationState,
        link: VerificationState,
        visa: VerificationState,
        freshness: VerificationState,
    ): String = buildString {
        when {
            link == VerificationState.INACCESSIBLE -> append("No usable application link.")
            activity == VerificationState.EXPIRED -> append("The source reports this posting is closed.")
            activity == VerificationState.CONFIRMED -> append("The source reports this posting is open.")
            else -> append("The app cannot confirm whether this posting is still open.")
        }
        when (visa) {
            VerificationState.CONFIRMED -> append(" Visa sponsorship is documented.")
            VerificationState.POSSIBLE -> append(" There is a possible route in for international applicants.")
            VerificationState.NOT_OFFERED -> append(" No visa route is offered.")
            else -> append(" Visa sponsorship is not stated.")
        }
        if (freshness == VerificationState.UNKNOWN && activity != VerificationState.EXPIRED) {
            append(" Re-check the listing before applying.")
        }
    }

    /** Maps a verification state to how strongly the underlying fact is established. */
    private fun strengthFor(state: VerificationState): EvidenceStrength = when (state) {
        VerificationState.CONFIRMED -> EvidenceStrength.DOCUMENTED
        VerificationState.POSSIBLE -> EvidenceStrength.INFERRED
        VerificationState.NOT_OFFERED -> EvidenceStrength.REFUSED
        VerificationState.EXPIRED -> EvidenceStrength.REFUSED
        VerificationState.INACCESSIBLE -> EvidenceStrength.DOCUMENTED
        VerificationState.UNKNOWN -> EvidenceStrength.UNKNOWN
    }

    companion object {
        private const val DAY_MS = 86_400_000L
        const val DEFAULT_RECENT_DAYS = 3
        const val DEFAULT_STALE_DAYS = 21
    }
}
