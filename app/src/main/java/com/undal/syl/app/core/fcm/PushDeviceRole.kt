package com.undal.syl.app.core.fcm

import android.content.Context

/**
 * Local routing hint, scoped to the account. Server rules still enforce authority.
 *
 * This is also the only synchronous answer to "is this phone the kitchen?", which
 * is why three paths that have no coroutine to wait in read it: the alert
 * watchdog deciding whether to revive the order watcher, the boot receiver
 * deciding the same thing before anyone has opened the app, and the push handler
 * deciding whether a message is a kitchen notice.
 *
 * All three used to ask Firebase for the ID token's claims and read
 * `Task.getResult()` on the spot. That call does not wait for a task — it throws
 * `IllegalStateException` unless the task has *already* finished, and a token
 * being fetched has not. The throw was swallowed into a `false`, so a kitchen
 * phone answered "I am a customer" to every one of those questions: the watchdog
 * disarmed itself on its first firing, a rebooted phone never restarted its order
 * watcher, and a push only reached the kitchen because this file said so.
 *
 * The role is written by the router the moment it has resolved the account against
 * the token, which is the authoritative read — so what lives here is the truth
 * this phone last established, not a guess.
 */
object PushDeviceRole {

    private const val PREFS = "push_device_role"
    private const val KEY_UID = "uid"
    private const val KEY_VENDOR = "vendor"

    /**
     * Remembers the role for [uid].
     *
     * `apply`, not `commit`: this runs on the main thread during a login, and the
     * value is read either later in the same process — where `apply` is already
     * visible — or in a process that starts after this one has had seconds to
     * flush. A synchronous disk write on the login path buys nothing.
     */
    fun remember(context: Context, uid: String, vendor: Boolean) {
        prefs(context).edit()
            .putString(KEY_UID, uid)
            .putBoolean(KEY_VENDOR, vendor)
            .apply()
    }

    /** True when the remembered account is [uid] and that account is the kitchen. */
    fun isKitchen(context: Context, uid: String?): Boolean {
        if (uid == null) return false
        val prefs = prefs(context)
        return prefs.getString(KEY_UID, null) == uid && prefs.getBoolean(KEY_VENDOR, false)
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
