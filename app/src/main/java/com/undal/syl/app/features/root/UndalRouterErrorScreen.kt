package com.undal.syl.app.features.root

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    UndalScaffold(title = "Undal HomeKitchen") { _ ->
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
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text("Try again")
                }
                TextButton(onClick = onOpenSupport, modifier = Modifier.fillMaxWidth()) {
                    Text("What delivery, payment and privacy mean here")
                }
                TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign out instead")
                }
            }
            Spacer(Modifier.height(UndalDimens.sm))
            UndalCard {
                Text("While this is on screen", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "Nothing has been changed and nothing has been lost: you are still signed in, " +
                        "the kitchen still has every order it had, and your basket is still on this phone. " +
                        "Tap Try again once the connection is back — a single tap is normally all it takes.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
