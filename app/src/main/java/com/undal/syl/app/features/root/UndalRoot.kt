package com.undal.syl.app.features.root

import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import com.undal.syl.app.core.background.OrderAlertController
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.features.auth.LoginScreen
import com.undal.syl.app.features.auth.RegisterScreen
import com.undal.syl.app.features.onboarding.LocationSetupScreen
import com.undal.syl.app.features.onboarding.UndalSplash
import com.undal.syl.app.features.cart.CartScreen
import com.undal.syl.app.features.inbox.InboxScreen
import com.undal.syl.app.features.item.ItemDetailScreen
import com.undal.syl.app.features.order.OrderHistoryScreen
import com.undal.syl.app.features.order.OrderTrackingScreen
import com.undal.syl.app.features.portal.SupportScreen
import com.undal.syl.app.features.profile.ProfileScreen
import com.undal.syl.app.features.store.StoreScreen
import com.undal.syl.app.features.legal.PolicyScreen
import com.undal.syl.app.features.vendor.chats.VendorChatListScreen
import com.undal.syl.app.features.vendor.chats.VendorChatScreen
import com.undal.syl.app.features.vendor.dashboard.VendorDashboardScreen
import com.undal.syl.app.features.vendor.inbox.VendorInboxScreen
import com.undal.syl.app.features.vendor.menu.VendorMenuScreen
import com.undal.syl.app.features.vendor.menu.VendorMenuViewModel
import com.undal.syl.app.features.vendor.orders.VendorOrderDetailScreen
import com.undal.syl.app.features.vendor.orders.VendorOrdersScreen
import com.undal.syl.app.features.vendor.schedule.VendorScheduleScreen
import com.undal.syl.app.features.vendor.settings.VendorStoreScreen
import com.undal.syl.app.features.vendor.DiagnosticsScreen

/** How long a second back press waits before it is a fresh one again. */
private const val EXIT_WINDOW_MILLIS = 2500L

/** How long a recovered crash's sentence stays on screen. */
private const val RECOVERY_BANNER_MILLIS = 8000L

/**
 * The `Context` of the activity hosting this composition, if there is one.
 *
 * `LocalContext` is the activity on this app's own phones, but it can arrive
 * wrapped by a theme or by a host, so the wrappers are walked rather than cast
 * through — a `ClassCastException` in a back handler would be a crash from a
 * back press, which is the bug this file exists to remove.
 */
private fun Context.findHostActivity(): ComponentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Moves the graph, and refuses to throw while doing it.
 *
 * `navigate` throws on a destination it cannot resolve and on a deep link it
 * cannot parse — and both of those used to happen inside a `LaunchedEffect` or a
 * tap handler, where an exception is an uncaught exception, which is the app
 * closing. A refusal is a sentence on the banner instead.
 */
private fun navigateSafely(
    navController: NavHostController,
    route: String,
    onFailure: (Throwable) -> Unit,
    builder: NavOptionsBuilder.() -> Unit = {
        popUpTo(0) { inclusive = true }
        launchSingleTop = true
    }
) {
    runCatching { navController.navigate(route, builder) }.onFailure(onFailure)
}

