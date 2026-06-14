package net.bam.sfit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import net.bam.sfit.data.DraftStore
import net.bam.sfit.data.SettingsStore
import net.bam.sfit.ui.HistoryScreen
import net.bam.sfit.ui.HistoryViewModel
import net.bam.sfit.ui.MainScreen
import net.bam.sfit.ui.MainViewModel
import net.bam.sfit.ui.MealScreen
import net.bam.sfit.ui.MealViewModel
import net.bam.sfit.ui.ScannerScreen
import net.bam.sfit.ui.SettingsScreen
import net.bam.sfit.ui.theme.SFitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = SettingsStore(applicationContext)
        val draftStore = DraftStore(applicationContext)
        setContent {
            SFitTheme { AppRoot(store, draftStore) }
        }
    }
}

private enum class Screen { Main, Settings, History, Meal, Scanner }

@Composable
private fun AppRoot(store: SettingsStore, draftStore: DraftStore) {
    var screen by remember { mutableStateOf(Screen.Main) }

    val factory = remember(store, draftStore) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(MainViewModel::class.java) -> MainViewModel(store)
                modelClass.isAssignableFrom(HistoryViewModel::class.java) -> HistoryViewModel(store)
                modelClass.isAssignableFrom(MealViewModel::class.java) -> MealViewModel(store, draftStore)
                else -> throw IllegalArgumentException("Unknown ViewModel $modelClass")
            } as T
        }
    }
    val vm: MainViewModel = viewModel(factory = factory)
    val historyVm: HistoryViewModel = viewModel(factory = factory)
    val mealVm: MealViewModel = viewModel(factory = factory)

    when (screen) {
        Screen.Main -> MainScreen(
            vm,
            onOpenSettings = { screen = Screen.Settings },
            onOpenHistory = { historyVm.load(); screen = Screen.History },
            onOpenMeal = { screen = Screen.Meal },
        )
        Screen.Settings -> SettingsScreen(store, onDone = { screen = Screen.Main })
        Screen.History -> HistoryScreen(historyVm, onBack = { screen = Screen.Main })
        Screen.Meal -> MealScreen(
            mealVm,
            onBack = { screen = Screen.Main },
            onScan = { screen = Screen.Scanner },
        )
        Screen.Scanner -> ScannerScreen(mealVm, onDone = { screen = Screen.Meal })
    }
}
