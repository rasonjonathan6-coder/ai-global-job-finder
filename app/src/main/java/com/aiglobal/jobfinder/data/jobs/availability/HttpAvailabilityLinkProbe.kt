package com.aiglobal.jobfinder.data.jobs.availability

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reaches one application link and reports only what it can reliably observe.
 *
 * Deliberately cautious, because the failure modes here are the dangerous ones:
 *  - It reads at most [MAX_BODY_BYTES] of the response, so a huge or endless page cannot hang
 *    the app; the markers that matter appear near the top of a posting page.
 *  - A timeout, a connection error, an SSL problem or an unexpected status all return
 *    [LinkProbeOutcome.Unknown]. None of them is ever read as "expired".
 *  - A plain HTTP 200 is handed to [LinkProbeClassifier], which requires a positive marker
 *    before it will call the posting open.
 *
 * It is a best-effort, per-link check run at most once a day per posting (the caller's cache
 * enforces that). It does not crawl, does not follow links out of the page, and sends a
 * single request with a clear User-Agent.
 */
class HttpAvailabilityLinkProbe(
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MS,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : AvailabilityLinkProbe {

    override suspend fun probe(url: String): LinkProbeOutcome = withContext(Dispatchers.IO) {
        if (url.isBlank()) {
            return@withContext LinkProbeOutcome.Unknown("The posting has no application link to check.")
        }
        runCatching { request(url) }.getOrElse { error ->
            // A network error, a timeout or a TLS failure is a fact about this device's
            // connection — never a claim that the vacancy closed.
            LinkProbeOutcome.Unknown(
                "The application link could not be reached (${error::class.simpleName}). This does not mean the posting is closed.",
            )
        }
    }

    private fun request(url: String): LinkProbeOutcome {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val status = connection.responseCode
            // A 2xx body comes from getInputStream; an error status is on the error stream.
            val stream: InputStream? = if (status in 200..299) {
                runCatching { connection.inputStream }.getOrNull()
            } else {
                connection.errorStream
            }
            val body = stream?.use { readCapped(it) } ?: ""
            return LinkProbeClassifier.classify(status, body)
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun readCapped(stream: InputStream): String {
        val buffer = ByteArray(16 * 1024)
        val out = StringBuilder()
        var total = 0
        while (total < MAX_BODY_BYTES) {
            val read = stream.read(buffer)
            if (read <= 0) break
            out.append(String(buffer, 0, read, Charsets.UTF_8))
            total += read
        }
        return out.toString()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Int = 8_000
        const val MAX_BODY_BYTES: Int = 256 * 1024
        const val DEFAULT_USER_AGENT: String =
            "AIGlobalJobFinder/1.0 (personal job search assistant; contact: local user)"
    }
}
