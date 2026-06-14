package net.bam.sfit.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.bam.sfit.data.FoodEntry
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: MainViewModel,
    onOpenSettings: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<FoodEntry?>(null) }
    var viewingMeal by remember { mutableStateOf<DiaryMeal?>(null) }

    editing?.let { entry ->
        EntryEditSheet(
            entry = entry,
            onSave = { qty -> vm.editEntry(entry, qty); editing = null },
            onDelete = { vm.deleteEntry(entry); editing = null },
            onDismiss = { editing = null },
        )
    }

    viewingMeal?.let { meal ->
        MealEntrySheet(
            meal = meal,
            onDelete = { vm.deleteLoggedMeal(meal.femId); viewingMeal = null },
            onDismiss = { viewingMeal = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Today") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        val hasData = state.hasGoal || state.entries.isNotEmpty()
        when {
            !state.configured -> Centered(padding) { UnconfiguredMessage(onOpenSettings) }
            !hasData && state.loading -> Centered(padding) { CircularProgressIndicator() }
            !hasData && state.error != null -> Centered(padding) { ErrorMessage(state.error!!) { vm.refresh() } }
            else -> TodayContent(
                state,
                onRefresh = vm::refresh,
                onEntryClick = { editing = it },
                onMealClick = { viewingMeal = it },
                Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

@Composable
private fun Centered(padding: PaddingValues, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private val MEAL_ORDER = listOf("breakfast", "lunch", "snacks", "dinner")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodayContent(
    state: DayState,
    onRefresh: () -> Unit,
    onEntryClick: (FoodEntry) -> Unit,
    onMealClick: (DiaryMeal) -> Unit,
    modifier: Modifier,
) {
    PullToRefreshBox(isRefreshing = state.loading, onRefresh = onRefresh, modifier = modifier) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { RemainingCalories(state) }
        }

        if (state.entries.isEmpty()) {
            item {
                Text(
                    "Nothing logged yet today.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            val byMeal = state.entries.groupBy { (it.mealType ?: "other").lowercase() }
            val keys = MEAL_ORDER.filter { it in byMeal } + byMeal.keys.filter { it !in MEAL_ORDER }
            keys.forEach { meal ->
                val list = byMeal.getValue(meal)
                item { MealHeader(meal, list.sumOf { it.consumedCalories }) }
                // Collapse grouped meal ingredients into one row; foods stay individual.
                items(buildDiaryRows(list, state.mealNames)) { row ->
                    when (row) {
                        is DiaryRow.Food -> EntryRow(row.entry, onClick = { onEntryClick(row.entry) })
                        is DiaryRow.Meal -> MealRow(row.meal, onClick = { onMealClick(row.meal) })
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun MealHeader(meal: String, kcal: Double) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            meal.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "${kcal.roundToInt()} kcal",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EntryRow(e: FoodEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(e.foodName ?: "(unnamed)", style = MaterialTheme.typography.bodyLarge)
            Text(
                buildString {
                    val q = e.quantity
                    append(if (q == q.toLong().toDouble()) q.toLong().toString() else "%.1f".format(q))
                    append(" ").append(e.displayUnit)
                    e.brandName?.let { append("  ·  ").append(it) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "${e.consumedCalories.roundToInt()} kcal",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A logged meal (recipe) collapsed into a single diary row. */
data class DiaryMeal(val femId: String, val name: String, val entries: List<FoodEntry>) {
    val totalCalories: Double get() = entries.sumOf { it.consumedCalories }
}

private sealed interface DiaryRow {
    data class Food(val entry: FoodEntry) : DiaryRow
    data class Meal(val meal: DiaryMeal) : DiaryRow
}

/** Collapse entries that share a food_entry_meal_id into one meal row, keeping
 *  standalone foods individual and preserving diary order. */
private fun buildDiaryRows(entries: List<FoodEntry>, mealNames: Map<String, String>): List<DiaryRow> {
    val rows = mutableListOf<DiaryRow>()
    val seen = HashSet<String>()
    for (e in entries) {
        val fem = e.foodEntryMealId
        if (fem.isNullOrBlank()) {
            rows.add(DiaryRow.Food(e))
        } else if (seen.add(fem)) {
            val group = entries.filter { it.foodEntryMealId == fem }
            rows.add(DiaryRow.Meal(DiaryMeal(fem, mealNames[fem] ?: "Meal", group)))
        }
    }
    return rows
}

private fun qtyLabel(e: FoodEntry): String {
    val q = e.quantity
    val num = if (q == q.toLong().toDouble()) q.toLong().toString() else "%.1f".format(q)
    return "$num ${e.displayUnit}"
}

@Composable
private fun MealRow(meal: DiaryMeal, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Restaurant,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(meal.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${meal.entries.size} ingredients",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "${meal.totalCalories.roundToInt()} kcal",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MealEntrySheet(meal: DiaryMeal, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(meal.name, style = MaterialTheme.typography.titleLarge)
            Text(
                "${meal.totalCalories.roundToInt()} kcal  ·  ${meal.entries.size} ingredients",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            meal.entries.forEach { e ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(e.foodName ?: "(unnamed)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            qtyLabel(e),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "${e.consumedCalories.roundToInt()} kcal",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Text("Delete meal")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryEditSheet(
    entry: FoodEntry,
    onSave: (Double) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var text by remember {
        val q = entry.quantity
        mutableStateOf(if (q == q.toLong().toDouble()) q.toLong().toString() else "%.1f".format(q))
    }
    val qty = text.trim().toDoubleOrNull()
    val valid = qty != null && qty > 0
    val kcal = if (valid && entry.servingSize > 0) entry.calories * qty!! / entry.servingSize else 0.0

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(entry.foodName ?: "(unnamed)", style = MaterialTheme.typography.titleLarge)
            entry.brandName?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Quantity (${entry.displayUnit})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${kcal.roundToInt()} kcal",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                ) { Text("Delete") }
                Button(
                    onClick = { qty?.let(onSave) },
                    enabled = valid,
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun RemainingCalories(state: DayState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        CalorieRing(
            consumed = state.consumedCalories,
            goal = state.goalCalories,
            hasGoal = state.hasGoal,
        )
        Text(
            text = if (state.hasGoal) {
                "${state.consumedCalories.roundToInt()} eaten  ·  ${state.goalCalories.roundToInt()} goal"
            } else {
                "${state.consumedCalories.roundToInt()} eaten  ·  no goal set"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Circular gauge of consumed vs goal, with the remaining count in the centre. */
@Composable
private fun CalorieRing(consumed: Double, goal: Double, hasGoal: Boolean) {
    val remaining = (goal - consumed).roundToInt()
    val over = hasGoal && consumed > goal
    val rawFraction = if (hasGoal && goal > 0) (consumed / goal).toFloat() else 0f
    val sweep by animateFloatAsState(
        targetValue = rawFraction.coerceIn(0f, 1f) * 360f,
        label = "ringSweep",
    )

    val ringColor = if (over) MaterialTheme.colorScheme.error else Color(0xFF2ECC71)
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val numberColor = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

    Box(modifier = Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(260.dp)) {
            val strokePx = 26.dp.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = trackColor, startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize, style = Stroke(strokePx, cap = StrokeCap.Round),
            )
            if (sweep > 0f) {
                drawArc(
                    color = ringColor, startAngle = -90f, sweepAngle = sweep, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(strokePx, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (!hasGoal) "eaten" else if (over) "over by" else "remaining",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${if (!hasGoal) consumed.roundToInt() else if (over) -remaining else remaining}",
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                color = numberColor,
            )
            Text(
                text = "kcal",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UnconfiguredMessage(onOpenSettings: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Connect to SparkyFitness",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            "Set your server URL and API key to see today's calories.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onOpenSettings) { Text("Open settings") }
    }
}

@Composable
private fun ErrorMessage(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Couldn't load", style = MaterialTheme.typography.titleLarge)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}
