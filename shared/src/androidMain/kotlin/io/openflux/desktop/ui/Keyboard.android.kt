package io.openflux.desktop.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity

/**
 * Measures the software keyboard once, where the insets are real - around the
 * activity's own content - and hands the height to every dialog below through
 * [LocalKeyboardOverlap].
 *
 * Inside a dialog this cannot be measured: a dialog window is neither resized by
 * the keyboard nor given its inset, which is why the dialogs read the value
 * instead of asking for it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProvideKeyboardOverlap(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val covered = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    CompositionLocalProvider(LocalKeyboardOverlap provides covered, content = content)
}