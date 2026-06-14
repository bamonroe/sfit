package net.bam.sfit.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
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

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

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

    /** GET /daily-summary?date=YYYY-MM-DD */
    suspend fun dailySummary(date: String): DailySummary =
        decode(getBody("/daily-summary?date=$date"), DailySummary())

    /** GET /measurements/check-in-measurements-range/{start}/{end} */
    suspend fun checkInRange(start: String, end: String): List<CheckIn> =
        decode(getBody("/measurements/check-in-measurements-range/$start/$end"), emptyList())

    /** GET /user-preferences (activity level, units, …) */
    suspend fun userPreferences(): UserPreferences =
        decode(getBody("/user-preferences"), UserPreferences())

    private companion object {
        const val TAG = "SFit"
    }
}
