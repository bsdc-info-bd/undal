package com.undal.syl.app.features.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSetupStateTest {
    @Test fun defaultMapPointIsNotAConfirmedDeliveryAddress() {
        assertFalse(LocationSetupViewModel.State().canSaveAddress)
    }
    @Test fun explicitConfirmationMakesIdleAddressEligibleForValidation() {
        assertTrue(LocationSetupViewModel.State(pinConfirmed = true).canSaveAddress)
    }
    @Test fun locationInFlightPreventsSubmissionEvenWithConfirmation() {
        assertFalse(LocationSetupViewModel.State(pinConfirmed = true, locating = true).canSaveAddress)
    }
    @Test fun pendingSavePreventsDuplicateSubmission() {
        assertFalse(LocationSetupViewModel.State(pinConfirmed = true, busy = true).canSaveAddress)
    }
}
