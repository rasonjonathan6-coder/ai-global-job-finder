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
 * The postings discovered since the user last reviewed them, plus whether a baseline has
 * been recorded yet. "New" means genuinely first seen after the baseline was established —
 * a re-written posting is an update, not a new opening — so this is the list the Jobs
 * "New" filter and the Home card both read.
 */
@Immutable
data class NewPostings(
    /**
     * Ids first seen strictly after the reviewed watermark. A fresh install has no baseline
     * yet, so this is empty until the first read completes: the initial market snapshot is
     * never presented as "new".
     */
    val jobIds: List<String> = emptyList(),
    /**
     * When each of [jobIds] was first seen, keyed by id. Carried beside the ids so a card can
     * date its discovery from a recorded fact instead of re-deriving it.
     */
    val discoveredAtByJobId: Map<String, Long> = emptyMap(),
    /** When the user last marked the new postings reviewed, or null when never. */
    val reviewedAtEpochMillis: Long? = null,
    /** True once the first read has recorded a baseline, so "no new postings" is meaningful. */
    val baselineEstablished: Boolean = false,
) {
    val count: Int get() = jobIds.size
    val isEmpty: Boolean get() = jobIds.isEmpty()
}

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
            val document = readDocument()
            val existing = document.records
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
            writeDocument(
                document.copy(
                    records = capped,
                    // The first read records the baseline; the moment is kept so "no new
                    // postings yet" can be told apart from "not searching yet".
                    baselineAtEpochMillis = document.baselineAtEpochMillis ?: now,
                ),
            )

            val announce = !firstRun || notifyBaseline
            DiscoveryDelta(
                newJobIds = if (announce) newIds else emptyList(),
                updatedJobIds = if (announce) updatedIds else emptyList(),
                knownCount = capped.size,
                firstRun = firstRun,
            )
        }
    }

    suspend fun knownCount(): Int = withContext(Dispatchers.IO) { mutex.withLock { readDocument().records.size } }

    /**
     * The postings first seen since the user last reviewed them. A fresh install has no
     * baseline yet, so this stays empty until the first read completes: the initial market
     * snapshot is never announced as new. Only genuinely new postings count — an update to a
     * posting already known is not a new opening.
     */
    suspend fun newPostings(): NewPostings = withContext(Dispatchers.IO) {
        mutex.withLock {
            val document = readDocument()
            val baseline = document.baselineAtEpochMillis
            val reviewed = document.reviewedAtEpochMillis
            val ids = if (baseline == null) {
                emptyList()
            } else {
                document.records.values
                    .filter { it.firstSeenAtEpochMillis > baseline && (reviewed == null || it.firstSeenAtEpochMillis > reviewed) }
                    .sortedByDescending { it.firstSeenAtEpochMillis }
                    .map { it.id }
            }
            NewPostings(
                jobIds = ids,
                discoveredAtByJobId = ids.associateWith { document.records.getValue(it).firstSeenAtEpochMillis },
                reviewedAtEpochMillis = reviewed,
                baselineEstablished = baseline != null,
            )
        }
    }

    /**
     * Marks every posting discovered so far as reviewed, so the "new" list clears and a later
     * discovery is measured from here. The watermark is the current time, which is always at or
     * after every recorded `firstSeenAtEpochMillis`, so nothing already seen can re-appear as new.
     */
    suspend fun markReviewed(at: Long = clock()): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val document = readDocument()
            writeDocument(
                document.copy(
                    reviewedAtEpochMillis = at,
                    baselineAtEpochMillis = document.baselineAtEpochMillis ?: at,
                ),
            )
        }
    }

    /** Matches a posting already recorded under a different id but the same canonical URL. */
    private fun matchByUrl(existing: Map<String, SeenRecord>, canonicalUrl: String): SeenRecord? {
        if (canonicalUrl.isBlank()) return null
        return existing.values.firstOrNull { it.canonicalUrl == canonicalUrl }
    }

    private fun readDocument(): LedgerDocument = runCatching {
        if (!file.isFile) return LedgerDocument()
        val root = JSONObject(file.readText())
        val array = root.optJSONArray("seen") ?: JSONArray()
        val records = (0 until array.length()).mapNotNull { index ->
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
        LedgerDocument(
            records = records,
            reviewedAtEpochMillis = root.optLongOrNull("reviewedAt"),
            baselineAtEpochMillis = root.optLongOrNull("baselineAt"),
        )
    }.onFailure { runCatching { file.delete() } }.getOrDefault(LedgerDocument())

    private fun writeDocument(document: LedgerDocument) {
        runCatching {
            file.parentFile?.mkdirs()
            val array = JSONArray()
            document.records.values.forEach { record ->
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
                put("reviewedAt", document.reviewedAtEpochMillis ?: JSONObject.NULL)
                put("baselineAt", document.baselineAtEpochMillis ?: JSONObject.NULL)
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

/** The persisted ledger file: the seen postings plus the review/baseline watermarks. */
private data class LedgerDocument(
    val records: Map<String, SeenRecord> = emptyMap(),
    /** When the user last marked the new postings reviewed, or null when never. */
    val reviewedAtEpochMillis: Long? = null,
    /** When the first read established the baseline, or null before any read has finished. */
    val baselineAtEpochMillis: Long? = null,
)

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key) || !has(key)) null else optLong(key)
