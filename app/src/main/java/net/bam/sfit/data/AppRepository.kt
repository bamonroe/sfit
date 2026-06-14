package net.bam.sfit.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val USAGE_WINDOW_DAYS = 28
private const val HISTORY_MONTHS = 11L

// ---- Shared data types (single source of truth for the whole app) ----

enum class Granularity { Daily, Weekly, Monthly }

/** One row in the history table (a day, week, or month). */
data class HistoryRow(
    val label: String,
    val weight: Double?,        // in display unit
    val weightDelta: Double?,   // vs the previous period, display unit
    val deficit: Double?,       // kcal; per-day for Daily, avg/day for Weekly/Monthly
)

/** How often (count) and how recently (lastDate, ISO) a food was logged. */
data class Usage(val count: Int, val lastDate: String)
data class FoodUsage(val food: LibraryFood, val usage: Usage)

/** Today's calorie summary. */
data class DayData(
    val goalCalories: Double = 0.0,
    val consumedCalories: Double = 0.0,
    val entries: List<FoodEntry> = emptyList(),
)

/** Weight/deficit history, pre-computed for every granularity from one fetch. */
data class HistoryData(
    val unit: String = "kg",
    val byGranularity: Map<String, List<HistoryRow>> = emptyMap(),
)

/** The food + meal library with per-food usage. */
data class LibraryData(
    val items: List<FoodUsage> = emptyList(),
    val meals: List<LibraryMeal> = emptyList(),
    val total: Int = 0,
)

/**
 * Centralized, app-wide data store. One [refresh] fetches everything every
 * screen needs (today, history, library) in parallel, updates the shared
 * flows, and persists each cache. Screens observe the flows; pull-to-refresh
 * anywhere calls [refresh].
 */
