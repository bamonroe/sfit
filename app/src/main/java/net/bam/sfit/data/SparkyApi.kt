package net.bam.sfit.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
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
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
    val quantity: Double = 0.0,
    @SerialName("serving_size") val servingSize: Double = 0.0,
    @SerialName("meal_type") val mealType: String? = null,
    @SerialName("food_name") val foodName: String? = null,
) {
    val consumedCalories: Double get() = if (servingSize > 0) calories * quantity / servingSize else 0.0
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

/** A check-in measurement row (we only need date + weight). */
@Serializable
data class CheckIn(
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

    /** Authenticated POST of a JSON body, returning the raw response body. */
    private suspend fun postBody(path: String, bodyJson: String): String = withContext(Dispatchers.IO) {
        val url = base + path
        Log.d(TAG, "POST $url ${bodyJson.take(120)}")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            Log.d(TAG, "HTTP ${resp.code} ${body.take(160)}")
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${body.take(200).ifBlank { resp.message }}")
            body
        }
    }

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

    /** GET /foods/barcode/{barcode} — local DB first, then OpenFoodFacts fallback. */
    suspend fun barcodeLookup(barcode: String): BarcodeResult =
        decode(getBody("/foods/barcode/$barcode"), BarcodeResult())

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

    private companion object {
        const val TAG = "SFit"
    }
}
