package com.aiglobal.jobfinder.data.submissions

/**
 * The app's real-send capability, derived from the single source of truth
 * ([SubmissionChannel.realSendSupported]) instead of a hand-maintained table that could
 * drift away from the gate the registry actually enforces.
 *
 * The point is that the app never claims it can send somewhere it cannot: a channel that
 * is not drivable reports the same refusal the registry would apply.
 */
object SubmissionCapability {

    /**
     * A plain-language statement of whether the app can send on [channel]. It cannot
     * disagree with the gate, because it reads the gate.
     */
    fun statement(channel: SubmissionChannel): String = if (channel.realSendSupported) {
        "The app can submit applications on this route."
    } else {
        channel.compatibilityNote
    }

    /**
     * True while no route can be driven by the app, which is the current state. Every
     * application the user reviews is prepared and then simulated, never sent.
     */
    val sendsNothing: Boolean
        get() = enumValues<SubmissionChannel>().none { it.realSendSupported }

    /** The number of routes the app can genuinely send on. Zero today. */
    val supportedChannelCount: Int
        get() = enumValues<SubmissionChannel>().count { it.realSendSupported }
}
