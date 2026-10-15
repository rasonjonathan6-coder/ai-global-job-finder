package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable

/**
 * The NVIDIA-hosted models this app will call. Model IDs are verified against the account's
 * `/v1/models` list before they are used; an ID that has been retired by NVIDIA is answered
 * with HTTP 410, which the client surfaces as a plain-language error rather than a failure
 * to parse. The list is ordered by preference so the first available model is used.
 */
@Immutable
data class NvidiaModel(
    val id: String,
    val label: String,
    val note: String,
)

object NvidiaModels {

    /** Models confirmed to answer `/chat/completions` for a personal NVIDIA account. */
    val known: List<NvidiaModel> = listOf(
        NvidiaModel(
            id = "openai/gpt-oss-20b",
            label = "GPT-OSS 20B",
            note = "Fast, low-cost general model. A good default for drafting and explaining.",
        ),
        NvidiaModel(
            id = "nvidia/nemotron-3-super-120b-a12b",
            label = "Nemotron 3 Super 120B",
            note = "Larger reasoning model. Higher quality, slower and more quota per call.",
        ),
        NvidiaModel(
            id = "z-ai/glm-5.3",
            label = "GLM 5.3",
            note = "General-purpose alternative if a preferred model is unavailable.",
        ),
    )

    val defaultModelId: String = known.first().id

    fun labelFor(id: String): String = known.firstOrNull { it.id == id }?.label ?: id

    fun isKnown(id: String): Boolean = known.any { it.id == id }
}

/**
 * The user's NVIDIA API configuration. The key is stored encrypted; this value only ever
 * carries it in memory for the moment a request is made.
 */
@Immutable
data class NvidiaConfig(
    val apiKey: String = "",
    val modelId: String = NvidiaModels.defaultModelId,
    /** Explicit, revocable consent to send CV/profile text to NVIDIA. Defaults to off. */
    val consentGranted: Boolean = false,
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    /** True only when a key exists *and* the user has consented to sending profile data. */
    val isReady: Boolean get() = isConfigured && consentGranted
}

/**
 * How a request to NVIDIA ended. Kept coarse and explicit so the UI can describe the
 * outcome honestly: "not configured", "you have not consented", "rate limited", etc.
 */
enum class NvidiaStatus(val label: String) {
    OK("Connected"),
    NOT_CONFIGURED("No API key"),
    CONSENT_REQUIRED("Consent required"),
    INVALID_KEY("Key rejected"),
    RATE_LIMITED("Rate limited"),
    TIMEOUT("Timed out"),
    NETWORK("Network error"),
    MODEL_UNAVAILABLE("Model unavailable"),
    BAD_RESPONSE("Unexpected response"),
    DISABLED("Turned off"),
}

/** The result of one call to the model: the text, or a classified reason it is absent. */
@Immutable
data class NvidiaResult(
    val status: NvidiaStatus,
    val text: String = "",
    /** A short, user-facing explanation of a non-OK status. */
    val message: String = "",
) {
    /** True when [text] came back and is usable. */
    val isSuccess: Boolean get() = status == NvidiaStatus.OK && text.isNotBlank()
}
