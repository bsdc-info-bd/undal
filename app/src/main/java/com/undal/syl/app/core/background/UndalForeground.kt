package com.undal.syl.app.core.background

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * The one door every Undal service walks through to reach the foreground.
 *
 * Android's rule is short and unforgiving: once `startForegroundService()` has
 * been called, the service **must** call `startForeground()` — within about five
 * seconds, and *even when the very next thing it does is stop itself*. Break the
 * rule and the platform does not log a warning; it kills the process from the
 * main thread with `ForegroundServiceDidNotStartInTimeException`, or on older
 * releases raises the "isn't responding" dialog. Neither can be caught by the
 * caller, because neither happens in the caller.
 *
 * That is exactly what Undal was doing. Three services were told to stop through
 * `startForegroundService()`, and all three answered by stopping — without ever
 * taking the foreground. The kitchen watch was the one that mattered, because a
 * *customer* login is what asked for that stop (`sync(context, isVendor = false)`),
 * so the crash landed on the phone of everybody who opened the app, a few seconds
 * after it started, and the crash guard then reopened the app to do it again.
 *
 * Two rules, both mechanical, both enforced by `tools/check_foreground_contract.py`:
 *
 *  1. **A stop is never a foreground start.** [stop] uses `Context.stopService`,
 *     which reaches `onDestroy` without ever creating the promise.
 *  2. **The first statement of `onStartCommand` takes the foreground.** Whatever
 *     the intent turns out to say, the promise is already kept by the time any
 *     branch decides to stop.
 *
 * Every call here is wrapped. A ROM that refuses a foreground service — and some
 * do, with a locked screen and a battery saver on — gets a logged refusal and a
 * `false`, never a dead process: a service that could not go to the foreground is
 * a service the readiness screen can report, while a crashed app reports nothing.
 */
object UndalForeground {

    private const val TAG = "UndalForeground"

    /**
     * The type every Undal foreground service runs as. Declared in the manifest
     * next to `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`, which is what Android 14 asks
     * for in return for `specialUse`.
     */
    val type: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

    /**
     * Keeps Android's promise for [service]. Returns false when the platform
     * refused it — the service is then an ordinary background service, which the
     * caller may treat as "not armed" but never as a reason to stop.
     */
    fun enter(service: Service, id: Int, notification: Notification): Boolean = runCatching {
        ServiceCompat.startForeground(service, id, notification, type)
        true
    }.getOrElse { error ->
        Log.w(TAG, "Android refused the foreground for ${service.javaClass.simpleName}", error)
        false
    }

    /**
     * Keeps the promise with a type of the service's own choosing. The order
     * alert is the one service that also plays audio, so it declares
     * `mediaPlayback` as well; everything else uses [enter].
     */
    fun enter(
        service: Service,
        id: Int,
        notification: Notification,
        foregroundServiceType: Int
    ): Boolean = runCatching {
        ServiceCompat.startForeground(service, id, notification, foregroundServiceType)
        true
    }.getOrElse { error ->
        Log.w(TAG, "Android refused the foreground for ${service.javaClass.simpleName}", error)
        false
    }

    /** Leaves the foreground and removes the notice. Safe whether or not it was up. */
    fun leave(service: Service, id: Int) {
        runCatching { ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            .onFailure { Log.w(TAG, "could not leave the foreground", it) }
        runCatching { NotificationManagerCompat.from(service).cancel(id) }
    }

    /**
     * Starts a service that will take the foreground itself. Returns false when
     * Android refused the start, which on Android 12+ is what happens to any
     * background start the platform has not exempted.
     */
    fun start(context: Context, intent: Intent): Boolean = runCatching {
        ContextCompat.startForegroundService(context, intent)
        true
    }.getOrElse { error ->
        Log.w(TAG, "Android refused to start ${intent.component?.className ?: "a service"}", error)
        false
    }

    /**
     * Asks a service to stop. This is `Context.stopService`, never
     * `startForegroundService`: a stop that asks for the foreground first is the
     * bug this file exists to remove, because the service then has to take a
     * foreground it is about to give up, and every path that forgets to is a
     * crash a few seconds later.
     */
    fun stop(context: Context, intent: Intent): Boolean = runCatching {
        context.stopService(intent)
        true
    }.getOrElse { error ->
        Log.w(TAG, "could not stop ${intent.component?.className ?: "a service"}", error)
        false
    }

    /** Convenience for the common shape: stop a service class outright. */
    fun stop(context: Context, service: Class<out Service>): Boolean =
        stop(context, Intent(context, service))
}
