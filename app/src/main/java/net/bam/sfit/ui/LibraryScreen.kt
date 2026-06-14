package net.bam.sfit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.bam.sfit.data.BarcodeFood
import net.bam.sfit.data.LibraryFood
import net.bam.sfit.data.LibraryMeal
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    mealVm: MealViewModel,
    onBulkAdd: () -> Unit,
    onEditFood: (BarcodeFood) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val mealState by mealVm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pickMealFor by remember { mutableStateOf<BarcodeFood?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = onBulkAdd) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Bulk add foods")
                    }
                    IconButton(onClick = vm::load) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.error != null -> Text(
                    state.error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item { SectionHeader("Meals", state.meals.size) }
                    if (state.meals.isEmpty() && !state.loading) item { EmptyRow("No meals yet") }
                    items(state.meals, key = { "m" + it.id }) { meal ->
                        MealRow(meal, onClick = { vm.openMeal(meal) })
                    }

                    item { SectionHeader("Foods", state.totalFoods) }
                    if (state.foods.isEmpty() && !state.loading) item { EmptyRow("No foods yet") }
                    items(state.foods, key = { "f" + it.id }) { food ->
                        FoodRow(food, onClick = { vm.openFood(food.id) })
                    }
                }
            }
            if (state.loading || state.detailLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
            }
        }
    }

    state.detail?.let { food ->
        FoodDetailSheet(
            food = food,
            onDismiss = vm::closeDetail,
            onAddToMeal = { pickMealFor = food; vm.closeDetail() },
            onEdit = { onEditFood(food); vm.closeDetail() },
            onDelete = { food.id?.let(vm::deleteFood) },
        )
    }

    pickMealFor?.let { food ->
        fun add() = mealVm.addFood(
            foodId = food.id ?: "",
            variantId = food.defaultVariant.id ?: "",
            name = food.name,
            brand = food.brand,
            calories = food.defaultVariant.calories,
            servingSize = food.defaultVariant.servingSize,
            servingUnit = food.defaultVariant.servingUnit,
        )
        MealTargetDialog(
            foodName = food.name,
            currentCount = mealState.draft.ingredients.size,
            currentName = mealState.draft.name,
            onAddToCurrent = { add(); vm.notify("Added to meal"); pickMealFor = null },
            onNewMeal = { mealVm.startNew(); add(); vm.notify("Started new meal"); pickMealFor = null },
            onDismiss = { pickMealFor = null },
        )
    }

    state.mealDetail?.let { meal ->
        MealDetailSheet(
            meal = meal,
            onDismiss = vm::closeMealDetail,
            onDelete = { vm.deleteMeal(meal.id) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MealDetailSheet(meal: LibraryMeal, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(meal.name.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.headlineSmall)
            Text(
                "${meal.totalCalories.roundToInt()} kcal  ·  ${meal.totalGrams.roundToInt()} g total",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Macro("Protein", meal.totalProtein, Modifier.weight(1f))
                Macro("Carbs", meal.totalCarbs, Modifier.weight(1f))
                Macro("Fat", meal.totalFat, Modifier.weight(1f))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Text("Ingredients", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            meal.foods.forEach { f ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(f.displayName, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f))
                    Text(
                        "${fmt(f.quantity)} ${f.unit} · ${f.kcal.roundToInt()} kcal",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            ) { Text("Delete meal", color = MaterialTheme.colorScheme.error) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete meal?") },
            text = { Text("Remove \"${meal.name}\" from your recipes?") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MealTargetDialog(
    foodName: String,
    currentCount: Int,
    currentName: String,
    onAddToCurrent: () -> Unit,
    onNewMeal: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hasDraft = currentCount > 0 || currentName.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add \"$foodName\" to…") },
        text = {
            Column {
                if (hasDraft) {
                    val label = currentName.ifBlank { "Current meal" }
                    TextButton(onClick = onAddToCurrent, modifier = Modifier.fillMaxWidth()) {
                        Text("$label  ·  $currentCount item${if (currentCount == 1) "" else "s"}")
                    }
                }
                TextButton(onClick = onNewMeal, modifier = Modifier.fillMaxWidth()) {
                    Text(if (hasDraft) "Start a new meal" else "New meal")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FoodDetailSheet(
    food: BarcodeFood,
    onDismiss: () -> Unit,
    onAddToMeal: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var confirmDelete by remember { mutableStateOf(false) }
    val v = food.defaultVariant

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(food.name.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.headlineSmall)
            if (!food.brand.isNullOrBlank()) {
                Text(food.brand!!, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text(
                "${v.calories.roundToInt()} kcal  ·  per ${fmt(v.servingSize)} ${v.servingUnit}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Macro("Protein", v.protein, Modifier.weight(1f))
                Macro("Carbs", v.carbs, Modifier.weight(1f))
                Macro("Fat", v.fat, Modifier.weight(1f))
            }
            Text(
                "Fiber ${fmt(v.dietaryFiber)}g · Sugar ${fmt(v.sugars)}g · Sodium ${v.sodium.roundToInt()}mg",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onAddToMeal, modifier = Modifier.weight(1f)) { Text("Add to meal") }
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("Edit") }
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.weight(1f),
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete food?") },
            text = { Text("Remove \"${food.name}\" from your database?") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Macro(label: String, grams: Double, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${fmt(grams)}g", style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun fmt(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else "%.1f".format(d)

@Composable
private fun SectionHeader(label: String, count: Int) {
    Text(
        text = "$label · $count",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun FoodRow(food: LibraryFood, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(food.name.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.bodyLarge)
        if (!food.brand.isNullOrBlank()) {
            Text(food.brand!!, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
}

@Composable
private fun MealRow(meal: LibraryMeal, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(meal.name.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.bodyLarge)
        Text(
            "${meal.totalCalories.roundToInt()} kcal",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

@Composable
private fun EmptyRow(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
}
