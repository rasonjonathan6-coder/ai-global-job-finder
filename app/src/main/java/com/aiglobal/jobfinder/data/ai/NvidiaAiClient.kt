package com.aiglobal.jobfinder.data.ai

import com.aiglobal.jobfinder.data.model.NvidiaResult
import com.aiglobal.jobfinder.data.model.NvidiaStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.zip.GZIPInputStream

/** One raw HTTP reply: the status and the body text, nothing interpreted. */
data class NvidiaHttpResponse(val statusCode: Int, val body: String)

/**
 * The network seam for NVIDIA, so the client's parsing and error classification can be
 * exercised with canned responses and no live call. The API key travels in the
 * `Authorization` header, never in the URL, so it cannot leak through a query string.
 */
interface NvidiaTransport {
    suspend fun chatCompletions(apiKey: String, body: String, timeoutMillis: Int): NvidiaHttpResponse
    suspend fun listModels(apiKey: String, timeoutMillis: Int): NvidiaHttpResponse
}

/** Default transport built on [HttpURLConnection]; no extra HTTP dependency is needed. */
object HttpNvidiaTransport : NvidiaTransport {

    override suspend fun chatCompletions(
        apiKey: String,
        body: String,
        timeoutMillis: Int,
    ): NvidiaHttpResponse = withContext(Dispatchers.IO) {
        request("$BASE_URL/chat/completions", apiKey, body, timeoutMillis)
    }

    override suspend fun listModels(apiKey: String, timeoutMillis: Int): NvidiaHttpResponse =
        withContext(Dispatchers.IO) {
            request("$BASE_URL/models", apiKey, null, timeoutMillis)
        }

    private fun request(url: String, apiKey: String, body: String?, timeoutMillis: Int): NvidiaHttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "gzip")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { raw ->
                val encoding = connection.contentEncoding?.lowercase().orEmpty()
                val decoded = if (encoding.contains("gzip")) GZIPInputStream(raw) else raw
                BufferedReader(InputStreamReader(decoded, Charsets.UTF_8)).use { it.readText() }
            }.orEmpty()
            return NvidiaHttpResponse(status, text)
        } finally {
            connection.disconnect()
        }
    }

    private const val BASE_URL = "https://integrate.api.nvidia.com/v1"
}

/**
 * Calls NVIDIA's OpenAI-compatible chat endpoint and classifies every failure into one
 * [NvidiaStatus], so the UI can say what actually happened instead of "something went wrong".
 *
 * Safety: the key is never placed in a message, the request body is bounded by
 * [maxOutputTokens], and every call has a timeout.
 */
