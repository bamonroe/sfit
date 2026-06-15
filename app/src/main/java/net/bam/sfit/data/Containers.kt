package net.bam.sfit.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A named container with a known tare (empty) weight in grams. Stored locally. */
@Serializable
data class Container(
    val id: String,
    val name: String,
    val tareGrams: Double,
)

@Serializable
private data class ContainerList(val items: List<Container> = emptyList())

private val Context.containerStore by preferencesDataStore(name = "containers")

/** Local store for the user's containers (tare weights for net-from-gross). */
class ContainerStore(private val context: Context) {
    private val key = stringPreferencesKey("data")
    private val json = Json { ignoreUnknownKeys = true }

    val containers: Flow<List<Container>> = context.containerStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<ContainerList>(it).items }.getOrNull() }
            ?: emptyList()
    }

    private suspend fun save(items: List<Container>) {
        context.containerStore.edit { it[key] = json.encodeToString(ContainerList(items.sortedBy { c -> c.name.lowercase() })) }
    }

    suspend fun upsert(container: Container) {
        val current = currentList()
        save(current.filterNot { it.id == container.id } + container)
    }

    suspend fun delete(id: String) {
        save(currentList().filterNot { it.id == id })
    }

    private suspend fun currentList(): List<Container> {
        val raw = context.containerStore.data.first()[key] ?: return emptyList()
        return runCatching { json.decodeFromString<ContainerList>(raw).items }.getOrNull() ?: emptyList()
    }
}
