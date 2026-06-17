package net.bam.sfit.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val cacheJson = Json { ignoreUnknownKeys = true }

// ---- Today ----

@Serializable
data class CachedDay(
    val date: String = "",
    val goalCalories: Double = 0.0,
    val consumedCalories: Double = 0.0,
    val entries: List<FoodEntry> = emptyList(),
    val mealNames: Map<String, String> = emptyMap(),
    val mealGrams: Map<String, Double> = emptyMap(),
)

private val Context.dayCacheStore by preferencesDataStore(name = "day_cache")

class DayCacheStore(private val context: Context) {
    private val key = stringPreferencesKey("data")
    suspend fun load(): CachedDay? {
        val raw = context.dayCacheStore.data.first()[key] ?: return null
        return runCatching { cacheJson.decodeFromString<CachedDay>(raw) }.getOrNull()
    }
    suspend fun save(data: CachedDay) {
        context.dayCacheStore.edit { it[key] = cacheJson.encodeToString(data) }
    }
}

// ---- History ----

@Serializable
data class CachedHistoryRow(
    val label: String = "",
    val weight: Double? = null,
    val weightDelta: Double? = null,
    val deficit: Double? = null,
    val scaleDeficit: Double? = null,
    val impliedMaintenance: Double? = null,
    val impliedCalories: Double? = null,
    val date: String? = null,
    val checkInId: String? = null,
)

@Serializable
data class CachedHistory(
    val unit: String = "kg",
    val byGranularity: Map<String, List<CachedHistoryRow>> = emptyMap(),
)

private val Context.historyCacheStore by preferencesDataStore(name = "history_cache")

class HistoryCacheStore(private val context: Context) {
    private val key = stringPreferencesKey("data")
    suspend fun load(): CachedHistory? {
        val raw = context.historyCacheStore.data.first()[key] ?: return null
        return runCatching { cacheJson.decodeFromString<CachedHistory>(raw) }.getOrNull()
    }
    suspend fun save(data: CachedHistory) {
        context.historyCacheStore.edit { it[key] = cacheJson.encodeToString(data) }
    }
}
