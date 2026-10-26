package com.undal.syl.app.features.portal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import com.undal.syl.app.core.config.UndalBuildInfo
import com.undal.syl.app.core.design.UndalPageIntro
import com.undal.syl.app.core.design.UndalSecondaryButton
import com.undal.syl.app.core.util.UndalPhone
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalKeyValueRow
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.theme.UndalDimens

/**
 * The customer-facing "delivery, payment and privacy" explainer (C-080 era).
 *
 * It is deliberately plain: what delivery costs, that cash is the only payment,
 * and exactly which facts about the customer the app stores. Every sentence is
 * true of the code in this repository.
 */
@Composable
fun SupportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    UndalScaffold(title = "Good to know", onBack = onBack, contentMaxWidth = 760.dp) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(UndalDimens.screenPadding)
        ) {
            UndalPageIntro("UNDAL · HELP", "Good food. Clear answers.",
                "Delivery, cash payment and your privacy — explained before you order.")
            Spacer(Modifier.height(UndalDimens.lg))
            UndalCard {
                Text("How delivery works", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "One kitchen in Sylhet delivers itself. The fee is calculated per kilometre from " +
                        "the kitchen, rounded up to whole kilometres, and you see the exact amount before " +
                        "you place the order. Outside our radius we cannot deliver, and the app tells you " +
                        "that plainly instead of taking an order we cannot serve.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(UndalDimens.sm))
            UndalCard {
                Text("Payment", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "Cash on delivery only. There is no card, no wallet and no online payment in " +
                        "Undal, so nobody ever asks you for card details. You pay the rider when the food " +
                        "reaches your door, and the amount is fixed at the moment you order.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(UndalDimens.sm))
            UndalCard {
                Text("Your data", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "Undal stores your name, email, phone number and the one delivery address you " +
                        "pinned. The address is used to check the delivery radius, to calculate the fee and " +
                        "to show the rider where to go. The app does not track your location in the " +
                        "background — it only reads it when you press the location button, and never after " +
                        "you close the app.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(UndalDimens.sm))
                Text(
                    text = "You can delete your account and every document attached to it from the profile " +
                        "screen. Order records without your account are kept only as the kitchen's sales " +
                        "history.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(UndalDimens.sm))
            UndalCard {
                Text("Cancelling", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "Once you place an order, only the kitchen can cancel it — because the cooking has " +
                        "already been planned around it. Tell the kitchen in the order chat and they will " +
                        "cancel with a note you can read.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(UndalDimens.sm))
            UndalCard {
                Text("Contact the kitchen", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                UndalKeyValueRow(
                    "Support number",
                    UndalBuildInfo.supportPhone.ifBlank { "Phone support is not configured" }
                )
                if (UndalBuildInfo.supportPhone.isNotBlank()) {
                    Spacer(Modifier.height(UndalDimens.sm))
                    UndalSecondaryButton("Call the kitchen", onClick = { UndalPhone.dial(context, UndalBuildInfo.supportPhone) })
                }
                UndalKeyValueRow("App version", UndalBuildInfo.versionName)
            }
            Spacer(Modifier.height(UndalDimens.xl))
        }
    }
}
