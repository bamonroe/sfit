package net.bam.sfit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.bam.sfit.data.Container
import net.bam.sfit.data.DraftIngredient
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealScreen(
    vm: MealViewModel,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onPickFromLibrary: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val containers by vm.containers.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showAddDialog by remember { mutableStateOf(false) }
    var showAddContainer by remember { mutableStateOf(false) }
    var showManageContainers by remember { mutableStateOf(false) }

    LaunchedEffect(state.message, state.error) {
        (state.error ?: state.message)?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }
    LaunchedEffect(state.createdOk) {
        if (state.createdOk) {
            vm.acknowledgeCreated()
            onBack()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("New meal") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = vm::discardDraft) { Text("Discard") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = state.draft.name,
                onValueChange = vm::setName,
                label = { Text("Meal name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onScan, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Text("  Scan", maxLines = 1)
                }
                OutlinedButton(onClick = onPickFromLibrary, modifier = Modifier.weight(1f)) {
                    Text("Library", maxLines = 1)
                }
                OutlinedButton(onClick = { showAddDialog = true }, modifier = Modifier.weight(1f)) {
                    Text("Barcode", maxLines = 1)
                }
            }

            if (state.resolving) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                    Text("  Looking up…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (state.draft.ingredients.isEmpty()) {
                Text(
                    "Scan, add from your library, or enter a barcode to build the meal.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            } else {
                state.draft.ingredients.forEachIndexed { i, ing ->
                    IngredientRow(
                        ing = ing,
                        onGrams = { vm.setGrams(i, it) },
                        onRemove = { vm.removeAt(i) },
                    )
                    HorizontalDivider()
                }
                Text(
                    "Total: ${state.draft.totalGrams.roundToInt()} g  ·  ${state.draft.totalKcal.roundToInt()} kcal",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                FinalWeightSection(
                    grossGrams = state.draft.grossGrams,
                    containerId = state.draft.containerId,
                    ingredientGrams = state.draft.totalGrams,
                    containers = containers,
                    onGrossChange = vm::setGrossGrams,
                    onSelectContainer = vm::selectContainer,
                    onAddContainer = { showAddContainer = true },
                    onManageContainers = { showManageContainers = true },
                )
                Button(
                    onClick = vm::createMeal,
                    enabled = !state.creating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.creating) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Create meal")
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        BarcodeEntryDialog(
            onAdd = { vm.addByBarcode(it); showAddDialog = false },
            onDismiss = { showAddDialog = false },
        )
    }
    if (showAddContainer) {
        AddContainerDialog(
            onAdd = { name, tare -> vm.addContainer(name, tare); showAddContainer = false },
            onDismiss = { showAddContainer = false },
        )
    }
    if (showManageContainers) {
        ManageContainersDialog(
            containers = containers,
            onDelete = vm::deleteContainer,
            onAdd = { showManageContainers = false; showAddContainer = true },
            onDismiss = { showManageContainers = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinalWeightSection(
    grossGrams: Double?,
    containerId: String?,
    ingredientGrams: Double,
    containers: List<Container>,
    onGrossChange: (Double?) -> Unit,
    onSelectContainer: (String?) -> Unit,
    onAddContainer: () -> Unit,
    onManageContainers: () -> Unit,
) {
    var text by remember { mutableStateOf(grossGrams?.let { fmtNum(it) } ?: "") }
    // Populate from a restored draft once, without clobbering live typing.
    LaunchedEffect(grossGrams) {
        if (text.isBlank() && grossGrams != null) text = fmtNum(grossGrams)
    }
    var menuOpen by remember { mutableStateOf(false) }
    val selected = containers.firstOrNull { it.id == containerId }
    val gross = text.toDoubleOrNull()
    val net = gross?.let { (it - (selected?.tareGrams ?: 0.0)).coerceAtLeast(0.0) }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text("Final weight (optional)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Weigh the finished dish (in its container), pick the container, and its tare " +
                "is subtracted. Used as the recipe's total instead of the " +
                "${ingredientGrams.roundToInt()} g ingredient sum.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it.filter { c -> c.isDigit() || c == '.' }
                onGrossChange(text.toDoubleOrNull())
            },
            label = { Text("Weighed weight (g)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        ExposedDropdownMenuBox(
            expanded = menuOpen,
            onExpandedChange = { menuOpen = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            OutlinedTextField(
                value = selected?.let { "${it.name}  ·  ${it.tareGrams.roundToInt()} g" } ?: "No container",
                onValueChange = {},
                readOnly = true,
                label = { Text("Container") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("No container") },
                    onClick = { onSelectContainer(null); menuOpen = false },
                )
                containers.forEach { c ->
                    DropdownMenuItem(
                        text = { Text("${c.name}  ·  ${c.tareGrams.roundToInt()} g") },
                        onClick = { onSelectContainer(c.id); menuOpen = false },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("+ Add container") },
                    onClick = { menuOpen = false; onAddContainer() },
                )
                if (containers.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Manage containers") },
                        onClick = { menuOpen = false; onManageContainers() },
                    )
                }
            }
        }
        if (net != null) {
            val math = selected?.let { "${gross!!.roundToInt()} − ${it.tareGrams.roundToInt()} g tare = " } ?: ""
            Text(
                "Recipe total: $math${net.roundToInt()} g",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun AddContainerDialog(onAdd: (String, Double) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var tare by remember { mutableStateOf("") }
    val tareVal = tare.toDoubleOrNull()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add container") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true,
                )
                OutlinedTextField(
                    value = tare,
                    onValueChange = { tare = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Tare weight (g)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && tareVal != null && tareVal > 0,
                onClick = { tareVal?.let { onAdd(name, it) } },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ManageContainersDialog(
    containers: List<Container>,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Containers") },
        text = {
            Column {
                containers.forEach { c ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("${c.name}  ·  ${c.tareGrams.roundToInt()} g", modifier = Modifier.weight(1f))
                        IconButton(onClick = { onDelete(c.id) }) {
                            Icon(Icons.Default.Close, contentDescription = "Delete ${c.name}")
                        }
                    }
                }
                TextButton(onClick = onAdd) { Text("+ Add container") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun IngredientRow(ing: DraftIngredient, onGrams: (Double) -> Unit, onRemove: () -> Unit) {
    var text by remember(ing.barcode) { mutableStateOf(if (ing.grams > 0) ing.grams.roundToInt().toString() else "") }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(ing.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            Text(
                buildString {
                    ing.brand?.let { append(it).append("  ·  ") }
                    append("${ing.kcal.roundToInt()} kcal")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it.filter { c -> c.isDigit() || c == '.' }
                onGrams(text.toDoubleOrNull() ?: 0.0)
            },
            label = { Text("g") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(96.dp),
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = "Remove")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BarcodeEntryDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add by barcode") },
        text = {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter { c -> c.isDigit() } },
                label = { Text("Barcode digits") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(onClick = { if (code.isNotBlank()) onAdd(code) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
