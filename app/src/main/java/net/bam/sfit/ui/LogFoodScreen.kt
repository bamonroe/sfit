package net.bam.sfit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Search the library and log a food to today's diary. Reuses LibraryViewModel
 *  (its food list/search/detail) and the shared LogFoodDialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogFoodScreen(vm: LibraryViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Start with a clean search; clear any open detail on the way out.
    LaunchedEffect(Unit) { vm.setQuery("") }
    DisposableEffect(Unit) { onDispose { vm.closeDetail() } }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Log food") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SearchField(
                value = state.query,
                onValueChange = vm::setQuery,
                placeholder = "Search your foods",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
            HorizontalDivider()

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
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

    // Once a tapped food's detail loads, go straight to the log dialog.
    state.detail?.let { food ->
        LogFoodDialog(
            food = food,
            onConfirm = { grams, meal -> vm.logFood(food, grams, meal) { onBack() } },
            onDismiss = vm::closeDetail,
        )
    }
}
