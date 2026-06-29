package net.bam.sfit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import net.bam.sfit.data.BarcodeFood
import kotlin.math.roundToInt

/** Shared body for the Log-food / Add-to-meal screens: a source dropdown
 *  (Library + each online provider), a search field, and the matching results.
 *  Library results filter live; provider results search on submit and import the
 *  tapped food into the library (the VM then opens its detail sheet). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodSourceSearch(state: LibraryState, vm: LibraryViewModel, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    var menuOpen by remember { mutableStateOf(false) }
    val isProvider = state.source != null

    Column(modifier = modifier.fillMaxSize()) {
        ExposedDropdownMenuBox(
            expanded = menuOpen,
            onExpandedChange = { menuOpen = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = state.source?.providerName ?: "Library",
                onValueChange = {},
                readOnly = true,
                label = { Text("Source") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Library") },
                    onClick = { vm.selectSource(null); menuOpen = false },
                )
                state.providers.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.providerName) },
                        onClick = { vm.selectSource(p); menuOpen = false },
                    )
                }
                if (state.loadingProviders && state.providers.isEmpty()) {
                    DropdownMenuItem(text = { Text("Loading providers…") }, onClick = {}, enabled = false)
                }
            }
        }

        if (isProvider) {
            // Provider search is explicit (network), triggered from the keyboard.
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                placeholder = { Text("Search ${state.source?.providerName}") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.searchProvider() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
            )
        } else {
            SearchField(
                value = state.query,
                onValueChange = vm::setQuery,
                placeholder = "Search your foods",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        HorizontalDivider()

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isProvider && state.searching -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp),
                )
                isProvider && !state.searched -> Hint(
                    "Search ${state.source?.providerName}, then tap a result to add it to your library.",
                )
                isProvider && state.providerResults.isEmpty() -> Hint("No matches.")
                isProvider -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.providerResults, key = { it.providerExternalId ?: it.name }) { food ->
                        ProviderResultRow(
                            food = food,
                            importing = state.importingKey ==
                                (food.providerExternalId ?: food.name),
                            onClick = { vm.importProviderFood(food) },
                        )
                        HorizontalDivider()
                    }
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.foods, key = { it.id }) { food ->
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { vm.openFood(food) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        ) {
                            Text(food.name, style = MaterialTheme.typography.bodyLarge)
                            food.brand?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopCenter).padding(32.dp),
        )
    }
}

@Composable
private fun ProviderResultRow(food: BarcodeFood, importing: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !importing, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(food.name, style = MaterialTheme.typography.bodyLarge)
            val v = food.defaultVariant
            val sub = buildString {
                food.brand?.takeIf { it.isNotBlank() }?.let { append(it) }
                if (v.calories > 0 && v.servingSize > 0) {
                    if (isNotEmpty()) append("  ·  ")
                    append("${v.calories.roundToInt()} kcal / ${fmtNum(v.servingSize)} ${v.servingUnit}")
                }
            }
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (importing) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}
