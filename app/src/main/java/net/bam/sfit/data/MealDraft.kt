package net.bam.sfit.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One ingredient in a meal draft (already resolved to a DB food). */
@Serializable
data class DraftIngredient(
    val foodId: String,
    val variantId: String,
    val name: String,
    val brand: String? = null,
    val barcode: String = "",
    val calories: Double = 0.0,   // per servingSize
    val servingSize: Double = 100.0,
    val servingUnit: String = "g",
    val grams: Double = 0.0,      // quantity the user sets later
) {
    val kcal: Double get() = if (servingSize > 0) calories * grams / servingSize else 0.0
}

/** A meal being assembled. Persisted locally so a background-kill can't lose it. */
@Serializable
data class MealDraft(
    val name: String = "",
    val ingredients: List<DraftIngredient> = emptyList(),
) {
    val isEmpty: Boolean get() = name.isBlank() && ingredients.isEmpty()
    val totalKcal: Double get() = ingredients.sumOf { it.kcal }
    val totalGrams: Double get() = ingredients.sumOf { it.grams }
}

private val Context.draftStore by preferencesDataStore(name = "meal_draft")

/** Persists the single in-progress [MealDraft] as JSON in DataStore. */
class DraftStore(private val context: Context) {
    private val key = stringPreferencesKey("draft")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): MealDraft? {
        val raw = context.draftStore.data.first()[key] ?: return null
        return runCatching { json.decodeFromString<MealDraft>(raw) }.getOrNull()
    }

    suspend fun save(draft: MealDraft) {
        context.draftStore.edit { it[key] = json.encodeToString(draft) }
    }

    suspend fun clear() {
        context.draftStore.edit { it.remove(key) }
    }
}
