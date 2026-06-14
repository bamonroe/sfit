package net.bam.sfit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import net.bam.sfit.data.AppRepository
import net.bam.sfit.data.FoodEntry

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

class MainViewModel(private val repo: AppRepository) : ViewModel() {
    val state: StateFlow<DayState> =
        combine(repo.day, repo.refreshing, repo.configured, repo.error) { day, loading, configured, error ->
            DayState(
                loading = loading,
                configured = configured,
                goalCalories = day.goalCalories,
                consumedCalories = day.consumedCalories,
                entries = day.entries,
                error = error,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayState())

    fun refresh() = repo.refresh()
}
