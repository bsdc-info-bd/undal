package com.undal.syl.app.core.background

import android.content.Context

/**
 * Whether the ten-second preparation tick makes a sound (V-034).
 *
 * The tick exists for one reason: a cook with the phone in a pocket must not lose
 * the thread of a countdown they promised a customer. It is not an alarm, and a
 * kitchen that finds it distracting must be able to silence it without silencing
 * the order alert — which is a different sound, on a different channel, for a
 * different question ("somebody answer this order" rather than "this one is still
 * cooking").
 *
 * Stored in a plain preference file rather than in Firestore: it describes this
 * phone's kitchen, not the shop, and it has to be readable by a foreground service
 * that may run before any snapshot has arrived.
 *
 * The countdown, the notification and the overdue escalation are unaffected by
 * this switch — only the sound is.
 */
object PrepTickSettings {

    private const val FILE = "undal_prep_tick"
    private const val KEY_ENABLED = "tick_sound_enabled"

    /** On by default: the tick is the feature, and silence is the opt-in. */
    fun enabled(context: Context): Boolean = runCatching {
        preferences(context).getBoolean(KEY_ENABLED, true)
    }.getOrDefault(true)

    fun setEnabled(context: Context, enabled: Boolean) {
        runCatching {
            preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
