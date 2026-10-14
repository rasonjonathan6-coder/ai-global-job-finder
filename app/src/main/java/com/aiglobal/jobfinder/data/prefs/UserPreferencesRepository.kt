package com.aiglobal.jobfinder.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaCredentials
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
}
