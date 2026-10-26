package com.undal.syl.app.core.fcm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local, bounded diagnostic evidence — never stores tokens, addresses or customer data. */
object AlertDeliveryJournal {
    private const val PREFS = "alert_delivery_journal"
    private const val LIMIT = 24

    @Synchronized
    fun record(context: Context, stage: String, detail: String) {
        // Diagnostics must never prevent an alarm from starting.
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val previous = JSONArray(prefs.getString("events", "[]"))
            val next = JSONArray().put(JSONObject()
                .put("time", System.currentTimeMillis())
                .put("stage", stage.take(60)).put("detail", detail.take(180)))
            for (index in 0 until minOf(previous.length(), LIMIT - 1)) next.put(previous.getJSONObject(index))
            prefs.edit().putString("events", next.toString()).apply()
        }
    }

    @Synchronized
    fun lines(context: Context): List<String> = runCatching {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val events = JSONArray(prefs.getString("events", "[]"))
        val format = java.text.SimpleDateFormat("dd MMM HH:mm:ss", java.util.Locale.ENGLISH).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Dhaka")
        }
        (0 until events.length()).map { index ->
            val event = events.getJSONObject(index)
            "${format.format(java.util.Date(event.getLong("time")))} · ${event.getString("stage")}\n${event.getString("detail")}"
        }
    }.getOrDefault(emptyList())
}
