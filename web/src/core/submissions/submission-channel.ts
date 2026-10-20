import type { Job } from "../models";

/**
 * The kind of application route a posting offers. Mirrors the Kotlin `SubmissionChannel`.
 *
 * `realSendSupported` is the compatibility gate: it is false for every channel today,
 * because the app must never send an application somewhere it cannot be held to account for.
 */
export class SubmissionChannel {
  private constructor(
    readonly key: string,
    readonly label: string,
    readonly realSendSupported: boolean,
    /** Plain-language reason shown to the user when a real send is refused. */
    readonly compatibilityNote: string,
  ) {}

  static readonly ATS_API = new SubmissionChannel(
    "ATS_API",
    "Employer API",
    false,
    "No verified employer API integration is available, so the app will not send it.",
  );
  static readonly EMPLOYER_PORTAL = new SubmissionChannel(
    "EMPLOYER_PORTAL",
    "Employer portal",
    false,
    "This employer needs you signed in on their own portal. The app cannot do that for you.",
  );
  static readonly EMAIL = new SubmissionChannel(
    "EMAIL",
    "Email",
    false,
    "Sending by email would need access to your mailbox. The app will never ask for your email password.",
  );
  static readonly UNKNOWN = new SubmissionChannel(
    "UNKNOWN",
    "Unverified channel",
    false,
    "The application route could not be verified, so nothing can be sent automatically.",
  );

  static readonly values: SubmissionChannel[] = [
    SubmissionChannel.ATS_API,
    SubmissionChannel.EMPLOYER_PORTAL,
    SubmissionChannel.EMAIL,
    SubmissionChannel.UNKNOWN,
  ];

  /**
   * Picks the route a posting offers, using only evidence in the posting. Anything not
   * recognisable is `UNKNOWN` rather than a guess, so the app never claims it can send
   * somewhere it cannot.
   */
  static infer(job: Job, sourceUrl: string = job.sourceUrl): SubmissionChannel {
    const url = sourceUrl.toLowerCase();
    if (url.trim() === "") return SubmissionChannel.UNKNOWN;
    // Mirrors Kotlin `substringAfter("://").substringBefore('/')`: a URL with no scheme
    // keeps the whole string before the first slash.
    const afterScheme = url.includes("://") ? url.slice(url.indexOf("://") + 3) : url;
    const host = afterScheme.split("/")[0];
    const atsHosts = [
      "greenhouse.io",
      "lever.co",
      "ashbyhq.com",
      "myworkdayjobs.com",
      "workday.com",
      "smartrecruiters.com",
      "workable.com",
      "bamboohr.com",
    ];
    if (atsHosts.some((it) => host.endsWith(it) || host.includes(it))) return SubmissionChannel.ATS_API;
    if (url.startsWith("mailto:")) return SubmissionChannel.EMAIL;
    if (host.trim() !== "" && host.includes(".")) return SubmissionChannel.EMPLOYER_PORTAL;
    return SubmissionChannel.UNKNOWN;
  }
}
