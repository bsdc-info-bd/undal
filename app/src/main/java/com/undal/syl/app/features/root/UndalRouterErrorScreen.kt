package com.undal.syl.app.features.root

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.undal.syl.app.core.design.UndalPrimaryButton
import com.undal.syl.app.core.design.UndalTextButton
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.theme.UndalDimens

/**
 * The screen for the one thing the router cannot decide: where this phone
 * belongs.
 *
 * It exists because the alternative was a crash. A device that signed in while
 * the connection was failing used to have its router give up in a coroutine,
 * which on Android is the process closing and the person landing on their home
 * screen with the app gone. Now the same failure arrives here, in words, with a
 * retry button — and the account on the phone is untouched, so the retry is the
 * whole fix.
 */
@Composable
fun UndalRouterErrorScreen(
    message: String,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    onOpenSupport: () -> Unit
) {
    UndalScaffold(title = "Open your account", contentMaxWidth = 600.dp) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(UndalDimens.screenPadding)
        ) {
            UndalCard {
                Text("We could not finish opening your account", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(UndalDimens.md))
                UndalPrimaryButton("Try again", onClick = onRetry)
                UndalTextButton("Delivery, payment and privacy", onClick = onOpenSupport, modifier = Modifier.fillMaxWidth())
                UndalTextButton("Sign out instead", onClick = onSignOut, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(UndalDimens.sm))
            UndalCard {
                Text("While this is on screen", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "We could not confirm where this account should open. Check your connection and try again. " +
                        "Retrying does not place an order or change your saved delivery details.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
