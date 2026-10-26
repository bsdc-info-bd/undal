package com.undal.syl.app.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UndalLayoutPolicyTest {
    @Test fun compactAndMediumWindowsStack() {
        for (width in listOf(320f, 360f, 412f, 600f, 839f)) {
            assertFalse(UndalLayoutPolicy.useSplit(width, 1f))
        }
    }
    @Test fun expandedWindowsCanSplit() {
        assertTrue(UndalLayoutPolicy.useSplit(840f, 1f))
        assertTrue(UndalLayoutPolicy.useSplit(1100f, 1.3f))
    }
    @Test fun largeTextOverridesWideWindow() {
        assertFalse(UndalLayoutPolicy.useSplit(1100f, 1.31f))
        assertFalse(UndalLayoutPolicy.useSplit(1100f, 2f))
    }
    @Test fun invalidMeasurementsFailToSimpleLayout() {
        assertFalse(UndalLayoutPolicy.useSplit(Float.NaN, 1f))
        assertFalse(UndalLayoutPolicy.useSplit(840f, Float.POSITIVE_INFINITY))
        assertFalse(UndalLayoutPolicy.useSplit(-1f, 1f))
        assertFalse(UndalLayoutPolicy.useSplit(840f, 0f))
    }
    @Test fun menuCardsGrowWithText() {
        assertEquals(148f, UndalLayoutPolicy.menuCardWidth(360f, 1f), 0.01f)
        assertEquals(296f, UndalLayoutPolicy.menuCardWidth(360f, 2f), 0.01f)
        assertEquals(220f, UndalLayoutPolicy.menuCardWidth(840f, 1f), 0.01f)
        assertEquals(440f, UndalLayoutPolicy.menuCardWidth(840f, 2f), 0.01f)
    }
    @Test fun smallOrInvalidFontScaleNeverMakesCardsTiny() {
        assertEquals(148f, UndalLayoutPolicy.menuCardWidth(360f, 0.8f), 0.01f)
        assertEquals(148f, UndalLayoutPolicy.menuCardWidth(360f, Float.NaN), 0.01f)
    }
    @Test fun twoCardsFitStandardPhoneButNotSmallestPhone() {
        val card = UndalLayoutPolicy.menuCardWidth(360f, 1f)
        assertTrue(2 * card + 12 <= 360 - 2 * 20)
        assertTrue(2 * card + 12 > 320 - 2 * 20)
    }
    @Test fun actionPairsUseTheirActualContainerWidth() {
        assertFalse(UndalLayoutPolicy.useInlineActions(320f, 1f))
        assertFalse(UndalLayoutPolicy.useInlineActions(359f, 1f))
        assertTrue(UndalLayoutPolicy.useInlineActions(360f, 1f))
        assertTrue(UndalLayoutPolicy.useInlineActions(600f, 1.3f))
    }
    @Test fun actionPairsStackAtLargeTextAndInvalidMeasurements() {
        assertFalse(UndalLayoutPolicy.useInlineActions(600f, 1.31f))
        assertFalse(UndalLayoutPolicy.useInlineActions(600f, 2f))
        assertFalse(UndalLayoutPolicy.useInlineActions(Float.POSITIVE_INFINITY, 1f))
        assertFalse(UndalLayoutPolicy.useInlineActions(600f, Float.NaN))
        assertFalse(UndalLayoutPolicy.useInlineActions(600f, 0f))
    }

    @Test fun composerKeepsComfortableSpaceForTheMessageField() {
        assertFalse(UndalLayoutPolicy.useInlineComposer(320f, 1f))
        assertFalse(UndalLayoutPolicy.useInlineComposer(479f, 1f))
        assertTrue(UndalLayoutPolicy.useInlineComposer(480f, 1f))
        assertTrue(UndalLayoutPolicy.useInlineComposer(760f, 1.3f))
    }
    @Test fun composerStacksAtLargeTextAndInvalidMeasurements() {
        assertFalse(UndalLayoutPolicy.useInlineComposer(760f, 2f))
        assertFalse(UndalLayoutPolicy.useInlineComposer(Float.POSITIVE_INFINITY, 1f))
        assertFalse(UndalLayoutPolicy.useInlineComposer(760f, Float.NaN))
    }

}
