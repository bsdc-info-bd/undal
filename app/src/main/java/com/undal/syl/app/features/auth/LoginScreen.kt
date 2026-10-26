package com.undal.syl.app.features.auth

import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.undal.syl.app.R
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalPrimaryButton
import com.undal.syl.app.core.design.UndalSecondaryButton
import com.undal.syl.app.core.design.UndalTextButton
import com.undal.syl.app.core.design.UndalTextField
import com.undal.syl.app.core.design.UndalPasswordField
import com.undal.syl.app.core.theme.UndalDimens

/**
 * One door for both portals (C-020 · V-001).
 *
 * There is no role switch on this screen on purpose: the portal a person lands in
 * is decided by the `vendor` claim on their account, so nothing about the UI can
 * be used to reach the kitchen console.
 */
@Composable
fun LoginScreen(
    onSignedIn: () -> Unit,
    onGoToRegister: () -> Unit,
    onGoToSupport: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(UndalDimens.screenPadding)
        ) {
            Spacer(Modifier.height(UndalDimens.lg))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_undal_mark),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(UndalDimens.md))
                Text("Make yourself at home.", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "Home-cooked food from our Sylhet kitchen, delivered cash on delivery.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(UndalDimens.xl))

            if (state.banner != null) {
                UndalAlertBanner(
                    text = state.banner.orEmpty(),
                    icon = Icons.Filled.Info,
                    containerColour = if (state.bannerIsError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    contentColour = if (state.bannerIsError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    }
                )
                Spacer(Modifier.height(UndalDimens.md))
            }

            UndalSecondaryButton(
                text = "Continue with Google",
                onClick = { viewModel.signInWithGoogle(context, onSignedIn) },
                enabled = !state.busy,
                loading = state.googleBusy
            )
            Spacer(Modifier.height(UndalDimens.sm))
            Text("or sign in with email", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(UndalDimens.md))

            UndalCard {
                Text("Sign in", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.md))
                UndalTextField(
                    value = state.email,
                    onValueChange = viewModel::onEmailChange,
                    label = "Email address",
                    error = state.emailError,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                    leadingIcon = Icons.Filled.Email,
                    enabled = !state.busy
                )
                Spacer(Modifier.height(UndalDimens.md))
                UndalPasswordField(
                    value = state.password,
                    onValueChange = viewModel::onPasswordChange,
                    error = state.passwordError,
                    imeAction = ImeAction.Done,
                    onImeAction = { viewModel.submit(onSignedIn) }
                )
                Spacer(Modifier.height(UndalDimens.lg))
                UndalPrimaryButton(
                    text = "Sign in",
                    onClick = { viewModel.submit(onSignedIn) },
                    loading = state.busy && !state.googleBusy,
                    enabled = !state.busy,
                    icon = Icons.Filled.Lock
                )
                Spacer(Modifier.height(UndalDimens.sm))
                UndalTextButton(
                    text = "Forgot your password?",
                    onClick = viewModel::resetPassword,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(UndalDimens.lg))

            UndalCard {
                Text("New to Undal?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "Create an account and pin your delivery address once — after that ordering takes four taps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(UndalDimens.md))
                UndalSecondaryButton(text = "Create an account", onClick = onGoToRegister, enabled = !state.busy)
            }

            Spacer(Modifier.height(UndalDimens.lg))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                UndalTextButton(text = "Delivery, payment and privacy info", onClick = onGoToSupport)
                Spacer(Modifier.height(UndalDimens.lg))
            }
        }
    }
}
