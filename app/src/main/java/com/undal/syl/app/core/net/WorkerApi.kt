package com.undal.syl.app.core.net

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
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

    data class CleanupBatch(
        val before: String,
        val orders: Int,
        val notifications: Int,
        val more: Boolean,
        /** `indexed` is the fast path; `scan` means the project is missing a composite index. */
        val strategy: String = "indexed",
        /** Everything that went wrong but did not stop the batch. Never dropped. */
        val warnings: List<String> = emptyList(),
        val mirrorsPending: Int = 0,
        val hint: String? = null
    )

    /** One line of the Worker's own pre-flight check of the cleanup path. */
    data class CleanupCheck(val name: String, val ok: Boolean, val detail: String, val hint: String?)

    data class CleanupDiagnosis(
        val verdict: String,
        val summary: String,
        val strategy: String,
        val finishedSample: Int,
        val mirrorsPending: Int,
        val indexUrl: String?,
        val checks: List<CleanupCheck>
    ) {
        val blocked: Boolean get() = verdict == "blocked"
        val degraded: Boolean get() = verdict == "degraded"
    }

    /**
     * A refusal the Worker explained.
     *
     * This exists because of one real failure: "Clear finished history" answered
     * `Cleanup paused (HTTP 502). You can safely try again.` for a project whose
     * Firestore was missing a composite index — a problem no amount of trying
     * again could fix, described in a sentence that promised the opposite. The
     * Worker now says what failed and what to do about it; this type carries both
     * halves, plus its own verdict on whether a repeat is worth making.
     */
    class WorkerRefusal(message: String, val retryable: Boolean, val hint: String?) : Exception(message)

    private data class WorkerAnswer(val status: Int, val json: JSONObject?, val transportError: String? = null) {
        val success: Boolean get() = status in 200..299
    }

    /**
     * Clears delivered and cancelled orders in bounded batches.
     *
     * `before` is the cursor the previous batch returned; passing it back keeps a
     * long cleanup from chasing orders that finish while it runs. A failure is
     * thrown as a [WorkerRefusal] carrying the Worker's own sentence, so the
     * screen can show the reason instead of a status code.
     */
    suspend fun cleanupHistory(before: String?): CleanupBatch =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val payload = JSONObject().put("confirmation", CLEANUP_CONFIRMATION)
            if (before != null) payload.put("before", before)
            val answer = postAdmin("/admin/cleanup", payload, 30)
            if (!answer.success) throw refusal(answer, "Cleanup paused (HTTP ${answer.status}).")
            val json = answer.json
                ?: throw WorkerRefusal("The Worker answered without a report. That batch may or may not have run; trying again is safe.", true, null)
            CleanupBatch(
                before = json.optString("before"),
                orders = json.optInt("orders"),
                notifications = json.optInt("notifications"),
                more = json.optBoolean("more"),
                strategy = json.optString("strategy").ifEmpty { "indexed" },
                warnings = stringArray(json.optJSONArray("warnings")),
                mirrorsPending = json.optInt("mirrorsPending"),
                hint = json.optString("hint").takeIf { it.isNotBlank() }
            )
        }

    /**
     * Asks the Worker to check the cleanup path end to end **without deleting
     * anything** — the index, the slower scan that replaces it, the Realtime
     * Database mirrors, the mirror backlog and the notification history.
     *
     * The diagnostics screen runs this so a merchant can be told which piece is
     * missing *before* they tap a destructive button.
     */
    suspend fun cleanupDiagnosis(): CleanupDiagnosis =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val payload = JSONObject().put("confirmation", CLEANUP_CONFIRMATION).put("dryRun", true)
            val answer = postAdmin("/admin/cleanup", payload, 45)
            if (!answer.success) throw refusal(answer, "The history self-test could not run (HTTP ${answer.status}).")
            val json = answer.json
                ?: throw WorkerRefusal("The Worker answered the self-test without a report.", true, null)
            val checksArray = json.optJSONArray("checks")
            val checks = if (checksArray == null) {
                emptyList()
            } else {
                (0 until checksArray.length()).mapNotNull { index ->
                    checksArray.optJSONObject(index)?.let { row ->
                        CleanupCheck(
                            name = row.optString("name"),
                            ok = row.optBoolean("ok"),
                            detail = row.optString("detail"),
                            hint = row.optString("hint").takeIf { it.isNotBlank() }
                        )
                    }
                }
            }
            CleanupDiagnosis(
                verdict = json.optString("verdict").ifEmpty { "blocked" },
                summary = json.optString("summary"),
                strategy = json.optString("strategy").ifEmpty { "indexed" },
                finishedSample = json.optInt("finishedSample"),
                mirrorsPending = json.optInt("mirrorsPending"),
                indexUrl = json.optString("indexUrl").takeIf { it.isNotBlank() },
                checks = checks
            )
        }

    private suspend fun postAdmin(path: String, body: JSONObject, timeoutSeconds: Long): WorkerAnswer {
        val token = runCatching { auth.currentUser?.getIdToken(false)?.await()?.token }.getOrNull()
            ?: return WorkerAnswer(401, null, "Not signed in — the kitchen account has to be signed in on this phone first.")
        val request = Request.Builder()
            .url(baseUrl + path)
            .header("authorization", "Bearer $token")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return runCatching {
            client.newBuilder()
                .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()
                .use { response ->
                    val text = response.body?.string().orEmpty()
                    WorkerAnswer(response.code, runCatching { JSONObject(text) }.getOrNull())
                }
        }.getOrElse { error -> WorkerAnswer(0, null, error.message ?: "Network unavailable") }
    }

    /**
     * Turns the Worker's answer into the sentence a merchant reads.
     *
     * The Worker retries transient Google failures itself, so a 5xx arriving here
     * has already been retried and comes with a reason and a hint. Only the cases
     * where a repeat genuinely changes the answer — no network at all, a rate
     * limit, a service that says come back — are marked retryable.
     */
    private fun refusal(answer: WorkerAnswer, fallback: String): WorkerRefusal {
        val json = answer.json
        val message = json?.optString("message")?.takeIf { it.isNotBlank() }
            ?: answer.transportError?.takeIf { it.isNotBlank() }
            ?: fallback
        val hint = json?.optString("hint")?.takeIf { it.isNotBlank() }
        val code = json?.optString("error").orEmpty()
        val retryable = answer.status == 0 || answer.status == 429 || answer.status == 503 ||
            answer.status == 504 || code == "internal-error"
        return WorkerRefusal(if (hint != null) "$message\n\n$hint" else message, retryable, hint)
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

        /** The word the Worker insists on before it deletes anything. */
        const val CLEANUP_CONFIRMATION = "DELETE_FINISHED_HISTORY"

        /** Reads an array of strings out of a report that may not carry one. */
        fun stringArray(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            return (0 until array.length())
                .mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
        }
    }
}
