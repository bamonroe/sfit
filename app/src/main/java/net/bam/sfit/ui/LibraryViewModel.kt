package net.bam.sfit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.bam.sfit.data.BarcodeFood
import net.bam.sfit.data.CachedFood
import net.bam.sfit.data.CachedLibrary
import net.bam.sfit.data.LibraryCacheStore
import net.bam.sfit.data.LibraryFood
import net.bam.sfit.data.LibraryMeal
import net.bam.sfit.data.SettingsStore
import net.bam.sfit.data.SparkyApi
import java.time.LocalDate

private const val USAGE_WINDOW_DAYS = 28

enum class SortMode { Frequency, Alphabetical }

/** How often (count) and how recently (lastDate, ISO) a food was logged. */
private data class Usage(val count: Int, val lastDate: String)
private class UsageAcc { var count = 0; var lastDate = "" }
private data class FoodUsage(val food: LibraryFood, val usage: Usage)

data class LibraryState(
    val loading: Boolean = false,
    val foods: List<LibraryFood> = emptyList(),
    val totalFoods: Int = 0,
    val meals: List<LibraryMeal> = emptyList(),
    val sortMode: SortMode = SortMode.Frequency,
    val error: String? = null,
    val detail: BarcodeFood? = null,     // food detail shown in the sheet
    val detailLoading: Boolean = false,
    val mealDetail: LibraryMeal? = null, // meal detail shown in the sheet
    val message: String? = null,
)

class LibraryViewModel(
    private val store: SettingsStore,
    private val cache: LibraryCacheStore,
) : ViewModel() {
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    // Foods with their usage, held in memory so re-sorting needs no network.
    private var loaded: List<FoodUsage> = emptyList()

    init {
        load()
    }

    /** Switch sort order using already-loaded data — no server call. */
    fun setSortMode(mode: SortMode) {
        _state.update { it.copy(sortMode = mode, foods = sortFoods(loaded, mode)) }
    }

    private fun sortFoods(items: List<FoodUsage>, mode: SortMode): List<LibraryFood> = when (mode) {
        SortMode.Alphabetical -> items.sortedBy { it.food.name.lowercase() }.map { it.food }
        SortMode.Frequency -> items.sortedWith(
            compareByDescending<FoodUsage> { it.usage.count }
                .thenByDescending { it.usage.lastDate }
                .thenBy { it.food.name.lowercase() },
        ).map { it.food }
    }

    private fun apply(items: List<FoodUsage>, meals: List<LibraryMeal>, total: Int, loading: Boolean) {
        loaded = items
        _state.update {
            it.copy(
                loading = loading,
                foods = sortFoods(items, it.sortMode),
                meals = meals,
                totalFoods = total,
                error = null,
            )
        }
    }

    fun load() {
        viewModelScope.launch {
            // 1) Render cached data instantly (list + re-sort work without waiting).
            cache.load()?.let { c ->
                val items = c.foods.map {
                    FoodUsage(LibraryFood(it.id, it.name, it.brand), Usage(it.count, it.lastDate))
                }
                apply(items, c.meals, c.totalFoods, loading = true)
            }

            val s = store.settings.first()
            if (!s.isConfigured) {
                _state.update {
                    it.copy(loading = false, error = if (loaded.isEmpty()) "Not configured" else null)
                }
                return@launch
            }
            _state.update { it.copy(loading = true, error = null) }
            try {
                val api = SparkyApi(s.baseUrl, s.apiKey)
                coroutineScope {
                    val pageD = async { api.foods(perPage = 500) }
                    val mealsD = async { api.meals() }
                    val usageD = async { computeUsage(api) }
                    val page = pageD.await()
                    val meals = mealsD.await().sortedBy { it.name.lowercase() }
                    val usage = usageD.await()
                    val items = page.foods.map { FoodUsage(it, usage[it.id] ?: Usage(0, "")) }
                    apply(items, meals, page.totalCount, loading = false)
                    cache.save(
                        CachedLibrary(
                            foods = items.map {
                                CachedFood(it.food.id, it.food.name, it.food.brand, it.usage.count, it.usage.lastDate)
                            },
                            meals = meals,
                            totalFoods = page.totalCount,
                        ),
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = if (loaded.isEmpty()) (e.message ?: "Failed to load") else null)
                }
            }
        }
    }

    /** Aggregate the last [USAGE_WINDOW_DAYS] days of diary entries into per-food
     *  usage (count = weekly-ish frequency signal, lastDate = recency). */
    private suspend fun computeUsage(api: SparkyApi): Map<String, Usage> = coroutineScope {
        val today = LocalDate.now()
        val entries = (0 until USAGE_WINDOW_DAYS)
            .map { offset -> today.minusDays(offset.toLong()).toString() }
            .map { date -> async { runCatching { api.foodEntriesForDate(date) }.getOrDefault(emptyList()) } }
            .awaitAll()
            .flatten()
        val acc = HashMap<String, UsageAcc>()
        for (e in entries) {
            if (e.foodId.isBlank()) continue
            val u = acc.getOrPut(e.foodId) { UsageAcc() }
            u.count++
            if (e.entryDate > u.lastDate) u.lastDate = e.entryDate
        }
        acc.mapValues { Usage(it.value.count, it.value.lastDate) }
    }

    fun openFood(id: String) {
        viewModelScope.launch {
            val s = store.settings.first()
            if (!s.isConfigured) return@launch
            _state.update { it.copy(detailLoading = true, detail = null) }
            try {
                val food = SparkyApi(s.baseUrl, s.apiKey).foodDetail(id)
                _state.update { it.copy(detailLoading = false, detail = food) }
            } catch (e: Exception) {
                _state.update { it.copy(detailLoading = false, message = e.message ?: "Couldn't load food") }
            }
        }
    }

    fun closeDetail() = _state.update { it.copy(detail = null) }

    fun openMeal(meal: LibraryMeal) = _state.update { it.copy(mealDetail = meal) }

    fun closeMealDetail() = _state.update { it.copy(mealDetail = null) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun notify(msg: String) = _state.update { it.copy(message = msg) }

    /** Log a food to today's diary, then notify the caller (e.g. to refresh Today). */
    fun logFood(food: BarcodeFood, grams: Double, mealType: String, onLogged: () -> Unit) {
        viewModelScope.launch {
            val s = store.settings.first()
            try {
                SparkyApi(s.baseUrl, s.apiKey).logFood(
                    foodId = food.id ?: "",
                    variantId = food.defaultVariant.id ?: "",
                    grams = grams,
                    mealType = mealType,
                    date = LocalDate.now().toString(),
                )
                _state.update { it.copy(message = "Logged ${food.name}") }
                onLogged()
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "Log failed") }
            }
        }
    }

    fun deleteMeal(id: String) {
        viewModelScope.launch {
            val s = store.settings.first()
            try {
                SparkyApi(s.baseUrl, s.apiKey).deleteMeal(id)
                _state.update { it.copy(mealDetail = null, message = "Deleted") }
                load()
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "Delete failed") }
            }
        }
    }

    fun deleteFood(id: String) {
        viewModelScope.launch {
            val s = store.settings.first()
            try {
                SparkyApi(s.baseUrl, s.apiKey).deleteFood(id)
                _state.update { it.copy(detail = null, message = "Deleted") }
                load()
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "Delete failed") }
            }
        }
    }
}
