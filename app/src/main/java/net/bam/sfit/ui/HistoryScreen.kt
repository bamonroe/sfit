package net.bam.sfit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.bam.sfit.data.Granularity
import net.bam.sfit.data.HistoryRow
import kotlin.math.roundToInt

private val lossGreen = Color(0xFF2ECC71)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: HistoryViewModel, onBack: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showLogWeight by remember { mutableStateOf(false) }
    // Reset expansion when the granularity changes.
    var expanded by remember(state.granularity) { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<HistoryRow?>(null) }

    if (showLogWeight) {
        LogWeightDialog(
            unit = state.unit,
            initial = state.rows.firstOrNull { it.weight != null }?.weight,
            onConfirm = { vm.logWeight(it); showLogWeight = false },
            onDismiss = { showLogWeight = false },
        )
    }

    editing?.let { row ->
        LogWeightDialog(
            unit = state.unit,
            initial = row.weight,
            onConfirm = { vm.logWeight(it, row.date); editing = null },
            onDismiss = { editing = null },
            title = "Edit weight",
            subtitle = row.label,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showLogWeight = true }) {
                        Icon(Icons.Default.MonitorWeight, contentDescription = "Log weight")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            GranularityToggle(state.granularity, onSelect = vm::selectGranularity)
            TableHeader(state.granularity, state.unit)
            HorizontalDivider()

            PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = vm::load,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.error != null -> Text(
                        state.error!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp),
                    )
                    state.rows.isEmpty() && !state.loading -> Text(
                        "No weigh-ins in this range.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.rows, key = { it.label }) { row ->
                            HistoryRowItem(
                                row = row,
                                expanded = expanded == row.label,
                                unit = state.unit,
                                onToggle = { expanded = if (expanded == row.label) null else row.label },
                                onEdit = { editing = row },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GranularityToggle(selected: Granularity, onSelect: (Granularity) -> Unit) {
    val options = Granularity.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        options.forEachIndexed { i, g ->
            SegmentedButton(
                selected = selected == g,
                onClick = { onSelect(g) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(g.name) }
        }
    }
}

@Composable
private fun TableHeader(g: Granularity, unit: String) {
    val first = when (g) {
        Granularity.Daily -> "Date"
        Granularity.Weekly -> "Week"
        Granularity.Monthly -> "Month"
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        HeaderCell(first, 1.5f, TextAlign.Start)
        HeaderCell("Weight ($unit)", 1.1f, TextAlign.End)
        HeaderCell("Change", 0.9f, TextAlign.End)
        HeaderCell("Deficit", 1.0f, TextAlign.End)
    }
}

@Composable
private fun HistoryRowItem(
    row: HistoryRow,
    expanded: Boolean,
    unit: String,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BodyCell(row.label, 1.5f, TextAlign.Start)
            BodyCell(row.weight?.let { "%.1f".format(it) } ?: "—", 1.1f, TextAlign.End)
            DeltaCell(row.weightDelta, 0.9f)
            DeficitCell(row.deficit, 1.0f)
        }
        if (expanded) HistoryRowDetail(row, unit, onEdit)
    }
}

@Composable
private fun HistoryRowDetail(row: HistoryRow, unit: String, onEdit: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        row.weight?.let { w ->
            val text = if (unit.startsWith("lb")) {
                "%.1f lb  (%.1f kg)".format(w, w / 2.2046226218)
            } else {
                "%.1f kg  (%.1f lb)".format(w, w * 2.2046226218)
            }
            DetailLine("Weight", text)
        }
        row.weightDelta?.let { DetailLine("Change", "%+.1f %s vs previous".format(it, unit)) }
        row.deficit?.let { DetailLine("Est. deficit", "${it.roundToInt()} kcal/day") }

        if (row.date != null) {
            FilledTonalButton(onClick = onEdit, modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Edit weight")
            }
        } else {
            Text(
                "Switch to Daily to edit a specific weigh-in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LogWeightDialog(
    unit: String,
    initial: Double?,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Log weight",
    subtitle: String = "Today's weigh-in.",
) {
    var text by remember { mutableStateOf(initial?.let { "%.1f".format(it) } ?: "") }
    val value = text.trim().toDoubleOrNull()
    val valid = value != null && value > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Weight ($unit)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { value?.let(onConfirm) }, enabled = valid) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float, align: TextAlign) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        textAlign = align,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RowScope.BodyCell(text: String, weight: Float, align: TextAlign) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        textAlign = align,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun RowScope.DeltaCell(delta: Double?, weight: Float) {
    if (delta == null) {
        BodyCell("—", weight, TextAlign.End)
        return
    }
    // Weight loss is the goal here: down = green, up = red.
    val color = when {
        delta < 0 -> lossGreen
        delta > 0 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = "%+.1f".format(delta),
        modifier = Modifier.weight(weight),
        textAlign = TextAlign.End,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        color = color,
    )
}

@Composable
private fun RowScope.DeficitCell(deficit: Double?, weight: Float) {
    if (deficit == null) {
        BodyCell("—", weight, TextAlign.End)
        return
    }
    val v = deficit.roundToInt()
    val color = when {
        v > 0 -> lossGreen           // deficit (good)
        v < 0 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = if (v > 0) "+$v" else "$v",
        modifier = Modifier.weight(weight),
        textAlign = TextAlign.End,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        color = color,
    )
}
