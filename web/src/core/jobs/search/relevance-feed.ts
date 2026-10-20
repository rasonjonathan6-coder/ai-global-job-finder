import { jobIsMaintenanceRelevant, type Job } from "../../models";

/**
 * Turns a broad, ranked feed into the set of postings that are actually the candidate's job.
 * Mirrors the Kotlin `RelevanceFeed`.
 *
 * The key-free remote boards (Remotive, Jobicy, Himalayas) return a newest-first global
 * sample, so their first page is mostly software, sales and support roles that have nothing
 * to do with an industrial maintenance tradesperson. Stage 70 makes the app stop presenting
 * that sample as a result set: after ranking, the feed keeps only the postings whose title
 * reads as a target role or the wider maintenance family (`classifyRole`), so the Jobs screen
 * and the Home dashboard open on relevant opportunities rather than unrelated ones.
 *
 * The rule is deliberately narrow — it keeps the tradesperson's own roles and the close
 * maintenance family, and drops everything else. It is the same `classifyRole` verdict the
 * coverage report and the smart-alert gate already use, so "relevant" means one thing
 * everywhere. A posting whose title is too thin to judge is not guessed at; it is dropped
 * like any other unrelated role, because a broad feed is not a licence to invent relevance.
 *
 * The feed is already sorted best-match-first, so this only *filters*; it never reorders.
 */

/** Only the postings whose role reads as the candidate's trade, order preserved. */
export function relevantJobs(jobs: Job[]): Job[] {
  return jobs.filter(jobIsMaintenanceRelevant);
}

/** The postings dropped for being an unrelated profession, order preserved. */
export function unrelatedJobs(jobs: Job[]): Job[] {
  return jobs.filter((job) => !jobIsMaintenanceRelevant(job));
}

/**
 * A plain-language account of what the filter kept and dropped, for the coverage line. It
 * always states the size of the broad sample the sources returned, so a small relevant set is
 * never mistaken for a thin market.
 */
export function relevanceSummary(totalRetrieved: number, relevant: number): string {
  if (totalRetrieved === 0) return "No postings were retrieved.";
  if (relevant === 0) {
    return `None of the ${totalRetrieved} retrieved postings matched your trade.`;
  }
  const dropped = totalRetrieved - relevant;
  if (dropped === 0) {
    return `All ${totalRetrieved} retrieved postings matched your trade.`;
  }
  return (
    `${relevant} of ${totalRetrieved} retrieved postings matched your trade; ` +
    `${dropped} unrelated ${dropped === 1 ? "posting was" : "postings were"} set aside.`
  );
}
