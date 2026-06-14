package net.bam.sfit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

data class LibraryState(
    val loading: Boolean = false,
    val foods: List<LibraryFood> = emptyList(),
    val totalFoods: Int = 0,
    val meals: List<LibraryMeal> = emptyList(),
    val error: String? = null,
    val detail: BarcodeFood? = null,     // food detail shown in the sheet
    val detailLoading: Boolean = false,
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
                val page = api.foods(perPage = 500)
                val meals = api.meals().sortedBy { it.name.lowercase() }
                val foods = page.foods.sortedBy { it.name.lowercase() }
                _state.update {
                    it.copy(
                        loading = false, foods = foods, totalFoods = page.totalCount,
                        meals = meals, error = null,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Failed to load") }
            }
        }
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

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun notify(msg: String) = _state.update { it.copy(message = msg) }

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
