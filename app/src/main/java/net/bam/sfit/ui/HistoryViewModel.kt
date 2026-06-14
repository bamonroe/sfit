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
import net.bam.sfit.data.SettingsStore
import net.bam.sfit.data.SparkyApi
import java.time.LocalDate

enum class RangePreset { Week, Month, Custom }

/** One day in the history table. */
data class HistoryRow(
    val date: String,
    val weight: Double?,
    val deficit: Double?,
)

data class HistoryState(
    val loading: Boolean = false,
    val preset: RangePreset = RangePreset.Month,
    val start: LocalDate = LocalDate.now().minusDays(29),
    val end: LocalDate = LocalDate.now(),
    val rows: List<HistoryRow> = emptyList(),
    val error: String? = null,
)

class HistoryViewModel(private val store: SettingsStore) : ViewModel() {
    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state.asStateFlow()

    init {
        load()
    }

    fun selectPreset(preset: RangePreset) {
        val today = LocalDate.now()
        val (start, end) = when (preset) {
            RangePreset.Week -> today.minusDays(6) to today
            RangePreset.Month -> today.minusDays(29) to today
            RangePreset.Custom -> _state.value.start to _state.value.end
        }
        _state.update { it.copy(preset = preset, start = start, end = end) }
        if (preset != RangePreset.Custom) load()
    }

    fun setCustomRange(start: LocalDate, end: LocalDate) {
        val (s, e) = if (start.isAfter(end)) end to start else start to end
        _state.update { it.copy(preset = RangePreset.Custom, start = s, end = e) }
        load()
    }

    fun load() {
        viewModelScope.launch {
            val settings = store.settings.first()
            if (!settings.isConfigured) {
                _state.update { it.copy(error = "Not configured", rows = emptyList()) }
                return@launch
            }
            val s = _state.value
            _state.update { it.copy(loading = true, error = null) }
            try {
                val api = SparkyApi(settings.baseUrl, settings.apiKey)
                val checkins = api.checkInRange(s.start.toString(), s.end.toString())
                    .filter { it.weight != null }
                // Fetch each weighed day's deficit concurrently (the set is sparse).
                val rows = coroutineScope {
                    checkins.map { ci ->
                        async {
                            // A day with no food logged (eaten == 0) yields a bogus
                            // "deficit" equal to full BMR — treat it as unavailable.
                            val deficit = runCatching {
                                val cb = api.dailySummary(ci.date).calorieBalance
                                if (cb.eaten > 0) cb.deficit else null
                            }.getOrNull()
                            HistoryRow(ci.date, ci.weight, deficit)
                        }
                    }.awaitAll()
                }.sortedByDescending { it.date }
                _state.update { it.copy(loading = false, rows = rows, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Failed to load") }
            }
        }
    }
}
