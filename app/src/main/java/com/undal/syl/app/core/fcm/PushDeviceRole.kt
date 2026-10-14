package com.undal.syl.app.core.fcm

import android.content.Context

/** Local routing hint, scoped to the account. Server rules still enforce authority. */
object PushDeviceRole {
    fun remember(context: Context, uid: String, vendor: Boolean) {
        context.getSharedPreferences("push_device_role", Context.MODE_PRIVATE).edit()
            .putString("uid", uid).putBoolean("vendor", vendor).commit()
    }

    fun isKitchen(context: Context, uid: String?): Boolean {
        if (uid == null) return false
        val prefs = context.getSharedPreferences("push_device_role", Context.MODE_PRIVATE)
        return prefs.getString("uid", null) == uid && prefs.getBoolean("vendor", false)
    }

    fun clear(context: Context) {
        context.getSharedPreferences("push_device_role", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
