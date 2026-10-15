package com.aiglobal.jobfinder.data.jobs.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Minimal HTTP JSON client built on [HttpURLConnection]. The app has three simple GET
 * feeds and no auth, so pulling in a full HTTP/JSON stack would be weight without
 * benefit. Only the behaviours these feeds need are implemented: gzip, timeouts,
 * a browser-like User-Agent, and a status check.
 */
object HttpJsonClient {

    private const val TIMEOUT_MS = 15_000
    private const val USER_AGENT =
        "AIGlobalJobFinder/1.0 (personal job search assistant; contact: local user)"

    /** GETs [url] and parses the body as a JSON object. */
    suspend fun getObject(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(getText(url, headers))
    }

    /** GETs [url] and parses the body as a JSON array. Lever's board feed is an array. */
    suspend fun getArray(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): JSONArray = withContext(Dispatchers.IO) {
        JSONArray(getText(url, headers))
    }

    /** POSTs [body] as JSON to [url] and parses the response as a JSON object. */
    suspend fun postObject(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(
            request(
                url = url,
                method = "POST",
                body = body,
                headers = headers + mapOf("Content-Type" to "application/json"),
            ),
        )
    }

    /** GETs [url] and returns the raw body as text. */
    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        request(url = url, method = "GET", body = null, headers = headers)
    }

    private fun request(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("User-Agent", USER_AGENT)
            for ((key, value) in headers) setRequestProperty(key, value)
            instanceFollowRedirects = true
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Length", body.toByteArray(Charsets.UTF_8).size.toString())
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                connection.errorStream?.close()
                throw java.io.IOException("HTTP $status for ${redactUrl(url)}")
            }
            val encoding = connection.contentEncoding?.lowercase().orEmpty()
            val rawStream = connection.inputStream
            val stream = if (encoding.contains("gzip")) GZIPInputStream(rawStream) else rawStream
            return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Strips anything that looks like a secret out of a URL before it is put in a message
     * or a stack trace. Adzuna requires its key as a query parameter, so without this a
     * failed request would carry the key into any log that records the exception.
     */
    internal fun redactUrl(url: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url
        val base = url.substring(0, queryStart)
        val params = url.substring(queryStart + 1)
            .split('&')
            .filter { it.isNotBlank() }
            .joinToString("&") { param ->
                val name = param.substringBefore('=')
                if (SECRET_PARAM.containsMatchIn(name)) "$name=***" else param
            }
        return "$base?$params"
    }

    private val SECRET_PARAM = Regex("(?i)(key|token|secret|password|passwd|auth|credential|app_?id)")
}
