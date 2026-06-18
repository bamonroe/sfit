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
import java.time.temporal.ChronoUnit

private const val USAGE_WINDOW_DAYS = 28
private const val HISTORY_MONTHS = 11L

// Energy density of body-weight change — the standard ~3500 kcal/lb rule of thumb.
// Used to translate a weight change into an implied energy (de)surplus.
private const val KCAL_PER_KG = 7700.0

// A missing day is imputed from a local quadratic over the previous N real points
// of that series (not the whole history), keeping each prediction local.
private const val IMPUTE_WINDOW = 10

/** Turn a refresh failure into something a human can act on. Connectivity issues
 *  (no network, DNS, timeout) are IOExceptions; the API's own checks already throw
 *  readable messages, so those pass through. */
private fun friendlyError(e: Throwable): String = when (e) {
    is java.io.IOException ->
        "Can't reach your SparkyFitness server. Check your connection (or the tailnet) and the Server URL."
    else -> e.message ?: "Something went wrong."
}

// ---- Shared data types (single source of truth for the whole app) ----

enum class Granularity { Daily, Weekly, Monthly }

/** One row in the history table (a day, week, or month). The energy columns are
 *  all kcal/day; which one the UI shows is the user's [EnergyMode] choice.
 *  - [deficit]: "Actual" — formula maintenance − logged intake.
 *  - [scaleDeficit]: deficit implied by the weight change alone (scale only).
 *  - [impliedMaintenance]: real TDEE — logged intake + the scale's energy.
 *  - [impliedCalories]: real intake — formula maintenance − the scale's energy. */
data class HistoryRow(
    val label: String,
    val weight: Double?,        // in display unit
    val weightDelta: Double?,   // vs the previous period, display unit
    val deficit: Double?,       // kcal; per-day for Daily, avg/day for Weekly/Monthly
    val scaleDeficit: Double? = null,
    val impliedMaintenance: Double? = null,
    val impliedCalories: Double? = null,
    // True when this row's weight / calories were (partly) imputed rather than logged.
    val weightImputed: Boolean = false,
    val caloriesImputed: Boolean = false,
    // ISO date of the underlying weigh-in — set only for Daily rows (a single
    // date), so that day's check-in can be edited. Null for week/month aggregates.
    val date: String? = null,
    // Check-in id for the Daily row's weigh-in, so it can be deleted.
    val checkInId: String? = null,
)

/** How often (count) and how recently (lastDate, ISO) a food was logged. */
data class Usage(val count: Int, val lastDate: String)
data class FoodUsage(val food: LibraryFood, val usage: Usage)

/** Today's calorie summary. */
data class DayData(
    val goalCalories: Double = 0.0,
    val consumedCalories: Double = 0.0,
    val entries: List<FoodEntry> = emptyList(),
    // food_entry_meal_id -> logged-meal name (e.g. "Bam salsa"), so grouped
    // ingredients collapse into one diary row.
    val mealNames: Map<String, String> = emptyMap(),
    // food_entry_meal_id -> the meal's logged total (dish) grams. Distinct from
    // the sum of its scaled ingredient entries, which is the raw-weight portion.
    val mealGrams: Map<String, Double> = emptyMap(),
)

/** Weight/deficit history, pre-computed for every granularity from one fetch. */
data class HistoryData(
    val unit: String = "kg",
    val byGranularity: Map<String, List<HistoryRow>> = emptyMap(),
)

/** A previous day's meal, ready to re-log verbatim onto today (the "+ → Repeat
 *  a meal" shortcut). [date] is the most recent prior day this meal type was
 *  logged; [entries] are that day's entries for that meal. */
