package com.undal.syl.app.core.config

import com.undal.syl.app.BuildConfig

/**
 * Typed, single-source access to everything the build injects.
 *
 * Every value here is written by Gradle from CI secrets or from
 * `gradle.properties`; no credential is ever committed to the repository.
 */
object UndalBuildInfo {

    /** Canonical application id. Changing this requires a new Firebase Android app. */
    const val PACKAGE_NAME: String = "com.undal.syl.app"

    /** Asia/Dhaka is fixed (no daylight saving), so a single zone id is correct everywhere. */
    const val TIMEZONE_ID: String = "Asia/Dhaka"

    /** Rupee-style display: amounts are always stored as Long paisa (100 paisa = 1 taka). */
    const val PAISA_PER_TAKA: Long = 100L

    val versionName: String get() = BuildConfig.VERSION_NAME
    val versionCode: Int get() = BuildConfig.VERSION_CODE

    val cloudinaryCloudName: String get() = BuildConfig.CLOUDINARY_CLOUD_NAME
    val cloudinaryUploadPreset: String get() = BuildConfig.CLOUDINARY_UPLOAD_PRESET

    val workerBaseUrl: String get() = BuildConfig.WORKER_BASE_URL
    val workerHost: String get() = BuildConfig.WORKER_HOST

    val firebaseWebApiKey: String get() = BuildConfig.FIREBASE_WEB_API_KEY
    val supportPhone: String get() = BuildConfig.SUPPORT_PHONE

    val storeTimezoneId: String get() = BuildConfig.STORE_TIMEZONE
    val currencySymbol: String get() = BuildConfig.CURRENCY_SYMBOL

    /** Resource name of the official order sound: `R.raw.undal_order_alert`. */
    val orderSoundName: String get() = BuildConfig.ORDER_SOUND_NAME

    /** True once the worker host has been replaced with the owner's real sub-domain. */
    val isWorkerConfigured: Boolean
        get() = workerBaseUrl.startsWith("https://") && !workerBaseUrl.contains(NOT_CONFIGURED_MARKER)

    /** Deep link for a dish / combo / order, used by the share sheet and the QR code. */
    fun shareUrl(path: String): String = workerBaseUrl.trimEnd('/') + "/" + path.trimStart('/')

    private const val NOT_CONFIGURED_MARKER = "YOUR-SUBDOMAIN"
}
