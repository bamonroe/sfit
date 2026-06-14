package net.bam.sfit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.bam.sfit.data.LibraryFood
import net.bam.sfit.data.LibraryMeal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: LibraryViewModel, onBulkAdd: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
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
                    if (state.meals.isEmpty() && !state.loading) {
                        item { EmptyRow("No meals yet") }
                    }
                    items(state.meals, key = { "m" + it.id }) { MealRow(it) }

                    item { SectionHeader("Foods", state.totalFoods) }
                    if (state.foods.isEmpty() && !state.loading) {
                        item { EmptyRow("No foods yet") }
                    }
                    items(state.foods, key = { "f" + it.id }) { FoodRow(it) }
                }
            }
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
            }
        }
    }
}

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
private fun FoodRow(food: LibraryFood) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(food.name.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.bodyLarge)
        if (!food.brand.isNullOrBlank()) {
            Text(
                food.brand!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun MealRow(meal: LibraryMeal) {
    Text(
        meal.name.ifBlank { "(unnamed)" },
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
    HorizontalDivider()
}

@Composable
private fun EmptyRow(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}
