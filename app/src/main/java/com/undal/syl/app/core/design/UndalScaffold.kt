package com.undal.syl.app.core.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.undal.syl.app.core.theme.UndalDimens

/**
 * Screen shell used by every page.
 *
 * The snackbar host lives here so no screen has to remember to add one, and the
 * content padding is applied once so pull-to-refresh and lazy lists behave the
 * same everywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UndalScaffold(
    title: String? = null,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    contentMaxWidth: Dp = 840.dp,
    content: @Composable (PaddingValues) -> Unit
) {
    val hostState = snackbarHostState ?: remember { SnackbarHostState() }
    val customerNavigation = LocalUndalCustomerNavigation.current
    // The title bar hides itself as the page scrolls, which is what gives a long
    // menu or a long order list the whole screen back on a small phone. It is a
    // standard Material effect driven by the page's own scroll, so it costs the
    // list nothing.
    val titleBar = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            // The keyboard is part of the layout: with the window edge-to-edge,
            // a chat box or a form at the bottom of the screen must rise with it.
            .imePadding()
            .nestedScroll(titleBar.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState) },
        floatingActionButton = floatingActionButton,
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.widthIn(max = contentMaxWidth).fillMaxWidth()) { bottomBar() }
                }
                if (customerNavigation != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(Modifier.widthIn(max = contentMaxWidth).fillMaxWidth()) {
                            UndalCustomerNavigationBar(customerNavigation)
                        }
                    }
                }
            }
        },
        topBar = {
            if (title != null) {
                TopAppBar(
                    navigationIcon = {
                        if (onBack != null) {
                            // 48dp of glass and a description a screen reader can
                            // read: the back arrow is the one control every single
                            // screen has, so it is the one that must never be a
                            // lucky tap.
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.size(UndalDimens.touchTarget)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    modifier = Modifier.size(UndalDimens.iconMd)
                                )
                            }
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (subtitle != null) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = { Row(verticalAlignment = Alignment.CenterVertically) { actions() } },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    ),
                    scrollBehavior = titleBar
                )
            }
        }
    ) { padding ->
        // Insets are consumed here. The argument remains for compatibility;
        // screen bodies must not apply it a second time.
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Box(modifier = Modifier.widthIn(max = contentMaxWidth).fillMaxSize()) {
                content(padding)
            }
        }
    }
}

/** Title-less variant for the full-screen splash and alert surfaces. */
@Composable
fun UndalBareScreen(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        content()
    }
}

/** Standard horizontal padding for screen bodies. */
val UndalScreenPadding = PaddingValues(
    horizontal = UndalDimens.screenPadding,
    vertical = UndalDimens.sm
)

/** Small helper so callers can drop a spacer between stacked sections. */
@Composable
fun UndalGap(size: androidx.compose.ui.unit.Dp = UndalDimens.sectionGap) {
    Spacer(Modifier.fillMaxWidth().padding(top = size))
}

/** Minimal centred title block for auth screens (brand mark + promise). */
@Composable
fun UndalBrandHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.width(UndalDimens.xs))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
