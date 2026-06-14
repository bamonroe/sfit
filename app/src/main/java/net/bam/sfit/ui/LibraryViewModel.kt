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
import net.bam.sfit.data.LibraryFood
import net.bam.sfit.data.LibraryMeal
import net.bam.sfit.data.SettingsStore
import net.bam.sfit.data.SparkyApi
import java.time.LocalDate

private const val USAGE_WINDOW_DAYS = 28

/** How often (count) and how recently (lastDate, ISO) a food was logged. */
private data class Usage(val count: Int, val lastDate: String)
private class UsageAcc { var count = 0; var lastDate = "" }

data class LibraryState(
    val loading: Boolean = false,
    val foods: List<LibraryFood> = emptyList(),
    val totalFoods: Int = 0,
    val meals: List<LibraryMeal> = emptyList(),
    val error: String? = null,
    val detail: BarcodeFood? = null,     // food detail shown in the sheet
    val detailLoading: Boolean = false,
    val mealDetail: LibraryMeal? = null, // meal detail shown in the sheet
    val message: String? = null,
)

class LibraryViewModel(private val store: SettingsStore) : ViewModel() {
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val s = store.settings.first()
            if (!s.isConfigured) {
                _state.update { it.copy(error = "Not configured") }
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
                    // Most-logged foods first; ties broken by most recent log; then name.
                    val foods = page.foods.sortedWith(
                        compareByDescending<LibraryFood> { usage[it.id]?.count ?: 0 }
                            .thenByDescending { usage[it.id]?.lastDate ?: "" }
                            .thenBy { it.name.lowercase() },
                    )
                    _state.update {
                        it.copy(
                            loading = false, foods = foods, totalFoods = page.totalCount,
                            meals = meals, error = null,
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Failed to load") }
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
