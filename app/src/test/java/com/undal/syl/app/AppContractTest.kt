package com.undal.syl.app

import com.undal.syl.app.core.config.UndalBuildInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build-contract tests. These guard the canonical constants of PART A: the
 * package name, the Asia/Dhaka timezone, paisa money handling and the official
 * order-sound resource name. They fail the CI build the moment anyone drifts.
 */
class AppContractTest {

    @Test
    fun applicationIdMatchesTheCanonicalPackage() {
        val applicationId = BuildConfig.APPLICATION_ID
        assertTrue(
            "unexpected applicationId: $applicationId",
            applicationId == UndalBuildInfo.PACKAGE_NAME ||
                applicationId == UndalBuildInfo.PACKAGE_NAME + ".debug"
        )
    }

    @Test
    fun buildInfoPackageNameIsCanonical() {
        assertEquals("com.undal.syl.app", UndalBuildInfo.PACKAGE_NAME)
    }

    @Test
    fun versionIdentityIsFilledByCi() {
        assertTrue("versionCode must be positive", BuildConfig.VERSION_CODE > 0)
        assertTrue("versionName must not be blank", BuildConfig.VERSION_NAME.isNotBlank())
    }

    @Test
    fun timezoneAndCurrencyAreLocked() {
        assertEquals("Asia/Dhaka", BuildConfig.STORE_TIMEZONE)
        assertEquals("Asia/Dhaka", UndalBuildInfo.TIMEZONE_ID)
        assertEquals("BDT", BuildConfig.CURRENCY_SYMBOL)
        assertEquals(100L, UndalBuildInfo.PAISA_PER_TAKA)
    }

    @Test
    fun orderSoundResourceNameIsOfficial() {
        assertEquals("undal_order_alert", BuildConfig.ORDER_SOUND_NAME)
        assertEquals("undal_order_alert", UndalBuildInfo.orderSoundName)
    }

    @Test
    fun cloudinaryUploadsUseTheUnsignedPreset() {
        // The preset is created by hand in the Cloudinary console and arrives
        // through the CLOUDINARY_UPLOAD_PRESET secret, so its name is the
        // owner's to choose: the setup guide asks for `undal_unsigned`, and the
        // preset in this account is `bsdc_unsigned`. Both are accepted — what
        // matters is that an unsigned preset is configured at all, because a
        // preset is the only way this app uploads a photo without ever holding
        // an API secret.
        val preset = BuildConfig.CLOUDINARY_UPLOAD_PRESET
        assertTrue(
            "unexpected upload preset: $preset — create an unsigned preset in the Cloudinary " +
                "console and put its exact name in the CLOUDINARY_UPLOAD_PRESET secret",
            preset == "bsdc_unsigned" || preset == "undal_unsigned"
        )
        assertTrue("cloud name must not be blank", BuildConfig.CLOUDINARY_CLOUD_NAME.isNotBlank())
    }

    @Test
    fun workerUrlIsHttpsAndHasNoTrailingSlash() {
        assertTrue(BuildConfig.WORKER_BASE_URL.startsWith("https://"))
        assertFalse(BuildConfig.WORKER_BASE_URL.endsWith("/"))
    }

    @Test
    fun chatAndOrderLiveMountPointsAreStable() {
        // Mirrors the Realtime Database contract (PART G2) used by UndalApp.keepSynced.
        val live = UndalBuildInfo.shareUrl("dish/undal-special-kacchi")
        assertTrue(live.startsWith(BuildConfig.WORKER_BASE_URL))
        assertTrue(live.contains("/dish/"))
        assertFalse(live.endsWith("/"))
    }
}
