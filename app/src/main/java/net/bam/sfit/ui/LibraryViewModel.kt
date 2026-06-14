package net.bam.sfit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.bam.sfit.data.AppRepository
import net.bam.sfit.data.BarcodeFood
import net.bam.sfit.data.FoodUsage
import net.bam.sfit.data.LibraryFood
import net.bam.sfit.data.LibraryMeal
import net.bam.sfit.data.SparkyApi
import java.time.LocalDate

enum class SortMode { Frequency, Alphabetical }

data class LibraryState(
    val loading: Boolean = false,
    val foods: List<LibraryFood> = emptyList(),
    val totalFoods: Int = 0,
    val meals: List<LibraryMeal> = emptyList(),
    val sortMode: SortMode = SortMode.Frequency,
    val query: String = "",
    val error: String? = null,
    val detail: BarcodeFood? = null,     // food detail shown in the sheet
    val detailLoading: Boolean = false,
    val mealDetail: LibraryMeal? = null, // meal detail shown in the sheet
    val message: String? = null,
)

/** Library UI bits that aren't shared app data (live only in this screen). */
private data class LibraryUi(
    val sortMode: SortMode = SortMode.Frequency,
    val query: String = "",
    val detail: BarcodeFood? = null,
    val detailLoading: Boolean = false,
    val mealDetail: LibraryMeal? = null,
    val message: String? = null,
)

class LibraryViewModel(private val repo: AppRepository) : ViewModel() {
    private val ui = MutableStateFlow(LibraryUi())

    val state: StateFlow<LibraryState> =
        combine(repo.library, repo.refreshing, repo.error, ui) { lib, loading, error, u ->
            val q = u.query.trim()
            val foods = sortFoods(lib.items.filter { it.matches(q) }, u.sortMode)
            val meals = lib.meals.filter { it.name.contains(q, ignoreCase = true) }
            LibraryState(
                loading = loading,
                foods = foods,
                // Show match count while searching, the full DB total otherwise.
                totalFoods = if (q.isBlank()) lib.total else foods.size,
                meals = meals,
                sortMode = u.sortMode,
                query = u.query,
                error = if (lib.items.isEmpty() && lib.meals.isEmpty()) error else null,
                detail = u.detail,
                detailLoading = u.detailLoading,
                mealDetail = u.mealDetail,
                message = u.message,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

    /** Pull-to-refresh: reload the whole app's data. */
    fun load() = repo.refresh()

    /** Switch sort order — no network, the screen re-derives from shared data. */
    fun setSortMode(mode: SortMode) = ui.update { it.copy(sortMode = mode) }

    /** Filter the library by name/brand — no network, all data is cached. */
    fun setQuery(q: String) = ui.update { it.copy(query = q) }

    private fun FoodUsage.matches(q: String): Boolean =
        q.isBlank() || food.name.contains(q, ignoreCase = true) ||
            (food.brand?.contains(q, ignoreCase = true) ?: false)

    private fun sortFoods(items: List<FoodUsage>, mode: SortMode): List<LibraryFood> = when (mode) {
        SortMode.Alphabetical -> items.sortedBy { it.food.name.lowercase() }.map { it.food }
        SortMode.Frequency -> items.sortedWith(
            compareByDescending<FoodUsage> { it.usage.count }
                .thenByDescending { it.usage.lastDate }
                .thenBy { it.food.name.lowercase() },
        ).map { it.food }
    }

    fun openFood(id: String) {
        viewModelScope.launch {
            val s = repo.store.settings.first()
            if (!s.isConfigured) return@launch
            ui.update { it.copy(detailLoading = true, detail = null) }
            try {
                val food = SparkyApi(s.baseUrl, s.apiKey).foodDetail(id)
                ui.update { it.copy(detailLoading = false, detail = food) }
            } catch (e: Exception) {
                ui.update { it.copy(detailLoading = false, message = e.message ?: "Couldn't load food") }
            }
        }
    }

    fun closeDetail() = ui.update { it.copy(detail = null) }
    fun openMeal(meal: LibraryMeal) = ui.update { it.copy(mealDetail = meal) }
    fun closeMealDetail() = ui.update { it.copy(mealDetail = null) }
    fun clearMessage() = ui.update { it.copy(message = null) }
    fun notify(msg: String) = ui.update { it.copy(message = msg) }

    /** Log a food to today's diary, then notify the caller (e.g. to refresh Today). */
    fun logFood(food: BarcodeFood, grams: Double, mealType: String, onLogged: () -> Unit) {
        viewModelScope.launch {
            val s = repo.store.settings.first()
            try {
                SparkyApi(s.baseUrl, s.apiKey).logFood(
                    foodId = food.id ?: "",
                    variantId = food.defaultVariant.id ?: "",
                    grams = grams,
                    mealType = mealType,
                    date = LocalDate.now().toString(),
                )
                ui.update { it.copy(message = "Logged ${food.name}") }
                onLogged()
            } catch (e: Exception) {
                ui.update { it.copy(message = e.message ?: "Log failed") }
            }
        }
    }

    fun deleteMeal(id: String) {
        viewModelScope.launch {
            val s = repo.store.settings.first()
            try {
                SparkyApi(s.baseUrl, s.apiKey).deleteMeal(id)
                ui.update { it.copy(mealDetail = null, message = "Deleted") }
                repo.refresh()
            } catch (e: Exception) {
                ui.update { it.copy(message = e.message ?: "Delete failed") }
            }
        }
    }

    fun deleteFood(id: String) {
        viewModelScope.launch {
            val s = repo.store.settings.first()
            try {
                SparkyApi(s.baseUrl, s.apiKey).deleteFood(id)
                ui.update { it.copy(detail = null, message = "Deleted") }
                repo.refresh()
            } catch (e: Exception) {
                ui.update { it.copy(message = e.message ?: "Delete failed") }
            }
        }
    }
}
