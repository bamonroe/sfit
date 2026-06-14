package net.bam.sfit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.bam.sfit.data.CachedHistory
import net.bam.sfit.data.CachedHistoryRow
import net.bam.sfit.data.HistoryCacheStore
import net.bam.sfit.data.SettingsStore
import net.bam.sfit.data.SparkyApi
import net.bam.sfit.data.UserPreferences
import net.bam.sfit.data.displayToKg
import net.bam.sfit.data.kgToDisplay
import net.bam.sfit.data.maintenanceCalories
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class Granularity { Daily, Weekly, Monthly }

/** One row in the history table (a day, week, or month). */
data class HistoryRow(
    val label: String,
    val weight: Double?,        // in display unit
    val weightDelta: Double?,   // vs the previous period, display unit
    val deficit: Double?,       // kcal; per-day for Daily, avg/day for Weekly/Monthly
)

data class HistoryState(
    val loading: Boolean = false,
    val granularity: Granularity = Granularity.Daily,
    val unit: String = "kg",
    val rows: List<HistoryRow> = emptyList(),
    val error: String? = null,
)

class HistoryViewModel(
    private val store: SettingsStore,
    private val cache: HistoryCacheStore,
) : ViewModel() {
    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state.asStateFlow()

    init {
        load()
    }

    fun selectGranularity(g: Granularity) {
        if (g == _state.value.granularity) return
        _state.update { it.copy(granularity = g, rows = emptyList()) }
        load()
    }

    /** Upsert today's body weight, entered in the current display unit. */
    fun logWeight(displayValue: Double) {
        viewModelScope.launch {
            val settings = store.settings.first()
            if (!settings.isConfigured) {
                _state.update { it.copy(error = "Not configured") }
                return@launch
            }
            _state.update { it.copy(loading = true, error = null) }
            try {
                val api = SparkyApi(settings.baseUrl, settings.apiKey)
                val kg = displayToKg(displayValue, _state.value.unit)
                api.logWeight(LocalDate.now().toString(), kg)
                load() // refresh the table + cache
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Failed to log weight") }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            val g = _state.value.granularity
            // 1) Show cached rows for this granularity instantly.
            cache.load()?.let { c ->
                c.byGranularity[g.name]?.let { cachedRows ->
                    _state.update {
                        it.copy(unit = c.unit, rows = cachedRows.map { r ->
                            HistoryRow(r.label, r.weight, r.weightDelta, r.deficit)
                        })
                    }
                }
            }

            val settings = store.settings.first()
            if (!settings.isConfigured) {
                _state.update { it.copy(loading = false, error = if (it.rows.isEmpty()) "Not configured" else null) }
                return@launch
            }
            _state.update { it.copy(loading = true, error = null) }
            try {
                val api = SparkyApi(settings.baseUrl, settings.apiKey)
                val today = LocalDate.now()
                val start = when (g) {
                    Granularity.Daily -> today.minusDays(59)
                    Granularity.Weekly -> today.minusWeeks(15)
                    Granularity.Monthly -> today.minusMonths(11).withDayOfMonth(1)
                }

                val prefs = runCatching { api.userPreferences() }.getOrDefault(UserPreferences())
                val bmr = runCatching { api.dailySummary(today.toString()).calorieBalance.bmr }
                    .getOrDefault(0.0)
                val maintenance = maintenanceCalories(bmr, prefs.activityLevel)

                val checkins = api.checkInRange(start.toString(), today.toString())
                    .mapNotNull { ci -> ci.weight?.let { LocalDate.parse(ci.date) to it } }
                val report = api.report(start.toString(), today.toString())

                val rows = buildRows(g, checkins, report, maintenance, prefs.weightUnit)
                val unit = unitLabel(prefs.weightUnit)
                _state.update { it.copy(loading = false, rows = rows, unit = unit, error = null) }

                // Re-cache: merge this granularity's rows into the stored map.
                val existing = cache.load() ?: CachedHistory()
                cache.save(
                    existing.copy(
                        unit = unit,
                        byGranularity = existing.byGranularity + (g.name to rows.map {
                            CachedHistoryRow(it.label, it.weight, it.weightDelta, it.deficit)
                        }),
                    ),
                )
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = if (it.rows.isEmpty()) (e.message ?: "Failed to load") else null) }
            }
        }
    }
}

private fun unitLabel(weightUnit: String) =
    if (weightUnit.startsWith("lb", ignoreCase = true)) "lb" else "kg"

private fun periodStart(d: LocalDate, g: Granularity): LocalDate = when (g) {
    Granularity.Daily -> d
    Granularity.Weekly -> d.minusDays((d.dayOfWeek.value - 1).toLong()) // ISO Monday
    Granularity.Monthly -> d.withDayOfMonth(1)
}

private val dayFmt = DateTimeFormatter.ofPattern("EEE, MMM d")
private val weekFmt = DateTimeFormatter.ofPattern("MMM d")
private val monthFmt = DateTimeFormatter.ofPattern("MMM yyyy")

private fun periodLabel(start: LocalDate, g: Granularity): String = when (g) {
    Granularity.Daily -> start.format(dayFmt)
    Granularity.Weekly -> "Wk of " + start.format(weekFmt)
    Granularity.Monthly -> start.format(monthFmt)
}

private fun buildRows(
    g: Granularity,
    checkins: List<Pair<LocalDate, Double>>,
    report: net.bam.sfit.data.Report,
    maintenance: Double,
    weightUnit: String,
): List<HistoryRow> {
    // Average weight per period.
    val weightByPeriod = sortedMapOf<LocalDate, MutableList<Double>>()
    for ((date, kg) in checkins) {
        val key = periodStart(date, g)
        weightByPeriod.getOrPut(key) { mutableListOf() }.add(kgToDisplay(kg, weightUnit))
    }
    // Average daily deficit per period (only days with food logged).
    val deficitSum = hashMapOf<LocalDate, Double>()
    val deficitCount = hashMapOf<LocalDate, Int>()
    for (nd in report.nutritionData) {
        if (nd.calories <= 0 || nd.date.isBlank()) continue
        val key = periodStart(runCatching { LocalDate.parse(nd.date) }.getOrNull() ?: continue, g)
        deficitSum[key] = (deficitSum[key] ?: 0.0) + (maintenance - nd.calories)
        deficitCount[key] = (deficitCount[key] ?: 0) + 1
    }

    val periods = weightByPeriod.keys.toList() // ascending
    val rows = periods.mapIndexed { i, key ->
        val avgWeight = weightByPeriod.getValue(key).average()
        val prevWeight = if (i > 0) weightByPeriod.getValue(periods[i - 1]).average() else null
        val deficit = deficitCount[key]?.let { c -> deficitSum.getValue(key) / c }
        HistoryRow(
            label = periodLabel(key, g),
            weight = avgWeight,
            weightDelta = prevWeight?.let { avgWeight - it },
            deficit = deficit,
        )
    }
    return rows.reversed() // newest first
}
