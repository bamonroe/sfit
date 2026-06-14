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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.bam.sfit.data.BarcodeFood
import net.bam.sfit.data.SettingsStore
import net.bam.sfit.data.SparkyApi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditFoodScreen(food: BarcodeFood, store: SettingsStore, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val v = food.defaultVariant
    var name by remember { mutableStateOf(food.name) }
    var brand by remember { mutableStateOf(food.brand ?: "") }
    var serving by remember { mutableStateOf(numStr(v.servingSize)) }
    var unit by remember { mutableStateOf(v.servingUnit) }
    var calories by remember { mutableStateOf(numStr(v.calories)) }
    var protein by remember { mutableStateOf(numStr(v.protein)) }
    var carbs by remember { mutableStateOf(numStr(v.carbs)) }
    var fat by remember { mutableStateOf(numStr(v.fat)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit food") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Field(name, { name = it }, "Name", Modifier.fillMaxWidth())
            Field(brand, { brand = it }, "Brand", Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumField(serving, { serving = it }, "Serving size", Modifier.weight(1f))
                Field(unit, { unit = it }, "Unit", Modifier.weight(1f))
            }
            NumField(calories, { calories = it }, "Calories (per serving)", Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumField(protein, { protein = it }, "Protein g", Modifier.weight(1f))
                NumField(carbs, { carbs = it }, "Carbs g", Modifier.weight(1f))
                NumField(fat, { fat = it }, "Fat g", Modifier.weight(1f))
            }

            error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }

            Button(
                onClick = {
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            val s = store.settings.first()
                            SparkyApi(s.baseUrl, s.apiKey).updateFood(
                                foodId = food.id ?: "",
                                variantId = v.id ?: "",
                                name = name.trim(),
                                brand = brand.trim().ifBlank { null },
                                servingSize = serving.toDoubleOrNull() ?: v.servingSize,
                                servingUnit = unit.trim().ifBlank { "g" },
                                calories = calories.toDoubleOrNull() ?: 0.0,
                                protein = protein.toDoubleOrNull() ?: 0.0,
                                carbs = carbs.toDoubleOrNull() ?: 0.0,
                                fat = fat.toDoubleOrNull() ?: 0.0,
                            )
                            onDone()
                        } catch (e: Exception) {
                            saving = false
                            error = e.message ?: "Save failed"
                        }
                    }
                },
                enabled = !saving && name.isNotBlank() && food.id != null && v.id != null,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = true, modifier = modifier)
}

@Composable
private fun NumField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '.' }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

private fun numStr(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