class AppRepository(
    val store: SettingsStore,
    private val dayCache: DayCacheStore,
    private val historyCache: HistoryCacheStore,
    private val libraryCache: LibraryCacheStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _day = MutableStateFlow(DayData())
    val day: StateFlow<DayData> = _day.asStateFlow()
    private val _history = MutableStateFlow(HistoryData())
    val history: StateFlow<HistoryData> = _history.asStateFlow()
    private val _library = MutableStateFlow(LibraryData())
    val library: StateFlow<LibraryData> = _library.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _configured = MutableStateFlow(false)
    val configured: StateFlow<Boolean> = _configured.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var settings = Settings()
    private var weightUnit = "kg"
    private var refreshJob: Job? = null

    init {
        // Render cached data instantly so every screen has something on launch.
        scope.launch {
            dayCache.load()?.takeIf { it.date == LocalDate.now().toString() }?.let { c ->
                _day.value = DayData(c.goalCalories, c.consumedCalories, c.entries)
            }
            historyCache.load()?.let { c ->
                _history.value = HistoryData(
                    c.unit,
                    c.byGranularity.mapValues { (_, rows) ->
                        rows.map { HistoryRow(it.label, it.weight, it.weightDelta, it.deficit) }
                    },
                )
            }
            libraryCache.load()?.let { c ->
                val items = c.foods.map {
                    FoodUsage(LibraryFood(it.id, it.name, it.brand), Usage(it.count, it.lastDate))
                }
                _library.value = LibraryData(items, c.meals, c.totalFoods)
            }
        }
        // React to config: first configure (and any URL/key change) triggers a refresh.
        scope.launch {
            store.settings.collect { s ->
                val prev = settings
                settings = s
                _configured.value = s.isConfigured
                if (s.isConfigured && s != prev) refresh()
            }
        }
    }

    /** Fetch everything the app needs, in parallel, and update all flows + caches. */
    fun refresh() {
        if (refreshJob?.isActive == true) return // coalesce concurrent pulls
        refreshJob = scope.launch {
            val s = store.settings.first()
            if (!s.isConfigured) {
                _error.value = "Not configured"
                return@launch
            }
            _refreshing.value = true
            _error.value = null
            try {
                val api = SparkyApi(s.baseUrl, s.apiKey)
                val today = LocalDate.now()
                val historyStart = today.minusMonths(HISTORY_MONTHS).withDayOfMonth(1)
                coroutineScope {
                    val summaryD = async { api.dailySummary(today.toString()) }
                    val prefsD = async {
                        runCatching { api.userPreferences() }.getOrDefault(UserPreferences())
                    }
                    val checkinsD = async { api.checkInRange(historyStart.toString(), today.toString()) }
                    val reportD = async { api.report(historyStart.toString(), today.toString()) }
                    val foodsD = async { api.foods(perPage = 500) }
                    val mealsD = async { api.meals() }
                    val usageD = async { computeUsage(api) }

                    val summary = summaryD.await()
                    val prefs = prefsD.await()
                    val checkins = checkinsD.await()
                        .mapNotNull { ci -> ci.weight?.let { LocalDate.parse(ci.date) to it } }
                    val report = reportD.await()
                    val foodsPage = foodsD.await()
                    val meals = mealsD.await().sortedBy { it.name.lowercase() }
                    val usage = usageD.await()

                    // --- Today ---
                    _day.value = DayData(summary.goals.calories, summary.consumedCalories, summary.foodEntries)

                    // --- History (compute every granularity from one fetch) ---
                    weightUnit = prefs.weightUnit
                    val maintenance = maintenanceCalories(summary.calorieBalance.bmr, prefs.activityLevel)
                    val byGran = Granularity.entries.associate { g ->
                        g.name to buildRows(g, checkins, report, maintenance, prefs.weightUnit)
                    }
                    _history.value = HistoryData(unitLabel(prefs.weightUnit), byGran)

                    // --- Library ---
                    val items = foodsPage.foods.map { FoodUsage(it, usage[it.id] ?: Usage(0, "")) }
                    _library.value = LibraryData(items, meals, foodsPage.totalCount)

                    // --- Persist all caches ---
                    dayCache.save(
                        CachedDay(today.toString(), summary.goals.calories, summary.consumedCalories, summary.foodEntries),
                    )
                    historyCache.save(
                        CachedHistory(
                            unitLabel(prefs.weightUnit),
                            byGran.mapValues { (_, rows) ->
                                rows.map { CachedHistoryRow(it.label, it.weight, it.weightDelta, it.deficit) }
                            },
                        ),
                    )
                    libraryCache.save(
                        CachedLibrary(
                            foods = items.map {
                                CachedFood(it.food.id, it.food.name, it.food.brand, it.usage.count, it.usage.lastDate)
                            },
                            meals = meals,
                            totalFoods = foodsPage.totalCount,
                        ),
                    )
                }
                _error.value = null
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to refresh"
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Upsert today's body weight (entered in the display unit), then refresh. */
    fun logWeight(displayValue: Double, onError: (String) -> Unit = {}) {
        scope.launch {
            val s = store.settings.first()
            if (!s.isConfigured) return@launch
            try {
                SparkyApi(s.baseUrl, s.apiKey)
                    .logWeight(LocalDate.now().toString(), displayToKg(displayValue, weightUnit))
                refresh()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to log weight")
            }
        }
    }

    /** Aggregate the last [USAGE_WINDOW_DAYS] days of diary entries into per-food usage. */
    private suspend fun computeUsage(api: SparkyApi): Map<String, Usage> = coroutineScope {
        val today = LocalDate.now()
        val entries = (0 until USAGE_WINDOW_DAYS)
            .map { offset -> today.minusDays(offset.toLong()).toString() }
            .map { date -> async { runCatching { api.foodEntriesForDate(date) }.getOrDefault(emptyList()) } }
            .awaitAll()
            .flatten()
        val acc = HashMap<String, UsageAcc>()
        for (e in entries) {
            if (e.foodId.isBlank()) continue
            val u = acc.getOrPut(e.foodId) { UsageAcc() }
            u.count++
            if (e.entryDate > u.lastDate) u.lastDate = e.entryDate
        }
        acc.mapValues { Usage(it.value.count, it.value.lastDate) }
    }
}

private class UsageAcc {
    var count = 0
    var lastDate = ""
}

// ---- History row building (shared by refresh + cache decode) ----

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
    report: Report,
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

// ---- Process-wide singleton (survives Activity recreation) ----

object Repo {
    @Volatile
    private var instance: AppRepository? = null

    fun get(context: Context): AppRepository = instance ?: synchronized(this) {
        instance ?: AppRepository(
            store = SettingsStore(context.applicationContext),
            dayCache = DayCacheStore(context.applicationContext),
            historyCache = HistoryCacheStore(context.applicationContext),
            libraryCache = LibraryCacheStore(context.applicationContext),
        ).also { instance = it }
    }
}
