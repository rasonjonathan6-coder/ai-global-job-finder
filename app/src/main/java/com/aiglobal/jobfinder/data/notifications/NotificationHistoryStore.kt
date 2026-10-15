package com.aiglobal.jobfinder.data.notifications

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Remembers which postings the user has already been notified about, so a job is never
 * announced twice — not on the next refresh, and not after a restart.
 *
 * Storage is a single newline-delimited file of job ids, written atomically. It is
 * deliberately tiny: the payload is just the ids, and a bounded cap keeps it from growing
 * without limit as the feed turns over.
 */
class NotificationHistoryStore(
    context: Context,
    private val file: File = File(context.filesDir, "notified_jobs.txt"),
) {

    private val mutex = Mutex()

    suspend fun notifiedIds(): Set<String> = withContext(Dispatchers.IO) {
        mutex.withLock { read() }
    }

    /** Adds [ids] to the history and returns the full set, capped to the most recent entries. */
    suspend fun record(ids: Collection<String>): Set<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val merged = (read() + ids).toList().takeLast(MAX_ENTRIES).toSet()
            write(merged)
            merged
        }
    }

    private fun read(): Set<String> =
        runCatching { file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() }
            .getOrDefault(emptySet())

    private fun write(ids: Set<String>) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(ids.joinToString("\n"))
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 2_000
    }
}
