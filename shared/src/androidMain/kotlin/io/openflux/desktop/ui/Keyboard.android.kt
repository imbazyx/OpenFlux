package io.openflux.desktop.ui

import android.view.WindowManager
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal actual fun softwareKeyboardVisible(): Boolean {
    val view = LocalView.current
    // A dialog window is not resized by the keyboard unless it asks to be, and
    // Compose's dialog does not ask: the keyboard lays itself over the bottom of
    // the dialog, which is where the buttons are. ADJUST_RESIZE makes the window
    // shrink instead, so the dialog's own scrolling body takes the squeeze and
    // the buttons stay on screen. STATE_UNCHANGED because this must not open the
    // keyboard by itself - the field that asked for it already did.
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED,
        )
        onDispose { }
    }
    return WindowInsets.isImeVisible
}