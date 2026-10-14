package com.undal.syl.app.core.net

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Client for the Undal Worker's notification relay.
 *
 * Every call attaches the caller's fresh Firebase ID token; the Worker verifies
 * it against Google's certificates and re-reads the order from Firestore, so the
 * JSON body here is only a pointer, never a source of truth.
 *
 * Order notifications matter more than they look: this is the piece that makes a
 * locked, sleeping kitchen phone ring. Failures are returned, never thrown, so a
 * caller can queue a WorkManager retry without a crash guard around it.
 */
@Singleton
class WorkerApi @Inject constructor(
    private val client: OkHttpClient,
    private val auth: FirebaseAuth,
    @Named("workerBaseUrl") private val baseUrl: String
) {

    sealed interface ApiResult {
        data object Success : ApiResult
        /** Worth retrying later: offline, 5xx, timeout, 429. */
        data class Retryable(val message: String) : ApiResult
        /** Do not retry: 4xx that a repeat cannot fix (bad payload, forbidden). */
        data class Permanent(val message: String) : ApiResult
    }

    suspend fun notifyOrderCreated(orderId: String): ApiResult =
        post("/notify/order-created", JSONObject().put("orderId", orderId))

    suspend fun notifyOrderStatus(orderId: String, status: String): ApiResult =
        post(
            "/notify/order-status",
            JSONObject().put("orderId", orderId).put("status", status)
        )

    suspend fun notifyChat(orderId: String, text: String): ApiResult =
        post("/notify/chat", JSONObject().put("orderId", orderId).put("text", text))

    suspend fun notifyVendorSelf(kind: String, title: String, body: String, orderId: String? = null): ApiResult {
        val payload = JSONObject()
            .put("kind", kind)
            .put("title", title)
            .put("body", body)
        if (orderId != null) payload.put("orderId", orderId)
        return post("/notify/vendor-self", payload)
    }

    /**
     * "Ring my phone" — asks the Worker to send a real order alert to this
     * kitchen, and returns what the server says it managed to reach.
     *
     * The readiness screen can verify everything the device knows about itself.
     * It cannot verify that the server can reach *it* — and that is exactly the
     * link that failed silently before: no vendor document, no device token, a
     * topic nobody subscribed to. This call is the missing half of the test.
     */
    suspend fun testAlert(): Result<String> = runCatching {
        val token = auth.currentUser?.getIdToken(false)?.await()?.token
            ?: error("Not signed in")
        val request = Request.Builder()
            .url("$baseUrl/notify/test-alert")
            .header("authorization", "Bearer $token")
            .header("content-type", "application/json")
            .post(JSONObject().toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newBuilder()
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("The kitchen relay refused the test (HTTP ${response.code}).")
                }
                body
            }
    }

    data class CleanupBatch(val before: String, val orders: Int, val notifications: Int, val more: Boolean)

    suspend fun cleanupHistory(before: String?): CleanupBatch = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val token = auth.currentUser?.getIdToken(false)?.await()?.token ?: error("Not signed in")
        val payload = JSONObject().put("confirmation", "DELETE_FINISHED_HISTORY")
        if (before != null) payload.put("before", before)
        val request = Request.Builder().url("$baseUrl/admin/cleanup")
            .header("authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Cleanup paused (HTTP ${response.code}). You can safely try again.")
            val result = JSONObject(response.body?.string().orEmpty())
            CleanupBatch(result.getString("before"), result.getInt("orders"),
                result.getInt("notifications"), result.getBoolean("more"))
        }
    }

    suspend fun health(): Result<String> = runCatching {
        val request = Request.Builder().url("$baseUrl/healthz").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("healthz ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    private suspend fun post(path: String, body: JSONObject): ApiResult {
        val token = runCatching { auth.currentUser?.getIdToken(false)?.await()?.token }.getOrNull()
            ?: return ApiResult.Permanent("Not signed in")

        val request = Request.Builder()
            .url(baseUrl + path)
            .header("authorization", "Bearer $token")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return runCatching {
            // The Worker can take a moment on a cold start, but a customer's
            // order confirmation must never wait longer than this.
            val call = client.newBuilder()
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
                .newCall(request)
            call.execute().use { response ->
                when {
                    response.isSuccessful -> ApiResult.Success
                    response.code == 401 || response.code == 403 -> ApiResult.Permanent("Not allowed")
                    response.code in 500..599 -> ApiResult.Retryable("Worker error ${response.code}")
                    response.code == 429 -> ApiResult.Retryable("Worker is busy")
                    else -> ApiResult.Permanent("Rejected ${response.code}")
                }
            }
        }.getOrElse { error ->
            ApiResult.Retryable(error.message ?: "Network unavailable")
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
