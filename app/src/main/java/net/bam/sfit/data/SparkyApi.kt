package net.bam.sfit.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * One logged food in the diary. Nutrients are stored *per serving_size*, so the
 * amount actually eaten is `field * quantity / serving_size`.
 */
@Serializable
data class FoodEntry(
    val id: String = "",
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
    val quantity: Double = 0.0,
    val unit: String = "g",
    @SerialName("serving_size") val servingSize: Double = 0.0,
    @SerialName("serving_unit") val servingUnit: String = "",
    @SerialName("meal_type") val mealType: String? = null,
    @SerialName("food_name") val foodName: String? = null,
    @SerialName("brand_name") val brandName: String? = null,
    @SerialName("food_id") val foodId: String = "",
    @SerialName("variant_id") val variantId: String = "",
    @SerialName("food_entry_meal_id") val foodEntryMealId: String? = null,
    @SerialName("entry_date") val entryDate: String = "",
) {
    val consumedCalories: Double get() = if (servingSize > 0) calories * quantity / servingSize else 0.0

    /** The unit the quantity is actually in. SparkyFitness defaults [unit] to
     *  "g" even for foods served in other units (e.g. "fl oz"), while the real
     *  unit lives in [servingUnit] (which serving_size is measured in). */
    val displayUnit: String get() = servingUnit.ifBlank { unit }
}

@Serializable
data class Goals(
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
)

/** Server-computed energy balance for a day. */
@Serializable
data class CalorieBalance(
    val eaten: Double = 0.0,
    val burned: Double = 0.0,
    val goal: Double = 0.0,
    val net: Double = 0.0,
    val bmr: Double = 0.0,
    val remaining: Double = 0.0,
) {
    /** Net calorie deficit vs maintenance: positive = under maintenance. */
    val deficit: Double get() = bmr + burned - eaten
}

@Serializable
data class DailySummary(
    @SerialName("foodEntries") val foodEntries: List<FoodEntry> = emptyList(),
    val goals: Goals = Goals(),
    val calorieBalance: CalorieBalance = CalorieBalance(),
) {
    val consumedCalories: Double get() = foodEntries.sumOf { it.consumedCalories }
    val remainingCalories: Double get() = goals.calories - consumedCalories
}

/** A meal logged to the diary — groups several food entries under one name. */
@Serializable
data class FoodEntryMeal(
    val id: String = "",
    val name: String = "",
    @SerialName("meal_type") val mealType: String? = null,
)

/** A check-in measurement row (we only need id + date + weight). */
@Serializable
data class CheckIn(
    val id: String = "",
    @SerialName("entry_date") val date: String = "",
    val weight: Double? = null,
)

@Serializable
data class UserPreferences(
    @SerialName("activity_level") val activityLevel: String = "not_much",
    @SerialName("default_weight_unit") val weightUnit: String = "kg",
)

/** One day's eaten calories from the range report. */
@Serializable
data class NutritionDay(
    val date: String = "",
    val calories: Double = 0.0,
)

@Serializable
data class Report(
    @SerialName("nutritionData") val nutritionData: List<NutritionDay> = emptyList(),
)

/** Kilograms → the user's preferred display unit. */
fun kgToDisplay(kg: Double, weightUnit: String): Double =
    if (weightUnit.startsWith("lb", ignoreCase = true)) kg * 2.2046226218 else kg

/** The user's display unit → kilograms (the canonical storage unit). */
fun displayToKg(value: Double, weightUnit: String): Double =
    if (weightUnit.startsWith("lb", ignoreCase = true)) value / 2.2046226218 else value

// ---- Barcode lookup + meal creation ----

@Serializable
data class BarcodeVariant(
    val id: String? = null,
    @SerialName("serving_size") val servingSize: Double = 100.0,
    @SerialName("serving_unit") val servingUnit: String = "g",
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
    @SerialName("dietary_fiber") val dietaryFiber: Double = 0.0,
    val sugars: Double = 0.0,
    val sodium: Double = 0.0,
)

@Serializable
data class BarcodeFood(
    val id: String? = null,
    val name: String = "",
    val brand: String? = null,
    val barcode: String? = null,
    @SerialName("provider_type") val providerType: String? = null,
    @SerialName("provider_external_id") val providerExternalId: String? = null,
    @SerialName("default_variant") val defaultVariant: BarcodeVariant = BarcodeVariant(),
)

