package net.bam.sfit.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** User configuration needed to reach their SparkyFitness instance. */
data class Settings(
    val baseUrl: String = "",
    val apiKey: String = "",
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()
}

/** Persists [Settings] via Jetpack DataStore. */
class SettingsStore(private val context: Context) {
    private val keyBaseUrl = stringPreferencesKey("base_url")
    private val keyApiKey = stringPreferencesKey("api_key")

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(baseUrl = p[keyBaseUrl] ?: "", apiKey = p[keyApiKey] ?: "")
    }

    suspend fun save(baseUrl: String, apiKey: String) {
        context.dataStore.edit { p ->
            p[keyBaseUrl] = baseUrl.trim()
            p[keyApiKey] = apiKey.trim()
        }
    }
}
