package io.openflux.desktop.ui

import androidx.compose.runtime.Composable

/**
 * Whether the software keyboard is on screen. Android answers for real; a
 * desktop has no keyboard to cover the window, so it says no.
 *
 * A dialog needs this because the keyboard overlays a dialog window instead of
 * resizing it: without knowing that the keyboard went away, a focused text field
 * asks for it back the moment the window takes focus again, and the buttons
 * underneath stay unreachable.
 */
@Composable
internal expect fun softwareKeyboardVisible(): Boolean