package Kinetic_Eco.Tracker.services

import android.util.Log
import Kinetic_Eco.Tracker.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Hands a Play purchase token to the server for verification.
 *
 * The response is informational — the authoritative entitlement arrives through
 * the Firestore listener in [EntitlementRepository] moments later, because the
 * function writes the document the app already watches. This class exists so the
 * paywall can tell the difference between "purchased and confirmed", "purchased
 * but we could not reach the server", and "declined".
 */
object PlayPurchaseVerifier {

    private const val TAG = "PlayPurchaseVerifier"

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val functionUrl = "${BuildConfig.FUNCTIONS_BASE_URL}/verifyPlayPurchase"

    /**
     * Result of one verification attempt.
     *
     * [Unreachable] is kept distinct from [Rejected] on purpose: a network failure
     * must be retried on the next launch, whereas a rejection is final and
     * retrying it forever would just hammer the endpoint.
     */
    sealed interface Outcome {
        /** Play and the server agree the subscription is live. */
        data class Verified(val expiryMs: Long, val willRenew: Boolean) : Outcome
        /** The server reached Play and says this token grants nothing. */
        data class Rejected(val message: String) : Outcome
        /** We never got an answer. Try again later; do not change entitlement. */
        data class Unreachable(val message: String) : Outcome
    }

    suspend fun verify(purchaseToken: String, productId: String): Outcome =
        withContext(Dispatchers.IO) {
            val user = auth.currentUser
                ?: return@withContext Outcome.Unreachable("Not signed in")

            try {
                val token = user.getIdToken(false).await().token
                    ?: return@withContext Outcome.Unreachable("Could not get auth token")

                val body = JSONObject().apply {
                    put("purchaseToken", purchaseToken)
                    put("productId", productId)
                }.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(functionUrl)
                    .post(body)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    val json = runCatching { JSONObject(raw) }.getOrNull()

                    if (!response.isSuccessful) {
                        val message = json?.optString("error").takeUnless { it.isNullOrBlank() }
                            ?: "HTTP ${response.code}"
                        Log.w(TAG, "Verification rejected (${response.code}): $message")
                        // 5xx is our own outage, not a verdict on the purchase, so it
                        // stays retryable — otherwise a bad deploy would look to the
                        // user like their subscription was refused.
                        return@use if (response.code >= 500) {
                            Outcome.Unreachable(message)
                        } else {
                            Outcome.Rejected(message)
                        }
                    }

                    val expiryMs = json?.optLong("expiryMs") ?: 0L
                    val active = json?.optBoolean("active") ?: false
                    if (!active && expiryMs <= System.currentTimeMillis()) {
                        Log.w(TAG, "Server verified the token but it grants no access")
                        return@use Outcome.Rejected("This subscription is no longer active")
                    }

                    Log.d(TAG, "✅ Verified; expires ${java.util.Date(expiryMs)}")
                    Outcome.Verified(expiryMs, json?.optBoolean("willRenew") ?: false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Verification call failed", e)
                Outcome.Unreachable(e.message ?: "Network error")
            }
        }
}