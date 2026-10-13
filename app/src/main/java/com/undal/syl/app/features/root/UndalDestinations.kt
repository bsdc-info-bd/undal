package com.undal.syl.app.features.root

/**
 * Every route in one place.
 *
 * Two families live here:
 *   · the auth and onboarding route, which Response 2 completes, and
 *   · the portal routes, declared now so push notifications and shared links
 *     have a real destination from the first build instead of a dead tap.
 *
 * Route strings double as deep-link targets: `undal://customer/home` and
 * `https://<worker>/app` both resolve through [routeForDeepLink].
 */
object UndalRoutes {

    const val SPLASH = "splash"
    const val LOGIN = "auth/login"
    const val REGISTER = "auth/register"
    const val LOCATION_SETUP = "onboarding/location"
    const val SUPPORT = "support"

    const val CUSTOMER_HOME = "customer/home"
    const val CUSTOMER_ORDERS = "customer/orders"
    const val CUSTOMER_INBOX = "customer/inbox"
    const val CUSTOMER_PROFILE = "customer/profile"

    const val VENDOR_CONSOLE = "vendor/dashboard"
    const val VENDOR_ORDERS = "vendor/orders"
    const val VENDOR_MENU = "vendor/menu"
    const val VENDOR_SCHEDULE = "vendor/schedule"
    const val VENDOR_STORE = "vendor/store"
    const val VENDOR_CHAT_LIST = "vendor/chats"

    const val VENDOR_DIAGNOSTICS = "vendor/diagnostics"
    const val VENDOR_ORDER_DETAIL = "vendor/order/{orderId}"
    const val VENDOR_CHAT = "vendor/chat/{orderId}"
    const val CUSTOMER_ORDER_DETAIL = "customer/order/{orderId}"
    const val CUSTOMER_CHAT = "customer/chat/{orderId}"

    fun vendorOrderDetail(orderId: String) = "vendor/order/$orderId"
    fun vendorChat(orderId: String) = "vendor/chat/$orderId"
    fun customerOrderDetail(orderId: String) = "customer/order/$orderId"
    fun customerChat(orderId: String) = "customer/chat/$orderId"

    /**
     * Translates a push deep link or a shared link into a route the graph knows.
     * Unknown targets land on the store home rather than a blank screen.
     */
    fun routeForDeepLink(deepLink: String?): String? {
        val cleaned = deepLink?.removePrefix("undal://")?.removePrefix("/")?.trim().orEmpty()
        if (cleaned.isEmpty()) return null
        return when {
            cleaned.startsWith("vendor/order/") -> cleaned.substringBefore('?')
            cleaned.startsWith("customer/order/") -> cleaned.substringBefore('?')
            cleaned.startsWith("vendor/chat/") -> cleaned.substringBefore('?')
            cleaned.startsWith("customer/chat/") -> cleaned.substringBefore('?')
            cleaned.startsWith("vendor/store") -> VENDOR_STORE
            cleaned.startsWith("vendor/orders") -> VENDOR_ORDERS
            cleaned.startsWith("vendor/menu") -> VENDOR_MENU
            cleaned.startsWith("vendor/diagnostics") -> VENDOR_DIAGNOSTICS
            cleaned.startsWith("customer/home") -> CUSTOMER_HOME
            cleaned.startsWith("customer/orders") -> CUSTOMER_ORDERS
            cleaned.startsWith("customer/inbox") -> CUSTOMER_INBOX
            cleaned.startsWith("customer/profile") -> CUSTOMER_PROFILE
            else -> null
        }
    }
}
