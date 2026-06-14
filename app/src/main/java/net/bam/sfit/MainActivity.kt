package net.bam.sfit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import net.bam.sfit.data.AppRepository
import net.bam.sfit.data.BarcodeFood
import net.bam.sfit.data.DraftStore
import net.bam.sfit.data.LibraryMeal
import net.bam.sfit.data.Repo
import net.bam.sfit.ui.BulkAddViewModel
import net.bam.sfit.ui.EditFoodScreen
import net.bam.sfit.ui.EditMealScreen
import net.bam.sfit.ui.HistoryScreen
import net.bam.sfit.ui.HistoryViewModel
import net.bam.sfit.ui.LibraryScreen
import net.bam.sfit.ui.LibraryViewModel
import net.bam.sfit.ui.LogFoodScreen
import net.bam.sfit.ui.LogWeightDialog
import net.bam.sfit.ui.MainScreen
import net.bam.sfit.ui.MainViewModel
import net.bam.sfit.ui.MealScreen
import net.bam.sfit.ui.MealViewModel
import net.bam.sfit.ui.ProviderSearchScreen
import net.bam.sfit.ui.ProviderSearchViewModel
import net.bam.sfit.ui.ScannerScreen
import net.bam.sfit.ui.SettingsScreen
import net.bam.sfit.ui.theme.SFitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = Repo.get(applicationContext)
        val draftStore = DraftStore(applicationContext)
        setContent {
            SFitTheme { AppRoot(repo, draftStore) }
        }
    }
}

private enum class Screen { Main, Settings, Meal, Scanner, BulkAdd, EditFood, EditMeal, ProviderSearch, LogFood }

@Composable
private fun AppRoot(
    repo: AppRepository,
    draftStore: DraftStore,
) {
    val store = repo.store
    var screen by remember { mutableStateOf(Screen.Main) }
    var editFood by remember { mutableStateOf<BarcodeFood?>(null) }
    var editMeal by remember { mutableStateOf<LibraryMeal?>(null) }

    val factory = remember(repo, draftStore) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(MainViewModel::class.java) -> MainViewModel(repo)
                modelClass.isAssignableFrom(HistoryViewModel::class.java) -> HistoryViewModel(repo)
                modelClass.isAssignableFrom(MealViewModel::class.java) -> MealViewModel(store, draftStore)
                modelClass.isAssignableFrom(LibraryViewModel::class.java) -> LibraryViewModel(repo)
                modelClass.isAssignableFrom(BulkAddViewModel::class.java) -> BulkAddViewModel(store)
                modelClass.isAssignableFrom(ProviderSearchViewModel::class.java) -> ProviderSearchViewModel(repo)
                else -> throw IllegalArgumentException("Unknown ViewModel $modelClass")
            } as T
        }
    }
    val vm: MainViewModel = viewModel(factory = factory)
    val historyVm: HistoryViewModel = viewModel(factory = factory)
    val mealVm: MealViewModel = viewModel(factory = factory)
    val libraryVm: LibraryViewModel = viewModel(factory = factory)
    val bulkVm: BulkAddViewModel = viewModel(factory = factory)
    val providerVm: ProviderSearchViewModel = viewModel(factory = factory)

    // System back mirrors the on-screen back arrows for the pushed screens.
    BackHandler(enabled = screen != Screen.Main) {
        when (screen) {
            Screen.Scanner -> screen = Screen.Meal
            Screen.BulkAdd -> { libraryVm.load(); screen = Screen.Main }
            Screen.EditFood -> { editFood = null; libraryVm.load(); screen = Screen.Main }
            Screen.EditMeal -> { editMeal = null; libraryVm.load(); screen = Screen.Main }
            Screen.ProviderSearch, Screen.LogFood -> screen = Screen.Main
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
            onProviderSearch = { screen = Screen.ProviderSearch },
            onLogFood = { screen = Screen.LogFood },
            onEditFood = { food -> editFood = food; screen = Screen.EditFood },
            onEditMeal = { meal -> editMeal = meal; screen = Screen.EditMeal },
            onLogged = vm::refresh,
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
        Screen.ProviderSearch -> ProviderSearchScreen(
            providerVm,
            onBack = { screen = Screen.Main },
        )
        Screen.LogFood -> LogFoodScreen(
            libraryVm,
            onBack = { screen = Screen.Main },
        )
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
    onProviderSearch: () -> Unit,
    onLogFood: () -> Unit,
    onEditFood: (BarcodeFood) -> Unit,
    onEditMeal: (LibraryMeal) -> Unit,
    onLogged: () -> Unit,
) {
    // Page 0 = Library (swipe right), 1 = Today (start), 2 = History (swipe left).
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var showWeight by remember { mutableStateOf(false) }
    val historyState by historyVm.state.collectAsStateWithLifecycle()
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
                    onEditFood = onEditFood,
                    onEditMeal = onEditMeal,
                    onLogged = onLogged,
                )
                1 -> MainScreen(mainVm, onOpenSettings)
                else -> HistoryScreen(historyVm)
            }
        }
        PageDots(
            count = 3,
            selected = pagerState.currentPage,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 6.dp),
        )
        // One add-everything button, the same on every page.
        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
        ) { Icon(Icons.Default.Add, contentDescription = "Add") }
    }

    if (showAdd) {
        AddSheet(
            onDismiss = { showAdd = false },
            onLogFood = { showAdd = false; onLogFood() },
            onLogWeight = { showAdd = false; showWeight = true },
            onNewMeal = { showAdd = false; onOpenMeal() },
            onScan = { showAdd = false; onBulkAdd() },
            onSearch = { showAdd = false; onProviderSearch() },
        )
    }
    if (showWeight) {
        LogWeightDialog(
            unit = historyState.unit,
            initial = historyState.rows.firstOrNull { it.weight != null }?.weight,
            onConfirm = { historyVm.logWeight(it); showWeight = false },
            onDismiss = { showWeight = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(
    onDismiss: () -> Unit,
    onLogFood: () -> Unit,
    onLogWeight: () -> Unit,
    onNewMeal: () -> Unit,
    onScan: () -> Unit,
    onSearch: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            AddItem(Icons.Default.RestaurantMenu, "Log food", "Add a food to today's diary", onLogFood)
            AddItem(Icons.Default.MonitorWeight, "Log weight", "Record a weigh-in", onLogWeight)
            AddItem(Icons.Default.Restaurant, "New meal", "Build a recipe", onNewMeal)
            AddItem(Icons.Default.QrCodeScanner, "Scan barcode", "Add a food by barcode", onScan)
            AddItem(Icons.Default.TravelExplore, "Search foods", "Find a food on Open Food Facts", onSearch)
        }
    }
}

@Composable
private fun AddItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
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
