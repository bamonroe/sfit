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
 * amount actually eaten is `field * quantity / serving_size` (mirrors the
 * SparkyFitness API and the `nutrition today` CLI).
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

@Serializable
data class DailySummary(
    @SerialName("foodEntries") val foodEntries: List<FoodEntry> = emptyList(),
    val goals: Goals = Goals(),
) {
    val consumedCalories: Double get() = foodEntries.sumOf { it.consumedCalories }
    val remainingCalories: Double get() = goals.calories - consumedCalories
}

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

    /** GET /daily-summary?date=YYYY-MM-DD */
    suspend fun dailySummary(date: String): DailySummary = withContext(Dispatchers.IO) {
        val url = "$base/daily-summary?date=$date"
        Log.d(TAG, "GET $url")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val ctype = resp.header("Content-Type") ?: "?"
            Log.d(TAG, "HTTP ${resp.code} [$ctype] ${body.length}B: ${body.take(300)}")
            if (!resp.isSuccessful) {
                error("HTTP ${resp.code}: ${body.take(160).ifBlank { resp.message }}")
            }
            if (body.isBlank()) return@withContext DailySummary()
            try {
                json.decodeFromString<DailySummary>(body)
            } catch (e: Exception) {
                Log.e(TAG, "JSON parse failed (content-type=$ctype). Body: ${body.take(300)}", e)
                error("Not JSON (got $ctype). Starts: \"${body.take(60).trim()}…\"")
            }
        }
    }

    private companion object {
        const val TAG = "SFit"
    }
}
