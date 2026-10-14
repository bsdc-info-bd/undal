package com.undal.syl.app.core.fcm

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.core.content.getSystemService

/**
 * The alarm stream — the single setting that can make a fully armed kitchen
 * phone completely silent.
 *
 * Everything about the order alert is deliberately routed to
 * [AudioManager.STREAM_ALARM]: the notification channel's sound, and the looping
 * player in [com.undal.syl.app.core.background.OrderAlertService]. The alarm
 * stream is the right choice — it is the stream a phone does not mute when it is
 * put on silent — but it also means one number on the phone can defeat every
 * other guarantee this app makes: **if the alarm volume is at zero, the phone
 * rings with nothing audible, and no permission, token, channel or watcher can
 * change that.**
 *
 * That is why this object exists in three places rather than one:
 *
 *   1. the readiness screen reports the number, and treats a silent stream as a
 *      *blocking* failure rather than a note, because an order can be missed;
 *   2. the owner can raise it with one tap from that screen ([raise]);
 *   3. an order that starts ringing raises it by itself, and only when it has to
 *      ([arm]), putting the owner's own value back when the ring ends
 *      ([restore]).
 *
 * A write needs `MODIFY_AUDIO_SETTINGS`, which is a normal permission granted at
 * install. A phone in Do Not Disturb can still refuse the write; that refusal is
 * caught here, logged, and reported on the screen, because a refused volume
 * change must never be able to take the alert down with it.
 */
object AlertVolume {

    private const val TAG = "UndalAlertVolume"

    /**
     * The share of maximum below which an order ring cannot be trusted. Half of
     * maximum is already quiet in a kitchen with a running exhaust fan, so the
     * line is drawn where the alert stops being dependable, not at zero.
     */
    const val AUDIBLE_PERCENT = 50

    /** The value this object wrote, and the owner's value that it replaced. */
    private data class Change(val was: Int, val setTo: Int)

    @Volatile
    private var change: Change? = null

    private fun audio(context: Context): AudioManager? =
        context.applicationContext.getSystemService<AudioManager>()

    /** The alarm volume as a whole percentage, `0`…`100`. */
    fun percent(context: Context): Int {
        val audio = audio(context) ?: return 100
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        if (max <= 0) return 0
        return audio.getStreamVolume(AudioManager.STREAM_ALARM) * 100 / max
    }

    fun isAudible(context: Context): Boolean = percent(context) >= AUDIBLE_PERCENT

    /**
     * Raises the alarm stream for the owner, who asked for it from the readiness
     * screen. Stays raised: nothing puts it back, because this was a decision,
     * not a side effect. Returns false when the platform refused the write.
     */
    fun raise(context: Context): Boolean {
        val audio = audio(context) ?: return false
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        if (max <= 0) return false
        change = null
        val written = write(audio, max)
        change = null
        return written
    }

    /**
     * What the alert itself uses: raise the stream only when it is below the
     * audible line, and remember what to put back. Returns true when the volume
     * was changed.
     *
     * A phone that is already loud is never touched — an app that silently
     * rewrites the owner's volume on every order is a worse phone to own than one
     * that occasionally rings quietly.
     */
    fun arm(context: Context): Boolean {
        val audio = audio(context) ?: return false
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        if (max <= 0) return false
        val was = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        if (was * 100 / max >= AUDIBLE_PERCENT) return false
        val written = write(audio, max)
        if (written) change = Change(was = was, setTo = max)
        return written
    }

    /**
     * Puts the owner's own value back when the ring ends — but only if the stream
     * still holds the value this object set. A volume the owner changed by hand
     * mid-ring is theirs, and is never overwritten.
     */
    fun restore(context: Context) {
        val pending = change ?: return
        change = null
        val audio = audio(context) ?: return
        runCatching {
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) == pending.setTo) {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, pending.was, 0)
            }
        }
    }

    private fun write(audio: AudioManager, target: Int): Boolean =
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0) }
            .onFailure { Log.w(TAG, "the platform refused the alarm volume change: ${it.message}") }
            .isSuccess

    /** One line for a log or a diagnostics row, never a guess. */
    fun describe(context: Context): String = "alarm volume ${percent(context)}%"
}
