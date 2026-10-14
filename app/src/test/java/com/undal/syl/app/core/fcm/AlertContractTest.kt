package com.undal.syl.app.core.fcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alert contract, pinned from the app's side.
 *
 * These strings are a wire format shared with the Worker. They cannot be felt by
 * a test — a message that arrives with a type nobody handles or a channel id
 * nobody created does not throw, it just does nothing — so the values are pinned
 * here and compared against the Worker's sources by
 * `tools/check-alert-ladder.mjs`. This test is the half that runs in the app's own
 * test suite, where a rename is noticed the moment it happens.
 */
class AlertContractTest {

    @Test
    fun `the kitchen topic is the one the worker fans out to`() {
        assertEquals("undal_kitchen", AlertContract.KITCHEN_TOPIC)
    }

    @Test
    fun `channel ids match the ones the phone creates`() {
        // If these ever diverge, an alert lands in Android's fallback channel:
        // no alarm sound, no lock-screen behaviour, and no error anywhere.
        assertEquals(AlarmChannels.VENDOR_NEW_ORDER, AlertContract.CHANNEL_VENDOR_NEW_ORDER)
        assertEquals(AlarmChannels.VENDOR_GENERAL, AlertContract.CHANNEL_VENDOR_GENERAL)
        assertEquals(AlarmChannels.CUSTOMER_ORDERS, AlertContract.CHANNEL_CUSTOMER_ORDERS)
        assertEquals(AlarmChannels.CUSTOMER_PROMOS, AlertContract.CHANNEL_CUSTOMER_PROMOS)
        assertEquals(AlarmChannels.VENDOR_WATCH, AlertContract.CHANNEL_VENDOR_WATCH)
    }

    @Test
    fun `the message types are the worker's spelling`() {
        assertEquals("order_created", AlertContract.TYPE_ORDER_CREATED)
        assertEquals("order_status", AlertContract.TYPE_ORDER_STATUS)
        assertEquals("chat", AlertContract.TYPE_CHAT)
        assertEquals("promo", AlertContract.TYPE_PROMO)
        assertEquals("vendor_self", AlertContract.TYPE_VENDOR_SELF)
    }

    @Test
    fun `every notification id is distinct`() {
        // Two alerts sharing an id means one replaces the other, which is how a
        // second order can look like no order at all.
        val ids = listOf(
            AlarmChannels.ORDER_ALERT_NOTIFICATION_ID,
            AlarmChannels.PREP_TIMER_NOTIFICATION_ID,
            AlarmChannels.HEARTBEAT_NOTIFICATION_ID,
            AlarmChannels.WATCH_NOTIFICATION_ID
        )
        assertEquals(ids.size, ids.toSet().size)
        assertNotEquals(0, ids.min())
    }

    @Test
    fun `the kitchen topic cannot collide with the promo topic`() {
        val topic = AlertContract.KITCHEN_TOPIC
        assertEquals(topic.lowercase(), topic)
        assertTrue("topics are lowercase alphanumeric plus _", topic.matches(Regex("^[a-z0-9_]+$")))
        assertNotEquals(topic, TokenRegistrar.PROMO_TOPIC)
    }
}
