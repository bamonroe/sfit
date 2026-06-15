package net.bam.sfit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import net.bam.sfit.data.Container
import net.bam.sfit.data.ContainerStore
import net.bam.sfit.data.DraftIngredient
import net.bam.sfit.data.DraftStore
import net.bam.sfit.data.MealDraft
import net.bam.sfit.data.MealLine
import net.bam.sfit.data.SettingsStore
import net.bam.sfit.data.SparkyApi
import java.util.UUID

data class MealUiState(
    val draft: MealDraft = MealDraft(),
    val resolving: Boolean = false,
    val creating: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val createdOk: Boolean = false,
)

class MealViewModel(
    private val settingsStore: SettingsStore,
    private val draftStore: DraftStore,
    private val containerStore: ContainerStore,
) : ViewModel() {
    private val _state = MutableStateFlow(MealUiState())
    val state: StateFlow<MealUiState> = _state.asStateFlow()

    val containers: StateFlow<List<Container>> =
        containerStore.containers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val draft get() = _state.value.draft

    fun setGrossGrams(grams: Double?) = mutate { it.copy(grossGrams = grams) }

    fun selectContainer(id: String?) = mutate { it.copy(containerId = id) }

    /** Net final weight (gross − selected container's tare), or null if no gross. */
    private fun netFinalGrams(d: MealDraft): Double? {
        val gross = d.grossGrams ?: return null
        val tare = containers.value.firstOrNull { it.id == d.containerId }?.tareGrams ?: 0.0
        return (gross - tare).coerceAtLeast(0.0)
    }

    fun addContainer(name: String, tareGrams: Double) {
        viewModelScope.launch {
            containerStore.upsert(Container(UUID.randomUUID().toString(), name.trim(), tareGrams))
        }
    }

    fun deleteContainer(id: String) {
        viewModelScope.launch { containerStore.delete(id) }
    }

    init {
        viewModelScope.launch {
            draftStore.load()?.let { saved -> _state.update { it.copy(draft = saved) } }
        }
        // Autosave the draft every 10s so a background-kill can't lose it.
        viewModelScope.launch {
            while (isActive) {
                delay(10_000)
                persist()
            }
        }
    }

    private suspend fun persist() = draftStore.save(draft)

    private fun mutate(block: (MealDraft) -> MealDraft) {
        _state.update { it.copy(draft = block(it.draft)) }
        viewModelScope.launch { persist() } // also save immediately on structural edits
    }

    fun setName(name: String) = mutate { it.copy(name = name) }

    /** Clear the draft to start a fresh meal. */
    fun startNew() = mutate { MealDraft() }

    fun setGrams(index: Int, grams: Double) = mutate { d ->
        d.copy(ingredients = d.ingredients.mapIndexed { i, ing ->
            if (i == index) ing.copy(grams = grams) else ing
        })
    }

    fun removeAt(index: Int) = mutate { d ->
        d.copy(ingredients = d.ingredients.filterIndexed { i, _ -> i != index })
    }

    fun clearMessage() = _state.update { it.copy(message = null, error = null) }

    /** One-shot ack so the "created → navigate back" effect doesn't re-fire on reopen. */
    fun acknowledgeCreated() = _state.update { it.copy(createdOk = false) }

    /** Look up a barcode and append it as an ingredient (deduped by barcode). */
    fun addByBarcode(barcode: String) {
        val code = barcode.trim()
        if (code.isBlank() || _state.value.resolving) return
        if (draft.ingredients.any { it.barcode == code }) {
            _state.update { it.copy(message = "Already in meal") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(resolving = true, error = null) }
            try {
                val s = settingsStore.settings.first()
                if (!s.isConfigured) {
                    _state.update { it.copy(resolving = false, error = "Not configured") }
                    return@launch
                }
                val ing = SparkyApi(s.baseUrl, s.apiKey).resolveIngredient(code)
                if (ing == null) {
                    _state.update { it.copy(resolving = false, message = "No match for $code") }
                    return@launch
                }
                mutate { d ->
                    d.copy(
                        ingredients = d.ingredients + DraftIngredient(
                            foodId = ing.foodId, variantId = ing.variantId,
                            name = ing.name, brand = ing.brand, barcode = ing.barcode,
                            calories = ing.calories, servingSize = ing.servingSize,
                            servingUnit = ing.servingUnit, grams = 0.0,
                        ),
                    )
                }
                _state.update { it.copy(resolving = false, message = "Added ${ing.name}") }
            } catch (e: Exception) {
                _state.update { it.copy(resolving = false, error = e.message ?: "Lookup failed") }
            }
        }
    }

    /** Add an already-known food (e.g. from the Library) to the draft. */
    fun addFood(
        foodId: String,
        variantId: String,
        name: String,
        brand: String?,
        calories: Double,
        servingSize: Double,
        servingUnit: String,
    ) {
        if (draft.ingredients.any { it.foodId == foodId }) {
            _state.update { it.copy(message = "Already in meal") }
            return
        }
        mutate { d ->
            d.copy(
                ingredients = d.ingredients + DraftIngredient(
                    foodId = foodId, variantId = variantId, name = name, brand = brand,
                    barcode = "", calories = calories, servingSize = servingSize,
                    servingUnit = servingUnit, grams = 0.0,
                ),
            )
        }
        _state.update { it.copy(message = "Added $name to meal") }
    }

    fun createMeal() {
        val d = draft
        val lines = d.ingredients.filter { it.grams > 0 }
            .map { MealLine(it.foodId, it.variantId, it.grams) }
        when {
            d.name.isBlank() -> { _state.update { it.copy(error = "Name the meal first") }; return }
            lines.isEmpty() -> { _state.update { it.copy(error = "Set quantities first") }; return }
            _state.value.creating -> return
        }
        viewModelScope.launch {
            _state.update { it.copy(creating = true, error = null) }
            try {
                val s = settingsStore.settings.first()
                SparkyApi(s.baseUrl, s.apiKey).createMeal(d.name, lines, netFinalGrams(d))
                draftStore.clear()
                _state.update {
                    MealUiState(message = "Created \"${d.name}\"", createdOk = true)
                }
            } catch (e: Exception) {
                _state.update { it.copy(creating = false, error = e.message ?: "Create failed") }
            }
        }
    }

    fun discardDraft() {
        viewModelScope.launch { draftStore.clear() }
        _state.update { MealUiState(message = "Draft cleared") }
    }
}