@Serializable
data class BarcodeResult(
    val source: String = "not_found",
    val food: BarcodeFood? = null,
)

// ---- Provider food search (generic V2 API) ----

/** Provider types that return foods (the rest, e.g. wger, are exercise sources). */
private val FOOD_PROVIDER_TYPES = setOf(
    "openfoodfacts", "usda", "swissfood", "nutritionix", "fatsecret", "mealie", "norish", "tandoor",
)

/** A configured external data provider (we only surface the food ones). */
@Serializable
data class ExternalProvider(
    val id: String = "",
    @SerialName("provider_type") val providerType: String = "",
    @SerialName("provider_name") val providerName: String = "",
    @SerialName("is_active") val isActive: Boolean = true,
)

/** /v2/foods/search/{providerType} returns normalized foods for any provider. */
@Serializable
private data class V2SearchResponse(val foods: List<BarcodeFood> = emptyList())

/** POST /foods body — flat nutrition fields, mirroring the verified payload. */
@Serializable
private data class ImportFoodRequest(
    val name: String,
    val brand: String? = null,
    @SerialName("is_custom") val isCustom: Boolean = true,
    val source: String = "imported",
    @SerialName("provider_type") val providerType: String? = null,
    @SerialName("provider_external_id") val providerExternalId: String? = null,
    val barcode: String? = null,
    @SerialName("serving_size") val servingSize: Double = 100.0,
    @SerialName("serving_unit") val servingUnit: String = "g",
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
    @SerialName("dietary_fiber") val dietaryFiber: Double = 0.0,
    val sugars: Double = 0.0,
    val sodium: Double = 0.0,
)

@Serializable
private data class SavedVariant(val id: String = "")

@Serializable
private data class SavedFood(
    val id: String = "",
    @SerialName("default_variant") val defaultVariant: SavedVariant = SavedVariant(),
)

@Serializable
private data class MealFoodReq(
    @SerialName("food_id") val foodId: String,
    @SerialName("variant_id") val variantId: String,
    val quantity: Double,
    val unit: String = "g",
)

@Serializable
private data class CreateMealRequest(
    val name: String,
    val description: String? = null,
    @SerialName("is_public") val isPublic: Boolean = false,
    @SerialName("serving_size") val servingSize: Double = 1.0,
    @SerialName("serving_unit") val servingUnit: String = "g",
    @SerialName("total_servings") val totalServings: Double,
    val foods: List<MealFoodReq>,
)

@Serializable
private data class CreatedMeal(val id: String = "")

@Serializable
private data class CheckInRequest(
    @SerialName("entry_date") val entryDate: String,
    val weight: Double,
)

@Serializable
private data class UpdateFoodEntryRequest(
    val quantity: Double,
    @SerialName("variant_id") val variantId: String? = null,
    @SerialName("meal_type") val mealType: String,
    val unit: String,
    @SerialName("entry_date") val entryDate: String,
)

@Serializable
private data class LogFoodRequest(
    @SerialName("food_id") val foodId: String,
    @SerialName("variant_id") val variantId: String,
    val quantity: Double,
    val unit: String = "g",
    @SerialName("meal_type") val mealType: String,
    @SerialName("entry_date") val entryDate: String,
)

/** A barcode resolved to a usable DB food, ready to put in a meal draft. */
data class ResolvedIngredient(
    val foodId: String,
    val variantId: String,
    val name: String,
    val brand: String?,
    val barcode: String,
    val calories: Double,      // per servingSize
    val servingSize: Double,
    val servingUnit: String,
)

/** One ingredient line for creating a meal. */
data class MealLine(val foodId: String, val variantId: String, val grams: Double)

// ---- Library (foods + meals listing) ----

@Serializable
data class LibraryFood(
    val id: String = "",
    val name: String = "",
    val brand: String? = null,
)

@Serializable
data class FoodsPage(
    val foods: List<LibraryFood> = emptyList(),
    val totalCount: Int = 0,
)

