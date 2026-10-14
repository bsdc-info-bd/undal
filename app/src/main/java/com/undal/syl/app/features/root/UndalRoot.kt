package com.undal.syl.app.features.root

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
import com.undal.syl.app.features.vendor.orders.VendorOrderDetailScreen
import com.undal.syl.app.features.vendor.orders.VendorOrdersScreen
import com.undal.syl.app.features.vendor.schedule.VendorScheduleScreen
import com.undal.syl.app.features.vendor.settings.VendorStoreScreen
import com.undal.syl.app.features.vendor.DiagnosticsScreen

/**
 * The app shell: one navigation graph, two portals, one authority on where a
 * cold start lands (UndalRootViewModel).
 *
 * Deep links arriving from a push or a shared link are handed to the graph after
 * the router has decided the portal, so a kitchen alert opens the order it is
 * about and a customer status push opens the right order — never a blank screen.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun UndalRoot(
    initialDeepLink: String?,
    navController: NavHostController = rememberNavController(),
    viewModel: UndalRootViewModel = hiltViewModel()
) {
    val destination by viewModel.destination.collectAsState()
    val ringing by OrderAlertController.ringing.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    // The alert's Accept / Reject buttons open the order with the right sheet
    // already raised, so the kitchen never has to hunt for the button.
    val requestedSheet = initialDeepLink
        ?.substringAfter("sheet=", "")
        ?.takeIf { it.isNotBlank() }

    // Portal routing: whenever the router changes its mind, the graph follows.
    LaunchedEffect(destination) {
        val target = when (destination) {
            is UndalRootViewModel.Destination.Splash -> UndalRoutes.SPLASH
            is UndalRootViewModel.Destination.Auth -> UndalRoutes.LOGIN
            is UndalRootViewModel.Destination.CustomerLocation -> UndalRoutes.LOCATION_SETUP
            is UndalRootViewModel.Destination.CustomerHome -> UndalRoutes.CUSTOMER_HOME
            is UndalRootViewModel.Destination.VendorDiagnostics -> UndalRoutes.VENDOR_DIAGNOSTICS
            is UndalRootViewModel.Destination.VendorConsole -> UndalRoutes.VENDOR_CONSOLE
            is UndalRootViewModel.Destination.Error -> UndalRoutes.LOGIN
        }
        navController.navigate(target) {
            popUpTo(0) { inclusive = true }
            launchSingleTop = true
        }
    }

    // A push deep link wins immediately: if the kitchen phone is ringing, the
    // first thing it shows is the order that is ringing.
    LaunchedEffect(initialDeepLink, ringing) {
        val route = UndalRoutes.routeForDeepLink(initialDeepLink) ?: return@LaunchedEffect
        if (route == UndalRoutes.SPLASH) return@LaunchedEffect
        navController.navigate(route) { launchSingleTop = true }
    }

    LaunchedEffect(ringing) {
        val active = ringing
        if (active != null) {
            navController.navigate(UndalRoutes.vendorOrderDetail(active.orderId)) { launchSingleTop = true }
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
                    navController.navigate(UndalRoutes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
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
                onOpenPromos = { navController.navigate(UndalRoutes.VENDOR_MENU) },
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
                    navController.navigate(UndalRoutes.LOGIN) { popUpTo(0) }
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

      if (errorMessage != null) {
          UndalAlertBanner(
              text = errorMessage.orEmpty(),
              icon = Icons.Filled.Info,
              containerColour = MaterialTheme.colorScheme.errorContainer,
              contentColour = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier
                  .align(Alignment.TopCenter)
                  .padding(UndalDimens.md)
          )
      }
    }
}