data class RepeatableMeal(
    val mealType: String,
    val date: String,
    val entries: List<FoodEntry>,
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
    // Most recent prior day's meal per meal type, for the "Repeat a meal" shortcut.
    // Derived from the same diary range that powers per-food usage (no extra fetch).
    private val _lastMeals = MutableStateFlow<List<RepeatableMeal>>(emptyList())
    val lastMeals: StateFlow<List<RepeatableMeal>> = _lastMeals.asStateFlow()

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
                _day.value = DayData(c.goalCalories, c.consumedCalories, c.entries, c.mealNames, c.mealGrams)
            }
            historyCache.load()?.let { c ->
                // Restore the unit so an offline weigh-in still converts correctly
                // (the var otherwise defaults to "kg" until the first successful refresh).
                weightUnit = c.unit
                _history.value = HistoryData(
                    c.unit,
                    c.byGranularity.mapValues { (_, rows) ->
                        rows.map {
                            HistoryRow(
                                it.label, it.weight, it.weightDelta, it.deficit,
                                it.scaleDeficit, it.impliedMaintenance, it.impliedCalories,
                                it.weightImputed, it.caloriesImputed,
                                it.date, it.checkInId,
                            )
                        }
                    },
                )
            }
            libraryCache.load()?.let { c ->
                val items = c.foods.map {
                    FoodUsage(LibraryFood(it.id, it.name, it.brand, it.variant), Usage(it.count, it.lastDate))
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
                    val recentD = async { recentEntries(api) }
                    val loggedMealsD = async {
                        runCatching { api.foodEntryMealsForDate(today.toString()) }.getOrDefault(emptyList())
                    }

                    val summary = summaryD.await()
                    val prefs = prefsD.await()
                    val rawCheckins = checkinsD.await().filter { it.weight != null }
                    val checkins = rawCheckins.map { LocalDate.parse(it.date) to it.weight!! }
                    val checkInIds = rawCheckins.associate { LocalDate.parse(it.date) to it.id }
                    val report = reportD.await()
                    val foodsPage = foodsD.await()
                    val meals = mealsD.await().sortedBy { it.name.lowercase() }
                    val recent = recentD.await()
                    val usage = computeUsage(recent)
                    _lastMeals.value = computeLastMeals(recent)
                    val loggedMeals = loggedMealsD.await()
                    val mealNames = loggedMeals.associate { it.id to it.name }
                    val mealGrams = loggedMeals.associate { it.id to it.quantity }

                    // --- Today ---
                    _day.value = DayData(
                        summary.goals.calories, summary.consumedCalories, summary.foodEntries,
                        mealNames, mealGrams,
                    )

                    // --- History (compute every granularity from one fetch) ---
                    weightUnit = prefs.weightUnit
                    val maintenance = maintenanceCalories(summary.calorieBalance.bmr, prefs.activityLevel)
                    val byGran = Granularity.entries.associate { g ->
                        g.name to buildRows(g, checkins, checkInIds, report, maintenance, prefs.weightUnit)
                    }
                    _history.value = HistoryData(unitLabel(prefs.weightUnit), byGran)

                    // --- Library ---
                    val items = foodsPage.foods.map { FoodUsage(it, usage[it.id] ?: Usage(0, "")) }
                    _library.value = LibraryData(items, meals, foodsPage.totalCount)

                    // --- Persist all caches ---
                    dayCache.save(
                        CachedDay(
                            today.toString(), summary.goals.calories, summary.consumedCalories,
                            summary.foodEntries, mealNames, mealGrams,
                        ),
                    )
                    historyCache.save(
                        CachedHistory(
                            unitLabel(prefs.weightUnit),
                            byGran.mapValues { (_, rows) ->
                                rows.map {
                                    CachedHistoryRow(
                                        it.label, it.weight, it.weightDelta, it.deficit,
                                        it.scaleDeficit, it.impliedMaintenance, it.impliedCalories,
                                        it.weightImputed, it.caloriesImputed,
                                        it.date, it.checkInId,
                                    )
                                }
                            },
                        ),
                    )
                    libraryCache.save(
                        CachedLibrary(
                            foods = items.map {
                                CachedFood(
                                    it.food.id, it.food.name, it.food.brand,
                                    it.usage.count, it.usage.lastDate, it.food.defaultVariant,
                                )
                            },
                            meals = meals,
                            totalFoods = foodsPage.totalCount,
                        ),
                    )
                }
                _error.value = null
            } catch (e: Exception) {
                _error.value = friendlyError(e)
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Reload only Today's diary (goal, entries, logged meals) — a light reconcile
     *  after a diary edit/log, instead of re-pulling the library, history and usage.
     *  Per-food usage stays as-is until the next full [refresh]. */
    fun refreshToday() {
        scope.launch {
            val s = store.settings.first()
            if (!s.isConfigured) return@launch
            try {
                val api = SparkyApi(s.baseUrl, s.apiKey)
                val today = LocalDate.now().toString()
                coroutineScope {
                    val summaryD = async { api.dailySummary(today) }
                    val loggedMealsD = async {
                        runCatching { api.foodEntryMealsForDate(today) }.getOrDefault(emptyList())
                    }
                    val summary = summaryD.await()
                    val loggedMeals = loggedMealsD.await()
                    val mealNames = loggedMeals.associate { it.id to it.name }
                    val mealGrams = loggedMeals.associate { it.id to it.quantity }
                    _day.value = DayData(
                        summary.goals.calories, summary.consumedCalories, summary.foodEntries,
                        mealNames, mealGrams,
                    )
                    dayCache.save(
                        CachedDay(
                            today, summary.goals.calories, summary.consumedCalories,
                            summary.foodEntries, mealNames, mealGrams,
                        ),
                    )
                }
            } catch (e: Exception) {
                // Leave the optimistic state; a later full refresh reconciles.
            }
        }
    }

    /** Upsert a day's body weight (entered in the display unit), then refresh.
     *  Defaults to today; pass an ISO date to edit a past weigh-in. */
    fun logWeight(
        displayValue: Double,
        date: String = LocalDate.now().toString(),
        onError: (String) -> Unit = {},
    ) {
        scope.launch {
            val s = store.settings.first()
            if (!s.isConfigured) return@launch
            try {
                SparkyApi(s.baseUrl, s.apiKey)
                    .logWeight(date, displayToKg(displayValue, weightUnit))
                refresh()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to log weight")
            }
        }
    }

    /** Change a diary entry's quantity. Updates Today locally at once, then
     *  confirms against the server (reverting to server truth on failure). */
    fun updateEntry(entry: FoodEntry, newQuantity: Double, onError: (String) -> Unit = {}) {
        setEntries(day.value.entries.map { if (it.id == entry.id) it.copy(quantity = newQuantity) else it })
        scope.launch {
            val s = store.settings.first()
            if (!s.isConfigured) return@launch
            try {
                SparkyApi(s.baseUrl, s.apiKey).updateFoodEntry(
                    id = entry.id,
                    quantity = newQuantity,
                    variantId = entry.variantId,
                    mealType = entry.mealType ?: "snacks",
                    unit = entry.unit,
                    date = entry.entryDate.ifBlank { LocalDate.now().toString() },
                )
            } catch (e: Exception) {
                onError(e.message ?: "Update failed")
            }
            refreshToday() // reconcile Today with the server (success or revert)
        }
    }

    /** Delete a diary entry. Removes it from Today locally at once, then
     *  confirms against the server (reverting to server truth on failure). */
    fun deleteEntry(entry: FoodEntry, onError: (String) -> Unit = {}) {
        setEntries(day.value.entries.filterNot { it.id == entry.id })
        scope.launch {
            val s = store.settings.first()
            if (!s.isConfigured) return@launch
            try {
                SparkyApi(s.baseUrl, s.apiKey).deleteFoodEntry(entry.id)
            } catch (e: Exception) {
                onError(e.message ?: "Delete failed")
            }
            refreshToday() // reconcile Today with the server (success or revert)
        }
    }

    /** Change a logged meal's total quantity (ingredients scale). Updates Today
     *  locally at once, then confirms against the server. */
    fun updateLoggedMeal(
        femId: String,
        name: String,
        entries: List<FoodEntry>,
        currentGrams: Double,
        newGrams: Double,
        onError: (String) -> Unit = {},
    ) {
        val mealType = entries.firstOrNull()?.mealType ?: "snacks"
        val date = entries.firstOrNull()?.entryDate?.ifBlank { null } ?: LocalDate.now().toString()
        // Scale by the dish total (what the user sees/edits), not the ingredient sum.
        val scale = newGrams / currentGrams.coerceAtLeast(1.0)
        // Optimistic: scale this meal's ingredient entries and its dish total in place.
        val updated = day.value.entries.map {
            if (it.foodEntryMealId == femId) it.copy(quantity = it.quantity * scale) else it
        }
        _day.value = _day.value.copy(
            entries = updated,
            consumedCalories = updated.sumOf { it.consumedCalories },
            mealGrams = day.value.mealGrams + (femId to newGrams),
        )
        scope.launch {
            val s = store.settings.first()
            if (!s.isConfigured) return@launch
            try {
                SparkyApi(s.baseUrl, s.apiKey)
                    .updateLoggedMeal(femId, name, mealType, date, currentGrams, newGrams, entries)
            } catch (e: Exception) {
                onError(e.message ?: "Update failed")
            }
            refreshToday()
        }
    }

    /** Delete a whole logged meal (its grouped entries). Removes them from Today
     *  locally at once, then confirms against the server. */
    fun deleteLoggedMeal(foodEntryMealId: String, onError: (String) -> Unit = {}) {
        val remaining = day.value.entries.filterNot { it.foodEntryMealId == foodEntryMealId }
        _day.value = _day.value.copy(
            entries = remaining,
            consumedCalories = remaining.sumOf { it.consumedCalories },
            mealNames = day.value.mealNames - foodEntryMealId,
            mealGrams = day.value.mealGrams - foodEntryMealId,
        )
        scope.launch {
            val s = store.settings.first()
            if (!s.isConfigured) return@launch
            try {
                SparkyApi(s.baseUrl, s.apiKey).deleteFoodEntryMeal(foodEntryMealId)
            } catch (e: Exception) {
                onError(e.message ?: "Delete failed")
            }
            refreshToday() // reconcile Today with the server (success or revert)
        }
    }

    /** Optimistically swap Today's entries and recompute consumed calories. */
    private fun setEntries(entries: List<FoodEntry>) {
        _day.value = _day.value.copy(
            entries = entries,
            consumedCalories = entries.sumOf { it.consumedCalories },
        )
    }

    /** Delete a weigh-in (check-in), then refresh. */
    fun deleteCheckIn(id: String, onError: (String) -> Unit = {}) {
        scope.launch {
            val s = store.settings.first()
            if (!s.isConfigured) return@launch
            try {
                SparkyApi(s.baseUrl, s.apiKey).deleteCheckIn(id)
                refresh()
            } catch (e: Exception) {
                onError(e.message ?: "Delete failed")
            }
        }
    }

    /** Aggregate the last [USAGE_WINDOW_DAYS] days of diary entries into per-food usage.
     *  One range request rather than a fan-out of per-day calls. */
    /** All diary entries in the recent usage window, in one ranged call. */
    private suspend fun recentEntries(api: SparkyApi): List<FoodEntry> {
        val today = LocalDate.now()
        val start = today.minusDays((USAGE_WINDOW_DAYS - 1).toLong()).toString()
        return runCatching { api.foodEntriesForDateRange(start, today.toString()) }
            .getOrDefault(emptyList())
    }

    private fun computeUsage(entries: List<FoodEntry>): Map<String, Usage> {
        val acc = HashMap<String, UsageAcc>()
        for (e in entries) {
            if (e.foodId.isBlank()) continue
            val u = acc.getOrPut(e.foodId) { UsageAcc() }
            u.count++
            if (e.entryDate > u.lastDate) u.lastDate = e.entryDate
        }
        return acc.mapValues { Usage(it.value.count, it.value.lastDate) }
    }

    /** For each meal type, the entries from the most recent *prior* day it was
     *  logged (today excluded — "from last time"), in canonical meal order. */
    private fun computeLastMeals(entries: List<FoodEntry>): List<RepeatableMeal> {
        val today = LocalDate.now().toString()
        // entry_date is a full ISO timestamp (e.g. 2026-06-17T04:00:00.000Z); compare
        // and group on the date part only.
        fun day(e: FoodEntry) = e.entryDate.take(10)
        val byType = entries
            .filter { it.entryDate.isNotBlank() && day(it) < today && !it.mealType.isNullOrBlank() }
            .groupBy { it.mealType!!.lowercase() }
        return MEAL_ORDER.mapNotNull { type ->
            val es = byType[type] ?: return@mapNotNull null
            val lastDay = es.maxOf { day(it) }
            RepeatableMeal(type, lastDay, es.filter { day(it) == lastDay })
        }
    }

    /** Re-log every food from a previous day's [meal] onto today, then reconcile
     *  Today. Each entry keeps its food, variant, quantity and unit. */
    fun repeatMeal(meal: RepeatableMeal, onResult: (String) -> Unit = {}) {
        scope.launch {
            val s = store.settings.first()
            if (!s.isConfigured) { onResult("Not configured"); return@launch }
            try {
                val api = SparkyApi(s.baseUrl, s.apiKey)
                val today = LocalDate.now().toString()
                meal.entries.forEach { api.logEntry(it, today) }
                refreshToday()
                val n = meal.entries.size
                onResult("Logged $n item${if (n == 1) "" else "s"} to ${meal.mealType.replaceFirstChar { it.uppercase() }}")
            } catch (e: Exception) {
                onResult(e.message ?: "Couldn't repeat that meal")
            }
        }
    }
}

