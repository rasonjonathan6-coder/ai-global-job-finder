package com.aiglobal.jobfinder.data.alerts

import com.aiglobal.jobfinder.data.model.AlertScope
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobAlert
import com.aiglobal.jobfinder.data.model.JobAlertRule
import com.aiglobal.jobfinder.data.model.VisaSupport

/**
 * Decides which live postings a saved search should surface.
 *
 * Alerts are computed from the current feed rather than a stored snapshot, so a posting
 * that appears while the app was closed is caught on the next refresh, and a rule the
 * user edits never leaves stale results behind. The engine is a pure function of
 * (rules, jobs), so it runs on the JVM and is covered by tests.
 */
class JobAlertEngine {

    fun alertsFor(
        rules: List<JobAlertRule>,
        jobs: List<Job>,
        now: Long,
    ): List<JobAlert> = rules
        .filter { it.enabled }
        .flatMap { rule -> jobs.filter { matches(rule, it) }.map { job -> toAlert(rule, job, now) } }

    /** True when [job] satisfies every constraint the rule sets. */
    fun matches(rule: JobAlertRule, job: Job): Boolean {
        if (job.matchScore < rule.minMatchScore) return false

        when (rule.scope) {
            AlertScope.STRONG_ONLY -> if (job.matchScore < 75) return false
            AlertScope.VISA_FRIENDLY -> if (!job.isVisaFriendly()) return false
            AlertScope.ANY_MATCH -> Unit
        }

        if (rule.countries.isNotEmpty() &&
            rule.countries.none { it.equals(job.country, ignoreCase = true) }
        ) {
            return false
        }

        if (rule.keywords.isNotEmpty()) {
            val haystack = buildString {
                append(job.title).append(' ')
                append(job.company).append(' ')
                append(job.summary).append(' ')
                append(job.matchedSkills.joinToString(" "))
            }.lowercase()
            if (rule.keywords.none { keyword -> haystack.contains(keyword.lowercase().trim()) }) return false
        }

        return true
    }

    private fun toAlert(rule: JobAlertRule, job: Job, now: Long) = JobAlert(
        ruleId = rule.id,
        ruleName = rule.name,
        jobId = job.id,
        jobTitle = job.title,
        company = job.company,
        country = job.country,
        countryFlag = job.countryFlag,
        matchScore = job.matchScore,
        isVisaFriendly = job.isVisaFriendly(),
        detectedAtEpochMillis = now,
    )
}

/** Shared notion of "this employer will sponsor a candidate from abroad". */
fun Job.isVisaFriendly(): Boolean =
    visaSupport == VisaSupport.FULL_SPONSORSHIP ||
        visaSupport == VisaSupport.SPONSORSHIP_AVAILABLE ||
        visaSupport == VisaSupport.RELOCATION_PACKAGE
