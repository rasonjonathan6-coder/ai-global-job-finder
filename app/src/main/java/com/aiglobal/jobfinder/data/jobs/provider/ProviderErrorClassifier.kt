package com.aiglobal.jobfinder.data.jobs.provider

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Turns whatever a provider threw into one [ProviderErrorCategory].
 *
 * The distinction matters to the user: a timeout, a rejected key and an unparseable body
 * all look like "no jobs" unless they are separated. The rules are ordered so the most
 * specific, most actionable cause wins.
 */
object ProviderErrorClassifier {

    fun classify(
        providerId: String,
        message: String?,
        cause: Throwable?,
    ): ProviderErrorCategory {
        val haystack = buildString {
            message?.let { append(it.lowercase()).append(' ') }
            cause?.message?.let { append(it.lowercase()) }
        }

        val httpStatus = HTTP_STATUS.find(haystack)?.groupValues?.get(1)?.toIntOrNull()
        if (httpStatus != null) {
            when (httpStatus) {
                401, 403 -> return ProviderErrorCategory.AUTHENTICATION
                429 -> return ProviderErrorCategory.RATE_LIMITED
            }
        }

        if (cause is SocketTimeoutException || cause is InterruptedIOException) {
            return ProviderErrorCategory.TIMEOUT
        }
        if (cause is UnknownHostException) return ProviderErrorCategory.NETWORK

        return when {
            AUTH.containsMatchIn(haystack) -> ProviderErrorCategory.AUTHENTICATION
            RATE_LIMIT.containsMatchIn(haystack) -> ProviderErrorCategory.RATE_LIMITED
            TIMEOUT.containsMatchIn(haystack) -> ProviderErrorCategory.TIMEOUT
            INVALID_RESPONSE.containsMatchIn(haystack) -> ProviderErrorCategory.INVALID_RESPONSE
            NETWORK.containsMatchIn(haystack) || cause is IOException -> ProviderErrorCategory.NETWORK
            httpStatus != null -> ProviderErrorCategory.HTTP_ERROR
            else -> ProviderErrorCategory.UNKNOWN
        }
    }

    /** Classification for a thrown error, honouring an explicit category on the exception. */
    fun classify(providerId: String, error: Throwable): ProviderErrorCategory {
        val explicit = (error as? JobProviderException)?.category
        if (explicit != null) return explicit
        return classify(providerId, error.message, error.cause ?: error)
    }

    private val HTTP_STATUS = Regex("\\b(\\d{3})\\b")
    private val AUTH = Regex("unauthor|forbidden|invalid (?:api )?key|invalid token|access denied|credential|not authorized|401|403")
    private val RATE_LIMIT = Regex("rate limit|too many requests|throttl|quota|429")
    private val TIMEOUT = Regex("timeout|timed out|deadline exceeded")
    private val INVALID_RESPONSE = Regex("invalid response|malformed|unexpected|not valid json|jsonexception|not a json")
    private val NETWORK = Regex("unable to resolve host|connection refused|connectexception|no address|network is unreachable|failed to connect|unknownhost")
}