@Serializable
data class MealFood(
    @SerialName("food_id") val foodId: String = "",
    @SerialName("variant_id") val variantId: String = "",
    @SerialName("food_name") val foodName: String? = null,
    val name: String? = null,
    val quantity: Double = 0.0,
    val unit: String = "g",
    @SerialName("serving_size") val servingSize: Double = 100.0,
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
) {
    val displayName: String get() = foodName ?: name ?: "(unnamed)"
    fun scaled(value: Double): Double = if (servingSize > 0) value * quantity / servingSize else 0.0
    val kcal: Double get() = scaled(calories)
}

@Serializable
data class LibraryMeal(
    val id: String = "",
    val name: String = "",
    val foods: List<MealFood> = emptyList(),
) {
    val totalCalories: Double get() = foods.sumOf { it.kcal }
    val totalProtein: Double get() = foods.sumOf { it.scaled(it.protein) }
    val totalCarbs: Double get() = foods.sumOf { it.scaled(it.carbs) }
    val totalFat: Double get() = foods.sumOf { it.scaled(it.fat) }
    val totalGrams: Double get() = foods.sumOf { it.quantity }
}

@Serializable
private data class UpdateFoodNameReq(val name: String, val brand: String? = null)

@Serializable
private data class UpdateVariantReq(
    val id: String,
    @SerialName("food_id") val foodId: String,
    @SerialName("serving_size") val servingSize: Double,
    @SerialName("serving_unit") val servingUnit: String,
    val calories: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
)

// Mirrors SparkyFitness ACTIVITY_MULTIPLIERS (Frontend calorieCalculations.ts).
private val ACTIVITY_MULTIPLIERS = mapOf(
    "not_much" to 1.2, "light" to 1.375, "moderate" to 1.55, "heavy" to 1.725,
)

/** Maintenance (TDEE) = BMR × activity multiplier, matching the server. */
fun maintenanceCalories(bmr: Double, activityLevel: String): Double =
    bmr * (ACTIVITY_MULTIPLIERS[activityLevel] ?: 1.2)

/** Thin client for the SparkyFitness REST API (auth: Bearer api key). */
class SparkyApi(baseUrl: String, private val apiKey: String) {
    // The SparkyFitness REST API always lives under /api; tolerate a base URL
    // entered without it (e.g. "http://fit.bam") so requests don't hit the SPA.
    private val base = run {
        val b = baseUrl.trim().trimEnd('/')
        if (b.endsWith("/api")) b else "$b/api"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** Authenticated GET returning the raw body, with logging + error surfacing. */
    private suspend fun getBody(path: String): String = withContext(Dispatchers.IO) {
        val url = base + path
        Log.d(TAG, "GET $url")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val ctype = resp.header("Content-Type") ?: "?"
            Log.d(TAG, "HTTP ${resp.code} [$ctype] ${body.length}B: ${body.take(160)}")
            if (!resp.isSuccessful) {
                error("HTTP ${resp.code}: ${body.take(160).ifBlank { resp.message }}")
            }
            if (!ctype.contains("json", ignoreCase = true) && body.startsWith("<")) {
                error("Not JSON (got $ctype). Check the Server URL.")
            }
            body
        }
    }

    private inline fun <reified T> decode(body: String, fallback: T): T =
        if (body.isBlank()) fallback else json.decodeFromString(body)

