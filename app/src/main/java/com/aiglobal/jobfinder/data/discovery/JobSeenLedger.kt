package com.aiglobal.jobfinder.data.discovery

import androidx.compose.runtime.Immutable
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.submissions.CanonicalUrl
import com.aiglobal.jobfinder.data.submissions.SubmissionKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One previously seen posting, with the content signal used to detect an update. */
@Immutable
data class SeenRecord(
    /** The stable posting id the feed assigns (`providerId:externalId`). */
    val id: String,
    /** Host-relative canonical URL, or empty when the source published no link. */
    val canonicalUrl: String,
    /** A cheap content signal (title + employer + location + salary + description length). */
    val contentSignature: String,
    /** When the app first saw this posting, so "discovered" can be dated. */
    val firstSeenAtEpochMillis: Long,
    /** When the app last read this posting from a source. */
    val lastSeenAtEpochMillis: Long,
)

/**
 * What changed between the previous read and this one.
 *
 * The distinction is the point of incremental search: a posting the app has never seen is
 * genuinely *new* and worth surfacing; one it already knew, whose content changed, is an
 * *update* to something the user may already have looked at; one that is unchanged is just
 * still there. Only the first two are "discovered"; the third is not news.
 */
@Immutable
data class DiscoveryDelta(
    val newJobIds: List<String> = emptyList(),
    val updatedJobIds: List<String> = emptyList(),
    /** Every posting the ledger now knows about, new or not. */
    val knownCount: Int = 0,
    val firstRun: Boolean = false,
) {
    val discoveredCount: Int get() = newJobIds.size + updatedJobIds.size
    val isEmpty: Boolean get() = newJobIds.isEmpty() && updatedJobIds.isEmpty()
}

/**
 * Remembers the postings the app has already seen, so a scheduled search can tell a
 * genuinely new opening apart from one it has shown before — including when the same
 * opening arrives from a different board under a different id, which is matched by its
 * canonical URL.
 *
 * Persistence is a single small JSON file, written atomically, capped so it cannot grow
 * without bound as the feed turns over. On the very first run the ledger is empty, so it
 * records the current feed as the baseline without announcing it: a fresh install should
 * not fire a burst of alerts for the whole market.
 */
class JobSeenLedger(
    context: android.content.Context,
    private val file: File = File(context.filesDir, "seen_jobs.json"),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val mutex = Mutex()

    /**
     * Folds [jobs] into the ledger and returns what changed.
     *
     * @param jobs the validated postings from the latest read
     * @param notifyBaseline when true, the very first run also reports the whole feed as new.
     *   Left false in production, so a fresh install records its baseline silently instead of
     *   announcing the entire market; tests set it true to exercise the announcement path.
     *   Later runs always report real changes regardless of this flag.
     */
    suspend fun observe(
        jobs: List<RawJob>,
        notifyBaseline: Boolean = false,
    ): DiscoveryDelta = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existing = read()
            val firstRun = existing.isEmpty()
            val now = clock()
            val next = LinkedHashMap<String, SeenRecord>(existing)
            val newIds = mutableListOf<String>()
            val updatedIds = mutableListOf<String>()

            for (raw in jobs) {
                val id = "${raw.providerId}:${raw.externalId}"
                val canonicalUrl = CanonicalUrl.of(raw.url)
                val signature = signatureOf(raw)
                val previous = existing[id] ?: matchByUrl(existing, canonicalUrl)

                when {
                    previous == null -> {
                        newIds += id
                        next[id] = SeenRecord(id, canonicalUrl, signature, now, now)
                    }
                    previous.contentSignature != signature -> {
                        updatedIds += id
                        next[id] = previous.copy(
                            canonicalUrl = canonicalUrl.ifBlank { previous.canonicalUrl },
                            contentSignature = signature,
                            lastSeenAtEpochMillis = now,
                        )
                    }
                    else -> {
                        next[id] = previous.copy(
                            canonicalUrl = canonicalUrl.ifBlank { previous.canonicalUrl },
                            lastSeenAtEpochMillis = now,
                        )
                    }
                }
            }

            // Keep only the most recently seen entries, so the file cannot grow forever.
            val capped = next.values
                .sortedByDescending { it.lastSeenAtEpochMillis }
                .take(MAX_ENTRIES)
                .associateBy { it.id }
            write(capped)

            val announce = !firstRun || notifyBaseline
            DiscoveryDelta(
                newJobIds = if (announce) newIds else emptyList(),
                updatedJobIds = if (announce) updatedIds else emptyList(),
                knownCount = capped.size,
                firstRun = firstRun,
            )
        }
    }

    suspend fun knownCount(): Int = withContext(Dispatchers.IO) { mutex.withLock { read().size } }

    /** Matches a posting already recorded under a different id but the same canonical URL. */
    private fun matchByUrl(existing: Map<String, SeenRecord>, canonicalUrl: String): SeenRecord? {
        if (canonicalUrl.isBlank()) return null
        return existing.values.firstOrNull { it.canonicalUrl == canonicalUrl }
    }

    private fun read(): Map<String, SeenRecord> = runCatching {
        if (!file.isFile) return emptyMap()
        val root = JSONObject(file.readText())
        val array = root.optJSONArray("seen") ?: return emptyMap()
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { obj ->
                val id = obj.optString("id")
                if (id.isBlank()) null
                else id to SeenRecord(
                    id = id,
                    canonicalUrl = obj.optString("url"),
                    contentSignature = obj.optString("sig"),
                    firstSeenAtEpochMillis = obj.optLong("first", 0L),
                    lastSeenAtEpochMillis = obj.optLong("last", 0L),
                )
            }
        }.toMap()
    }.onFailure { runCatching { file.delete() } }.getOrDefault(emptyMap())

    private fun write(records: Map<String, SeenRecord>) {
        runCatching {
            file.parentFile?.mkdirs()
            val array = JSONArray()
            records.values.forEach { record ->
                array.put(
                    JSONObject().apply {
                        put("id", record.id)
                        put("url", record.canonicalUrl)
                        put("sig", record.contentSignature)
                        put("first", record.firstSeenAtEpochMillis)
                        put("last", record.lastSeenAtEpochMillis)
                    },
                )
            }
            val root = JSONObject().apply {
                put("version", SCHEMA_VERSION)
                put("seen", array)
            }
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 3_000
        const val SCHEMA_VERSION = 1

        /**
         * A cheap content signal: enough to notice a re-written posting (a new title, salary,
         * location or a materially different description) without hashing the whole body on
         * every run. Description length rather than its text keeps the signature stable when
         * only whitespace or markup changes.
         */
        fun signatureOf(raw: RawJob): String = listOf(
            SubmissionKey.normalise(raw.title),
            SubmissionKey.normalise(raw.company),
            SubmissionKey.normalise(raw.location),
            raw.salaryMin ?: 0,
            raw.salaryMax ?: 0,
            raw.salaryCurrency ?: "",
            raw.descriptionHtml.length / 64,
        ).joinToString("|")
    }
}
