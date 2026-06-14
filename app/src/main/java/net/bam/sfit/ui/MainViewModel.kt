package net.bam.sfit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.bam.sfit.data.FoodEntry
import net.bam.sfit.data.Settings
import net.bam.sfit.data.SettingsStore
import net.bam.sfit.data.SparkyApi
import java.time.LocalDate

/** UI state for the main (remaining-calories) screen. */
data class DayState(
    val loading: Boolean = false,
    val configured: Boolean = false,
    val goalCalories: Double = 0.0,
    val consumedCalories: Double = 0.0,
    val entries: List<FoodEntry> = emptyList(),
    val error: String? = null,
) {
    val remaining: Double get() = goalCalories - consumedCalories
    val hasGoal: Boolean get() = goalCalories > 0.0
}

class MainViewModel(private val store: SettingsStore) : ViewModel() {
    private val _state = MutableStateFlow(DayState())
    val state: StateFlow<DayState> = _state.asStateFlow()

    private var settings = Settings()

    init {
        // React to config changes: when configured, (re)load today's summary.
        viewModelScope.launch {
            store.settings.collect { s ->
                val wasConfigured = settings.isConfigured
                settings = s
                _state.update { it.copy(configured = s.isConfigured) }
                if (s.isConfigured && (!wasConfigured || s != settings)) refresh()
            }
        }
    }

    fun refresh() {
        val s = settings
        if (!s.isConfigured) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val summary = SparkyApi(s.baseUrl, s.apiKey)
                    .dailySummary(LocalDate.now().toString())
                _state.update {
                    it.copy(
                        loading = false,
                        configured = true,
                        goalCalories = summary.goals.calories,
                        consumedCalories = summary.consumedCalories,
                        entries = summary.foodEntries,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Failed to load") }
            }
        }
    }
}
