package com.aiglobal.jobfinder.data.jobs

import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.model.SalaryPeriod
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Last-known-good copy of the feeds, so the app opens with content and keeps working
 * offline. Written as one JSON document after every successful sync. The cache only
 * holds raw postings; scoring is always recomputed against the current profile.
 */
class JobCache(private val directory: File) {

    private val file: File get() = File(directory, FILE_NAME)

    fun read(): List<RawJob> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("jobs") ?: JSONArray()
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::fromJson)
            }
        }.onFailure { runCatching { file.delete() } }
            .getOrDefault(emptyList())
    }

    fun write(jobs: List<RawJob>) {
        runCatching {
            directory.mkdirs()
            val root = JSONObject().apply {
                put("version", SCHEMA_VERSION)
                put("savedAt", System.currentTimeMillis())
                put("jobs", JSONArray().apply { jobs.forEach { put(it.toJson()) } })
            }
            val temp = File(directory, "$FILE_NAME.tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }
    }

    private fun RawJob.toJson(): JSONObject = JSONObject().apply {
        put("providerId", providerId)
        put("providerName", providerName)
        put("externalId", externalId)
        put("title", title)
        put("company", company)
        put("location", location)
        put("descriptionHtml", descriptionHtml)
        put("url", url)
        put("tags", JSONArray(tags))
        put("employmentType", employmentType ?: JSONObject.NULL)
        put("postedAt", postedAtEpochMillis ?: JSONObject.NULL)
        put("isRemote", isRemote)
        put("salaryMin", salaryMin ?: JSONObject.NULL)
        put("salaryMax", salaryMax ?: JSONObject.NULL)
        put("salaryCurrency", salaryCurrency ?: JSONObject.NULL)
        put("salaryPeriod", salaryPeriod?.name ?: JSONObject.NULL)
    }

    private fun fromJson(json: JSONObject): RawJob = RawJob(
        providerId = json.optString("providerId"),
        providerName = json.optString("providerName"),
        externalId = json.optString("externalId"),
        title = json.optString("title"),
        company = json.optString("company"),
        location = json.optString("location"),
        descriptionHtml = json.optString("descriptionHtml"),
        url = json.optString("url"),
        tags = json.optJSONArray("tags").toStringList(),
        employmentType = json.optStringOrNull("employmentType"),
        postedAtEpochMillis = json.optLongOrNull("postedAt"),
        isRemote = json.optBoolean("isRemote", false),
        salaryMin = json.optIntOrNull("salaryMin"),
        salaryMax = json.optIntOrNull("salaryMax"),
        salaryCurrency = json.optStringOrNull("salaryCurrency"),
        salaryPeriod = json.optStringOrNull("salaryPeriod")
            ?.let { runCatching { SalaryPeriod.valueOf(it) }.getOrNull() },
    )

    private companion object {
        const val FILE_NAME = "job_cache.json"
        const val SCHEMA_VERSION = 1
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { if (isNull(it)) null else optString(it).takeIf { s -> s.isNotBlank() } }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key) || !has(key)) null else optInt(key)

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key) || !has(key)) null else optLong(key)
