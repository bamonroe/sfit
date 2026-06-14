package net.bam.sfit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.bam.sfit.data.AppRepository
import net.bam.sfit.data.ProviderFood
import net.bam.sfit.data.SparkyApi

data class ProviderSearchState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<ProviderFood> = emptyList(),
    val searched: Boolean = false,
    val importingCode: String? = null,   // code currently being imported
    val message: String? = null,
)

/** Search the Open Food Facts provider and import a hit into the food library
 *  (reusing the barcode → DB pipeline). */
class ProviderSearchViewModel(private val repo: AppRepository) : ViewModel() {
    private val _state = MutableStateFlow(ProviderSearchState())
    val state: StateFlow<ProviderSearchState> = _state.asStateFlow()

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun search() {
        val q = _state.value.query.trim()
        if (q.isBlank() || _state.value.loading) return
        viewModelScope.launch {
            val s = repo.store.settings.first()
            if (!s.isConfigured) {
                _state.update { it.copy(message = "Not configured") }
                return@launch
            }
            _state.update { it.copy(loading = true, message = null, searched = true) }
            try {
                val results = SparkyApi(s.baseUrl, s.apiKey).searchOpenFoodFacts(q)
                _state.update { it.copy(loading = false, results = results) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, results = emptyList(), message = e.message ?: "Search failed")
                }
            }
        }
    }

    /** Import a result into the library (then refresh shared data). */
    fun import(food: ProviderFood) {
        if (_state.value.importingCode != null) return
        viewModelScope.launch {
            val s = repo.store.settings.first()
            if (!s.isConfigured) return@launch
            _state.update { it.copy(importingCode = food.code) }
            try {
                val ing = SparkyApi(s.baseUrl, s.apiKey).resolveIngredient(food.code)
                if (ing == null) {
                    _state.update { it.copy(importingCode = null, message = "Couldn't import ${food.name}") }
                } else {
                    _state.update { it.copy(importingCode = null, message = "Added ${ing.name} to library") }
                    repo.refresh()
                }
            } catch (e: Exception) {
                _state.update { it.copy(importingCode = null, message = e.message ?: "Import failed") }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
