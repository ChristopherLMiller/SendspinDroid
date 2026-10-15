package com.sendspindroid.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.NavigationDrawerScope
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.rememberDrawerState
import com.sendspindroid.ui.adaptive.TvOverscanHorizontal
import com.sendspindroid.ui.adaptive.TvOverscanVertical

/** One entry of the TV side menu. */
class TvMenuEntry(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit = {},
)

/**
 * The TV frame around a screen: a full-height side menu at the left edge,
 * as TV apps have, in place of the phone's top bar and overflow menu.
 *
 * Collapsed, the menu is a rail of icons beside the screen. It opens over the
 * screen, behind a scrim, as soon as it takes focus: D-pad Left from the
 * screen's left edge, the Back key, or a remote's Menu key. It opens on
 * [home], the entry for the screen being shown. D-pad Right, or choosing
 * [home], closes it, with focus on the nearest control of the screen. Any
 * other entry closes it before it acts, so focus is back on the screen when
 * a dialog or another screen it opens is closed. Back in the open menu calls
 * [onLeave].
 *
 * A screen with nothing to focus, such as one waiting for a server, leaves
 * focus in the menu. The menu stays collapsed then, so the screen is not
 * dimmed, and opens at the first key; closing it only collapses it again.
 *
 * @param home The screen being shown, first and selected.
 * @param entries Actions, below [home].
 * @param footerEntries Entries kept at the bottom, such as Settings.
 * @param header Shown above the entries; told whether the menu is open.
 * @param onLeave Back pressed in the open menu.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvShell(
    home: TvMenuEntry,
    entries: List<TvMenuEntry>,
    footerEntries: List<TvMenuEntry>,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable ColumnScope.(open: Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val menuOpen = drawerState.currentValue == DrawerValue.Open
    val homeEntry = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // The drawer opens and closes as focus enters and leaves it, but focus
    // that is already in it does not enter it again: these set it too.
    val openMenu = {
        homeEntry.requestFocus()
        drawerState.setValue(DrawerValue.Open)
    }
    // As D-pad Right does. Restoring what had focus on the screen is not
    // reliable in Compose 1.7: restored into a lazy list, the focus can end up
    // on the list itself, with nothing focused that the user can see.
    val closeMenu: () -> Unit = {
        if (!focusManager.moveFocus(FocusDirection.Right)) drawerState.setValue(DrawerValue.Closed)
    }

    // Back first takes the user to the menu, and only Back there leaves:
    // the Android TV pattern for an app with a left-side menu.
    BackHandler(enabled = !menuOpen) { openMenu() }
    // Closed first, so the app comes back to the screen
    BackHandler(enabled = menuOpen) {
        closeMenu()
        onLeave()
    }

    val m3 = M3Theme.colorScheme
    val menuBackground = m3.surfaceContainer

    ModalNavigationDrawer(
        modifier = modifier,
        drawerState = drawerState,
        scrimBrush = SolidColor(m3.scrim.copy(alpha = 0.6f)),
        drawerContent = { value ->
            TvMaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .background(if (value == DrawerValue.Open) menuBackground else Color.Transparent)
                        .padding(horizontal = RailPadding, vertical = TvOverscanVertical)
                        // Focus in the collapsed menu: the first key opens it
                        .onPreviewKeyEvent { event ->
                            val opens = drawerState.currentValue == DrawerValue.Closed && event.key in MenuKeys
                            if (opens && event.type == KeyEventType.KeyDown) drawerState.setValue(DrawerValue.Open)
                            opens
                        }
                        .focusProperties { enter = { homeEntry } }
                        .focusGroup()
                        .selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    header(value == DrawerValue.Open)
                    MenuItem(home, onClick = closeMenu, modifier = Modifier.focusRequester(homeEntry), selected = true)
                    entries.forEach { MenuItem(it, onClick = { closeMenu(); it.onClick() }) }
                    Spacer(modifier = Modifier.weight(1f))
                    footerEntries.forEach { MenuItem(it, onClick = { closeMenu(); it.onClick() }) }
                }
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // The modal drawer lays the screen out under its rail. Screens
                // keep the TV side margin themselves, so this adds the rest.
                .padding(start = RailWidth - TvOverscanHorizontal)
                .onPreviewKeyEvent { event ->
                    val menuKey = event.key == Key.Menu
                    if (menuKey && event.type == KeyEventType.KeyDown) openMenu()
                    menuKey
                }
                .focusGroup()
        ) {
            content()
        }
    }
}

/** The keys that move or act in the menu: Back has handlers of its own. */
private val MenuKeys = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
)

private val RailPadding = 12.dp

/** The collapsed menu: its entries' icons, with [RailPadding] either side. */
private val RailWidth = NavigationDrawerItemDefaults.CollapsedDrawerItemWidth + RailPadding * 2

@Composable
private fun NavigationDrawerScope.MenuItem(
    entry: TvMenuEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    NavigationDrawerItem(
        selected = selected,
        onClick = onClick,
        leadingContent = { Icon(imageVector = entry.icon, contentDescription = null) },
        modifier = modifier,
    ) {
        Text(text = entry.label, maxLines = 1)
    }
}

/**
 * The app's Material3 colours, for the Compose for TV components of the menu,
 * which read their own MaterialTheme.
 */
@Composable
private fun TvMaterialTheme(content: @Composable () -> Unit) {
    val m3 = M3Theme.colorScheme
    val colorScheme = remember(m3) {
        darkColorScheme(
            primary = m3.primary,
            onPrimary = m3.onPrimary,
            primaryContainer = m3.primaryContainer,
            onPrimaryContainer = m3.onPrimaryContainer,
            inversePrimary = m3.inversePrimary,
            secondary = m3.secondary,
            onSecondary = m3.onSecondary,
            secondaryContainer = m3.secondaryContainer,
            onSecondaryContainer = m3.onSecondaryContainer,
            tertiary = m3.tertiary,
            onTertiary = m3.onTertiary,
            tertiaryContainer = m3.tertiaryContainer,
            onTertiaryContainer = m3.onTertiaryContainer,
            background = m3.background,
            onBackground = m3.onBackground,
            surface = m3.surface,
            onSurface = m3.onSurface,
            surfaceVariant = m3.surfaceVariant,
            onSurfaceVariant = m3.onSurfaceVariant,
            surfaceTint = m3.surfaceTint,
            inverseSurface = m3.inverseSurface,
            inverseOnSurface = m3.inverseOnSurface,
            error = m3.error,
            onError = m3.onError,
            errorContainer = m3.errorContainer,
            onErrorContainer = m3.onErrorContainer,
            scrim = m3.scrim,
            border = m3.outline,
            borderVariant = m3.outlineVariant,
        )
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
