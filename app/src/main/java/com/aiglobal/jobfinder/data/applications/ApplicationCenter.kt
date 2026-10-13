package com.aiglobal.jobfinder.data.applications

import com.aiglobal.jobfinder.data.coverletter.CoverLetterGenerator
import com.aiglobal.jobfinder.data.expat.ExpatBenefitsCatalog
import com.aiglobal.jobfinder.data.model.ApplicationEvent
import com.aiglobal.jobfinder.data.model.ApplicationEventType
import com.aiglobal.jobfinder.data.model.ApplicationRecord
import com.aiglobal.jobfinder.data.model.ApplicationReminder
import com.aiglobal.jobfinder.data.model.ApplicationStatus
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.CoverLetter
import com.aiglobal.jobfinder.data.model.ExpatGuide
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.LetterLanguage
import com.aiglobal.jobfinder.data.model.ReminderKind
import com.aiglobal.jobfinder.data.model.isTerminal
import com.aiglobal.jobfinder.data.model.stepIndex
import com.aiglobal.jobfinder.data.repository.JobRepository
import com.aiglobal.jobfinder.data.repository.SampleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The application pipeline: everything the user does *after* finding a job.
 *
 * Responsibilities:
 * - Track each application through [ApplicationStatus] and keep an append-only timeline.
 * - Generate grounded cover letters from the CV and the posting (no network, no invention).
 * - Derive reminders and the next action from the records, so a stale reminder is impossible.
 * - Serve the expat settlement guide for a destination country.
 *
 * Records are persisted by [ApplicationStore]; this class owns the rules, not the storage.
 */
