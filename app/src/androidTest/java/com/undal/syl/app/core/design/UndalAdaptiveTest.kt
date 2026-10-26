package com.undal.syl.app.core.design

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.undal.syl.app.core.theme.UndalTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class UndalAdaptiveTest {
    @get:Rule val compose = createComposeRule()

    private fun verifyPair(fontScale: Float, split: Boolean) {
        var primary = Offset.Zero
        var secondary = Offset.Zero
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                UndalTheme {
                    Box(Modifier.requiredWidth(900.dp)) {
                        UndalAdaptivePair(
                            primary = { Box(Modifier.size(40.dp).onGloballyPositioned { primary = it.positionInRoot() }) },
                            secondary = { Box(Modifier.size(40.dp).onGloballyPositioned { secondary = it.positionInRoot() }) }
                        )
                    }
                }
            }
        }
        compose.runOnIdle {
            if (split) assertTrue("Expanded layout should put panels beside one another", secondary.x > primary.x)
            else assertTrue("Large text should stack even in a wide window", secondary.y > primary.y)
        }
    }

    @Test fun expandedPanelsUseTwoColumns() = verifyPair(fontScale = 1f, split = true)
    @Test fun largeTextForcesAReadableStack() = verifyPair(fontScale = 2f, split = false)
    private fun verifyActions(width: Int, fontScale: Float, inline: Boolean) {
        var primary = Offset.Zero
        var secondary = Offset.Zero
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                UndalTheme {
                    Box(Modifier.requiredWidth(width.dp)) {
                        UndalActionGroup(
                            primary = { Box(Modifier.size(40.dp).onGloballyPositioned { primary = it.positionInRoot() }) },
                            secondary = { Box(Modifier.size(40.dp).onGloballyPositioned { secondary = it.positionInRoot() }) }
                        )
                    }
                }
            }
        }
        compose.runOnIdle {
            if (inline) assertTrue("Buttons should share the row", secondary.x > primary.x && secondary.y == primary.y)
            else assertTrue("Buttons need a readable vertical stack", secondary.y > primary.y)
        }
    }

    @Test fun actionPairStacksInNarrowContainer() = verifyActions(320, 1f, inline = false)
    @Test fun actionPairSharesComfortableWidth() = verifyActions(400, 1f, inline = true)
    @Test fun actionPairStacksForLargeText() = verifyActions(400, 2f, inline = false)

    @Test fun singleActionKeepsTheFullAvailableWidth() {
        var parentWidth = 0
        var actionWidth = 0
        compose.setContent {
            UndalTheme {
                Box(Modifier.requiredWidth(400.dp).onGloballyPositioned { parentWidth = it.size.width }) {
                    UndalActionGroup(primary = {
                        Box(Modifier.fillMaxWidth().height(40.dp).onGloballyPositioned { actionWidth = it.size.width })
                    })
                }
            }
        }
        compose.runOnIdle {
            assertTrue("A missing secondary action must not reserve half a row", parentWidth > 0 && actionWidth == parentWidth)
        }
    }

}
