package com.undal.syl.app.features.auth

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSignInTest {
    @Test
    fun dismissingAccountChooserIsNotAnError() {
        assertNull(GoogleSignIn.errorMessage(GetCredentialCancellationException()))
    }

    @Test(expected = CancellationException::class)
    fun coroutineCancellationIsNotSwallowed() {
        GoogleSignIn.errorMessage(CancellationException("screen closed"))
    }

    @Test
    fun credentialFailureExplainsRecoveryWithoutLeakingProviderDetails() {
        val message = GoogleSignIn.errorMessage(NoCredentialException("private provider detail")).orEmpty()
        assertTrue(message.contains("Google Play services"))
        assertTrue(message.contains("signing fingerprints"))
        assertFalse(message.contains("private provider detail"))
    }

    @Test
    fun missingConfigurationHasAnActionableMessage() {
        assertEquals("Configure this build", GoogleSignIn.errorMessage(IllegalStateException("Configure this build")))
    }

    @Test
    fun unexpectedFailureDoesNotExposeTheCredential() {
        val message = GoogleSignIn.errorMessage(Exception("secret token value")).orEmpty()
        assertTrue(message.contains("email and password"))
        assertFalse(message.contains("secret token value"))
    }
}