// Canonical meal-type order shared across the app (matches the Today screen).
private val MEAL_ORDER = listOf("breakfast", "lunch", "snacks", "dinner")

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

/** Least-squares fit y = b0 + b1·x + b2·x² (x centred for numerical stability),
 *  returning a predictor — or null if there's no data. Falls back to a straight
 *  line for two points (or a degenerate quadratic) and a constant for one. */
private fun quadFit(points: List<Pair<Double, Double>>): ((Double) -> Double)? {
    if (points.isEmpty()) return null
    val mean = points.map { it.first }.average()
    val xs = points.map { it.first - mean }
    val ys = points.map { it.second }
    val n = points.size
    if (n == 1) { val c = ys[0]; return { c } }

    fun linear(): (Double) -> Double {
        val sx = xs.sum(); val sy = ys.sum()
        val sxx = xs.sumOf { it * it }
        val sxy = xs.indices.sumOf { xs[it] * ys[it] }
        val denom = n * sxx - sx * sx
        if (kotlin.math.abs(denom) < 1e-9) { val c = sy / n; return { c } }
        val b1 = (n * sxy - sx * sy) / denom
        val b0 = (sy - b1 * sx) / n
        return { x -> b0 + b1 * (x - mean) }
    }
    if (n == 2) return linear()

    val s1 = xs.sum()
    val s2 = xs.sumOf { it * it }
    val s3 = xs.sumOf { it * it * it }
    val s4 = xs.sumOf { it * it * it * it }
    val t0 = ys.sum()
    val t1 = xs.indices.sumOf { xs[it] * ys[it] }
    val t2 = xs.indices.sumOf { xs[it] * xs[it] * ys[it] }
    fun det3(m: Array<DoubleArray>) =
        m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
            m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
    val a = arrayOf(
        doubleArrayOf(n.toDouble(), s1, s2),
        doubleArrayOf(s1, s2, s3),
        doubleArrayOf(s2, s3, s4),
    )
    val d = det3(a)
    if (kotlin.math.abs(d) < 1e-9) return linear()
    val rhs = doubleArrayOf(t0, t1, t2)
    fun withCol(col: Int) = Array(3) { r -> DoubleArray(3) { c -> if (c == col) rhs[r] else a[r][c] } }
    val b0 = det3(withCol(0)) / d
    val b1 = det3(withCol(1)) / d
    val b2 = det3(withCol(2)) / d
    return { x -> val xc = x - mean; b0 + b1 * xc + b2 * xc * xc }
}

