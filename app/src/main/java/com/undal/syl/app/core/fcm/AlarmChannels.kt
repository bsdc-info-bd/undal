package com.undal.syl.app.core.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.getSystemService
import com.undal.syl.app.R

/**
 * Notification channels and the deep-links into the system settings screens the
 * diagnostics page uses (PART I-6).
 *
 * The vendor order channel is the most important object in this app: it carries
 * ALARM-class audio, bypasses Do Not Disturb, shows on the lock screen and is
 * marked ongoing while an order is ringing.
 */
object AlarmChannels {

    // The ids live in AlertContract, next to the topic and the message types the
    // Worker also names: one file to read when the two sides have to agree.
    const val VENDOR_NEW_ORDER = AlertContract.CHANNEL_VENDOR_NEW_ORDER
    const val VENDOR_GENERAL = AlertContract.CHANNEL_VENDOR_GENERAL
    const val VENDOR_WATCH = AlertContract.CHANNEL_VENDOR_WATCH
    const val CUSTOMER_ORDERS = AlertContract.CHANNEL_CUSTOMER_ORDERS
    const val CUSTOMER_PROMOS = AlertContract.CHANNEL_CUSTOMER_PROMOS

    const val ORDER_ALERT_NOTIFICATION_ID = 9101
    const val PREP_TIMER_NOTIFICATION_ID = 9102
    const val HEARTBEAT_NOTIFICATION_ID = 9103
    const val WATCH_NOTIFICATION_ID = 9109

    /** Creates every channel. Safe to call on each app start; existing ones are untouched. */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return
        val soundUri = orderSoundUri(context)

        val vendorOrder = NotificationChannel(
            VENDOR_NEW_ORDER,
            context.getString(R.string.channel_vendor_new_order_title),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_vendor_new_order_desc)
            enableLights(true)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 500)
            setSound(
                soundUri,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            // Keeps the alert audible when Do Not Disturb is on, provided the
            // owner grants notification-policy access. ReadinessReports tells them
            // whether they have, instead of leaving it invisible.
            setBypassDnd(true)
            // A channel's sound and importance are frozen the moment it is
            // created: an owner who ever muted this channel would otherwise be
            // un-mutable from inside the app forever. Deleting and recreating it
            // restores the alarm sound, and ReadinessReports says when to do it.
            if (isMuted(context, VENDOR_NEW_ORDER)) {
                manager.deleteNotificationChannel(VENDOR_NEW_ORDER)
                manager.createNotificationChannel(this)
            }
        }

        val vendorGeneral = NotificationChannel(
            VENDOR_GENERAL,
            context.getString(R.string.channel_vendor_general_title),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.channel_vendor_general_desc)
            enableVibration(true)
        }

        // Deliberately the quietest channel Android has. It proves the kitchen
        // is armed without buzzing for it — an ongoing notification is what
        // keeps a foreground service alive, not a way to get attention.
        val vendorWatch = NotificationChannel(
            VENDOR_WATCH,
            context.getString(R.string.channel_vendor_watch_title),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = context.getString(R.string.channel_vendor_watch_desc)
            setShowBadge(false)
        }

        val customerOrders = NotificationChannel(
            CUSTOMER_ORDERS,
            context.getString(R.string.channel_customer_orders_title),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_customer_orders_desc)
            enableVibration(true)
            setShowBadge(true)
        }

        val customerPromos = NotificationChannel(
            CUSTOMER_PROMOS,
            context.getString(R.string.channel_customer_promos_title),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.channel_customer_promos_desc)
        }

        manager.createNotificationChannels(
            listOf(vendorOrder, vendorGeneral, vendorWatch, customerOrders, customerPromos)
        )
    }

    /**
     * True when the owner has switched this channel off or dropped it to a
     * silent importance — the two settings that make a ringing kitchen silent
     * without changing anything else in the app.
     */
    fun isMuted(context: Context, channelId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = context.getSystemService<NotificationManager>() ?: return false
        val channel = manager.getNotificationChannel(channelId) ?: return false
        return channel.importance == NotificationManager.IMPORTANCE_NONE ||
            (channel.importance < NotificationManager.IMPORTANCE_HIGH && channel.sound == null)
    }

    /** True when the alarm sound is still attached to the channel. */
    fun hasOrderSound(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = context.getSystemService<NotificationManager>() ?: return true
        val channel = manager.getNotificationChannel(VENDOR_NEW_ORDER) ?: return true
        return channel.sound != null
    }

    /** The official order sound as a notification sound URI (res/raw is exposeable). */
    fun orderSoundUri(context: Context): Uri =
        Uri.parse("android.resource://${context.packageName}/${R.raw.undal_order_alert}")

    fun channelImportance(context: Context, channelId: String): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return NotificationManager.IMPORTANCE_HIGH
        val manager = context.getSystemService<NotificationManager>() ?: return null
        return manager.getNotificationChannel(channelId)?.importance
    }

    /** System settings deep links used by the vendor diagnostics screen. */
    fun appNotificationSettings(context: Context): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun channelSettings(context: Context, channelId: String): Intent {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        } else {
            appNotificationSettings(context)
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Android 14's "alerts may open over the lock screen" screen.
     *
     * `USE_FULL_SCREEN_INTENT` is granted at install for calling and alarm apps
     * and *revoked by default for everything else*, which is why a kitchen phone
     * can ring with the order hidden behind the keyguard. The permission cannot
     * be requested at runtime — only this screen can grant it.
     */
    fun fullScreenIntentSettings(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            appNotificationSettings(context)
        }

    fun batteryOptimisationSettings(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun requestIgnoreBatteryOptimisations(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun exactAlarmSettings(): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun applicationDetailsSettings(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Manufacturer autostart screens, in the order they exist on real phones.
     * Each candidate is probed with resolveActivity before it is offered, so the
     * diagnostics page never shows a button that opens nothing.
     */
    fun autostartCandidates(): List<Pair<String, Intent>> = listOf(
        "Xiaomi · Autostart" to Intent().setClassName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        ),
        "Oppo · Startup manager" to Intent().setClassName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity"
        ),
        "Realme · Startup manager" to Intent().setClassName(
            "com.oplus.safecenter",
            "com.oplus.safecenter.startupapp.StartupAppListActivity"
        ),
        "Vivo · Autostart" to Intent().setClassName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        ),
        "Huawei · Startup manager" to Intent().setClassName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        ),
        "Samsung · Battery settings" to Intent().setClassName(
            "com.samsung.android.lool",
            "com.samsung.android.sm.ui.battery.BatteryActivity"
        )
    ).map { (label, intent) -> label to intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
}