class ApplicationCenter(
    private val repository: JobRepository,
    private val store: ApplicationStore,
    /** The CV-derived profile; drives the seeded cover letters. */
    private val candidateProfile: Flow<CandidateProfile> = flowOf(CandidateProfile()),
    private val generator: CoverLetterGenerator = CoverLetterGenerator(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val applications: StateFlow<List<ApplicationRecord>> = store.applications
    val coverLetters: StateFlow<List<CoverLetter>> = store.coverLetters

    /** Reminders ordered by date, overdue ones flagged. */
    val reminders: StateFlow<List<ApplicationReminder>> =
        applications
            .map { apps -> buildReminders(apps, clock()) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            store.load()
            if (!store.hadPersistedDocument) seedFromSample()
        }
    }

    // ------------------------------------------------------------------ queries

    fun application(id: String): ApplicationRecord? = applications.value.firstOrNull { it.id == id }

    fun applicationForJob(jobId: String): ApplicationRecord? =
        applications.value.firstOrNull { it.jobId == jobId }

    fun coverLetter(id: String?): CoverLetter? =
        id?.let { wanted -> coverLetters.value.firstOrNull { it.id == wanted } }

    fun coverLetterForJob(jobId: String): CoverLetter? =
        coverLetters.value.filter { it.jobId == jobId }.maxByOrNull { it.generatedAtEpochMillis }

    fun countByStatus(status: ApplicationStatus): Int = applications.value.count { it.status == status }

    /** Applications that are still moving forward. */
    val activeCount: Int get() = applications.value.count { !it.status.isTerminal && it.status != ApplicationStatus.SAVED }

    val upcomingInterviewCount: Int get() = applications.value.count { it.interviewUpcoming(clock()) }

    /** Reminders whose moment has arrived or passed. */
    fun dueReminders(now: Long = clock()): List<ApplicationReminder> =
        buildReminders(applications.value, now).filter { it.due }

    fun expatGuide(country: String): ExpatGuide = ExpatBenefitsCatalog.forCountry(country)

    /** The single most useful next action for an application, in plain language. */
    fun nextAction(record: ApplicationRecord, now: Long = clock()): String = when {
        record.status == ApplicationStatus.SAVED -> "Start preparing your CV and cover letter."
        record.status == ApplicationStatus.PREPARING && !record.hasCoverLetter ->
            "Generate a cover letter, then submit."
        record.status == ApplicationStatus.PREPARING -> "Review everything, then mark it as applied."
        record.status == ApplicationStatus.APPLIED && record.followUpDue(now) ->
            "Time to follow up with the employer."
        record.status == ApplicationStatus.APPLIED -> "Wait 1–2 weeks, then follow up."
        record.status == ApplicationStatus.INTERVIEW && record.interviewUpcoming(now) ->
            "Prepare for the interview and confirm the time zone."
        record.status == ApplicationStatus.INTERVIEW -> "Practise likely questions before the round."
        record.status == ApplicationStatus.OFFER -> "Review the contract, visa route and start date."
        record.status == ApplicationStatus.REJECTED -> "Keep the record; look for a closer match."
        else -> "Archived."
    }

    // ------------------------------------------------------------------ commands

    /** Saves a job to the pipeline, or returns the existing record if it is already tracked. */
    suspend fun trackJob(job: Job): ApplicationRecord {
        applicationForJob(job.id)?.let { return it }
        val now = clock()
        val record = ApplicationRecord(
            id = idFactory(),
            jobId = job.id,
            jobTitle = job.title,
            company = job.company,
            country = job.country,
            countryFlag = job.countryFlag,
            companyAccent = job.companyAccent,
            status = ApplicationStatus.SAVED,
            matchScore = job.matchScore,
            appliedAtEpochMillis = null,
            updatedAtEpochMillis = now,
            nextStepLabel = "Decide whether to apply",
            nextStepAtEpochMillis = null,
            hasCoverLetter = false,
            sourceUrl = job.sourceUrl,
            source = job.source,
            timeline = listOf(
                ApplicationEvent(
                    id = idFactory(),
                    type = ApplicationEventType.CREATED,
                    label = "Saved to your pipeline",
                    detail = "Match score ${job.matchScore}% · ${job.country}",
                    atEpochMillis = now,
                ),
            ),
        )
        store.putApplication(record)
        return record
    }

    suspend fun setStatus(applicationId: String, status: ApplicationStatus) {
        val current = application(applicationId) ?: return
        if (current.status == status) return
        val now = clock()
        val appliedAt = current.appliedAtEpochMillis ?: if (status == ApplicationStatus.APPLIED) now else null
        val updated = current.copy(
            status = status,
            appliedAtEpochMillis = appliedAt,
            updatedAtEpochMillis = now,
            nextStepLabel = nextStepLabelFor(status),
            nextStepAtEpochMillis = nextStepAtFor(status, now),
            timeline = current.timeline + ApplicationEvent(
                id = idFactory(),
                type = ApplicationEventType.STATUS_CHANGE,
                label = "Moved to ${status.displayName()}",
                detail = nextStepLabelFor(status) ?: "",
                atEpochMillis = now,
            ),
        )
        store.putApplication(updated)
    }

    suspend fun addNote(applicationId: String, note: String) {
        val current = application(applicationId) ?: return
        val trimmed = note.trim()
        if (trimmed.isEmpty()) return
        val now = clock()
        store.putApplication(
            current.copy(
                notes = trimmed,
                updatedAtEpochMillis = now,
                timeline = current.timeline + ApplicationEvent(
                    id = idFactory(),
                    type = ApplicationEventType.NOTE,
                    label = "Note added",
                    detail = trimmed,
                    atEpochMillis = now,
                ),
            ),
        )
    }

    /** Schedules (or clears) the interview date. Drives the interview reminder. */
    suspend fun setInterviewAt(applicationId: String, epochMillis: Long?) {
        val current = application(applicationId) ?: return
        val now = clock()
        val label = if (epochMillis != null) "Interview scheduled" else "Interview date cleared"
        store.putApplication(
            current.copy(
                interviewAtEpochMillis = epochMillis,
                status = if (epochMillis != null && current.status.stepIndex < ApplicationStatus.INTERVIEW.stepIndex) {
                    ApplicationStatus.INTERVIEW
                } else {
                    current.status
                },
                updatedAtEpochMillis = now,
                nextStepLabel = if (epochMillis != null) "Interview" else current.nextStepLabel,
                nextStepAtEpochMillis = epochMillis ?: current.nextStepAtEpochMillis,
                timeline = current.timeline + ApplicationEvent(
                    id = idFactory(),
                    type = if (epochMillis != null) ApplicationEventType.REMINDER else ApplicationEventType.NOTE,
                    label = label,
                    atEpochMillis = now,
                ),
            ),
        )
    }

    /** Schedules (or clears) the follow-up reminder. */
    suspend fun setFollowUpAt(applicationId: String, epochMillis: Long?) {
        val current = application(applicationId) ?: return
        val now = clock()
        store.putApplication(
            current.copy(
                followUpAtEpochMillis = epochMillis,
                updatedAtEpochMillis = now,
                timeline = current.timeline + ApplicationEvent(
                    id = idFactory(),
                    type = if (epochMillis != null) ApplicationEventType.REMINDER else ApplicationEventType.NOTE,
                    label = if (epochMillis != null) "Follow-up reminder set" else "Follow-up reminder cleared",
                    atEpochMillis = now,
                ),
            ),
        )
    }

    suspend fun archive(applicationId: String) {
        setStatus(applicationId, ApplicationStatus.CLOSED)
    }

    suspend fun deleteApplication(applicationId: String) {
        val current = application(applicationId) ?: return
        current.coverLetterId?.let { store.removeCoverLetter(it) }
        store.removeApplication(applicationId)
    }

    /**
     * Writes a cover letter for [job] from the candidate's own CV. The job is tracked
     * first if it is not already, so generating a letter always leaves a pipeline record.
     */
    suspend fun generateCoverLetter(
        job: Job,
        profile: CandidateProfile,
        language: LetterLanguage = LetterLanguage.forCountry(job.country),
    ): CoverLetter {
        val record = trackJob(job)
        val letter = generator.generate(job, profile, language)
        store.putCoverLetter(letter)

        val now = clock()
        val advanced = if (record.status == ApplicationStatus.SAVED) ApplicationStatus.PREPARING else record.status
        store.updateApplication(record.id) { existing ->
            existing.copy(
                coverLetterId = letter.id,
                hasCoverLetter = true,
                status = advanced,
                updatedAtEpochMillis = now,
                nextStepLabel = if (advanced == ApplicationStatus.PREPARING) "Review and submit" else existing.nextStepLabel,
                timeline = existing.timeline + ApplicationEvent(
                    id = idFactory(),
                    type = ApplicationEventType.COVER_LETTER,
                    label = "Cover letter generated (${language.label})",
                    detail = "${letter.wordCount} words · ${letter.usedFacts.size} facts from your CV",
                    atEpochMillis = now,
                ),
            )
        }
        return letter
    }

    /** Persists a hand-edited letter body. */
    suspend fun updateCoverLetterBody(letterId: String, paragraphs: List<String>) {
        val cleaned = paragraphs.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return
        store.updateCoverLetter(letterId) { it.copy(paragraphs = cleaned, edited = true) }
    }

    // ------------------------------------------------------------------ internals

    private suspend fun seedFromSample() {
        val now = clock()
        val jobs = repository.jobs.first()
        val profile = candidateProfile.first()

        SampleData.applications(now).forEach { record ->
            if (!record.hasCoverLetter) {
                store.putApplication(record)
                return@forEach
            }
            val job = jobs.firstOrNull { it.id == record.jobId }
            if (job == null) {
                store.putApplication(record)
                return@forEach
            }
            val letter = generator.generate(job, profile, LetterLanguage.forCountry(job.country))
            store.putCoverLetter(letter)
            store.putApplication(record.copy(coverLetterId = letter.id))
        }
    }

    private fun buildReminders(apps: List<ApplicationRecord>, now: Long): List<ApplicationReminder> =
        apps.filter { !it.status.isTerminal }
            .flatMap { record ->
                buildList {
                    record.followUpAtEpochMillis?.let { at ->
                        add(
                            ApplicationReminder(
                                applicationId = record.id,
                                jobTitle = record.jobTitle,
                                company = record.company,
                                countryFlag = record.countryFlag,
                                kind = ReminderKind.FOLLOW_UP,
                                atEpochMillis = at,
                                due = at <= now,
                                detail = "Follow up on your ${record.jobTitle} application.",
                            ),
                        )
                    }
                    record.interviewAtEpochMillis?.let { at ->
                        add(
                            ApplicationReminder(
                                applicationId = record.id,
                                jobTitle = record.jobTitle,
                                company = record.company,
                                countryFlag = record.countryFlag,
                                kind = ReminderKind.INTERVIEW,
                                atEpochMillis = at,
                                due = at <= now,
                                detail = "Interview with ${record.company}.",
                            ),
                        )
                    }
                }
            }
            .sortedBy { it.atEpochMillis }

    private fun nextStepLabelFor(status: ApplicationStatus): String? = when (status) {
        ApplicationStatus.SAVED -> "Decide whether to apply"
        ApplicationStatus.PREPARING -> "Review and submit"
        ApplicationStatus.APPLIED -> "Awaiting recruiter reply"
        ApplicationStatus.INTERVIEW -> "Prepare for the interview"
        ApplicationStatus.OFFER -> "Review the offer"
        ApplicationStatus.REJECTED -> null
        ApplicationStatus.CLOSED -> null
    }

    private fun nextStepAtFor(status: ApplicationStatus, now: Long): Long? = when (status) {
        ApplicationStatus.APPLIED -> now + FOLLOW_UP_DELAY_MS
        else -> null
    }

    private companion object {
        const val FOLLOW_UP_DELAY_MS = 7L * 24 * 60 * 60 * 1000
    }
}

private fun ApplicationStatus.displayName(): String = when (this) {
    ApplicationStatus.SAVED -> "Saved"
    ApplicationStatus.PREPARING -> "Preparing"
    ApplicationStatus.APPLIED -> "Applied"
    ApplicationStatus.INTERVIEW -> "Interview"
    ApplicationStatus.OFFER -> "Offer"
    ApplicationStatus.REJECTED -> "Not selected"
    ApplicationStatus.CLOSED -> "Archived"
}
