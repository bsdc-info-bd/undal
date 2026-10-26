package com.undal.syl.app.core.design

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The four customer destinations that should stay one tap away. */
enum class UndalCustomerTab { HOME, ORDERS, BASKET, ACCOUNT }

/** Navigation supplied by the root graph and rendered consistently by every customer screen. */
data class UndalCustomerNavigation(
    val selectedTab: UndalCustomerTab,
    val onHome: () -> Unit,
    val onOrders: () -> Unit,
    val onBasket: () -> Unit,
    val onAccount: () -> Unit
)

/** Vendor and auth surfaces intentionally have no customer navigation. */
val LocalUndalCustomerNavigation = staticCompositionLocalOf<UndalCustomerNavigation?> { null }

/**
 * Persistent customer navigation inspired by the clear, thumb-friendly tab bar
 * in the supplied Foodpanda references. The app's own routes and labels remain
 * Undal-specific; the bar is shared by the home, dish, cart, order and account
 * screens so customers never have to backtrack to find a core destination.
 */
@Composable
fun UndalCustomerNavigationBar(navigation: UndalCustomerNavigation) {
    val scheme = MaterialTheme.colorScheme
    NavigationBar(
        modifier = Modifier.fillMaxWidth(),
        containerColor = scheme.surface,
        contentColor = scheme.onSurface,
        tonalElevation = 1.dp
    ) {
        NavigationBarItem(
            selected = navigation.selectedTab == UndalCustomerTab.HOME,
            onClick = navigation.onHome,
            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
            label = { Text("Home") },
            colors = customerNavigationColors()
        )
        NavigationBarItem(
            selected = navigation.selectedTab == UndalCustomerTab.ORDERS,
            onClick = navigation.onOrders,
            icon = { Icon(Icons.Filled.List, contentDescription = null) },
            label = { Text("Orders") },
            colors = customerNavigationColors()
        )
        NavigationBarItem(
            selected = navigation.selectedTab == UndalCustomerTab.BASKET,
            onClick = navigation.onBasket,
            icon = { Icon(Icons.Filled.ShoppingCart, contentDescription = null) },
            label = { Text("Basket") },
            colors = customerNavigationColors()
        )
        NavigationBarItem(
            selected = navigation.selectedTab == UndalCustomerTab.ACCOUNT,
            onClick = navigation.onAccount,
            icon = { Icon(Icons.Filled.Person, contentDescription = null) },
            label = { Text("Account") },
            colors = customerNavigationColors()
        )
    }
}

@Composable
private fun customerNavigationColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
)
