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
class UserPreferencesRepository(private val context: Context) {

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

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[themeKey] ?: ThemeMode.System.name) }
            .getOrDefault(ThemeMode.System)
    }

    /**
     * Adzuna API credentials, entered by the user in Settings. Adzuna is the only
     * source that needs a key; it stays dormant until both values are present.
     */
    val adzunaCredentials: Flow<AdzunaCredentials> = context.dataStore.data.map { prefs ->
        AdzunaCredentials(
            appId = prefs[adzunaAppIdKey].orEmpty(),
            appKey = prefs[adzunaAppKeyKey].orEmpty(),
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[themeKey] = mode.name }
    }

    suspend fun setAdzunaCredentials(appId: String, appKey: String) {
        context.dataStore.edit {
            it[adzunaAppIdKey] = appId.trim()
            it[adzunaAppKeyKey] = appKey.trim()
        }
    }

    /** Jooble API key; the provider stays dormant while it is blank. */
    val joobleApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[joobleKey].orEmpty()
    }

    suspend fun setJoobleApiKey(key: String) {
        context.dataStore.edit { it[joobleKey] = key.trim() }
    }

    /** Sources the user has switched off. Kept so a disabled source stays disabled. */
    val disabledSourceIds: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[disabledSourcesKey].orEmpty()
    }

    suspend fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[disabledSourcesKey].orEmpty()
            prefs[disabledSourcesKey] = if (enabled) current - sourceId else current + sourceId
        }
    }

    /**
     * The user's expat priorities and gates. Priorities are stored as "key=weight" strings
     * because DataStore has no map type; unknown or malformed entries are dropped rather
     * than crashing, so an old build's leftovers cannot break a newer one.
     */
    val expatPreferences: Flow<ExpatPreferences> = context.dataStore.data.map { prefs ->
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
        context.dataStore.edit { prefs ->
            prefs[expatPrioritiesKey] = priorities
                .filterKeys { JobBenefitTypes.byKey(it) != null }
                .map { (key, weight) -> "$key=$weight" }
                .toSet()
        }
    }

    suspend fun setExpatMinimumSalary(minimumEur: Int?) {
        context.dataStore.edit { prefs ->
            if (minimumEur == null || minimumEur <= 0) prefs.remove(expatMinSalaryKey)
            else prefs[expatMinSalaryKey] = minimumEur
        }
    }

    suspend fun setExpatTargetCountries(countries: Set<String>) {
        context.dataStore.edit { prefs -> prefs[expatTargetCountriesKey] = countries }
    }

    suspend fun setExpatMinScores(minExpatScore: Int, minMatchScore: Int) {
        context.dataStore.edit { prefs ->
            prefs[expatMinExpatScoreKey] = minExpatScore.coerceIn(0, 100)
            prefs[expatMinMatchScoreKey] = minMatchScore.coerceIn(0, 100)
        }
    }

    suspend fun setRequireVisaSponsorship(required: Boolean) {
        context.dataStore.edit { prefs -> prefs[expatRequireVisaKey] = required }
    }
}
