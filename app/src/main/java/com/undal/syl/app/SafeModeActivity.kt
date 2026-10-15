package com.undal.syl.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.undal.syl.app.core.config.UndalBuildInfo
import com.undal.syl.app.core.util.UndalCrashGuard

/**
 * The screen Undal shows when it cannot start.
 *
 * It exists because of what used to happen instead. The crash guard reopened the
 * app, the app crashed again on the way in, the guard reopened it again, and
 * after the third time the guard gave up and let Android close the process — so
 * the person who had tapped an icon ended up back on their home screen with no
 * idea whether their orders were safe. That is the report this app kept getting:
 * "it reopens and reopens and then it crashes."
 *
 * Three rules keep this screen from becoming part of the problem:
 *
 *  1. **It depends on nothing.** No Compose, no Hilt, no Firebase, no repository
 *     and no theme attribute beyond the window background — a plain `Activity`
 *     with its views built in code. Whatever broke on the way into the app cannot
 *     break here, because nothing here is what broke.
 *  2. **It tells the truth.** The exception the guard caught is on screen, in
 *     words, and it can be copied and sent. A crash the owner can read is a crash
 *     that gets fixed; a crash that only ever lived in a logcat nobody captured
 *     is not.
 *  3. **It always offers a way back.** Trying again is one tap, and the kitchen's
 *     phone number is another, because a person standing outside a shop holding a
 *     phone that will not open still needs to be able to order.
 */
class SafeModeActivity : Activity() {

    private var report: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        report = UndalCrashGuard.lastReport(this) ?: NO_REPORT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
        }

        root.addView(
            TextView(this).apply {
                text = getString(R.string.safe_mode_title)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(COLOR_INK)
            }
        )

        root.addView(
            TextView(this).apply {
                text = getString(R.string.safe_mode_body)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(COLOR_INK)
                setPadding(0, dp(10), 0, dp(18))
                setLineSpacing(0f, 1.25f)
            }
        )

        root.addView(
            TextView(this).apply {
                text = getString(R.string.safe_mode_report_heading)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(COLOR_MUTED)
            }
        )

        root.addView(
            TextView(this).apply {
                text = report
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
                setTextColor(COLOR_INK)
                setBackgroundColor(COLOR_PANEL)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setTextIsSelectable(true)
                layoutParams = fullWidth().apply { topMargin = dp(6); bottomMargin = dp(20) }
            }
        )

        root.addView(button(getString(R.string.safe_mode_try_again)) { tryAgain() }, row())
        root.addView(button(getString(R.string.safe_mode_copy)) { copyReport() }, row())
        root.addView(button(getString(R.string.safe_mode_call)) { callTheKitchen() }, row())

        root.addView(
            TextView(this).apply {
                text = getString(R.string.safe_mode_footnote)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(COLOR_MUTED)
                setPadding(0, dp(16), 0, 0)
                setLineSpacing(0f, 1.2f)
            }
        )

        setContentView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(
                    root,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        )
    }

    /**
     * Opens Undal again, in a clean task.
     *
     * The crash budget is deliberately *not* reset here: if this attempt fails the
     * same way, the guard has to be able to notice that and stop offering. Only the
     * report is cleared, so a later failure is never shown next to this one.
     */
    private fun tryAgain() {
        UndalCrashGuard.clearLastReport(this)
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
        }.onFailure { error ->
            toast(getString(R.string.safe_mode_retry_refused))
            Log.e(TAG, "the retry could not be started", error)
        }
    }

    private fun copyReport() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            toast(getString(R.string.safe_mode_copy_refused))
            return
        }
        runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, report))
            toast(getString(R.string.safe_mode_copied))
        }.onFailure { toast(getString(R.string.safe_mode_copy_refused)) }
    }

    /** `ACTION_DIAL`, never `ACTION_CALL`: no call permission, no surprise charge. */
    private fun callTheKitchen() {
        val number = UndalBuildInfo.supportPhone
        if (number.isBlank()) {
            toast(getString(R.string.safe_mode_no_phone))
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
        }.onFailure { toast(getString(R.string.safe_mode_no_phone)) }
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { runCatching { onClick() } }
        }

    private fun row(): LinearLayout.LayoutParams = fullWidth().apply { bottomMargin = dp(4) }

    private fun fullWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

    private fun toast(message: String) {
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "UndalSafeMode"
        const val CLIP_LABEL = "Undal crash report"

        /** The report is read back to the person, so a missing one has to say so. */
        const val NO_REPORT = "Undal could not read back what went wrong. " +
            "Tap 'Try opening Undal again'; if it fails a second time, call the kitchen."

        // Written as literals rather than `Color.parseColor`, so this screen
        // imports nothing from the graphics package it does not need: the fewer
        // names in this file, the fewer ways it can fail to open.
        val COLOR_INK: Int = 0xFF1B1C1E.toInt()
        val COLOR_MUTED: Int = 0xFF5C6067.toInt()
        val COLOR_PANEL: Int = 0xFFF1F3F5.toInt()
    }
}
