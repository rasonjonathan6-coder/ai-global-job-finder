package com.aiglobal.jobfinder.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaCredentials
import com.aiglobal.jobfinder.data.model.ExpatPreferences
import com.aiglobal.jobfinder.data.model.JobBenefitTypes
import com.aiglobal.jobfinder.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_job_finder_prefs")

/** Persists the small amount of state that must survive process death. */
class UserPreferencesRepository(
    context: Context,
    /**
     * The backing store. Defaults to the process-wide app preferences delegate; tests inject
     * one bound to a temporary file so they never touch the real preferences. The delegate
     * must stay the default: it caches a single DataStore per Context, which is what keeps
     * production from opening two stores on the same file.
     */
    private val dataStore: DataStore<Preferences> = context.dataStore,
) {

    private val themeKey = stringPreferencesKey("theme_mode")
    private val adzunaAppIdKey = stringPreferencesKey("adzuna_app_id")
    private val adzunaAppKeyKey = stringPreferencesKey("adzuna_app_key")
    private val joobleKey = stringPreferencesKey("jooble_api_key")
    private val disabledSourcesKey = stringSetPreferencesKey("disabled_source_ids")

    private val expatPrioritiesKey = stringSetPreferencesKey("expat_priorities")
    private val expatMinSalaryKey = intPreferencesKey("expat_min_salary_eur")
    private val expatTargetCountriesKey = stringSetPreferencesKey("expat_target_countries")
    private val expatMinExpatScoreKey = intPreferencesKey("expat_min_expat_score")
    private val expatMinMatchScoreKey = intPreferencesKey("expat_min_match_score")
    private val expatRequireVisaKey = booleanPreferencesKey("expat_require_visa")
    private val demoModeKey = booleanPreferencesKey("demo_mode_enabled")
    private val autoApplyEnabledKey = booleanPreferencesKey("auto_apply_enabled")
    private val savedJobIdsKey = stringSetPreferencesKey("saved_job_ids")

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[themeKey] ?: ThemeMode.System.name) }
            .getOrDefault(ThemeMode.System)
    }

    /**
     * Demo mode. Off by default, so a fresh install starts with an empty personal workspace
     * and never shows the sample candidate or the sample applications. When on, the app
     * serves curated fixture data so the UI can be explored without a CV or a network.
     */
    val demoMode: Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[demoModeKey] ?: false }

    suspend fun setDemoMode(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[demoModeKey] = enabled }
    }

    /**
     * The auto-apply preference. Persisted so the choice the user made in Profile survives a
     * restart and the profile's `autoApplyEnabled` reflects it after relaunch, not only in
     * the session where it was toggled.
     */
    val autoApplyEnabled: Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[autoApplyEnabledKey] ?: false }

    suspend fun setAutoApplyEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[autoApplyEnabledKey] = enabled }
    }

    /**
     * Ids of the postings the user bookmarked. Persisted so the bookmark and the "saved only"
     * filter survive a restart; the posting itself is re-fetched and re-scored from the feed.
     */
    val savedJobIds: Flow<Set<String>> = dataStore.data
        .map { prefs -> prefs[savedJobIdsKey].orEmpty() }

    suspend fun setSavedJobIds(ids: Set<String>) {
        dataStore.edit { prefs -> prefs[savedJobIdsKey] = ids }
    }

    /**
     * Adzuna API credentials, entered by the user in Settings. Adzuna is the only
     * source that needs a key; it stays dormant until both values are present.
     */
    val adzunaCredentials: Flow<AdzunaCredentials> = dataStore.data.map { prefs ->
        AdzunaCredentials(
            appId = prefs[adzunaAppIdKey].orEmpty(),
            appKey = prefs[adzunaAppKeyKey].orEmpty(),
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[themeKey] = mode.name }
    }

    suspend fun setAdzunaCredentials(appId: String, appKey: String) {
        dataStore.edit {
            it[adzunaAppIdKey] = appId.trim()
            it[adzunaAppKeyKey] = appKey.trim()
        }
    }

    /** Jooble API key; the provider stays dormant while it is blank. */
    val joobleApiKey: Flow<String> = dataStore.data.map { prefs ->
        prefs[joobleKey].orEmpty()
    }

    suspend fun setJoobleApiKey(key: String) {
        dataStore.edit { it[joobleKey] = key.trim() }
    }

    /** Sources the user has switched off. Kept so a disabled source stays disabled. */
    val disabledSourceIds: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[disabledSourcesKey].orEmpty()
    }

    suspend fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[disabledSourcesKey].orEmpty()
            prefs[disabledSourcesKey] = if (enabled) current - sourceId else current + sourceId
        }
    }

    /**
     * The user's expat priorities and gates. Priorities are stored as "key=weight" strings
     * because DataStore has no map type; unknown or malformed entries are dropped rather
     * than crashing, so an old build's leftovers cannot break a newer one.
     */
    val expatPreferences: Flow<ExpatPreferences> = dataStore.data.map { prefs ->
        val priorities = prefs[expatPrioritiesKey]
            .orEmpty()
            .mapNotNull { entry ->
                val key = entry.substringBefore('=', "").trim()
                val weight = entry.substringAfter('=', "").trim().toIntOrNull()
                if (key.isNotBlank() && weight != null && JobBenefitTypes.byKey(key) != null) key to weight
                else null
            }
            .toMap()
        ExpatPreferences(
            priorities = priorities,
            minimumSalaryEur = prefs[expatMinSalaryKey],
            targetCountries = prefs[expatTargetCountriesKey].orEmpty(),
            minExpatScore = prefs[expatMinExpatScoreKey] ?: 0,
            minMatchScore = prefs[expatMinMatchScoreKey] ?: 0,
            requireVisaSponsorship = prefs[expatRequireVisaKey] ?: false,
        )
    }

    suspend fun setExpatPriorities(priorities: Map<String, Int>) {
        dataStore.edit { prefs ->
            prefs[expatPrioritiesKey] = priorities
                .filterKeys { JobBenefitTypes.byKey(it) != null }
                .map { (key, weight) -> "$key=$weight" }
                .toSet()
        }
    }

    suspend fun setExpatMinimumSalary(minimumEur: Int?) {
        dataStore.edit { prefs ->
            if (minimumEur == null || minimumEur <= 0) prefs.remove(expatMinSalaryKey)
            else prefs[expatMinSalaryKey] = minimumEur
        }
    }

    suspend fun setExpatTargetCountries(countries: Set<String>) {
        dataStore.edit { prefs -> prefs[expatTargetCountriesKey] = countries }
    }

    suspend fun setExpatMinScores(minExpatScore: Int, minMatchScore: Int) {
        dataStore.edit { prefs ->
            prefs[expatMinExpatScoreKey] = minExpatScore.coerceIn(0, 100)
            prefs[expatMinMatchScoreKey] = minMatchScore.coerceIn(0, 100)
        }
    }

    suspend fun setRequireVisaSponsorship(required: Boolean) {
        dataStore.edit { prefs -> prefs[expatRequireVisaKey] = required }
    }
}
