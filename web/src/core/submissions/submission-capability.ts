import { SubmissionChannel } from "./submission-channel";

/**
 * The app's real-send capability, derived from the single source of truth
 * (`SubmissionChannel.realSendSupported`) instead of a hand-maintained table that could
 * drift away from the gate the registry actually enforces. Mirrors the Kotlin
 * `SubmissionCapability`.
 */
export class SubmissionCapability {
  /**
   * A plain-language statement of whether the app can send on `channel`. It cannot disagree
   * with the gate, because it reads the gate.
   */
  static statement(channel: SubmissionChannel): string {
    return channel.realSendSupported
      ? "The app can submit applications on this route."
      : channel.compatibilityNote;
  }

  /** True while no route can be driven by the app, which is the current state. */
  static get sendsNothing(): boolean {
    return SubmissionChannel.values.every((channel) => !channel.realSendSupported);
  }

  /** The number of routes the app can genuinely send on. Zero today. */
  static get supportedChannelCount(): number {
    return SubmissionChannel.values.filter((channel) => channel.realSendSupported).length;
  }
}
