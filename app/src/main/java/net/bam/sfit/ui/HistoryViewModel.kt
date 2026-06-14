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

data class HistoryState(
    val loading: Boolean = false,
    val granularity: Granularity = Granularity.Daily,
    val unit: String = "kg",
    val rows: List<HistoryRow> = emptyList(),
    val error: String? = null,
)

class HistoryViewModel(private val repo: AppRepository) : ViewModel() {
    private val granularity = MutableStateFlow(Granularity.Daily)

    val state: StateFlow<HistoryState> =
        combine(repo.history, repo.refreshing, repo.error, granularity) { history, loading, error, g ->
            val rows = history.byGranularity[g.name] ?: emptyList()
            HistoryState(
                loading = loading,
                granularity = g,
                unit = history.unit,
                rows = rows,
                // Surface the error only when there's nothing to show.
                error = if (rows.isEmpty()) error else null,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryState())

    fun selectGranularity(g: Granularity) {
        granularity.value = g // instant: every granularity is precomputed
    }

    /** Pull-to-refresh: reload the whole app's data. */
    fun load() = repo.refresh()

    /** Log/edit a day's body weight (entered in the current display unit).
     *  Defaults to today; pass an ISO date to edit a past weigh-in. */
    fun logWeight(displayValue: Double, date: String? = null) =
        if (date != null) repo.logWeight(displayValue, date) else repo.logWeight(displayValue)
}
