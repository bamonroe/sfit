package net.bam.sfit.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.bam.sfit.data.BarcodeFood

/** Search the library or an online provider and add a food to the in-progress
 *  meal. Provider results are imported into the library first (see the shared
 *  [FoodSourceSearch]); when a tapped food's detail loads it's added via [onPick]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickFoodScreen(vm: LibraryViewModel, onPick: (BarcodeFood) -> Unit, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.openPicker() }
    DisposableEffect(Unit) { onDispose { vm.closeDetail() } }

    // When a tapped/imported food's detail loads, add it to the meal and clear.
    LaunchedEffect(state.detail) {
        state.detail?.let { food ->
            onPick(food)
            vm.closeDetail()
            snackbar.showSnackbar("Added ${food.name} to meal")
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Add food") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        FoodSourceSearch(state, vm, modifier = Modifier.fillMaxSize().padding(padding))
    }
}
