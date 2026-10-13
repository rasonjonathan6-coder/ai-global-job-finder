package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable

/** Outcome of the most recent attempt to read one source. */
enum class SourceState { IDLE, LOADING, SUCCESS, ERROR }

/** A job source and how its last sync went, surfaced so failures are never silent. */
@Immutable
data class JobSource(
    val id: String,
    val name: String,
    val homepage: String,
    val state: SourceState,
    val jobCount: Int = 0,
    val message: String? = null,
) {
    val isOfficial: Boolean get() = id == "arbeitnow" || id == "remotive" || id == "jobicy"
}

/** Aggregate sync status for the whole job feed. */
@Immutable
data class JobSyncState(
    val isRefreshing: Boolean = false,
    val lastSyncedAtEpochMillis: Long? = null,
    val sources: List<JobSource> = emptyList(),
    /** True when every source failed, so the list on screen is cached data only. */
    val showingCachedOnly: Boolean = false,
) {
    val successCount: Int get() = sources.count { it.state == SourceState.SUCCESS }
    val errorCount: Int get() = sources.count { it.state == SourceState.ERROR }
}
