package net.bam.sfit.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A food plus its cached usage stats, so either sort order works offline. */
@Serializable
data class CachedFood(
    val id: String = "",
    val name: String = "",
    val brand: String? = null,
    val count: Int = 0,
    val lastDate: String = "",
)

/** Everything the Library needs, cached locally. */
@Serializable
data class CachedLibrary(
    val foods: List<CachedFood> = emptyList(),
    val meals: List<LibraryMeal> = emptyList(),
    val totalFoods: Int = 0,
)

private val Context.libraryCacheStore by preferencesDataStore(name = "library_cache")

/** Persists the Library data so it renders instantly and re-sorts without the network. */
class LibraryCacheStore(private val context: Context) {
    private val key = stringPreferencesKey("data")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): CachedLibrary? {
        val raw = context.libraryCacheStore.data.first()[key] ?: return null
        return runCatching { json.decodeFromString<CachedLibrary>(raw) }.getOrNull()
    }

    suspend fun save(data: CachedLibrary) {
        context.libraryCacheStore.edit { it[key] = json.encodeToString(data) }
    }
}
