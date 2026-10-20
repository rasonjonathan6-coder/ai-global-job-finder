import {
  AlertScope,
  jobIsVisaFriendly,
  type Job,
  type JobAlert,
  type JobAlertRule,
} from "../models";

/**
 * Decides which live postings a saved search should surface. Mirrors the Kotlin
 * `JobAlertEngine`.
 *
 * Alerts are computed from the current feed rather than a stored snapshot, so a posting
 * that appears while the app was closed is caught on the next refresh, and a rule the
 * user edits never leaves stale results behind. The engine is a pure function of
 * (rules, jobs, now).
 */
export class JobAlertEngine {
  alertsFor(rules: JobAlertRule[], jobs: Job[], now: number): JobAlert[] {
    return rules
      .filter((rule) => rule.enabled)
      .flatMap((rule) => jobs.filter((job) => this.matches(rule, job)).map((job) => toAlert(rule, job, now)));
  }

  /** True when `job` satisfies every constraint the rule sets. */
  matches(rule: JobAlertRule, job: Job): boolean {
    if (job.matchScore < rule.minMatchScore) return false;

    switch (rule.scope) {
      case AlertScope.STRONG_ONLY:
        if (job.matchScore < 75) return false;
        break;
      case AlertScope.VISA_FRIENDLY:
        if (!jobIsVisaFriendly(job)) return false;
        break;
      case AlertScope.ANY_MATCH:
        break;
    }

    if (rule.countries.length > 0 && !rule.countries.some((c) => c.toLowerCase() === job.country.toLowerCase())) {
      return false;
    }

    if (rule.keywords.length > 0) {
      const haystack = [job.title, job.company, job.summary, job.matchedSkills.join(" ")].join(" ").toLowerCase();
      if (!rule.keywords.some((keyword) => haystack.includes(keyword.toLowerCase().trim()))) return false;
    }

    return true;
  }
}

function toAlert(rule: JobAlertRule, job: Job, now: number): JobAlert {
  return {
    ruleId: rule.id,
    ruleName: rule.name,
    jobId: job.id,
    jobTitle: job.title,
    company: job.company,
    country: job.country,
    countryFlag: job.countryFlag,
    matchScore: job.matchScore,
    isVisaFriendly: jobIsVisaFriendly(job),
    detectedAtEpochMillis: now,
    read: false,
  };
}
