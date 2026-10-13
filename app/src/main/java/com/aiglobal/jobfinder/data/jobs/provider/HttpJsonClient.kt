package com.aiglobal.jobfinder.data.jobs.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /** GETs [url] and returns the raw body as text. */
    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("User-Agent", USER_AGENT)
            for ((key, value) in headers) setRequestProperty(key, value)
            instanceFollowRedirects = true
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                connection.errorStream?.close()
                throw java.io.IOException("HTTP $status for $url")
            }
            val encoding = connection.contentEncoding?.lowercase().orEmpty()
            val rawStream = connection.inputStream
            val stream = if (encoding.contains("gzip")) GZIPInputStream(rawStream) else rawStream
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
