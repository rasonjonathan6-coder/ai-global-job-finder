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

    /**
     * Suggested starting points. These are long-lived, widely-hosted Meta/NVIDIA models that
     * answer `/chat/completions` on `integrate.api.nvidia.com`. The list is a convenience,
     * not a whitelist: NVIDIA retires and adds models often, so the app also discovers the
     * account's real list at runtime (`/v1/models`) and lets the user type any id. A model
     * that is not in this list is still valid if the account serves it.
     */
    val known: List<NvidiaModel> = listOf(
        NvidiaModel(
            id = "meta/llama-3.1-8b-instruct",
            label = "Llama 3.1 8B Instruct",
            note = "Fast, low-cost general model. A good default for drafting and explaining.",
        ),
        NvidiaModel(
            id = "meta/llama-3.3-70b-instruct",
            label = "Llama 3.3 70B Instruct",
            note = "Larger model. Higher quality writing, slower and more quota per call.",
        ),
        NvidiaModel(
            id = "nvidia/llama-3.3-nemotron-super-49b-v1",
            label = "Nemotron Super 49B",
            note = "NVIDIA reasoning model. Strong at explanation, slower.",
        ),
        NvidiaModel(
            id = "mistralai/mistral-nemo-12b-instruct",
            label = "Mistral NeMo 12B",
            note = "General-purpose alternative if a preferred model is unavailable.",
        ),
    )

    val defaultModelId: String = known.first().id

    /** The label for an id: the known label, else the id itself so a custom id is legible. */
    fun labelFor(id: String): String = known.firstOrNull { it.id == id }?.label ?: id

    /** True when the id is one of the suggested models. */
    fun isKnown(id: String): Boolean = known.any { it.id == id }

    /**
     * True when the id is usable at all. Any non-blank id is accepted, because the account's
     * `/v1/models` list is the real source of truth and a hard whitelist would silently
     * reject a model the user's account genuinely serves.
     */
    fun isSelectable(id: String): Boolean = id.isNotBlank()

    /** A model entry for an id discovered from the account, with a readable label. */
    fun discovered(id: String): NvidiaModel = known.firstOrNull { it.id == id }
        ?: NvidiaModel(id = id, label = id, note = "Discovered on your NVIDIA account.")
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
