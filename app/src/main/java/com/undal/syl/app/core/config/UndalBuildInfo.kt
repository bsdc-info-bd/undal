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

    /**
     * The Firebase project this build was compiled against, read from
     * google-services.json at runtime.
     *
     * It is public — it names the `.firebaseapp.com` sign-in page — and it is the
     * one fact that makes a database refusal diagnosable: if this is not the
     * project the kitchen's rules, accounts and menu were set up in, every read
     * and write is refused with nothing but "Missing or insufficient
     * permissions". The vendor Diagnostics screen prints it for that reason.
     */
    val firebaseProjectId: String
        get() = runCatching { com.google.firebase.FirebaseApp.getInstance().options.projectId }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: ""

    val firebaseWebApiKey: String get() = BuildConfig.FIREBASE_WEB_API_KEY
    val supportPhone: String get() = BuildConfig.SUPPORT_PHONE

    val storeTimezoneId: String get() = BuildConfig.STORE_TIMEZONE
    val currencySymbol: String get() = BuildConfig.CURRENCY_SYMBOL

    /** Resource name of the official order sound: `R.raw.undal_order_alert`. */
    val orderSoundName: String get() = BuildConfig.ORDER_SOUND_NAME

    /** True once the worker host has been filled in with the owner's real sub-domain. */
    val isWorkerConfigured: Boolean
        get() = workerBaseUrl.startsWith("https://") && !workerBaseUrl.contains(NOT_CONFIGURED_MARKER)

    /**
     * True when the profile-photo pipeline can work: both the cloud name and the
     * unsigned preset must be present. Neither is a secret, so both live in the
     * build config — and a blank one produces a clear sentence on screen instead
     * of a network error nobody can act on.
     */
    val isCloudinaryConfigured: Boolean
        get() = cloudinaryCloudName.isNotBlank() && cloudinaryUploadPreset.isNotBlank()

    /**
     * The Web storefront's address, or null when this build does not know it.
     *
     * A build compiled without the owner's `WORKER_HOST` secret has no address to
     * point at, and the honest answer is `null`: every caller either says so on
     * the screen or leaves the link out. Sharing `https://undal.YOUR-SUBDOMAIN…`
     * into a WhatsApp group is worse than sharing nothing — the customer taps it,
     * the browser says the site cannot be reached, and the kitchen looks broken.
     */
    val webStoreHost: String? get() = workerHost.takeIf { isWorkerConfigured && it.isNotBlank() }
    val webStoreBaseUrl: String? get() = workerBaseUrl.takeIf { isWorkerConfigured }

    /** Deep link for a dish / combo / order, or null when there is no host to link to. */
    fun shareUrlOrNull(path: String): String? {
        val base = webStoreBaseUrl ?: return null
        return base.trimEnd('/') + "/" + path.trimStart('/')
    }

    private const val NOT_CONFIGURED_MARKER = "YOUR-SUBDOMAIN"
}
