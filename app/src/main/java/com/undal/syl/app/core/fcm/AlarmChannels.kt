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

    const val VENDOR_NEW_ORDER = "vendor_new_order"
    const val VENDOR_GENERAL = "vendor_general"
    const val CUSTOMER_ORDERS = "customer_orders"
    const val CUSTOMER_PROMOS = "customer_promos"

    const val ORDER_ALERT_NOTIFICATION_ID = 9101
    const val PREP_TIMER_NOTIFICATION_ID = 9102
    const val HEARTBEAT_NOTIFICATION_ID = 9103

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
            setBypassDnd(true)
        }

        val vendorGeneral = NotificationChannel(
            VENDOR_GENERAL,
            context.getString(R.string.channel_vendor_general_title),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.channel_vendor_general_desc)
            enableVibration(true)
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

        manager.createNotificationChannels(listOf(vendorOrder, vendorGeneral, customerOrders, customerPromos))
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
