package com.undal.syl.app.core.design

import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp

/**
 * Accessibility helpers (P-044).
 *
 * Three things make this app usable by somebody who cannot see it:
 *
 *  1. **A reachable target.** Nothing a finger must hit is smaller than 48 dp,
 *     whether it is a button, a chip or a quantity stepper — the kitchen taps
 *     these with a thumb while holding a pan.
 *  2. **A described state.** Colour alone never carries meaning: a timeline step
 *     that is "done" or "happening now" says so in words to TalkBack, and so does
 *     a countdown ring.
 *  3. **An order that matches the screen.** `undalOrdered` pins the reading order
 *     of a card (title, then price, then actions) regardless of how the layout is
 *     nested, which is what a screen reader follows.
 */
fun Modifier.undalTouchTarget(): Modifier =
    this.minimumInteractiveComponentSize().then(Modifier)

/** Pins this element's place in the TalkBack reading order. */
fun Modifier.undalOrdered(index: Int): Modifier = this.semantics { traversalIndex = index.toFloat() }

/** Says the state in words: "accepted, happening now", "waiting". */
fun Modifier.undalStateDescription(text: String): Modifier =
    this.semantics { stateDescription = text }

/** Marks a text as a heading so TalkBack can jump between sections. */
fun Modifier.undalHeading(): Modifier = this.semantics { heading() }

/** Minimum target size in dp, for documentation and for custom hit areas. */
const val UNDAL_MIN_TOUCH_DP: Int = 48

/** The dp value as a unit, so callers do not import `dp` for one screen. */
val UndalTouchTargetDp = 48.dp
