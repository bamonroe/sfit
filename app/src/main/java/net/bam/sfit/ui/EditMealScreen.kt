package net.bam.sfit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import net.bam.sfit.data.LibraryMeal
import net.bam.sfit.data.MealLine
import net.bam.sfit.data.SettingsStore
import net.bam.sfit.data.SparkyApi

/** Editable ingredient row (grams is mutable state). */
private class EditLine(val foodId: String, val variantId: String, val name: String, grams: String) {
    var grams by mutableStateOf(grams)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditMealScreen(meal: LibraryMeal, store: SettingsStore, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(meal.name) }
    val lines = remember {
        meal.foods.map { EditLine(it.foodId, it.variantId, it.displayName, numStr(it.quantity)) }
            .toMutableStateList()
    }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

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
                    Text(line.name, modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge)
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
                Text("No ingredients — removing all will leave an empty meal.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }

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
                            SparkyApi(s.baseUrl, s.apiKey).updateMeal(meal.id, name.trim(), mealLines)
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
}

private fun numStr(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
