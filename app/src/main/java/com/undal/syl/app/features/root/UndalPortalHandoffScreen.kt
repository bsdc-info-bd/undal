package com.undal.syl.app.features.root

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalPrimaryButton
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.design.UndalTextButton
import com.undal.syl.app.core.session.UndalRole
import com.undal.syl.app.core.theme.UndalDimens

/**
 * A valid account in the wrong APK is not a router failure. Keep the two
 * portals separate, open the installed counterpart when possible, and otherwise
 * take the person to the project's real release asset. No account or branch
 * data is sent to the other app by this handoff.
 */
@Composable
fun UndalPortalHandoffScreen(
    accountRole: UndalRole,
    onSignOut: () -> Unit,
    onOpenSupport: () -> Unit,
) {
    val context = LocalContext.current
    val needsVendor = accountRole == UndalRole.VENDOR
    val portalName = if (needsVendor) "Undal Vendor" else "Undal Customer"
    val packageName = if (needsVendor) "com.undal.syl.vendor" else "com.undal.syl.app"
    val releaseAsset = if (needsVendor) "undal-vendor.apk" else "undal.apk"

    UndalScaffold(title = "Open the other Undal app", contentMaxWidth = 600.dp) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(UndalDimens.screenPadding)
        ) {
            UndalCard {
                Text(
                    text = if (needsVendor) "This is a kitchen staff account" else "This is a customer account",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = if (needsVendor) {
                        "Branch controls and live kitchen orders are only in Undal Vendor. " +
                            "This customer app will not show them. Open or install the vendor app to continue."
                    } else {
                        "Shopping, addresses and customer orders belong in Undal Customer. " +
                            "Open or install the customer app to continue. Kitchen staff can still preview the shop after opening the vendor console."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(UndalDimens.md))
                UndalPrimaryButton("Open or install $portalName", onClick = {
                    val installed = context.packageManager.getLaunchIntentForPackage(packageName)
                    val download = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/bsdc-info-bd/undal/releases/latest/download/$releaseAsset"),
                    )
                    val intent = installed ?: download
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(
                            context,
                            "No browser is available. Download $releaseAsset from the Undal GitHub releases page.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                })
                UndalTextButton("Delivery, payment and privacy", onClick = onOpenSupport, modifier = Modifier.fillMaxWidth())
                UndalTextButton("Sign out instead", onClick = onSignOut, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