private fun buildRows(
    g: Granularity,
    checkins: List<Pair<LocalDate, Double>>,
    checkInIds: Map<LocalDate, String>,
    report: Report,
    maintenance: Double,
    weightUnit: String,
): List<HistoryRow> {
    // --- Real daily observations (average any duplicate same-day weigh-ins). ---
    val realKg = sortedMapOf<LocalDate, Double>()
    run {
        val acc = hashMapOf<LocalDate, MutableList<Double>>()
        for ((date, kg) in checkins) acc.getOrPut(date) { mutableListOf() }.add(kg)
        acc.forEach { (date, v) -> realKg[date] = v.average() }
    }
    val realCal = sortedMapOf<LocalDate, Double>()
    for (nd in report.nutritionData) {
        if (nd.calories <= 0 || nd.date.isBlank()) continue
        val date = runCatching { LocalDate.parse(nd.date) }.getOrNull() ?: continue
        realCal[date] = nd.calories
    }
    if (realKg.isEmpty() && realCal.isEmpty()) return emptyList()

    // Real observations as sorted (epochDay, value) lists, for the local fits.
    val kgList = realKg.entries.map { it.key.toEpochDay().toDouble() to it.value }
    val calList = realCal.entries.map { it.key.toEpochDay().toDouble() to it.value }
    val start = listOfNotNull(realKg.keys.minOrNull(), realCal.keys.minOrNull()).min()
    val end = LocalDate.now()

    // Per period: weights (real + imputed), whether any day was imputed, and the
    // daily deficit sum/count over the filled series.
    val weightByPeriod = sortedMapOf<LocalDate, MutableList<Double>>()
    val weightImpByPeriod = hashMapOf<LocalDate, Boolean>()
    val deficitSum = hashMapOf<LocalDate, Double>()
    val deficitCount = hashMapOf<LocalDate, Int>()
    val calImpByPeriod = hashMapOf<LocalDate, Boolean>()

    // Impute day [x] from a local quadratic over the previous up-to-IMPUTE_WINDOW
    // real points (those strictly before x, given by [idx]). Needs ≥1 prior point,
    // so days before a series' first observation are never back-filled. The prior
    // points need not be contiguous — gaps are skipped, not back-extrapolated into.
    fun predict(series: List<Pair<Double, Double>>, idx: Int, x: Double): Double? =
        if (idx == 0) null else quadFit(series.subList(maxOf(0, idx - IMPUTE_WINDOW), idx))?.invoke(x)

    var wIdx = 0
    var cIdx = 0
    var d = start
    while (!d.isAfter(end)) {
        val key = periodStart(d, g)
        val x = d.toEpochDay().toDouble()
        // Advance each cursor to the count of real points strictly before today.
        while (wIdx < kgList.size && kgList[wIdx].first < x) wIdx++
        while (cIdx < calList.size && calList[cIdx].first < x) cIdx++

        val kg = realKg[d] ?: predict(kgList, wIdx, x)
        if (kg != null) {
            weightByPeriod.getOrPut(key) { mutableListOf() }.add(kgToDisplay(kg, weightUnit))
            if (realKg[d] == null) weightImpByPeriod[key] = true
        }
        val cal = realCal[d] ?: predict(calList, cIdx, x)
        if (cal != null) {
            deficitSum[key] = (deficitSum[key] ?: 0.0) + (maintenance - cal)
            deficitCount[key] = (deficitCount[key] ?: 0) + 1
            if (realCal[d] == null) calImpByPeriod[key] = true
        }
        d = d.plusDays(1)
    }

    val periods = weightByPeriod.keys.toList() // ascending
    val rows = periods.mapIndexed { i, key ->
        val avgWeight = weightByPeriod.getValue(key).average()
        val prevKey = if (i > 0) periods[i - 1] else null
        val prevWeight = prevKey?.let { weightByPeriod.getValue(it).average() }
        val weightDelta = prevWeight?.let { avgWeight - it }
        // "Actual" deficit = formula maintenance − intake, averaged over the period's days.
        val deficit = deficitCount[key]?.let { c -> deficitSum.getValue(key) / c }
        val avgIntake = deficit?.let { maintenance - it }

        // Energy implied purely by the weight change, spread over the days it spans.
        val gap = prevKey?.let { ChronoUnit.DAYS.between(it, key).toDouble() }
        val scaleDeficit = if (weightDelta != null && gap != null && gap > 0) {
            -displayToKg(weightDelta, weightUnit) * KCAL_PER_KG / gap
        } else {
            null
        }
        HistoryRow(
            label = periodLabel(key, g),
            weight = avgWeight,
            weightDelta = weightDelta,
            deficit = deficit,
            scaleDeficit = scaleDeficit,
            // Real TDEE: what maintenance must be if the log and the scale are both right.
            impliedMaintenance = if (avgIntake != null && scaleDeficit != null) avgIntake + scaleDeficit else null,
            // Real intake: what you must have eaten if the formula and the scale are both right.
            impliedCalories = if (scaleDeficit != null && maintenance > 0) maintenance - scaleDeficit else null,
            weightImputed = weightImpByPeriod[key] == true,
            caloriesImputed = calImpByPeriod[key] == true,
            // Only a real same-day weigh-in is editable/deletable; an imputed day isn't.
            date = if (g == Granularity.Daily) key.toString() else null,
            checkInId = if (g == Granularity.Daily) checkInIds[key] else null,
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
