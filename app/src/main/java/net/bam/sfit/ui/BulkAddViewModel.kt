package net.bam.sfit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.bam.sfit.data.SettingsStore
import net.bam.sfit.data.SparkyApi

data class BulkAddState(
    val count: Int = 0,
    val resolving: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

/**
 * Bulk-adds foods to the DB by scanning barcodes. Resolving a barcode already
 * imports the product into the food database (POST /foods) when it isn't there,
 * so each successful scan is an add.
 */
class BulkAddViewModel(private val store: SettingsStore) : ViewModel() {
    private val _state = MutableStateFlow(BulkAddState())
    val state: StateFlow<BulkAddState> = _state.asStateFlow()

    private val seen = mutableSetOf<String>()

    fun reset() {
        seen.clear()
        _state.update { BulkAddState() }
    }

    fun addByBarcode(barcode: String) {
        val code = barcode.trim()
        if (code.isBlank() || _state.value.resolving || code in seen) return
        viewModelScope.launch {
            _state.update { it.copy(resolving = true, error = null) }
            try {
                val s = store.settings.first()
                if (!s.isConfigured) {
                    _state.update { it.copy(resolving = false, error = "Not configured") }
                    return@launch
                }
                val ing = SparkyApi(s.baseUrl, s.apiKey).resolveIngredient(code)
                if (ing == null) {
                    _state.update { it.copy(resolving = false, message = "No match for $code") }
                    return@launch
                }
                seen += code
                _state.update { it.copy(resolving = false, count = it.count + 1, message = "Added ${ing.name}") }
            } catch (e: Exception) {
                _state.update { it.copy(resolving = false, error = e.message ?: "Lookup failed") }
            }
        }
    }
}
