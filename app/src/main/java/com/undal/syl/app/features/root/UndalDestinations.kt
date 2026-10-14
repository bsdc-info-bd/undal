package com.undal.syl.app.features.root

/**
 * Every route in one place.
 *
 * Three families live here:
 *   · the auth and onboarding routes,
 *   · the customer portal — store, dish, cart, orders, order tracking, inbox and
 *     profile — which Response 3 fills in, and
 *   · the vendor console routes, declared so pushes and shared links always have
 *     a real destination instead of a dead tap.
 *
 * Route strings double as deep-link targets: `undal://customer/home`, a shared
 * `https://<worker>/dish/<id>` App Link and a push `clickAction` of
 * `customer/order/<id>` all resolve through [routeForDeepLink].
 */
object UndalRoutes {

    const val SPLASH = "splash"
    const val LOGIN = "auth/login"
    const val REGISTER = "auth/register"
    const val LOCATION_SETUP = "onboarding/location"
    const val SUPPORT = "support"

    const val CUSTOMER_HOME = "customer/home"
    const val CUSTOMER_ITEM = "customer/item/{itemId}"
    const val CUSTOMER_CART = "customer/cart"
    const val CUSTOMER_ORDERS = "customer/orders"
    const val CUSTOMER_INBOX = "customer/inbox"
    const val CUSTOMER_PROFILE = "customer/profile"

    const val VENDOR_CONSOLE = "vendor/dashboard"
    const val VENDOR_ORDERS = "vendor/orders"
    const val VENDOR_MENU = "vendor/menu"
    const val VENDOR_SCHEDULE = "vendor/schedule"
    const val VENDOR_STORE = "vendor/store"
    const val VENDOR_CHAT_LIST = "vendor/chats"

    /**
     * The coupon and discount studio.
     *
     * It renders the menu manager opened on its Offers tab rather than a screen of
     * its own: a promotion belongs beside the dishes it discounts, and two places
     * to edit a coupon would be two places to disagree. The dashboard's "Offers"
     * button lands here, on the tab, instead of dropping the owner into Dishes.
     */
    const val VENDOR_PROMOS = "vendor/promos"

    const val VENDOR_DIAGNOSTICS = "vendor/diagnostics"
    const val ROUTER_ERROR = "router/error"
    const val VENDOR_INBOX = "vendor/inbox"
    const val POLICY = "policy"
    const val VENDOR_ORDER_DETAIL = "vendor/order/{orderId}"
    const val VENDOR_CHAT = "vendor/chat/{orderId}"
    const val CUSTOMER_ORDER_DETAIL = "customer/order/{orderId}"
    const val CUSTOMER_CHAT = "customer/chat/{orderId}"

    const val ARG_ITEM_ID = "itemId"
    const val ARG_ORDER_ID = "orderId"

    fun vendorOrderDetail(orderId: String) = "vendor/order/$orderId"
    fun vendorChat(orderId: String) = "vendor/chat/$orderId"
    fun customerOrderDetail(orderId: String) = "customer/order/$orderId"
    fun customerChat(orderId: String) = "customer/chat/$orderId"

    /** A dish or a combo is the same screen — the id decides which. */
    fun customerItem(itemId: String) = "customer/item/$itemId"

    /**
     * The five screens that start a portal: the places the back button falls back
     * to. Everything else in the graph is a step *inside* one of them, and the
     * rule for leaving a step is the pop the graph already knows how to do.
     */
    val ROOTS: Set<String> = setOf(
        SPLASH,
        LOGIN,
        REGISTER,
        LOCATION_SETUP,
        CUSTOMER_HOME,
        VENDOR_CONSOLE,
        VENDOR_DIAGNOSTICS,
        ROUTER_ERROR,
    )

    fun isRoot(route: String?): Boolean = route != null && route in ROOTS

    /** True when the route belongs to the kitchen's console rather than the shop. */
    fun isVendor(route: String?): Boolean = route != null && route.startsWith("vendor/")

    /** True when the route belongs to the customer's shop. */
    fun isCustomer(route: String?): Boolean = route != null && route.startsWith("customer/")

    /**
     * Translates a push deep link or a shared link into a route the graph knows.
     * Unknown targets land on the store home rather than a blank screen.
     */
    fun routeForDeepLink(deepLink: String?): String? {
        val cleaned = deepLink?.removePrefix("undal://")?.removePrefix("/")?.trim().orEmpty()
        if (cleaned.isEmpty()) return null
        val path = cleaned.substringBefore('?')
        val id = path.substringAfterLast('/')
        return when {
            path.startsWith("vendor/order/") -> path
            path.startsWith("vendor/chat/") -> path
            path.startsWith("customer/order/") -> path
            path.startsWith("customer/chat/") -> path
            path.startsWith("dish/") && id.isNotBlank() -> customerItem(id)
            path.startsWith("combo/") && id.isNotBlank() -> customerItem(id)
            path.startsWith("order/") && id.isNotBlank() -> customerOrderDetail(id)
            path.startsWith("customer/item/") && id.isNotBlank() -> customerItem(id)
            path.startsWith("vendor/dashboard") -> VENDOR_CONSOLE
            path.startsWith("vendor/console") -> VENDOR_CONSOLE
            path.startsWith("vendor/store") -> VENDOR_STORE
            path.startsWith("vendor/orders") -> VENDOR_ORDERS
            path.startsWith("vendor/menu") -> VENDOR_MENU
            path.startsWith("vendor/promos") -> VENDOR_PROMOS
            // A push about a promotion opens the studio, not the menu list.
            path.startsWith("vendor/coupons") -> VENDOR_PROMOS
            path.startsWith("vendor/diagnostics") -> VENDOR_DIAGNOSTICS
            path.startsWith("vendor/inbox") -> VENDOR_INBOX
            path.startsWith("policy") -> POLICY
            path.startsWith("vendor/schedule") -> VENDOR_SCHEDULE
            path.startsWith("vendor/chats") -> VENDOR_CHAT_LIST
            path.startsWith("store") -> CUSTOMER_HOME
            path.startsWith("app") -> CUSTOMER_HOME
            path.startsWith("open") -> CUSTOMER_HOME
            path.startsWith("download") -> CUSTOMER_HOME
            path.startsWith("customer/home") -> CUSTOMER_HOME
            path.startsWith("customer/cart") -> CUSTOMER_CART
            path.startsWith("customer/orders") -> CUSTOMER_ORDERS
            path.startsWith("customer/inbox") -> CUSTOMER_INBOX
            path.startsWith("customer/profile") -> CUSTOMER_PROFILE
            else -> null
        }
    }
}
