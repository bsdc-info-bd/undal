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
    fun cloudinaryIsEitherConfiguredOrHonestlyNotConfigured() {
        // The preset is created by hand in the Cloudinary console and arrives
        // through the CLOUDINARY_UPLOAD_PRESET secret, so its name is the
        // owner's to choose: the setup guide asks for `undal_unsigned`, and the
        // preset in this account is `bsdc_unsigned`.
        //
        // The contract this test pins is the honest one, in both directions: a
        // build with both values reports uploads as available, and a build
        // missing either of them must report the opposite — never a fake cloud
        // name that would post a customer's photo into a stranger's account.
        val cloud = BuildConfig.CLOUDINARY_CLOUD_NAME
        val preset = BuildConfig.CLOUDINARY_UPLOAD_PRESET
        if (cloud.isBlank() || preset.isBlank()) {
            assertFalse(
                "a build without a cloud name and preset must not claim uploads work",
                UndalBuildInfo.isCloudinaryConfigured
            )
        } else {
            assertTrue("both values present means uploads work", UndalBuildInfo.isCloudinaryConfigured)
            assertTrue(
                "unexpected upload preset: $preset — create an unsigned preset in the Cloudinary " +
                    "console and put its exact name in the CLOUDINARY_UPLOAD_PRESET secret",
                preset == "bsdc_unsigned" || preset == "undal_unsigned"
            )
        }
    }

    @Test
    fun workerUrlIsHttpsAndNeverAPlaceholder() {
        val url = BuildConfig.WORKER_BASE_URL
        // A build without the owner's host carries an empty address on purpose:
        // every screen that needs one says so instead of dialling a host that
        // does not exist. What must never happen is a *fake* address — the
        // documentation's example, or http://.
        if (url.isNotEmpty()) {
            assertTrue("the Worker address must be https", url.startsWith("https://"))
            assertFalse("the Worker address must not end with a slash", url.endsWith("/"))
            assertFalse("a placeholder address is not an address", url.contains("YOUR-SUBDOMAIN"))
        }
        assertFalse(
            "a build that claims to be configured must have an address",
            UndalBuildInfo.isWorkerConfigured && url.isBlank()
        )
    }

    @Test
    fun sharedLinksAreNeverBuiltOnAPlaceholderHost() {
        // Mirrors the deep-link contract (PART N): every link that can leave the
        // app — the dish share, the store share, the verification e-mail's
        // continue URL — goes through these two accessors, and both answer
        // honestly when the build has no host to point at.
        val link = UndalBuildInfo.shareUrlOrNull("dish/undal-special-kacchi")
        val host = UndalBuildInfo.webStoreHost
        if (UndalBuildInfo.isWorkerConfigured) {
            assertTrue("a configured build shares a link", link != null && link.isNotEmpty())
            assertTrue("the link is on the configured host", link!!.startsWith(BuildConfig.WORKER_BASE_URL))
            assertTrue("the link is a dish link", link.contains("/dish/"))
            assertFalse("the link does not end in a slash", link.endsWith("/"))
            assertTrue("the store host is reported", !host.isNullOrBlank())
        } else {
            assertEquals("no host means no link", null, link)
            assertEquals("no host means no host", null, host)
        }
        assertFalse("no link may name the placeholder host", link?.contains("YOUR-SUBDOMAIN") == true)
        assertFalse("no host may name the placeholder host", host?.contains("YOUR-SUBDOMAIN") == true)
    }
}
