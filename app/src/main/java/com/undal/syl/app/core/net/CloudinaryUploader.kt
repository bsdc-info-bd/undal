package com.undal.syl.app.core.net

import android.content.Context
import android.net.Uri
import com.cloudinary.android.MediaManager
import com.cloudinary.android.callback.ErrorInfo
import com.cloudinary.android.callback.UploadCallback
import com.undal.syl.app.core.config.UndalBuildInfo
import com.undal.syl.app.core.util.UndalResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * One photo in, one URL out — through the Cloudinary unsigned preset (C-079).
 *
 * The app holds the cloud name and the unsigned preset, never the API secret:
 * a phone in someone's pocket is the last place an account credential belongs.
 * The preset itself is restricted in the Cloudinary console to images, so even
 * a modified client cannot push arbitrary files through it.
 *
 * The SDK does the transport work (retries, backoff, background dispatch) and
 * this class only translates its callbacks into a coroutine result the profile
 * screen can await.
 */
@Singleton
class CloudinaryUploader @Inject constructor(
    @ApplicationContext private val context: Context
) {

    data class UploadedImage(val secureUrl: String, val publicId: String, val bytes: Long)

    /**
     * Uploads [uri] and returns the permanent `secure_url`.
     *
     * The request is capped at [MAX_UPLOAD_BYTES]; a photo straight out of a
     * modern camera is far smaller than that after the preset's own limits, and
     * refusing an 80 MB video mis-picked as a photo is friendlier than a
     * five-minute upload on mobile data.
     */
    suspend fun uploadImage(
        uri: Uri,
        onProgress: (Float) -> Unit = {}
    ): UndalResult<UploadedImage> {
        if (!UndalBuildInfo.isCloudinaryConfigured) {
            return UndalResult.Failure(
                "Photo upload is not configured in this build yet — the kitchen can still see your name " +
                    "and phone, which is what the rider needs."
            )
        }
        return suspendCancellableCoroutine { continuation ->
            val callback = object : UploadCallback {
                override fun onStart(requestId: String) = Unit

                override fun onProgress(requestId: String, bytes: Long, totalBytes: Long) {
                // Cloudinary compresses on its side; the phone only needs to show
                // how far the bytes have travelled.
                val fraction = if (totalBytes > 0L) bytes.toFloat() / totalBytes.toFloat() else 0f
                onProgress(fraction.coerceIn(0f, 1f))
            }

                override fun onSuccess(requestId: String, resultData: MutableMap<Any?, Any?>?) {
                    val url = resultData?.get("secure_url") as? String
                    val publicId = resultData?.get("public_id") as? String
                    val bytes = (resultData?.get("bytes") as? Number)?.toLong() ?: 0L
                    if (continuation.isActive) {
                        continuation.resume(
                            if (url.isNullOrBlank()) {
                                UndalResult.Failure("Cloudinary accepted the photo but returned no link. Try once more.")
                            } else {
                                UndalResult.Success(
                                    UploadedImage(
                                        secureUrl = url,
                                        publicId = publicId.orEmpty(),
                                        bytes = bytes
                                    )
                                )
                            }
                        )
                    }
                }

                override fun onError(requestId: String, error: ErrorInfo) {
                    if (continuation.isActive) {
                        continuation.resume(UndalResult.Failure(describe(error)))
                    }
                }

                override fun onReschedule(requestId: String, error: ErrorInfo) {
                    // The SDK is retrying by itself; the customer waits and the
                    // progress copy keeps moving. Nothing to do here.
                }
            }

            runCatching {
                MediaManager.get()
                    .upload(uri)
                    .unsigned(UndalBuildInfo.cloudinaryUploadPreset)
                    .maxFileSize(MAX_UPLOAD_BYTES)
                    .callback(callback)
                    .dispatch(context)
            }.onFailure { error ->
                if (continuation.isActive) {
                    continuation.resume(
                        UndalResult.Failure(error.message ?: "The photo could not be uploaded from this device.")
                    )
                }
            }
        }
    }

    private fun describe(error: ErrorInfo): String = when (error.code) {
        ErrorInfo.FILE_DOES_NOT_EXIST, ErrorInfo.URI_DOES_NOT_EXIST, ErrorInfo.RESOURCE_DOES_NOT_EXIST ->
            "That photo cannot be read any more. Pick it again from your gallery."
        ErrorInfo.NETWORK_ERROR -> "No internet connection for the upload. Your photo is safe — try again."
        ErrorInfo.PAYLOAD_EMPTY, ErrorInfo.BYTE_ARRAY_PAYLOAD_EMPTY -> "That file came through empty. Pick another photo."
        ErrorInfo.PAYLOAD_LOAD_FAILURE -> "The photo could not be loaded. Pick another one."
        ErrorInfo.SIGNATURE_FAILURE -> "The upload preset on Cloudinary is not set to unsigned. Tell the kitchen."
        ErrorInfo.PREPROCESS_ERROR -> "That photo is too large to upload. Try a smaller one."
        ErrorInfo.TOO_MANY_ERRORS -> "The upload failed repeatedly — check your data connection and try again."
        ErrorInfo.REQUEST_CANCELLED -> "The upload was cancelled."
        else -> error.description.ifBlank { "The photo upload failed. Try again." }
    }

    private companion object {
        const val MAX_UPLOAD_BYTES = 6L * 1024L * 1024L
    }
}