class NvidiaAiClient(
    private val transport: NvidiaTransport = HttpNvidiaTransport,
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MS,
    private val maxOutputTokens: Int = DEFAULT_MAX_TOKENS,
) {

    /**
     * Sends one system+user prompt and returns the model's text, or a classified failure.
     * A blank key short-circuits to [NvidiaStatus.NOT_CONFIGURED] with no network call.
     */
    suspend fun complete(
        apiKey: String,
        modelId: String,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = maxOutputTokens,
    ): NvidiaResult {
        if (apiKey.isBlank()) return failure(NvidiaStatus.NOT_CONFIGURED, "Add your NVIDIA API key in Profile.")
        if (modelId.isBlank()) return failure(NvidiaStatus.MODEL_UNAVAILABLE, "No NVIDIA model is selected.")
        // A model id that is not in `vendor/name` shape cannot resolve at NVIDIA, so reject it
        // before spending a request rather than surfacing an opaque HTTP error.
        if (!looksLikeModelId(modelId)) {
            return failure(
                NvidiaStatus.UNSUPPORTED_MODEL,
                "That does not look like a model id. Use the form vendor/model, e.g. meta/llama-3.1-8b-instruct.",
            )
        }

        val body = buildRequestBody(modelId, systemPrompt, userPrompt, maxTokens)
        val response = try {
            transport.chatCompletions(apiKey, body, timeoutMillis)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return classifyThrowable(t)
        }
        return interpret(response)
    }

    /**
     * The cheapest possible end-to-end inference check: a two-word prompt at the smallest
     * output budget the client allows. It proves the account can actually run the selected
     * model — not just list it — before the user relies on it. The answer is thrown away;
     * only the classified outcome matters.
     */
    suspend fun testModel(apiKey: String, modelId: String): NvidiaResult {
        if (apiKey.isBlank()) return failure(NvidiaStatus.NOT_CONFIGURED, "Add your NVIDIA API key first.")
        if (modelId.isBlank()) return failure(NvidiaStatus.MODEL_UNAVAILABLE, "Select a model first.")
        if (!looksLikeModelId(modelId)) {
            return failure(
                NvidiaStatus.UNSUPPORTED_MODEL,
                "That does not look like a model id. Use the form vendor/model.",
            )
        }
        val body = buildRequestBody(
            modelId = modelId,
            systemPrompt = "You are a connectivity check. Reply with the single word: ok",
            userPrompt = "ok",
            maxTokens = 1,
        )
        val response = try {
            transport.chatCompletions(apiKey, body, timeoutMillis)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return classifyThrowable(t)
        }
        if (response.statusCode !in 200..299) return interpretError(response)
        // "On" must mean "this model, with these settings, produced text". A reasoning model
        // can answer HTTP 200 with `content: null` (all budget spent thinking), and an empty
        // body is equally unusable, so both fail here rather than falsely marking the service
        // operational. This matches what [complete] requires.
        val text = extractContent(response.body)
        return if (text.isBlank()) {
            failure(
                NvidiaStatus.BAD_RESPONSE,
                "The model answered but returned no text with these settings. Pick another model.",
            )
        } else {
            NvidiaResult(NvidiaStatus.OK, text = "The model answered. It can be used for drafting.")
        }
    }

    /** True for a plausible NVIDIA model id (`vendor/name`), the only shape the API resolves. */
    internal fun looksLikeModelId(modelId: String): Boolean =
        MODEL_ID.matches(modelId.trim())

    /** Validates a key by listing models. Used by the "Test connection" action. */
    suspend fun validateKey(apiKey: String): NvidiaResult {
        if (apiKey.isBlank()) return failure(NvidiaStatus.NOT_CONFIGURED, "Enter an API key first.")
        val response = try {
            transport.listModels(apiKey, timeoutMillis)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return classifyThrowable(t)
        }
        return when {
            response.statusCode == 200 -> NvidiaResult(NvidiaStatus.OK)
            response.statusCode in AUTH_FAILURE -> failure(
                NvidiaStatus.INVALID_KEY,
                "NVIDIA rejected this key. Check it has not expired.",
            )
            response.statusCode == 429 -> failure(
                NvidiaStatus.RATE_LIMITED,
                "NVIDIA is rate limiting this account. Try again shortly.",
            )
            response.statusCode in 500..599 -> failure(
                NvidiaStatus.NETWORK,
                "NVIDIA returned HTTP ${response.statusCode}. Its service may be unavailable.",
            )
            else -> failure(
                NvidiaStatus.BAD_RESPONSE,
                "NVIDIA answered with HTTP ${response.statusCode}.",
            )
        }
    }

    /**
     * Asks NVIDIA which models the account can actually use. Unlike [validateKey] this returns
     * the ids, so the user can pick a real one instead of guessing. On success [NvidiaResult.text]
     * is a newline-separated list of ids; the failure classification is the same as elsewhere.
     */
    suspend fun listModelIds(apiKey: String): NvidiaResult {
        if (apiKey.isBlank()) return failure(NvidiaStatus.NOT_CONFIGURED, "Enter an API key first.")
        val response = try {
            transport.listModels(apiKey, timeoutMillis)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return classifyThrowable(t)
        }
        if (response.statusCode !in 200..299) return interpretError(response)
        val ids = extractModelIds(response.body)
        return if (ids.isEmpty()) {
            failure(NvidiaStatus.BAD_RESPONSE, "NVIDIA returned no models for this account.")
        } else {
            NvidiaResult(NvidiaStatus.OK, text = ids.joinToString("\n"))
        }
    }

    /** Reads `data[].id` from a `/v1/models` body, tolerating any ordering or extra fields. */
    internal fun extractModelIds(body: String): List<String> = runCatching {
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        buildList {
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
                if (id != null) add(id)
            }
        }.distinct().sorted()
    }.getOrDefault(emptyList())

    internal fun interpret(response: NvidiaHttpResponse): NvidiaResult {
        if (response.statusCode !in 200..299) return interpretError(response)
        val text = extractContent(response.body)
        return if (text.isBlank()) {
            failure(
                NvidiaStatus.BAD_RESPONSE,
                "The model returned no usable text. It may have been cut off by the output limit.",
            )
        } else {
            NvidiaResult(NvidiaStatus.OK, text = text)
        }
    }

    private fun interpretError(response: NvidiaHttpResponse): NvidiaResult {
        val detail = extractDetail(response.body)
        return when {
            response.statusCode in AUTH_FAILURE -> failure(
                NvidiaStatus.INVALID_KEY,
                "NVIDIA rejected the key. Open the key in your NVIDIA account and paste it again.",
            )
            response.statusCode == 429 -> failure(
                NvidiaStatus.RATE_LIMITED,
                "NVIDIA is rate limiting requests or the account quota is reached. Wait, then retry.",
            )
            // 404/410 are how NVIDIA reports a model that is not available to this account or
            // has been retired. Both mean "pick another model", not "the app is broken".
            response.statusCode == 404 || response.statusCode == 410 -> failure(
                NvidiaStatus.MODEL_UNAVAILABLE,
                detail.ifBlank { "This model is not available to your account. Choose another model." },
            )
            response.statusCode in 500..599 -> failure(
                NvidiaStatus.NETWORK,
                "NVIDIA returned HTTP ${response.statusCode}. Its service may be temporarily unavailable.",
            )
            else -> failure(NvidiaStatus.BAD_RESPONSE, "NVIDIA answered with HTTP ${response.statusCode}.")
        }
    }

    private fun classifyThrowable(t: Throwable): NvidiaResult = when (t) {
        is SocketTimeoutException -> failure(
            NvidiaStatus.TIMEOUT,
            "NVIDIA did not answer in time. Try again, or pick a smaller model.",
        )
        is UnknownHostException -> failure(
            NvidiaStatus.NETWORK,
            "NVIDIA could not be reached. Check your internet connection.",
        )
        else -> failure(NvidiaStatus.NETWORK, "The request to NVIDIA failed: ${t.javaClass.simpleName}.")
    }

    /**
     * Extracts `choices[0].message.content`; tolerant of a non-string or missing content.
     * A JSON `null` is treated as absent: reasoning models return `content: null`, and the
     * naive `toString()` would turn it into the literal text "null".
     */
    internal fun extractContent(body: String): String = runCatching {
        val choices = JSONObject(body).optJSONArray("choices") ?: return ""
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
        if (message.isNull("content")) return ""
        when (val value = message.opt("content")) {
            null, JSONObject.NULL -> ""
            is String -> value.trim()
            else -> value.toString().trim()
        }
    }.getOrDefault("")

    /** Pulls the human-readable reason out of an NVIDIA error body, e.g. `detail` or `title`. */
    internal fun extractDetail(body: String): String = runCatching {
        val json = JSONObject(body)
        listOf("detail", "title", "message", "error")
            .asSequence()
            .mapNotNull { key ->
                when (val value = json.opt(key)) {
                    is String -> value.takeIf { it.isNotBlank() }
                    is JSONObject -> value.optString("message").takeIf { it.isNotBlank() }
                    else -> null
                }
            }
            .firstOrNull()
            .orEmpty()
    }.getOrDefault("")

    private fun buildRequestBody(modelId: String, systemPrompt: String, userPrompt: String, maxTokens: Int): String =
        JSONObject().apply {
            put("model", modelId)
            put("temperature", 0.2)
            put("top_p", 0.9)
            put("max_tokens", maxTokens.coerceIn(64, MAX_TOKENS_CEILING))
            put(
                "messages",
                org.json.JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", userPrompt))
                },
            )
        }.toString()

    private fun failure(status: NvidiaStatus, message: String) =
        NvidiaResult(status = status, message = message)

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000
        const val DEFAULT_MAX_TOKENS = 700
        const val MAX_TOKENS_CEILING = 2_000
        private val AUTH_FAILURE = setOf(401, 403)

        /** NVIDIA model ids are always `vendor/name`, e.g. `meta/llama-3.1-8b-instruct`. */
        private val MODEL_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*$")
    }
}
