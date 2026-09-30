package io.openflux.pc

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.openflux.desktop.createAppContainer
import io.openflux.desktop.ui.DesktopScrollbars
import io.openflux.desktop.ui.Shortcuts
import io.openflux.desktop.ui.shell.OpenFluxApp
import io.openflux.desktop.web.KcefBrowserViews

/**
 * Windows entry point.
 *
 * Everything the user sees is the shared interface, so this file decides only
 * three things: the window, the desktop implementations of the three hooks the
 * shared code leaves to the platform, and nothing else. Anything about how the
 * app looks or behaves belongs in commonMain.
 */
fun main() = application {
    val container = remember { createAppContainer(Version.VALUE) }
    val shortcuts = remember { Shortcuts() }
    val windowState = rememberWindowState(size = DpSize(1180.dp, 820.dp))

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "OpenFlux",
    ) {
        OpenFluxApp(
            container = container,
            scrollbars = DesktopScrollbars,
            shortcuts = shortcuts,
            browsers = KcefBrowserViews,
        )
    }
}