    /** Authenticated request with an optional JSON body, returning the raw body. */
    private suspend fun sendBody(method: String, path: String, bodyJson: String?): String =
        withContext(Dispatchers.IO) {
            val url = base + path
            Log.d(TAG, "$method $url ${bodyJson?.take(120).orEmpty()}")
            val rb = bodyJson?.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .method(method, rb)
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                Log.d(TAG, "HTTP ${resp.code} ${body.take(160)}")
                if (!resp.isSuccessful) error("HTTP ${resp.code}: ${body.take(200).ifBlank { resp.message }}")
                body
            }
        }

    private suspend fun postBody(path: String, bodyJson: String) = sendBody("POST", path, bodyJson)

    /** GET /daily-summary?date=YYYY-MM-DD */
    suspend fun dailySummary(date: String): DailySummary =
        decode(getBody("/daily-summary?date=$date"), DailySummary())

    /** GET /measurements/check-in-measurements-range/{start}/{end} */
    suspend fun checkInRange(start: String, end: String): List<CheckIn> =
        decode(getBody("/measurements/check-in-measurements-range/$start/$end"), emptyList())

    /** GET /user-preferences (activity level, units, …) */
    suspend fun userPreferences(): UserPreferences =
        decode(getBody("/user-preferences"), UserPreferences())

    /** GET /reports?startDate=&endDate= — per-day nutrition totals over a range. */
    suspend fun report(start: String, end: String): Report =
        decode(getBody("/reports?startDate=$start&endDate=$end"), Report())

    /** GET /foods/foods-paginated — the user's foods. */
    suspend fun foods(page: Int = 1, perPage: Int = 200): FoodsPage =
        decode(getBody("/foods/foods-paginated?page=$page&itemsPerPage=$perPage"), FoodsPage())

    /** GET /meals?filter=mine — the user's recipes. */
    suspend fun meals(): List<LibraryMeal> =
        decode(getBody("/meals?filter=mine"), emptyList())

    /** GET /foods/food-entries/{date} — diary entries logged on a day. */
    suspend fun foodEntriesForDate(date: String): List<FoodEntry> =
        decode(getBody("/foods/food-entries/$date"), emptyList())

    /** GET /food-entry-meals/by-date/{date} — meals logged to the diary that day. */
    suspend fun foodEntryMealsForDate(date: String): List<FoodEntryMeal> =
        decode(getBody("/food-entry-meals/by-date/$date"), emptyList())

    /** DELETE /food-entry-meals/{id} — remove a logged meal (and its entries). */
    suspend fun deleteFoodEntryMeal(id: String) {
        sendBody("DELETE", "/food-entry-meals/$id", null)
    }

    /** POST /food-entries — log a food to the diary. */
    suspend fun logFood(
        foodId: String,
        variantId: String,
        grams: Double,
        mealType: String,
        date: String,
    ) {
        sendBody(
            "POST", "/food-entries",
            json.encodeToString(LogFoodRequest(foodId, variantId, grams, "g", mealType, date)),
        )
    }

    /** POST /measurements/check-in — upsert a day's body weight (kg). */
    suspend fun logWeight(date: String, kg: Double) {
        sendBody("POST", "/measurements/check-in", json.encodeToString(CheckInRequest(date, kg)))
    }

    /** DELETE /measurements/check-in/{id} — remove a weigh-in. */
    suspend fun deleteCheckIn(id: String) {
        sendBody("DELETE", "/measurements/check-in/$id", null)
    }

    /** PUT /food-entries/{id} — change a diary entry's quantity. */
    suspend fun updateFoodEntry(
        id: String,
        quantity: Double,
        variantId: String,
        mealType: String,
        unit: String,
        date: String,
    ) {
        sendBody(
            "PUT", "/food-entries/$id",
            json.encodeToString(
                UpdateFoodEntryRequest(quantity, variantId.ifBlank { null }, mealType, unit, date),
            ),
        )
    }

    /** DELETE /food-entries/{id} — remove a diary entry. */
    suspend fun deleteFoodEntry(id: String) {
        sendBody("DELETE", "/food-entries/$id", null)
    }

    /** GET /foods/{id} — full food incl. default_variant nutrition. */
    suspend fun foodDetail(id: String): BarcodeFood =
        decode(getBody("/foods/$id"), BarcodeFood())

    /** DELETE /foods/{id} */
    suspend fun deleteFood(id: String) {
        sendBody("DELETE", "/foods/$id", null)
    }

    /** DELETE /meals/{id} */
    suspend fun deleteMeal(id: String) {
        sendBody("DELETE", "/meals/$id", null)
    }

    /** Edit a food's name and its default variant's per-serving nutrition. */
    suspend fun updateFood(
        foodId: String,
        variantId: String,
        name: String,
        brand: String?,
        servingSize: Double,
        servingUnit: String,
        calories: Double,
        protein: Double,
        carbs: Double,
        fat: Double,
    ) {
        sendBody("PUT", "/foods/$foodId", json.encodeToString(UpdateFoodNameReq(name, brand)))
        sendBody(
            "PUT", "/foods/food-variants/$variantId",
            json.encodeToString(
                UpdateVariantReq(variantId, foodId, servingSize, servingUnit, calories, protein, carbs, fat),
            ),
        )
    }

    /** GET /foods/barcode/{barcode} — local DB first, then OpenFoodFacts fallback. */
    suspend fun barcodeLookup(barcode: String): BarcodeResult =
        decode(getBody("/foods/barcode/$barcode"), BarcodeResult())

    /** GET /external-providers — the user's active food data providers. */
    suspend fun foodProviders(): List<ExternalProvider> =
        decode(getBody("/external-providers"), emptyList<ExternalProvider>())
            .filter { it.isActive && it.providerType in FOOD_PROVIDER_TYPES }

    /** GET /v2/foods/search/{providerType} — normalized text search for any
     *  provider. Some providers (e.g. Open Food Facts) intermittently return an
     *  HTML error the server wraps as 500, so retry a couple of times. */
    suspend fun searchProvider(providerType: String, query: String, providerId: String): List<BarcodeFood> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val pid = if (providerId.isNotBlank()) "&providerId=$providerId" else ""
        var last: Exception? = null
        repeat(3) {
            try {
                return decode(getBody("/v2/foods/search/$providerType?query=$q$pid"), V2SearchResponse())
                    .foods.filter { it.name.isNotBlank() }
            } catch (e: Exception) {
                last = e
                delay(600)
            }
        }
        throw last ?: IllegalStateException("Search failed")
    }

    /** Import a provider search result into the food DB (POST /foods). */
    suspend fun addFood(food: BarcodeFood): Boolean = importFood(food).id.isNotBlank()

    /** POST /foods — import a (provider) food into the DB; returns id + variant id. */
    private suspend fun importFood(f: BarcodeFood): SavedFood {
        val v = f.defaultVariant
        val req = ImportFoodRequest(
            name = f.name, brand = f.brand,
            providerType = f.providerType, providerExternalId = f.providerExternalId,
            barcode = f.barcode,
            servingSize = v.servingSize, servingUnit = v.servingUnit,
            calories = v.calories, protein = v.protein, carbs = v.carbs, fat = v.fat,
            dietaryFiber = v.dietaryFiber, sugars = v.sugars, sodium = v.sodium,
        )
        return json.decodeFromString(postBody("/foods", json.encodeToString(req)))
    }

    /**
     * Resolve a scanned/typed barcode to a DB food usable in a meal. Returns null
     * if the barcode isn't found anywhere.
     */
    suspend fun resolveIngredient(barcode: String): ResolvedIngredient? {
        val res = barcodeLookup(barcode)
        val f = res.food ?: return null
        val (foodId, variantId) =
            if (res.source == "local" && f.id != null && f.defaultVariant.id != null) {
                f.id to f.defaultVariant.id
            } else {
                val saved = importFood(f)
                saved.id to saved.defaultVariant.id
            }
        if (foodId.isBlank() || variantId.isBlank()) return null
        val v = f.defaultVariant
        return ResolvedIngredient(
            foodId = foodId, variantId = variantId,
            name = f.name, brand = f.brand, barcode = barcode,
            calories = v.calories, servingSize = v.servingSize, servingUnit = v.servingUnit,
        )
    }

    /**
     * POST /meals — create a gram-based recipe. serving_size=1g and
     * total_servings=total grams makes later gram logging scale correctly.
     */
    suspend fun createMeal(name: String, lines: List<MealLine>): String {
        val totalGrams = lines.sumOf { it.grams }.coerceAtLeast(1.0)
        val req = CreateMealRequest(
            name = name,
            totalServings = totalGrams,
            foods = lines.map { MealFoodReq(it.foodId, it.variantId, it.grams) },
        )
        val created: CreatedMeal = json.decodeFromString(postBody("/meals", json.encodeToString(req)))
        return created.id
    }

    /** PUT /meals/{id} — replace a recipe's name and ingredients. */
    suspend fun updateMeal(mealId: String, name: String, lines: List<MealLine>) {
        val totalGrams = lines.sumOf { it.grams }.coerceAtLeast(1.0)
        val req = CreateMealRequest(
            name = name,
            totalServings = totalGrams,
            foods = lines.map { MealFoodReq(it.foodId, it.variantId, it.grams) },
        )
        sendBody("PUT", "/meals/$mealId", json.encodeToString(req))
    }

    private companion object {
        const val TAG = "SFit"
    }
}
