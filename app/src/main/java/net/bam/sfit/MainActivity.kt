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
import net.bam.sfit.data.SettingsStore
import net.bam.sfit.ui.MainScreen
import net.bam.sfit.ui.MainViewModel
import net.bam.sfit.ui.SettingsScreen
import net.bam.sfit.ui.theme.SFitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = SettingsStore(applicationContext)
        setContent {
            SFitTheme { AppRoot(store) }
        }
    }
}

private enum class Screen { Main, Settings }

@Composable
private fun AppRoot(store: SettingsStore) {
    var screen by remember { mutableStateOf(Screen.Main) }

    val vm: MainViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(store) as T
        },
    )

    when (screen) {
        Screen.Main -> MainScreen(vm, onOpenSettings = { screen = Screen.Settings })
        Screen.Settings -> SettingsScreen(store, onDone = { screen = Screen.Main })
    }
}
