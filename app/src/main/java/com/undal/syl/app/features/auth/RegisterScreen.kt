package com.undal.syl.app.features.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import com.undal.syl.app.core.design.PasswordStrengthMeter
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalPasswordField
import com.undal.syl.app.core.design.UndalPrimaryButton
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.design.UndalTextField
import com.undal.syl.app.core.design.UndalTextButton
import com.undal.syl.app.core.theme.UndalDimens

/**
 * Customer registration (C-003 … C-008).
 *
 * The account is created here; the address is pinned on the very next screen and
 * the profile document is written only once that address is complete. That keeps
 * the promise the rules enforce: a customer row in the database always has a real
 * delivery address attached.
 */
@Composable
fun RegisterScreen(
    onRegistered: () -> Unit,
    onBackToLogin: () -> Unit,
    viewModel: RegisterViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    UndalScaffold(
        title = "Create your account",
        subtitle = "Two steps: account details, then your delivery pin",
        onBack = onBackToLogin
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(UndalDimens.screenPadding)
        ) {
            if (state.banner != null) {
                UndalAlertBanner(
                    text = state.banner.orEmpty(),
                    icon = Icons.Filled.Info,
                    containerColour = MaterialTheme.colorScheme.errorContainer,
                    contentColour = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(UndalDimens.md))
            }

            UndalCard {
                Text("Your details", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.md))
                UndalTextField(
                    value = state.name,
                    onValueChange = viewModel::onNameChange,
                    label = "Full name",
                    error = state.nameError,
                    helper = "The kitchen and the rider use this name.",
                    leadingIcon = Icons.Filled.Person,
                    imeAction = ImeAction.Next,
                    enabled = !state.busy
                )
                Spacer(Modifier.height(UndalDimens.md))
                UndalTextField(
                    value = state.email,
                    onValueChange = viewModel::onEmailChange,
                    label = "Email address",
                    error = state.emailError,
                    keyboardType = KeyboardType.Email,
                    leadingIcon = Icons.Filled.Email,
                    imeAction = ImeAction.Next,
                    enabled = !state.busy
                )
                Spacer(Modifier.height(UndalDimens.md))
                UndalTextField(
                    value = state.phone,
                    onValueChange = viewModel::onPhoneChange,
                    label = "Mobile number",
                    error = state.phoneError,
                    helper = "11 digits, like 01712345678. The rider calls this number.",
                    keyboardType = KeyboardType.Phone,
                    leadingIcon = Icons.Filled.Phone,
                    imeAction = ImeAction.Next,
                    enabled = !state.busy
                )
            }

            Spacer(Modifier.height(UndalDimens.md))

            UndalCard {
                Text("Password", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.md))
                UndalPasswordField(
                    value = state.password,
                    onValueChange = viewModel::onPasswordChange,
                    error = state.passwordError,
                    helper = "At least 6 characters, with a letter and a number.",
                    imeAction = ImeAction.Next,
                    onImeAction = { }
                )
                PasswordStrengthMeter(password = state.password)
                Spacer(Modifier.height(UndalDimens.md))
                UndalPasswordField(
                    value = state.confirmPassword,
                    onValueChange = viewModel::onConfirmChange,
                    label = "Confirm password",
                    error = state.confirmError,
                    imeAction = ImeAction.Done,
                    onImeAction = { viewModel.submit(onRegistered) }
                )
            }

            Spacer(Modifier.height(UndalDimens.md))

            UndalAlertBanner(
                text = "Next step: pin your delivery address. The kitchen needs a real map point to " +
                    "calculate the delivery fee and to know it can reach you.",
                icon = Icons.Filled.Info
            )

            Spacer(Modifier.height(UndalDimens.lg))

            UndalPrimaryButton(
                text = "Create account and pin my address",
                onClick = { viewModel.submit(onRegistered) },
                loading = state.busy,
                icon = Icons.Filled.Add
            )

            Spacer(Modifier.height(UndalDimens.sm))

            UndalTextButton(
                text = "I already have an account",
                onClick = onBackToLogin,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(UndalDimens.xl))
        }
    }
}
