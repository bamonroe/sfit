package net.bam.sfit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

// Shared UI helpers + small composables, so the screens don't each carry their
// own copy. See CLAUDE.md §7 (UI conventions).

/** App-wide display formatter: integer if whole, else one decimal. */
fun fmtNum(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else "%.1f".format(d)

/** Integer if whole, else full precision — for prefilling editable number fields
 *  (where rounding to one decimal would silently lose the stored value). */
fun fullNum(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

/** Numeric input filter for gram fields that may be negative (a subtracted
 *  ingredient, e.g. whey poured off). Keeps one optional leading '-', digits and
 *  dots; strips everything else. The minus is entered via [SignToggle], not the
 *  soft keyboard (which often hides it). */
fun signedGramFilter(s: String): String {
    val body = s.filter { it.isDigit() || it == '.' }
    return if (s.startsWith("-")) "-$body" else body
}

/** Flip the sign of a gram string for the ± toggle. */
fun flipGramSign(s: String): String = when {
    s.startsWith("-") -> s.drop(1)
    s.isBlank() -> "-"
    else -> "-$s"
}

/** Compact +/− toggle that marks an ingredient as subtracted (negative grams). */
@Composable
fun SignToggle(negative: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Text(
            if (negative) "−" else "+",
            style = MaterialTheme.typography.titleLarge,
            color = if (negative) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One macro stat: bold grams over a small label. */
@Composable
fun MacroCell(label: String, grams: Double, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${fmtNum(grams)}g", style = MaterialTheme.typography.titleMedium)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Protein / Carbs / Fat, three-up. Pass padding via [modifier]. */
@Composable
fun MacroRow(protein: Double, carbs: Double, fat: Double, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MacroCell("Protein", protein, Modifier.weight(1f))
        MacroCell("Carbs", carbs, Modifier.weight(1f))
        MacroCell("Fat", fat, Modifier.weight(1f))
    }
}

/** Single-line labelled text field. */
@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

/** Numeric field: digits + a single decimal point, number keyboard. */
@Composable
fun NumField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '.' }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** Search box with a leading magnifier and a clear (✕) action when non-empty. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                }
            }
        },
        singleLine = true,
        modifier = modifier,
    )
}
