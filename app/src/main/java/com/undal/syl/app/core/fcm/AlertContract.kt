package com.undal.syl.app.core.fcm

/**
 * The contract between the Worker that sends an order alert and this app that
 * has to ring for it.
 *
 * Every name here appears on both sides — a topic, a message type, a channel id
 * — and a mismatch is silent in the worst possible way: FCM accepts the message,
 * the app receives it, and nothing happens, or the notification lands in Android's
 * fallback channel where the alarm sound and the lock-screen behaviour are not the
 * ones the kitchen needs. `tools/check-alert-ladder.mjs` reads this file, the
 * FCM service, the channels and the Worker sources together and fails the build
 * when they drift apart.
 *
 * It exists because the app used to spell these strings out at each use site.
 */
object AlertContract {

    /**
     * The topic every kitchen device subscribes to.
     *
     * This is the second route an order alert takes, and the reason the kitchen
     * can still ring when `vendorUid()` finds nothing on the server: a topic push
     * needs no account lookup at all. Must match `KITCHEN_TOPIC` in
     * `worker/src/alerting.ts`.
     */
    const val KITCHEN_TOPIC = "undal_kitchen"

    /** Message types the Worker sends. */
    const val TYPE_ORDER_CREATED = "order_created"
    const val TYPE_ORDER_STATUS = "order_status"
    const val TYPE_CHAT = "chat"
    const val TYPE_PROMO = "promo"
    const val TYPE_VENDOR_SELF = "vendor_self"

    /** Channel ids, shared with the Worker's `channelId` field. */
    const val CHANNEL_VENDOR_NEW_ORDER = "vendor_new_order"
    /** The quiet, always-on "the kitchen is watching" channel. */
    const val CHANNEL_VENDOR_WATCH = "vendor_watch"
    const val CHANNEL_VENDOR_GENERAL = "vendor_general"
    const val CHANNEL_CUSTOMER_ORDERS = "customer_orders"
    const val CHANNEL_CUSTOMER_PROMOS = "customer_promos"
}
