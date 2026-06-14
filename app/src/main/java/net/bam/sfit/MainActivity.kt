package net.bam.sfit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.bam.sfit.data.BarcodeFood
import net.bam.sfit.data.DraftStore
import net.bam.sfit.data.LibraryMeal
import net.bam.sfit.data.SettingsStore
import net.bam.sfit.ui.BulkAddViewModel
import net.bam.sfit.ui.EditFoodScreen
import net.bam.sfit.ui.EditMealScreen
import net.bam.sfit.ui.HistoryScreen
import net.bam.sfit.ui.HistoryViewModel
import net.bam.sfit.ui.LibraryScreen
import net.bam.sfit.ui.LibraryViewModel
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

private enum class Screen { Main, Settings, Meal, Scanner, BulkAdd, EditFood, EditMeal }

@Composable
private fun AppRoot(store: SettingsStore, draftStore: DraftStore) {
    var screen by remember { mutableStateOf(Screen.Main) }
    var editFood by remember { mutableStateOf<BarcodeFood?>(null) }
    var editMeal by remember { mutableStateOf<LibraryMeal?>(null) }

    val factory = remember(store, draftStore) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(MainViewModel::class.java) -> MainViewModel(store)
                modelClass.isAssignableFrom(HistoryViewModel::class.java) -> HistoryViewModel(store)
                modelClass.isAssignableFrom(MealViewModel::class.java) -> MealViewModel(store, draftStore)
                modelClass.isAssignableFrom(LibraryViewModel::class.java) -> LibraryViewModel(store)
                modelClass.isAssignableFrom(BulkAddViewModel::class.java) -> BulkAddViewModel(store)
                else -> throw IllegalArgumentException("Unknown ViewModel $modelClass")
            } as T
        }
    }
    val vm: MainViewModel = viewModel(factory = factory)
    val historyVm: HistoryViewModel = viewModel(factory = factory)
    val mealVm: MealViewModel = viewModel(factory = factory)
    val libraryVm: LibraryViewModel = viewModel(factory = factory)
    val bulkVm: BulkAddViewModel = viewModel(factory = factory)

    // System back mirrors the on-screen back arrows for the pushed screens.
    BackHandler(enabled = screen != Screen.Main) {
        when (screen) {
            Screen.Scanner -> screen = Screen.Meal
            Screen.BulkAdd -> { libraryVm.load(); screen = Screen.Main }
            Screen.EditFood -> { editFood = null; libraryVm.load(); screen = Screen.Main }
            Screen.EditMeal -> { editMeal = null; libraryVm.load(); screen = Screen.Main }
            else -> screen = Screen.Main // Settings, Meal
        }
    }

    when (screen) {
        Screen.Main -> HomePager(
            mainVm = vm,
            libraryVm = libraryVm,
            mealVm = mealVm,
            historyVm = historyVm,
            onOpenSettings = { screen = Screen.Settings },
            onOpenMeal = { screen = Screen.Meal },
            onBulkAdd = { bulkVm.reset(); screen = Screen.BulkAdd },
            onEditFood = { food -> editFood = food; screen = Screen.EditFood },
            onEditMeal = { meal -> editMeal = meal; screen = Screen.EditMeal },
        )
        Screen.Settings -> SettingsScreen(store, onDone = { screen = Screen.Main })
        Screen.Meal -> MealScreen(
            mealVm,
            onBack = { screen = Screen.Main },
            onScan = { screen = Screen.Scanner },
        )
        Screen.Scanner -> {
            val s by mealVm.state.collectAsStateWithLifecycle()
            ScannerScreen(
                onBarcode = mealVm::addByBarcode,
                onDone = { screen = Screen.Meal },
                title = "Scan into meal",
                statusText = s.error ?: s.message
                    ?: if (s.resolving) "Looking up…" else "Point at a barcode",
                countText = "${s.draft.ingredients.size} in meal",
            )
        }
        Screen.BulkAdd -> {
            val s by bulkVm.state.collectAsStateWithLifecycle()
            ScannerScreen(
                onBarcode = bulkVm::addByBarcode,
                onDone = { libraryVm.load(); screen = Screen.Main },
                title = "Bulk add foods",
                statusText = s.error ?: s.message
                    ?: if (s.resolving) "Looking up…" else "Point at a barcode",
                countText = "${s.count} added",
            )
        }
        Screen.EditFood -> {
            val food = editFood
            if (food == null) {
                screen = Screen.Main
            } else {
                EditFoodScreen(
                    food = food,
                    store = store,
                    onDone = { editFood = null; libraryVm.load(); screen = Screen.Main },
                )
            }
        }
        Screen.EditMeal -> {
            val meal = editMeal
            if (meal == null) {
                screen = Screen.Main
            } else {
                EditMealScreen(
                    meal = meal,
                    store = store,
                    onDone = { editMeal = null; libraryVm.load(); screen = Screen.Main },
                )
            }
        }
    }
}

/** Library ⟵ Today ⟶ History swipe pager (Today centred). */
@Composable
private fun HomePager(
    mainVm: MainViewModel,
    libraryVm: LibraryViewModel,
    mealVm: MealViewModel,
    historyVm: HistoryViewModel,
    onOpenSettings: () -> Unit,
    onOpenMeal: () -> Unit,
    onBulkAdd: () -> Unit,
    onEditFood: (BarcodeFood) -> Unit,
    onEditMeal: (LibraryMeal) -> Unit,
) {
    // Page 0 = Library (swipe right), 1 = Today (start), 2 = History (swipe left).
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    val scope = rememberCoroutineScope()
    // System back from a side page returns to Today (rather than exiting).
    BackHandler(enabled = pagerState.currentPage != 1) {
        scope.launch { pagerState.animateScrollToPage(1) }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> LibraryScreen(
                    libraryVm,
                    mealVm = mealVm,
                    onBulkAdd = onBulkAdd,
                    onEditFood = onEditFood,
                    onEditMeal = onEditMeal,
                )
                1 -> MainScreen(mainVm, onOpenSettings, onOpenMeal)
                else -> HistoryScreen(historyVm)
            }
        }
        PageDots(
            count = 3,
            selected = pagerState.currentPage,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 6.dp),
        )
    }
}

@Composable
private fun PageDots(count: Int, selected: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { i ->
            val on = i == selected
            Box(
                modifier = Modifier
                    .size(if (on) 9.dp else 7.dp)
                    .clip(CircleShape)
                    .background(
                        if (on) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    ),
            )
        }
    }
}
