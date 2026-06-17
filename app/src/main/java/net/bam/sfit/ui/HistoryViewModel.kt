package net.bam.sfit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import net.bam.sfit.data.AppRepository
import net.bam.sfit.data.Granularity
import net.bam.sfit.data.HistoryRow

/** Which energy interpretation the History table's last column shows.
 *  See [HistoryRow] for the math behind each. */
enum class EnergyMode { Actual, ImpliedDeficit, ImpliedMaintenance, ImpliedCalories }

/** Full name for the selector + expanded detail. */
val EnergyMode.label: String
    get() = when (this) {
        EnergyMode.Actual -> "Actual deficit"
        EnergyMode.ImpliedDeficit -> "Implied deficit"
        EnergyMode.ImpliedMaintenance -> "Implied maintenance"
        EnergyMode.ImpliedCalories -> "Implied calories"
    }

/** Compact label for the narrow column header. */
val EnergyMode.shortLabel: String
    get() = when (this) {
        EnergyMode.Actual -> "Deficit"
        EnergyMode.ImpliedDeficit -> "Impl. def."
        EnergyMode.ImpliedMaintenance -> "Impl. TDEE"
        EnergyMode.ImpliedCalories -> "Impl. cals"
    }

/** Deficit-style modes are signed (a +/− gap, less = surplus); level modes are an
 *  absolute kcal/day amount. Drives sign + colour in the cell. */
val EnergyMode.isDeficit: Boolean
    get() = this == EnergyMode.Actual || this == EnergyMode.ImpliedDeficit

/** The value for this row under the given mode (kcal/day), or null if not derivable. */
fun HistoryRow.energy(mode: EnergyMode): Double? = when (mode) {
    EnergyMode.Actual -> deficit
    EnergyMode.ImpliedDeficit -> scaleDeficit
    EnergyMode.ImpliedMaintenance -> impliedMaintenance
    EnergyMode.ImpliedCalories -> impliedCalories
}

/** Which inputs each mode leans on — used to decide if its value was imputed.
 *  (Formula maintenance is never imputed, so only logged intake + weight matter.) */
val EnergyMode.usesWeight: Boolean get() = this != EnergyMode.Actual
val EnergyMode.usesCalories: Boolean get() = this == EnergyMode.Actual || this == EnergyMode.ImpliedMaintenance

/** True when this row's value under [mode] drew on any imputed input. */
fun HistoryRow.energyImputed(mode: EnergyMode): Boolean =
    (mode.usesWeight && weightImputed) || (mode.usesCalories && caloriesImputed)

data class HistoryState(
    val loading: Boolean = false,
    val granularity: Granularity = Granularity.Daily,
    val energyMode: EnergyMode = EnergyMode.Actual,
    val unit: String = "kg",
    val rows: List<HistoryRow> = emptyList(),
    val error: String? = null,
)

class HistoryViewModel(private val repo: AppRepository) : ViewModel() {
    private val granularity = MutableStateFlow(Granularity.Daily)
    private val energyMode = MutableStateFlow(EnergyMode.Actual)

    val state: StateFlow<HistoryState> =
        combine(repo.history, repo.refreshing, repo.error, granularity, energyMode) { history, loading, error, g, mode ->
            val rows = history.byGranularity[g.name] ?: emptyList()
            HistoryState(
                loading = loading,
                granularity = g,
                energyMode = mode,
                unit = history.unit,
                rows = rows,
                // Surface the error only when there's nothing to show.
                error = if (rows.isEmpty()) error else null,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryState())

    fun selectGranularity(g: Granularity) {
        granularity.value = g // instant: every granularity is precomputed
    }

    fun selectEnergyMode(m: EnergyMode) {
        energyMode.value = m // instant: every metric is precomputed on each row
    }

    /** Pull-to-refresh: reload the whole app's data. */
    fun load() = repo.refresh()

    /** Log/edit a day's body weight (entered in the current display unit).
     *  Defaults to today; pass an ISO date to edit a past weigh-in. */
    fun logWeight(displayValue: Double, date: String? = null) =
        if (date != null) repo.logWeight(displayValue, date) else repo.logWeight(displayValue)

    /** Delete a weigh-in by its check-in id. */
    fun deleteCheckIn(id: String) = repo.deleteCheckIn(id)
}
