package net.bam.sfit.ui

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val rowDateFmt = DateTimeFormatter.ofPattern("EEE, MMM d")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: HistoryViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            RangeToggle(state.preset, onSelect = vm::selectPreset)

            if (state.preset == RangePreset.Custom) {
                CustomRangeRow(state, onPick = vm::setCustomRange)
            }

            TableHeader()
            HorizontalDivider()

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
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
                        items(state.rows, key = { it.date }) { HistoryRowItem(it) }
                    }
                }
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 32.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeToggle(selected: RangePreset, onSelect: (RangePreset) -> Unit) {
    val options = RangePreset.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        options.forEachIndexed { i, preset ->
            SegmentedButton(
                selected = selected == preset,
                onClick = { onSelect(preset) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(preset.name) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangeRow(state: HistoryState, onPick: (LocalDate, LocalDate) -> Unit) {
    var picking by remember { mutableStateOf<String?>(null) } // "start" | "end" | null

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = { picking = "start" }, modifier = Modifier.weight(1f)) {
            Text(state.start.format(rowDateFmt))
        }
        OutlinedButton(onClick = { picking = "end" }, modifier = Modifier.weight(1f)) {
            Text(state.end.format(rowDateFmt))
        }
    }

    picking?.let { which ->
        val initial = if (which == "start") state.start else state.end
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    val millis = pickerState.selectedDateMillis
                    if (millis != null) {
                        val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        if (which == "start") onPick(picked, state.end) else onPick(state.start, picked)
                    }
                    picking = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        ) { DatePicker(state = pickerState) }
    }
}

@Composable
private fun TableHeader() {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        HeaderCell("Date", 1.4f, TextAlign.Start)
        HeaderCell("Weight", 1f, TextAlign.End)
        HeaderCell("Deficit", 1f, TextAlign.End)
    }
}

@Composable
private fun HistoryRowItem(row: HistoryRow) {
    val date = runCatching { LocalDate.parse(row.date).format(rowDateFmt) }.getOrDefault(row.date)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BodyCell(date, 1.4f, TextAlign.Start)
        BodyCell(row.weight?.let { "%.1f".format(it) } ?: "—", 1f, TextAlign.End)
        DeficitCell(row.deficit, 1f)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(
    text: String,
    weight: Float,
    align: TextAlign,
) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        textAlign = align,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BodyCell(
    text: String,
    weight: Float,
    align: TextAlign,
) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        textAlign = align,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.DeficitCell(deficit: Double?, weight: Float) {
    if (deficit == null) {
        BodyCell("—", weight, TextAlign.End)
        return
    }
    val v = deficit.roundToInt()
    val color = when {
        v > 0 -> Color(0xFF2ECC71)                // deficit (good)
        v < 0 -> MaterialTheme.colorScheme.error   // surplus
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
