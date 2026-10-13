package com.undal.syl.app.features.legal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.undal.syl.app.core.config.UndalBuildInfo
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.features.vendor.VendorSection

/**
 * Privacy and policy pages that work with the radio off (P-031 · V-096).
 *
 * The text is bundled in the app instead of fetched from the web, because a
 * policy page that needs a connection is a policy page nobody can read when the
 * customer is standing at the door asking questions. The wording describes what
 * this app actually does — no template legalese.
 */
@Composable
fun PolicyScreen(onBack: () -> Unit) {
    UndalScaffold(
        title = "Privacy and policies",
        subtitle = "Readable offline · lasts as long as the app is installed",
        onBack = onBack
    ) { _ ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(UndalDimens.screenPadding)
        ) {
            item {
                UndalCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(UndalDimens.sm)
                    ) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(UndalDimens.iconMd)
                        )
                        Spacer(Modifier.width(UndalDimens.xs))
                        Text(
                            text = "Undal HomeKitchen is one kitchen in Sylhet taking its own orders. " +
                                "These pages describe how that app treats your information.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(UndalDimens.sm)) }

            item {
                VendorSection(
                    title = "What we collect",
                    subtitle = "Only what a delivery needs."
                ) {
                    PolicyBody(
                        listOf(
                            "Your name, so the kitchen knows who is calling and who is at the door.",
                            "Your phone number, so the rider can reach you and so the kitchen can call " +
                                "about an order.",
                            "Your delivery address, the door pin you place on the map, and any landmark " +
                                "note you add.",
                            "Your order history: dishes, amounts in taka, delivery fee, coupon used and " +
                                "the status changes of each order.",
                            "The device token Firebase uses to send you order notifications. It identifies " +
                                "the app install, not you.",
                            "A profile photo, only if you choose to upload one. Photos go to Cloudinary and " +
                                "the app keeps the resulting link."
                        )
                    )
                }
            }

            item { Spacer(Modifier.height(UndalDimens.sm)) }

            item {
                VendorSection(
                    title = "What we never do",
                    subtitle = "The short list matters more than the long one."
                ) {
                    PolicyBody(
                        listOf(
                            "We do not sell, rent or trade your details to anybody.",
                            "We do not take card or wallet payments. Undal is cash on delivery only, so " +
                                "the app never sees a card number, a PIN or an OTP.",
                            "We do not track your location in the background. The app reads your position " +
                                "when you place a pin or tap the locate button.",
                            "We do not send marketing notifications you did not ask for: the only pushes " +
                                "this app sends are about your own orders and, if the kitchen enables it, " +
                                "store offers."
                        )
                    )
                }
            }

            item { Spacer(Modifier.height(UndalDimens.sm)) }

            item {
                VendorSection(
                    title = "Where the data lives",
                    subtitle = "Firebase Spark and Cloudinary free tier — the same stack that runs the store."
                ) {
                    PolicyBody(
                        listOf(
                            "Accounts, orders, the menu and the store document live in Google Cloud " +
                                "Firestore under the project this app was built for.",
                            "Chat messages and the live order status live in Firebase Realtime Database, " +
                                "so a status change reaches your screen in under a second.",
                            "Photos live in Cloudinary, with the file link stored alongside the record that " +
                                "uses it.",
                            "Map tiles are standard OpenStreetMap tiles. No Google Maps API key is used " +
                                "anywhere in this app, and no location data is sent to Google."
                        )
                    )
                }
            }

            item { Spacer(Modifier.height(UndalDimens.sm)) }

            item {
                VendorSection(
                    title = "Your choices",
                    subtitle = "What you can ask for, and how."
                ) {
                    PolicyBody(
                        listOf(
                            "Order notifications can be switched off in Android settings. The app still " +
                                "shows every status inside the order page.",
                            "You can change your saved address, name and photo at any time from the " +
                                "profile screen.",
                            "You can ask the kitchen to delete your account and your order history. Deleting " +
                                "the account removes the sign-in and the profile; completed order records " +
                                "may be kept for the kitchen's own accounts of the day.",
                            "Chat messages stay with the order they belong to. Deleting your account does " +
                                "not silently delete the kitchen's copy of the order it cooked."
                        )
                    )
                }
            }

            item { Spacer(Modifier.height(UndalDimens.sm)) }

            item {
                VendorSection(
                    title = "Cash on delivery, cancellations and refunds",
                    subtitle = "No money moves inside this app."
                ) {
                    PolicyBody(
                        listOf(
                            "You pay the rider in cash, in taka, when the food arrives. The app shows the " +
                                "exact amount before you place the order.",
                            "If the kitchen cannot cook your order it cancels it with a written reason, and " +
                                "nothing is charged because nothing was taken.",
                            "A customer cannot cancel an order from the app. Call the kitchen — the number " +
                                "is on every order screen — and the owner decides, because the food may " +
                                "already be on the stove.",
                            "If the food arrives wrong or cold, call the kitchen. Refunds are handled by " +
                                "the kitchen as a cash matter; there is no online balance to refund."
                        )
                    )
                }
            }

            item { Spacer(Modifier.height(UndalDimens.sm)) }

            item {
                VendorSection(
                    title = "Reaching a person",
                    subtitle = "One kitchen, one phone, one number."
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Phone,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(UndalDimens.iconMd)
                            )
                            Spacer(Modifier.width(UndalDimens.xs))
                            Text(
                                text = UndalBuildInfo.supportPhone.ifBlank {
                                    "The kitchen number is printed on the store page."
                                },
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        Spacer(Modifier.height(UndalDimens.xs))
                        Text(
                            text = "Undal HomeKitchen · Sylhet, Bangladesh · built with Undal " +
                                UndalBuildInfo.versionName + " (build " + UndalBuildInfo.versionCode + ").",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(UndalDimens.xl)) }
        }
    }
}

@Composable
private fun PolicyBody(paragraphs: List<String>) {
    Column {
        paragraphs.forEach { paragraph ->
            Text(
                text = "•  $paragraph",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = UndalDimens.xs)
            )
        }
    }
}
