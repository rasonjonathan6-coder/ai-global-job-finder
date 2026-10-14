package com.aiglobal.jobfinder.data.jobs.provider

import org.json.JSONObject

/**
 * Narrow seam over the HTTP client so providers can be exercised in tests with a
 * canned JSON body and no network. The default implementation is [HttpJsonFeed].
 */
interface JsonFeed {
    suspend fun objectAt(url: String, headers: Map<String, String> = emptyMap()): JSONObject

    /** POSTs a JSON body. Only the providers whose source uses POST need this. */
    suspend fun objectPost(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject
}

object HttpJsonFeed : JsonFeed {
    override suspend fun objectAt(url: String, headers: Map<String, String>): JSONObject =
        HttpJsonClient.getObject(url, headers)

    override suspend fun objectPost(
        url: String,
        body: String,
        headers: Map<String, String>,
    ): JSONObject = HttpJsonClient.postObject(url, body, headers)
}

/** Safely reads a JSON array of strings, tolerating nulls and non-string entries. */
internal fun org.json.JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        if (isNull(index)) null else optString(index).takeIf { it.isNotBlank() }
    }
}
