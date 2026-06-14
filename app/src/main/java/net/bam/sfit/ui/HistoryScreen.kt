package net.bam.sfit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

private val lossGreen = Color(0xFF2ECC71)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: HistoryViewModel, onBack: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()

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
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            GranularityToggle(state.granularity, onSelect = vm::selectGranularity)
            TableHeader(state.granularity, state.unit)
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
                        items(state.rows, key = { it.label }) { HistoryRowItem(it) }
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
private fun HistoryRowItem(row: HistoryRow) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BodyCell(row.label, 1.5f, TextAlign.Start)
        BodyCell(row.weight?.let { "%.1f".format(it) } ?: "—", 1.1f, TextAlign.End)
        DeltaCell(row.weightDelta, 0.9f)
        DeficitCell(row.deficit, 1.0f)
    }
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
