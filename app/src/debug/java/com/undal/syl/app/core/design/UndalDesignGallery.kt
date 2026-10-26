package com.undal.syl.app.core.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.theme.UndalTheme

/** Android Studio specimens only: no Firebase, network or production routes. */
@Composable
private fun DesignGallery(dark: Boolean = false) {
    UndalTheme(darkTheme = dark) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(UndalDimens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(UndalDimens.lg)
            ) {
                UndalPageIntro("UNDAL · DESIGN SPECIMEN", "A little taste of home.",
                    "Warm surfaces. Clear actions. Room to breathe.")
                UndalAdaptivePair(
                    primary = {
                        UndalCard {
                            UndalSectionHeader("Your meal", subtitle = "Sample component content")
                            Text("A name that can wrap without losing the price", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(UndalDimens.sm))
                            UndalTextField("", {}, "Cooking note", helper = "Optional · the kitchen reads this.")
                        }
                    },
                    secondary = {
                        UndalCard {
                            UndalSectionHeader("Your receipt")
                            UndalKeyValueRow("Food subtotal", "৳240.00")
                            UndalKeyValueRow("Delivery", "৳25.00")
                            UndalKeyValueRow("Total", "৳265.00", emphasise = true)
                            Spacer(Modifier.height(UndalDimens.sm))
                            UndalPrimaryButton("Review order · cash on delivery", {})
                            UndalSecondaryButton("Change delivery address", {})
                        }
                    }
                )
                UndalCard {
                    UndalSectionHeader("Account actions", subtitle = "Sample responsive button group")
                    UndalActionGroup(primary = { UndalSecondaryButton("Your orders", {}) },
                        secondary = { UndalSecondaryButton("Notifications", {}) })
                }
                UndalCard {
                    UndalSectionHeader("Kitchen next action", subtitle = "Specimen only · not a real order")
                    UndalPrimaryButton("Accept · choose prep time", {})
                }
                UndalCard {
                    UndalSectionHeader("Message composer", subtitle = "Specimen only · no message is sent")
                    UndalMessageComposer(draft = "Your order is being packed.", onDraftChange = {},
                        onSend = {}, sending = false)
                }
                UndalAlertBanner("Example recovery state: your changes have not been saved. Try again.",
                    containerColour = MaterialTheme.colorScheme.errorContainer,
                    contentColour = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Preview(name = "Compact · warm kitchen", widthDp = 360, heightDp = 800)
@Composable
private fun CompactGallery() = DesignGallery()

@Preview(name = "Large text · stacked", widthDp = 360, heightDp = 800, fontScale = 2f)
@Composable
private fun LargeTextGallery() = DesignGallery()

@Preview(name = "Expanded · paired", widthDp = 1024, heightDp = 800)
@Composable
private fun ExpandedGallery() = DesignGallery()

@Preview(name = "Dark · clear contrast", widthDp = 360, heightDp = 800)
@Composable
private fun DarkGallery() = DesignGallery(dark = true)
