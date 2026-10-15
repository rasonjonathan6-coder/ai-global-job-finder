package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable

/**
 * How a feature actually works. This is the honest classification the AI screen must show:
 * the app must never describe a deterministic rule as a generative model, and must never
 * imply that CV text is sent to a cloud service when it is not.
 */
enum class AiCapabilityKind(val label: String) {
    /** A pure function of its inputs: phrase tables, weights, thresholds. */
    RULE_BASED("On-device rules"),

    /** Reads a document into structured fields (PDF/DOCX/TXT). */
    DOCUMENT_PARSER("Document parser"),

    /** Template filled from verified facts only. */
    TEMPLATE("Grounded template"),

    /** A trained machine-learning model running on the device. */
    ON_DEVICE_MODEL("On-device model"),

    /** A remote generative or ML service, reached only when the user configures and enables it. */
    REMOTE_MODEL("Remote AI service"),
}

/** Where the feature runs, so the privacy claim is explicit rather than implied. */
enum class AiProcessingLocation(val label: String) {
    ON_DEVICE("On this device"),

    /** Data leaves the device and is processed by NVIDIA. */
    REMOTE("Sent to NVIDIA"),
}

/**
 * One capability of the app, described exactly as it is implemented. [note] carries the
 * plain-language caveat that keeps the screen truthful (for example, that a rule-based
 * score is not a machine-learning prediction).
 */
@Immutable
data class AiCapability(
    val name: String,
    val kind: AiCapabilityKind,
    val location: AiProcessingLocation,
    val description: String,
    val note: String,
)

/**
 * The complete, current inventory of what the "AI" in this app does.
 *
 * It is written to be checkable against the source tree: every entry names the mechanism
 * that actually runs. The optional drafting assistant is a real remote generative model
 * (NVIDIA), and it is labelled as such and marked opt-in — it runs only when the user
 * saves an API key, turns it on, and consents to sending their CV facts.
 */
object AiCapabilityCatalog {

    /** True when the app ships a trained on-device model. It does not, today. */
    const val HAS_ON_DEVICE_MODEL: Boolean = false

    /** True when the app can contact a remote AI service. It can, once the user configures NVIDIA. */
    const val HAS_REMOTE_MODEL: Boolean = true

    val capabilities: List<AiCapability> = listOf(
        AiCapability(
            name = "CV reading",
            kind = AiCapabilityKind.DOCUMENT_PARSER,
            location = AiProcessingLocation.ON_DEVICE,
            description = "Opens your PDF, Word or text CV and reads it into structured fields.",
            note = "This is a document parser, not a language model. It reads the file you picked and nothing else.",
        ),
        AiCapability(
            name = "CV skill extraction",
            kind = AiCapabilityKind.RULE_BASED,
            location = AiProcessingLocation.ON_DEVICE,
            description = "Finds skills, titles, employers, dates and languages using a fixed vocabulary and pattern rules.",
            note = "Deterministic and offline. A skill is only listed when its phrase appears in your CV.",
        ),
        AiCapability(
            name = "Job matching",
            kind = AiCapabilityKind.RULE_BASED,
            location = AiProcessingLocation.ON_DEVICE,
            description = "Scores each posting on six weighted signals: role, skills, experience, location, language and recency.",
            note = "A transparent weighted score, not a machine-learning prediction. Every point is explained.",
        ),
        AiCapability(
            name = "Madagascar eligibility check",
            kind = AiCapabilityKind.RULE_BASED,
            location = AiProcessingLocation.ON_DEVICE,
            description = "Reads the posting for statements about who may apply and whether a visa is sponsored.",
            note = "Only what the posting states is treated as fact; silence is reported as 'not stated'.",
        ),
        AiCapability(
            name = "Expat benefit evidence",
            kind = AiCapabilityKind.RULE_BASED,
            location = AiProcessingLocation.ON_DEVICE,
            description = "Finds accommodation, relocation, flights, leave and insurance claims with the sentence that proves each one.",
            note = "Benefits are never assumed from a job being international; each claim carries its evidence.",
        ),
        AiCapability(
            name = "Scam screening",
            kind = AiCapabilityKind.RULE_BASED,
            location = AiProcessingLocation.ON_DEVICE,
            description = "Flags upfront fees, unregistered employers, unrealistic pay and contact red flags.",
            note = "A rule-based warning list, not a guarantee. Always verify the employer yourself.",
        ),
        AiCapability(
            name = "Cover letter drafting",
            kind = AiCapabilityKind.TEMPLATE,
            location = AiProcessingLocation.ON_DEVICE,
            description = "Writes a letter from your verified CV facts and the selected posting.",
            note = "A grounded template, not a generative model. It reports which facts it could and could not use.",
        ),
        AiCapability(
            name = "Interview coaching",
            kind = AiCapabilityKind.TEMPLATE,
            location = AiProcessingLocation.ON_DEVICE,
            description = "Builds practice questions from the posting and your CV and scores your answers on structure.",
            note = "Question templates and a rubric, not a live AI interviewer.",
        ),
        AiCapability(
            name = "AI drafting assistant",
            kind = AiCapabilityKind.REMOTE_MODEL,
            location = AiProcessingLocation.REMOTE,
            description = "An optional NVIDIA-hosted language model that explains a match, drafts a cover letter, or rewrites your summary.",
            note = "Off until you turn it on. When on, only your CV facts and the posting text are sent to NVIDIA — never the CV file, your email, phone or address. Review every draft before you send it.",
        ),
    )

    /** One-line honesty statement for the AI screen header. */
    val summary: String =
        "Most capabilities run on your device as transparent rules and parsers. " +
            "One optional drafting assistant uses NVIDIA's hosted model, and only when you " +
            "turn it on — your CV file is never uploaded."
}
