# SFit — developer guide for Claude Code

A custom native Android client for a **self-hosted [SparkyFitness](https://github.com/CodeWithCJ/SparkyFitness)**
instance. Kotlin + Jetpack Compose (Material 3, Material You dynamic colour). Built
primarily for a Pixel 8a; also runs on a Galaxy Tab (SM-P620). This file is the
orientation doc for future sessions — read it before diving in.

`README.md` is the short human-facing summary; this file is the deep map.

---

## 1. Build, install, deploy

**JDK quirk (important):** the system JDK is too new for this Gradle version.
Builds use a local **JDK 21 pinned in `~/.gradle/gradle.properties`**
(`org.gradle.java.home=…`). That property lives outside the repo (machine-specific,
git-ignored). If a build fails with a Gradle/JVM incompatibility, that pin is why —
don't "fix" it by bumping Gradle.

```sh
./gradlew :app:assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
```
Every `assembleDebug` also copies the APK to the repo root as **`sfit-debug.apk`**
(a `finalizedBy` task in `app/build.gradle.kts`) for easy sideloading; it's git-ignored
via the `*.apk` rule.

- Android SDK at `~/Android/Sdk` (see `local.properties`, git-ignored). `adb` lives in
  `~/Android/Sdk/platform-tools`.
- Toolchain: AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.10.01, `compileSdk`/`targetSdk` 35,
  `minSdk` 26. Java/Kotlin target 17. Release is **not** minified.

**Install to a device:**
```sh
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```
Known devices (serials drift; re-check with `adb devices -l`):
- **Tablet** — Galaxy Tab SM-P620, USB, serial `R52Y50CAJZB`. Used for testing.
- **Phone** — Pixel 8a (`akita`), the daily driver. Usually reached over **wireless
  debugging** on the tailnet, not USB.

**Phone over wireless debugging (no classic tcpip:5555):** the phone uses modern
Android pairing. You can't `adb connect …:5555` (refused). Flow: ask the user to enable
*Developer options → Wireless debugging → Pair device with pairing code*, then
`adb pair <ip:pairPort> <code>` and `adb connect <ip:connectPort>`. Once paired it
appears in `adb devices` as `adb-<serial>-…._adb-tls-connect._tcp`. `adb mdns services`
lists the connect endpoint. Tailnet name is `pixel8a` (`100.64.0.3`); LAN IP varies.

**Driving the app for UI verification** (see `verify`/`run` skills): launch with
`adb -s <serial> shell monkey -p net.bam.sfit -c android.intent.category.LAUNCHER 1`,
screenshot with `adb exec-out screencap -p > out.png`, and get exact tap coordinates
from `adb exec-out uiautomator dump /dev/tty` (don't guess from scaled screenshots —
bounds are in real device px). Tablet screen is 1200×2000.

---

## 2. Connectivity & server

- Base URL + API key are entered in **Settings**, persisted via DataStore
  (`SettingsStore`). No default URL.
- Use the **tailnet** address, e.g. `http://fit.bam/api`. The manifest sets
  `usesCleartextTraffic="true"` precisely so plain-HTTP tailnet URLs work. The public
  URL bot-blocks scripted calls — prefer the tailnet.
- Auth is `Authorization: Bearer <apiKey>` on every request.
- `SparkyApi` normalizes the base URL: accepts `http://fit.bam` (auto-appends `/api`)
  or an already-suffixed `…/api`, and trims slashes.

**Server-side constraints worth knowing** (from the SparkyFitness source, not this repo):
- `GET /foods/foods-paginated` returns only `id, name, brand, barcode, is_custom,
  user_id, shared_with_public, provider_*` + the default variant. It does **not**
  return `created_at`, and its server `sortBy` only supports `name` + nutrition fields.
- `foods.id` is a random **UUIDv4** (`gen_random_uuid()`) — *not* time-ordered, so it
  can't proxy "date added." The `created_at` column exists in the DB but isn't exposed
  by the list endpoint. (This is why the Library sort offers A–Z / frequency / last-log
  but **not** "date added" — the data simply isn't available client-side.)

---

## 3. Architecture at a glance

```
MainActivity ── AppRoot ── Screen state-machine (enum) ─┬─ HomePager: Library ⟵ Today ⟶ History
                                                        └─ pushed screens (Meal, Scanner, Edit*, …)
        │
   ViewModels (one StateFlow each, via combine) ── observe ──┐
        │                                                    │
   AppRepository  ── StateFlows: day / history / library / refreshing / configured / error
        │  (process singleton via Repo.get)
        ├─ SparkyApi (OkHttp + kotlinx.serialization)  → the SparkyFitness REST API
        ├─ *CacheStore (DataStore) → instant cold-start render
        └─ SettingsStore / DraftStore / ContainerStore (DataStore)
```

Layers:
- **`net.bam.sfit` (root):** `MainActivity` — single activity, all navigation.
- **`net.bam.sfit.ui`:** Compose screens + their ViewModels.
- **`net.bam.sfit.data`:** repository, API client, DataStore caches, domain types.
- **`net.bam.sfit.ui.theme`:** `SFitTheme` (Material You).

### Navigation (`MainActivity.kt`)
- One activity. `AppRoot` holds `var screen by remember { Screen.Main }`, a `when(screen)`
  renders the current destination. `Screen` enum: `Main, Settings, Meal, Scanner, BulkAdd,
  EditFood, EditMeal, ProviderSearch, LogFood, CustomFood, PickFood` (`MainActivity.kt:93`).
- **No NavController/router.** Navigation is plain state mutation; screens receive
  `onX` lambda callbacks (`onEditFood`, `onLogged`, `onBack`, …) to request a transition.
- `HomePager` (`MainActivity.kt:240`) is a 3-page `HorizontalPager`, **Today centred**
  (page 1; page 0 = Library, page 2 = History). One global FAB ("add everything" sheet)
  and `PageDots` overlay every page.
- `BackHandler` (`MainActivity.kt:128`) mirrors on-screen back arrows; from a side pager
  page it returns to Today, from a pushed screen it pops to Main (clearing `editFood`/
  `editMeal` and calling `libraryVm.load()` where edits may have changed data).
- ViewModels are created once via a custom `ViewModelProvider.Factory`
  (`MainActivity.kt:106`) that injects `repo`/stores. `MealViewModel` gets
  `store + draftStore + containerStore`; most others get `repo`.

### ViewModel pattern
- Each VM exposes a **single** `StateFlow<XState>` built with `combine(repo flows, local
  ui flow) { … }.stateIn(viewModelScope, WhileSubscribed(5_000), Default)`.
- Shared/server data comes from `AppRepository` flows; screen-local UI bits (sort mode,
  search query, which sheet is open, transient messages) live in a private
  `MutableStateFlow<…Ui>` inside the VM.
- Screens collect with `collectAsStateWithLifecycle()`.
- VMs never show UI directly. Errors/toasts are carried as `message`/`error` in state and
  surfaced by the Composable (snackbar pattern, §6).

---

## 4. Data layer (`data/`)

### `AppRepository` — single source of truth
- **One `refresh()` fetches everything** the app needs (today, 11 months of history,
  full library, per-food usage, today's logged meals) **in parallel** inside one
  `coroutineScope { async {…} }`, then updates all flows and persists all caches
  (`AppRepository.kt:133`). Concurrent pulls are coalesced (`refreshJob?.isActive`).
- Pull-to-refresh **anywhere** calls the same `refresh()`.
- On init it renders cached data immediately (day cache only if its date == today) and
  restores the weight unit from the history cache (so an offline weigh-in still converts
  correctly), then refreshes when settings become configured / change (`AppRepository.kt:100`).
  The app is fully openable offline: each ViewModel suppresses `repo.error` when its cache
  is non-empty, so all three screens render from cache without internet.
- **`Repo.get(context)`** is a process-wide singleton (double-checked locking) so data
  survives Activity recreation (`AppRepository.kt:454`).
- **Optimistic updates** for diary edits/deletes: mutate `_day` locally at once, fire the
  server call in the background, then `refresh()` to reconcile (reverting to server truth
  on failure). See `updateEntry`, `deleteEntry`, `updateLoggedMeal`, `deleteLoggedMeal`.
- **Usage** (`count`, `lastDate` per food) is computed client-side from the last
  `USAGE_WINDOW_DAYS = 28` days of diary entries via **one** ranged call
  (`foodEntriesForDateRange` → `/food-entries/range/{start}/{end}`), not a per-day fan-out
  (`computeUsage`). This powers the Library's "by frequency" / "by last log" sorts — the
  28-day window means older-but-unlogged foods have count 0 / empty lastDate.
- **`refreshToday()`** is a light reconcile (today's `dailySummary` + `foodEntryMeals` only)
  used after diary edits/logs (`updateEntry`/`deleteEntry`/`updateLoggedMeal`/
  `deleteLoggedMeal`, and `logFood`/`logMeal`), instead of re-pulling the whole library +
  history + usage. Full `refresh()` is reserved for pull-to-refresh, weight logging, and
  library mutations (food/meal create/delete). Per-food usage therefore updates on the next
  full refresh, not on every single log.
- **Reuse-fetched data:** `LibraryFood` carries the `default_variant` nutrition that the
  foods response already returns, cached in `CachedFood.variant`. `openFood(food)` builds a
  `BarcodeFood` from it (`LibraryFood.toBarcodeFood()`) — so the detail/log/edit sheet opens
  instantly, works offline, and skips a per-tap `foodDetail()` call. (`foodDetail` is still
  used by EditMealScreen's add-ingredient path.)

### `SparkyApi` — REST client (OkHttp + kotlinx.serialization)
- JSON config: `ignoreUnknownKeys = true`, `coerceInputValues = true`,
  `encodeDefaults = true`, `explicitNulls = false`. Adding a field to a `@Serializable`
  data class safely picks it up **if** the server returns it (unknown keys are ignored).
- DTOs use snake_case via `@SerialName` (`food_entry_meal_id`, `entry_date`,
  `dietary_fiber`, …).
- Throws on non-2xx; if a response looks like HTML it suggests checking the Server URL
  (you hit an SPA/error page, not the API). Provider search retries 3× with 600 ms
  backoff (OpenFoodFacts intermittently 500s with HTML).
- Endpoint map: see §5.

### DataStore stores
- `SettingsStore` — `base_url`, `api_key`; `isConfigured = both non-blank`.
- Caches (`Caches.kt`, `LibraryCache.kt`) — `CachedDay` / `CachedHistory` / `CachedLibrary`,
  each a single JSON blob, for instant cold-start. `CachedFood` carries usage
  (`count`, `lastDate`). Day cache is only used when its `date == today`.
- `DraftStore` (`MealDraft.kt`) — one in-progress recipe (survives process death;
  `MealViewModel` autosaves). `ContainerStore` (`Containers.kt`) — local-only tare
  weights for weighing finished dishes; never synced to the server.

### Key domain logic (don't re-derive these wrong)
- **Nutrients are per `serving_size`, not per unit.** Consumed = `nutrient * quantity /
  servingSize` (`FoodEntry.consumedCalories`). Display unit = `servingUnit` or `unit`.
- **Meal collapsing on Today:** diary entries sharing a `food_entry_meal_id` are grouped
  into one row. `mealNames[femId]` is the label; **`mealGrams[femId]` is the logged dish
  total** (what the user sees/edits), which is *not* the sum of the scaled ingredient
  entries (that's the raw-weight portion). Keep these distinct.
- **Recipe net weight is authoritative:** a recipe's `servingSize × totalServings` is its
  tared net total; gram-logging scales by that, **not** by the ingredient sum (which can
  differ once you weigh the finished dish / account for container tare). Falls back to the
  ingredient sum only if the recipe has no recorded net.
- **Logging a meal**: POST sends the meal-template id + original ingredient quantities and
  the server scales by `grams / totalServings`; the PUT (edit logged meal) path has no
  template, so the client pre-scales ingredients by `newGrams / currentGrams`.
- **History:** weight = average per period; deficit = `(maintenance − eaten) / daysWithFood`
  averaged per period; maintenance = `BMR × activityMultiplier`. Granularities
  Daily/Weekly(ISO Monday)/Monthly are all precomputed from one fetch; only Daily rows
  carry a `date`+`checkInId` (editable/deletable). Newest first.

---

## 5. API endpoint reference (`SparkyApi.kt`)

Reads:
- `GET /daily-summary?date=` → today's goal + consumed + entries
- `GET /foods/foods-paginated?page=&itemsPerPage=` → library foods (see §2 caveat)
- `GET /meals?filter=mine` → recipes
- `GET /foods/food-entries/{date}` → diary entries for a day
- `GET /food-entries/range/{start}/{end}` → all diary entries in a range, one call
  (drives usage; note the `/food-entries` mount, distinct from the `/foods` one above)
- `GET /food-entry-meals/by-date/{date}` → logged-meal grouping for a day
- `GET /foods/{id}` → full food detail (BarcodeFood); `GET /foods/barcode/{barcode}`
- `GET /user-preferences` → weight unit, activity level
- `GET /measurements/check-in-measurements-range/{start}/{end}` → weigh-ins
- `GET /reports?startDate=&endDate=` → per-day calories (for deficit history)
- `GET /external-providers`, `GET /v2/foods/search/{providerType}?query=&providerId=`

Writes:
- `POST /food-entries` (log food), `PUT /food-entries/{id}`, `DELETE /food-entries/{id}`
- `POST /food-entry-meals` (log meal), `PUT …/{id}`, `DELETE …/{id}`
- `POST /foods` (create/import), `PUT /foods/{id}` (name+brand),
  `PUT /foods/food-variants/{id}` (nutrition), `DELETE /foods/{id}`
- `POST /meals`, `PUT /meals/{id}`, `DELETE /meals/{id}`
- `POST /measurements/check-in` (upsert weight), `DELETE /measurements/check-in/{id}`

---

## 6. Screens (`ui/`)

- **MainScreen / MainViewModel** — *Today*. Remaining-calorie ring + grouped diary.
  Tap an entry → bottom sheet to edit quantity / delete; tap a meal row → sheet to edit
  dish grams / delete / see ingredients.
- **LibraryScreen / LibraryViewModel** — searchable foods + meals. **Sort popup**
  (TopAppBar): A–Z / by frequency / by last log radios + a **Reverse** switch
  (`SortMode` enum + `reverse` flag in the VM; `sortFoods` builds the comparator for the
  natural direction then reverses the whole list when toggled). Tap food/meal → detail
  sheet → log / add-to-meal / edit / make-food / delete.
- **HistoryScreen / HistoryViewModel** — weight + energy table, Daily/Weekly/Monthly
  toggle; Daily rows expand to edit/delete a weigh-in. A **Tune-icon popup** (`EnergyMode`)
  swaps what the last column shows, all precomputed per row in `buildRows` from the energy
  identity `intake − maintenance ≈ Δweight·E/N` (`E = KCAL_PER_KG = 7700`, `N` = day-gap
  between weigh-in periods): **Actual** deficit (formula maintenance − logged intake),
  **Implied deficit** (scale alone, `−Δw·E/N`), **Implied maintenance** (real TDEE =
  intake + scale energy), **Implied calories** (real intake = maintenance − scale energy).
  Deficit modes are signed/green-coded; the level modes (maintenance/intake) are neutral.
  Expanding a row lists all four. Implied modes are noisy on Daily (single-day water swings),
  meaningful on Weekly/Monthly.
- **SettingsScreen** — base URL + API key (no VM; writes `SettingsStore` directly).
- **MealScreen / MealViewModel** — build a recipe: scan / pick-from-library / manual
  barcode ingredients, optional finished-dish weight with container tare; autosaves draft.
- **EditMealScreen** — edit a recipe's name / ingredient grams / net weight.
- **CustomFoodScreen** / **EditFoodScreen** — manual food create / edit-or-delete.
- **ScannerScreen** — reusable CameraX + ML Kit barcode scanner (used by Meal scan and
  Bulk-add); debounces repeat scans. **BulkAddViewModel** imports each scan into the library.
- **ProviderSearchScreen / ProviderSearchViewModel** — search any configured external
  provider and import to the library.
- **PickFoodScreen / LogFoodScreen** — reuse `LibraryViewModel` to pick an ingredient
  for a meal, or to log a food straight to the diary.

---

## 7. UI conventions

- **Theme:** `SFitTheme` uses Material You dynamic colour on Android 12+, falling back to
  default light/dark schemes. No custom typography/shapes. Always pull colours from
  `MaterialTheme.colorScheme.*`.
- **Section headers:** the `SectionHeader(label, count, suffix?)` composable
  (`LibraryScreen.kt`) — tinted surfaceVariant bar showing `"label · count · suffix"`.
- **Sheets vs dialogs:** rich detail/picker views use `ModalBottomSheet`; quick inputs and
  confirmations use `AlertDialog`. Multi-choice toggles (meal type, grams/calories,
  granularity) use `SingleChoiceSegmentedButtonRow`.
- **Snackbars:** `SnackbarHost` in the `Scaffold`; `LaunchedEffect(state.message) {
  snackbar.showSnackbar(it); vm.clearMessage() }`.
- **Pull-to-refresh:** every list screen wraps its `LazyColumn` in `PullToRefreshBox`
  whose `onRefresh` calls the VM's `load()`/`refresh()`.
- **Shared composables/helpers** live in `ui/UiComponents.kt` (same `net.bam.sfit.ui`
  package, so no import needed): `fmtNum(d)` (integer if whole, else one decimal — the
  app-wide display formatter), `fullNum(d)` (full precision, for prefilling editable number
  fields), `MacroCell`/`MacroRow` (protein/carbs/fat), `Field`/`NumField` (text/number
  form inputs), and `SearchField`. Reach for these before re-rolling a local copy.

---

## 8. Gotchas / conventions for edits

- Match surrounding style: this codebase favours tight, comment-explained domain logic
  (the *why*, e.g. net-weight vs ingredient-sum). Mirror that density.
- Kotlin enums use `.entries` (e.g. `SortMode.entries`, `Granularity.entries`).
- Changing what the app reads from the API? Remember the cache classes — a new field
  usually needs adding to both the live DTO and the corresponding `Cached*` shape, or it
  won't survive a cold start.
- Before claiming a server field exists, check the SparkyFitness source (`gh api
  repos/CodeWithCJ/SparkyFitness/contents/…`) — the list endpoints are selective (§2).
- After a feature: build, install on the **tablet**, verify by driving the UI
  (screenshots + `uiautomator dump`), then push to the **phone**.