/**
 * The app shell: one navigation graph, two portals, one authority on where a
 * cold start lands (UndalRootViewModel).
 *
 * Deep links arriving from a push or a shared link are handed to the graph after
 * the router has decided the portal, so a kitchen alert opens the order it is
 * about and a customer status push opens the right order — never a blank screen.
 *
 * The shell also owns the phone's back button, for the whole app: every screen
 * is a step inside this graph, so the graph itself is the only thing that can
 * always answer "where does back go from here?".
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun UndalRoot(
    initialDeepLink: String?,
    recoveredFromCrash: Boolean = false,
    navController: NavHostController = rememberNavController(),
    viewModel: UndalRootViewModel = hiltViewModel()
) {
    val destination by viewModel.destination.collectAsState()
    val ringing by OrderAlertController.ringing.collectAsState()
    val routerError by viewModel.errorMessage.collectAsState()
    val context = LocalContext.current

    // A crash the guard caught: the app reopened itself, and this is the one
    // sentence that explains why it is on screen again.
    var recoveryMessage by remember {
        mutableStateOf(
            if (recoveredFromCrash) com.undal.syl.app.core.util.UndalCrashGuard.RECOVERY_SENTENCE else null
        )
    }
    LaunchedEffect(recoveryMessage) {
        if (recoveryMessage != null) {
            delay(RECOVERY_BANNER_MILLIS)
            recoveryMessage = null
        }
    }

    /**
     * One back button for the whole app.
     *
     * Every screen is a step inside the graph, so the first rule is the plain
     * one: leave the step. Only when there is nothing left to leave does the
     * back press mean the portal itself, and only from a portal's own home does
     * it mean leaving the app — where it asks first, so a thumb resting on the
     * edge of the screen cannot close the kitchen's phone mid-order.
     */
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    var lastExitPress by remember { mutableLongStateOf(0L) }

    fun leavePortal() {
        val home = if (UndalRoutes.isVendor(currentRoute)) UndalRoutes.VENDOR_CONSOLE else UndalRoutes.CUSTOMER_HOME
        navigateSafely(navController, home, viewModel::onNavigationFailed) { launchSingleTop = true }
    }

    fun closeApp() {
        // No host activity (a preview, a test): fall back to the portal's home
        // rather than doing nothing at all.
        context.findHostActivity()?.finish() ?: leavePortal()
    }

    BackHandler(enabled = true) {
        val lifecycle = currentEntry?.lifecycle
        val inGraph = currentEntry != null && lifecycle != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (inGraph && navController.previousBackStackEntry != null) {
            navController.popBackStack()
            return@BackHandler
        }
        if (inGraph && !UndalRoutes.isRoot(currentRoute)) {
            leavePortal()
            return@BackHandler
        }
        val now = System.currentTimeMillis()
        if (now - lastExitPress <= EXIT_WINDOW_MILLIS) {
            closeApp()
        } else {
            lastExitPress = now
            Toast.makeText(context, "Press back again to leave Undal", Toast.LENGTH_SHORT).show()
        }
    }

    // The alert's Accept / Reject buttons open the order with the right sheet
    // already raised, so the kitchen never has to hunt for the button.
    val requestedSheet = initialDeepLink
        ?.substringAfter("sheet=", "")
        ?.takeIf { it.isNotBlank() }

    // Portal routing: whenever the router changes its mind, the graph follows.
    // The move replaces the whole back stack with the portal's first screen —
    // which is what makes back inside a portal always land somewhere real.
    LaunchedEffect(destination) {
        val target = when (destination) {
            is UndalRootViewModel.Destination.Splash -> return@LaunchedEffect
            is UndalRootViewModel.Destination.Auth -> UndalRoutes.LOGIN
            is UndalRootViewModel.Destination.CustomerLocation -> UndalRoutes.LOCATION_SETUP
            is UndalRootViewModel.Destination.CustomerHome -> UndalRoutes.CUSTOMER_HOME
            is UndalRootViewModel.Destination.VendorDiagnostics -> UndalRoutes.VENDOR_DIAGNOSTICS
            is UndalRootViewModel.Destination.VendorConsole -> UndalRoutes.VENDOR_CONSOLE
            is UndalRootViewModel.Destination.Error -> UndalRoutes.ROUTER_ERROR
        }
        navigateSafely(navController, target, viewModel::onNavigationFailed)
    }

    // A push deep link wins immediately: if the kitchen phone is ringing, the
    // first thing it shows is the order that is ringing.
    LaunchedEffect(initialDeepLink) {
        val route = UndalRoutes.routeForDeepLink(initialDeepLink) ?: return@LaunchedEffect
        if (route == UndalRoutes.SPLASH) return@LaunchedEffect
        navigateSafely(navController, route, viewModel::onNavigationFailed) { launchSingleTop = true }
    }

    LaunchedEffect(ringing) {
        val active = ringing
        if (active != null) {
            navigateSafely(
                navController,
                UndalRoutes.vendorOrderDetail(active.orderId),
                viewModel::onNavigationFailed
            ) { launchSingleTop = true }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
      // The shared-element scope for the whole app: a dish photo travels from
      // the menu card to the dish page with one identity, which is what makes
      // the second screen feel like the first one opening (C-039).
      SharedTransitionLayout {
      NavHost(
        navController = navController,
        startDestination = UndalRoutes.SPLASH
      ) {
        composable(UndalRoutes.SPLASH) {
            UndalSplash(message = "Opening Undal HomeKitchen…")
        }

        // The router could not decide where this phone belongs — said in words,
        // with a retry, instead of a crash and a jump to the home screen.
        composable(UndalRoutes.ROUTER_ERROR) {
            UndalRouterErrorScreen(
                message = routerError
                    ?: "Undal could not read your account just now.",
                onRetry = viewModel::onErrorAcknowledged,
                onSignOut = viewModel::signOut,
                onOpenSupport = { navigateSafely(navController, UndalRoutes.SUPPORT, viewModel::onNavigationFailed) }
            )
        }

        composable(UndalRoutes.LOGIN) {
            LoginScreen(
                onSignedIn = viewModel::onSignedIn,
                onGoToRegister = { navController.navigate(UndalRoutes.REGISTER) { launchSingleTop = true } },
                onGoToSupport = { navController.navigate(UndalRoutes.SUPPORT) }
            )
        }

        composable(UndalRoutes.REGISTER) {
            RegisterScreen(
                onRegistered = { navController.navigate(UndalRoutes.LOCATION_SETUP) { launchSingleTop = true } },
                onBackToLogin = { navController.popBackStack() }
            )
        }

        composable(UndalRoutes.LOCATION_SETUP) {
            LocationSetupScreen(
                onSaved = viewModel::onAddressSaved,
                onSignOut = {
                    viewModel.onSignedIn()
                    navigateSafely(navController, UndalRoutes.LOGIN, viewModel::onNavigationFailed)
                }
            )
        }

        composable(UndalRoutes.SUPPORT) {
            SupportScreen(onBack = { navController.popBackStack() })
        }

        // `enterTransition = null` keeps the graph's default motion and picks the
        // destination scope that carries the animated-visibility needed by the
        // shared dish photo.
        composable(
            route = UndalRoutes.CUSTOMER_HOME,
            enterTransition = null,
            exitTransition = null
        ) {
            // The customer's notification permission is part of this product's
            // promise: without it Android drops every order update silently.
            val consentViewModel: com.undal.syl.app.core.fcm.NotificationConsentViewModel = hiltViewModel()
            com.undal.syl.app.core.fcm.CustomerNotificationConsent(progress = consentViewModel.progress)
            StoreScreen(
                onOpenItem = { itemId ->
                    navController.navigate(UndalRoutes.customerItem(itemId)) { launchSingleTop = true }
                },
                onOpenCart = { navController.navigate(UndalRoutes.CUSTOMER_CART) { launchSingleTop = true } },
                onOpenOrders = { navController.navigate(UndalRoutes.CUSTOMER_ORDERS) { launchSingleTop = true } },
                onOpenInbox = { navController.navigate(UndalRoutes.CUSTOMER_INBOX) { launchSingleTop = true } },
                onOpenProfile = { navController.navigate(UndalRoutes.CUSTOMER_PROFILE) { launchSingleTop = true } },
                onEditAddress = { navController.navigate(UndalRoutes.LOCATION_SETUP) { launchSingleTop = true } },
                sharedImageModifier = { itemId ->
                    Modifier.sharedElement(rememberSharedContentState("dish-photo-$itemId"), this)
                }
            )
        }

        composable(
            route = UndalRoutes.CUSTOMER_ITEM,
            enterTransition = null,
            exitTransition = null
        ) { backStackEntry ->
            val itemId = backStackEntry.arguments?.getString(UndalRoutes.ARG_ITEM_ID).orEmpty()
            ItemDetailScreen(
                itemId = itemId,
                onBack = { navController.popBackStack() },
                onOpenItem = { nextId ->
                    navController.navigate(UndalRoutes.customerItem(nextId)) { launchSingleTop = true }
                },
                onOpenCart = { navController.navigate(UndalRoutes.CUSTOMER_CART) { launchSingleTop = true } },
                headerImageModifier = Modifier.sharedElement(
                    rememberSharedContentState("dish-photo-$itemId"),
                    this
                )
            )
        }

        composable(UndalRoutes.CUSTOMER_CART) {
            CartScreen(
                onBack = { navController.popBackStack() },
                onBrowseMenu = { navController.navigate(UndalRoutes.CUSTOMER_HOME) { launchSingleTop = true } },
                onEditAddress = { navController.navigate(UndalRoutes.LOCATION_SETUP) { launchSingleTop = true } },
                onOpenOrder = { orderId ->
                    navController.navigate(UndalRoutes.customerOrderDetail(orderId)) { launchSingleTop = true }
                }
            )
        }

        composable(UndalRoutes.CUSTOMER_ORDERS) {
            OrderHistoryScreen(
                onBack = { navController.popBackStack() },
                onOpenOrder = { orderId ->
                    navController.navigate(UndalRoutes.customerOrderDetail(orderId)) { launchSingleTop = true }
                },
                onOpenCart = { navController.navigate(UndalRoutes.CUSTOMER_CART) { launchSingleTop = true } },
                onBrowseMenu = { navController.navigate(UndalRoutes.CUSTOMER_HOME) { launchSingleTop = true } }
            )
        }

        composable(UndalRoutes.CUSTOMER_ORDER_DETAIL) { backStackEntry ->
            val orderId = backStackEntry.arguments?.getString(UndalRoutes.ARG_ORDER_ID).orEmpty()
            OrderTrackingScreen(
                orderId = orderId,
                onBack = { navController.popBackStack() },
                onOpenCart = { navController.navigate(UndalRoutes.CUSTOMER_CART) { launchSingleTop = true } }
            )
        }

        composable(UndalRoutes.CUSTOMER_CHAT) { backStackEntry ->
            val orderId = backStackEntry.arguments?.getString(UndalRoutes.ARG_ORDER_ID).orEmpty()
            OrderTrackingScreen(
                orderId = orderId,
                onBack = { navController.popBackStack() },
                onOpenCart = { navController.navigate(UndalRoutes.CUSTOMER_CART) { launchSingleTop = true } },
                openChatInitially = true
            )
        }

        composable(UndalRoutes.CUSTOMER_INBOX) {
            InboxScreen(
                onBack = { navController.popBackStack() },
                onOpenOrder = { orderId ->
                    navController.navigate(UndalRoutes.customerOrderDetail(orderId)) { launchSingleTop = true }
                }
            )
        }

        composable(UndalRoutes.CUSTOMER_PROFILE) {
            ProfileScreen(
                onBack = { navController.popBackStack() },
                onEditAddress = { navController.navigate(UndalRoutes.LOCATION_SETUP) { launchSingleTop = true } },
                onOpenOrders = { navController.navigate(UndalRoutes.CUSTOMER_ORDERS) { launchSingleTop = true } },
                onOpenInbox = { navController.navigate(UndalRoutes.CUSTOMER_INBOX) { launchSingleTop = true } }
            )
        }

        composable(UndalRoutes.VENDOR_DIAGNOSTICS) {
            DiagnosticsScreen(
                onContinue = viewModel::onDiagnosticsFinished,
                onOpenSupport = { navController.navigate(UndalRoutes.SUPPORT) }
            )
        }

        composable(UndalRoutes.VENDOR_CONSOLE) {
            VendorDashboardScreen(
                onOpenOrders = { navController.navigate(UndalRoutes.VENDOR_ORDERS) },
                onOpenMenu = { navController.navigate(UndalRoutes.VENDOR_MENU) },
                onOpenPromos = { navController.navigate(UndalRoutes.VENDOR_PROMOS) },
                onOpenSchedule = { navController.navigate(UndalRoutes.VENDOR_SCHEDULE) },
                onOpenSettings = { navController.navigate(UndalRoutes.VENDOR_STORE) },
                onOpenChats = { navController.navigate(UndalRoutes.VENDOR_CHAT_LIST) },
                onOpenDiagnostics = { navController.navigate(UndalRoutes.VENDOR_DIAGNOSTICS) },
                onOpenInbox = { navController.navigate(UndalRoutes.VENDOR_INBOX) },
                onOpenOrder = { orderId -> navController.navigate(UndalRoutes.vendorOrderDetail(orderId)) },
                onPreviewAsCustomer = { navController.navigate(UndalRoutes.CUSTOMER_HOME) }
            )
        }

        composable(UndalRoutes.VENDOR_ORDERS) {
            VendorOrdersScreen(
                onBack = { navController.popBackStack() },
                onOpenOrder = { orderId -> navController.navigate(UndalRoutes.vendorOrderDetail(orderId)) }
            )
        }

        composable(UndalRoutes.VENDOR_ORDER_DETAIL) { backStackEntry ->
            val orderId = backStackEntry.arguments?.getString(UndalRoutes.ARG_ORDER_ID).orEmpty()
            VendorOrderDetailScreen(
                orderId = orderId,
                sheet = requestedSheet,
                onBack = { navController.popBackStack() },
                onOpenChats = { navController.navigate(UndalRoutes.vendorChat(orderId)) }
            )
        }

        composable(UndalRoutes.VENDOR_MENU) {
            VendorMenuScreen(
                onBack = { navController.popBackStack() },
                onPreviewAsCustomer = { navController.navigate(UndalRoutes.CUSTOMER_HOME) }
            )
        }

        // The studio: the same manager, opened on the offers it is about.
        composable(UndalRoutes.VENDOR_PROMOS) {
            VendorMenuScreen(
                onBack = { navController.popBackStack() },
                onPreviewAsCustomer = { navController.navigate(UndalRoutes.CUSTOMER_HOME) },
                startTab = VendorMenuViewModel.Tab.OFFERS
            )
        }

        composable(UndalRoutes.VENDOR_SCHEDULE) {
            VendorScheduleScreen(onBack = { navController.popBackStack() })
        }

        composable(UndalRoutes.VENDOR_STORE) {
            VendorStoreScreen(
                onBack = { navController.popBackStack() },
                onOpenPolicy = { navController.navigate(UndalRoutes.POLICY) },
                onOpenDiagnostics = { navController.navigate(UndalRoutes.VENDOR_DIAGNOSTICS) },
                onPreviewAsCustomer = { navController.navigate(UndalRoutes.CUSTOMER_HOME) },
                onSignOut = {
                    viewModel.signOut()
                    navigateSafely(navController, UndalRoutes.LOGIN, viewModel::onNavigationFailed)
                }
            )
        }

        composable(UndalRoutes.VENDOR_CHAT_LIST) {
            VendorChatListScreen(
                onBack = { navController.popBackStack() },
                onOpenChat = { orderId -> navController.navigate(UndalRoutes.vendorChat(orderId)) },
                onOpenOrder = { orderId -> navController.navigate(UndalRoutes.vendorOrderDetail(orderId)) }
            )
        }

        composable(UndalRoutes.VENDOR_CHAT) { backStackEntry ->
            val orderId = backStackEntry.arguments?.getString(UndalRoutes.ARG_ORDER_ID).orEmpty()
            VendorChatScreen(
                orderId = orderId,
                onBack = { navController.popBackStack() },
                onOpenOrder = { id -> navController.navigate(UndalRoutes.vendorOrderDetail(id)) }
            )
        }

        composable(UndalRoutes.VENDOR_INBOX) {
            VendorInboxScreen(
                onBack = { navController.popBackStack() },
                onOpenOrder = { orderId -> navController.navigate(UndalRoutes.vendorOrderDetail(orderId)) }
            )
        }

        composable(UndalRoutes.POLICY) {
            PolicyScreen(onBack = { navController.popBackStack() })
        }
      }
      }

      // Two banners at most, and the recovery one wins: it explains why the app
      // is on screen again, which is the thing the person is wondering about.
      val banner = recoveryMessage ?: routerError
      if (banner != null) {
          val recovered = recoveryMessage != null
          UndalAlertBanner(
              text = banner,
              icon = Icons.Filled.Info,
              containerColour = if (recovered) {
                  MaterialTheme.colorScheme.secondaryContainer
              } else {
                  MaterialTheme.colorScheme.errorContainer
              },
              contentColour = if (recovered) {
                  MaterialTheme.colorScheme.onSecondaryContainer
              } else {
                  MaterialTheme.colorScheme.onErrorContainer
              },
              modifier = Modifier
                  .align(Alignment.TopCenter)
                  .padding(UndalDimens.md)
                  .clickable {
                      if (recovered) recoveryMessage = null else viewModel.onErrorDismissed()
                  }
          )
      }
    }
}
