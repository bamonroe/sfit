package net.bam.sfit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.bam.sfit.data.LibraryFood
import net.bam.sfit.data.LibraryMeal
import net.bam.sfit.data.MealLine
import net.bam.sfit.data.SettingsStore
import net.bam.sfit.data.SparkyApi
import kotlin.math.roundToInt

/** Editable ingredient row. Grams is mutable state; calories/serving carry over
 *  so each line can show its scaled kcal. */
private class EditLine(
    val foodId: String,
    val variantId: String,
    val name: String,
    grams: String,
    val calories: Double,
    val servingSize: Double,
) {
    var grams by mutableStateOf(grams)

    val kcal: Double
        get() {
            val g = grams.toDoubleOrNull() ?: 0.0
            return if (servingSize > 0) calories * g / servingSize else 0.0
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditMealScreen(meal: LibraryMeal, store: SettingsStore, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(meal.name) }
    val lines = remember {
        meal.foods.map {
            EditLine(it.foodId, it.variantId, it.displayName, fullNum(it.quantity), it.calories, it.servingSize)
        }.toMutableStateList()
    }
    var netWeight by remember { mutableStateOf(fullNum(meal.totalGrams)) }
    var saving by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    /** Resolve a library food to a variant and append it as a new line. */
    fun addFood(food: LibraryFood) {
        if (lines.any { it.foodId == food.id }) {
            note = "${food.name} is already in the meal"; return
        }
        scope.launch {
            adding = true
            error = null
            try {
                val s = store.settings.first()
                val detail = SparkyApi(s.baseUrl, s.apiKey).foodDetail(food.id)
                val v = detail.defaultVariant
                lines.add(
                    EditLine(food.id, v.id ?: "", detail.name.ifBlank { food.name }, "", v.calories, v.servingSize),
                )
                note = "Added ${detail.name.ifBlank { food.name }}"
            } catch (e: Exception) {
                error = e.message ?: "Couldn't add food"
            } finally {
                adding = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit meal") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Meal name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            lines.forEach { line ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(line.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                        Text(
                            "${line.kcal.roundToInt()} kcal",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = line.grams,
                        onValueChange = { line.grams = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("g") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(96.dp),
                    )
                    IconButton(onClick = { lines.remove(line) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove")
                    }
                }
            }
            if (lines.isEmpty()) {
                Text("No ingredients — add one below, or removing all will leave an empty meal.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            OutlinedButton(
                onClick = { note = null; error = null; showPicker = true },
                enabled = !adding,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                if (adding) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("  Add ingredient")
                }
            }

            note?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            val ingredientSum = lines.sumOf { it.grams.toDoubleOrNull() ?: 0.0 }
            Text("Net weight", style = MaterialTheme.typography.titleSmall)
            Text(
                "The recipe's total weight — scales gram-logging. Defaults to the " +
                    "current total; the ingredient sum is ${ingredientSum.roundToInt()} g.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            OutlinedTextField(
                value = netWeight,
                onValueChange = { netWeight = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Net weight (g)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }

            Button(
                onClick = {
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            val s = store.settings.first()
                            val mealLines = lines.mapNotNull { l ->
                                val g = l.grams.toDoubleOrNull() ?: 0.0
                                if (g > 0) MealLine(l.foodId, l.variantId, g) else null
                            }
                            SparkyApi(s.baseUrl, s.apiKey)
                                .updateMeal(meal.id, name.trim(), mealLines, netWeight.toDoubleOrNull())
                            onDone()
                        } catch (e: Exception) {
                            saving = false
                            error = e.message ?: "Save failed"
                        }
                    }
                },
                enabled = !saving && name.isNotBlank() && lines.any { (it.grams.toDoubleOrNull() ?: 0.0) > 0 },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Save meal")
                }
            }
        }
    }

    if (showPicker) {
        AddIngredientSheet(
            store = store,
            onPickFood = { food -> showPicker = false; addFood(food) },
            onDismiss = { showPicker = false },
        )
    }
}

/** Searchable library picker: tap a food to resolve + append it as an
 *  ingredient. To use another recipe as an ingredient, turn it into a food
 *  first ("Make food" on the meal), then pick it here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddIngredientSheet(
    store: SettingsStore,
    onPickFood: (LibraryFood) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var foods by remember { mutableStateOf<List<LibraryFood>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            val s = store.settings.first()
            foods = SparkyApi(s.baseUrl, s.apiKey).foods().foods
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load foods"
        } finally {
            loading = false
        }
    }

    val q = query.trim()
    val shownFoods = foods.filter {
        q.isBlank() || it.name.contains(q, ignoreCase = true) || (it.brand?.contains(q, ignoreCase = true) ?: false)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search your foods",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            HorizontalDivider()

            Box(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                when {
                    loading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
                    )
                    error != null -> Text(
                        error!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(shownFoods, key = { it.id }) { food ->
                            PickerRow(
                                title = food.name.ifBlank { "(unnamed)" },
                                subtitle = food.brand?.takeIf { it.isNotBlank() },
                                onClick = { onPickFood(food) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.Default.Add, contentDescription = "Add")
    }
    HorizontalDivider()
}
